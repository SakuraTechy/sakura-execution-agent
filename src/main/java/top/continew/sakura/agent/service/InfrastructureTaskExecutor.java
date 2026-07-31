package top.continew.sakura.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.AgentExecutionException;
import top.continew.sakura.agent.support.LocalActionPolicy;

/**
 * 执行节点内唯一接触网络、JDBC 驱动和数据库原生协议的组件。
 * 不使用 admin 旧的静态 JDBC/SSH 工具，以保证并发任务可取消且不会复用凭据。
 */
public class InfrastructureTaskExecutor {

    private static final int MAX_OUTPUT_BYTES = 256 * 1024;
    private static final int DEFAULT_MAX_ROWS = 200;
    private static final int DEFAULT_MAX_FILE_RESULTS = 200;
    private static final int MAX_FILE_RESULTS = 1_000;
    private static final int DEFAULT_MAX_IP_PROBES = 64;
    private static final int MAX_CAPTCHA_IMAGE_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final Path driverDirectory;
    private final AgentLogger logger;
    private final LocalActionPolicy localActionPolicy;
    private final Map<String, Runnable> cancelActions = new ConcurrentHashMap<>();

    public InfrastructureTaskExecutor(ObjectMapper objectMapper, Path driverDirectory, AgentLogger logger) {
        this(objectMapper, driverDirectory, logger, LocalActionPolicy.fromSystemProperties());
    }

    public InfrastructureTaskExecutor(ObjectMapper objectMapper,
                                      Path driverDirectory,
                                      AgentLogger logger,
                                      LocalActionPolicy localActionPolicy) {
        this.objectMapper = objectMapper;
        this.driverDirectory = driverDirectory;
        this.logger = logger;
        this.localActionPolicy = localActionPolicy;
    }

    public ExecutionOutcome execute(InfrastructureTaskRequest request) throws Exception {
        Instant startedAt = Instant.now();
        try {
            logger.info("EXECUTION_DISPATCHED", request.taskId(), request.actionType(), null);
            return switch (request.actionType()) {
                case "server_command" -> executeSsh(request, startedAt);
                case "database_sql" -> executeJdbc(request, startedAt);
                case "database_native" -> executeMongo(request, startedAt);
                case "host_command" -> executeHostCommand(request, startedAt);
                case "host_file_lookup" -> executeHostFileLookup(request, startedAt);
                case "host_file_delete" -> executeHostFileDelete(request, startedAt);
                case "global_variable_system_info" -> executeSystemInfo(request, startedAt);
                case "global_variable_available_ip" -> executeAvailableIpProbe(request, startedAt);
                case "global_variable_property" -> executeRuntimeProperty(request, startedAt);
                case "captcha_ocr" -> executeCaptchaOcr(request, startedAt);
                case "server_file_upload" -> executeSftpUpload(request, startedAt);
                case "host_pointer_move" -> executeHostPointerMove(request, startedAt);
                default -> throw new IllegalArgumentException("不支持的基础设施操作：" + request.actionType());
            };
        } finally {
            cancelActions.remove(request.taskId());
        }
    }

    public void cancel(String taskId) {
        Runnable cancelAction = cancelActions.remove(taskId);
        if (cancelAction != null) {
            logger.warn("EXECUTION_CANCEL_SIGNALLED", taskId, null, "activeResource=true");
            cancelAction.run();
            return;
        }
        logger.warn("EXECUTION_CANCEL_SIGNALLED", taskId, null, "activeResource=false");
    }

    private ExecutionOutcome executeSsh(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        if (request.sshTarget() == null || isBlank(request.command())) {
            throw new IllegalArgumentException("服务器命令缺少目标或命令内容");
        }
        InfrastructureTaskRequest.SshTarget target = request.sshTarget();
        if (isBlank(target.host()) || isBlank(target.username())) {
            throw new IllegalArgumentException("服务器命令缺少 SSH 主机或用户名");
        }
        String platform = normalizePlatform(target.platform());
        String shell = normalizeShell(request.shell());
        validateShell(platform, shell);
        logger.info("SSH_TARGET_VALIDATED", request.taskId(), request.actionType(), "host=" + target.host() + " port="
            + (target.port() == null ? 22 : target.port()) + " timeoutMs=" + timeoutMillis(request.timeoutMs())
            + " platform=" + platform + " shell=" + shell + " commandLength=" + request.command().length());
        requireKnownHosts();
        String knownHostsPath = System.getProperty("sakura.agent.known-hosts");
        logger.info("SSH_KNOWN_HOSTS_VALIDATED", request.taskId(), request.actionType(),
            "file=" + knownHostsPath + " strictHostKeyChecking=yes");
        JSch jsch = new JSch();
        jsch.setKnownHosts(System.getProperty("sakura.agent.known-hosts"));
        Session session = jsch.getSession(target.username(), target.host(), target.port() == null ? 22 : target.port());
        if (!isBlank(target.password())) {
            session.setPassword(target.password());
        }
        session.setConfig("StrictHostKeyChecking", "yes");
        ChannelExec channel = null;
        try {
            logger.info("SSH_CONNECTING", request.taskId(), request.actionType(),
                "host=" + target.host() + " port=" + (target.port() == null ? 22 : target.port())
                    + " timeoutMs=" + timeoutMillis(request.timeoutMs()));
            try {
                session.connect(timeoutMillis(request.timeoutMs()));
            } catch (JSchException e) {
                logger.error("SSH_CONNECT_FAILED", request.taskId(), request.actionType(),
                    "host=" + target.host() + " port=" + (target.port() == null ? 22 : target.port())
                        + " reason=" + sshFailureReason(e));
                throw e;
            }
            String actualFingerprint = session.getHostKey() == null ? "unknown" : session.getHostKey().getFingerPrint(jsch);
            logger.info("SSH_CONNECTED", request.taskId(), request.actionType(),
                "host=" + target.host() + " port=" + (target.port() == null ? 22 : target.port())
                    + " hostKeyFingerprint=" + actualFingerprint);
            if (!isBlank(target.knownHostFingerprint())) {
                if (!target.knownHostFingerprint().equalsIgnoreCase(actualFingerprint)) {
                    throw new IllegalStateException("SSH 主机指纹与环境绑定不一致");
                }
            }
            if ("powershell".equals(shell) && "linux".equals(platform)) {
                checkPwshInstalled(session, request);
            }
            channel = (ChannelExec) session.openChannel("exec");
            String remoteCommand = buildShellCommand(platform, shell, request.command());
            channel.setCommand(remoteCommand);
            CappedOutputStream stderr = new CappedOutputStream(MAX_OUTPUT_BYTES);
            channel.setErrStream(stderr, true);
            InputStream stdout = channel.getInputStream();
            ChannelExec runningChannel = channel;
            cancelActions.put(request.taskId(), () -> closeQuietly(runningChannel, session));
            logger.info("SSH_COMMAND_STARTED", request.taskId(), request.actionType(),
                "platform=" + platform + " shell=" + shell + " commandLength=" + request.command().length());
            try {
                channel.connect(timeoutMillis(request.timeoutMs()));
            } catch (JSchException e) {
                logger.error("SSH_CHANNEL_FAILED", request.taskId(), request.actionType(),
                    "host=" + target.host() + " port=" + (target.port() == null ? 22 : target.port())
                        + " reason=" + sshFailureReason(e));
                throw e;
            }
            CappedOutputStream output = new CappedOutputStream(MAX_OUTPUT_BYTES);
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis(request.timeoutMs())).toNanos();
            byte[] buffer = new byte[4096];
            while (true) {
                while (stdout.available() > 0) {
                    int read = stdout.read(buffer);
                    if (read > 0) {
                        output.write(buffer, 0, read);
                    }
                }
                if (channel.isClosed()) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("服务器命令执行超时");
                }
                Thread.sleep(25);
            }
            while (stdout.available() > 0) {
                int read = stdout.read(buffer);
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            int exitCode = channel.getExitStatus();
            ExecutionOutcome outcome = outcome(startedAt, exitCode, null, null, output.text(), stderr.text());
            logger.info("SSH_COMMAND_COMPLETED", request.taskId(), request.actionType(), "durationMs=" + outcome.durationMs()
                + " exitCode=" + exitCode + " stdoutBytes=" + output.size() + " stderrBytes=" + stderr.size());
            return outcome;
        } finally {
            closeQuietly(channel, session);
        }
    }

    private ExecutionOutcome executeJdbc(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        if (request.jdbcTarget() == null || isBlank(request.sql())) {
            throw new IllegalArgumentException("数据库 SQL 操作缺少连接或 SQL 内容");
        }
        InfrastructureTaskRequest.JdbcTarget target = request.jdbcTarget();
        logger.info("JDBC_TARGET_VALIDATED", request.taskId(), request.actionType(), "driverProfile=" + target.driverProfile()
            + " driverClass=" + target.driverClass() + " sqlMode=" + normalizeSqlMode(request.sqlMode()) + " parameterCount="
            + (request.parameters() == null ? 0 : request.parameters().size()) + " timeoutMs=" + timeoutMillis(request.timeoutMs())
            + " maxRows=" + Math.max(1, request.maxRows() == 0 ? DEFAULT_MAX_ROWS : request.maxRows()) + " "
            + jdbcConnectionDiagnostic(target) + " sqlFingerprint=" + fingerprint(request.sql()) + " sqlLength="
            + request.sql().length() + " parameterTypes=" + parameterTypes(request.parameters()));
        Driver driver = loadDriver(request);
        Properties properties = new Properties();
        if (!isBlank(target.username())) {
            properties.setProperty("user", target.username());
        }
        if (!isBlank(target.password())) {
            properties.setProperty("password", target.password());
        }
        try (Connection connection = driver.connect(target.jdbcUrl(), properties)) {
            if (connection == null) {
                throw new SQLException("JDBC 驱动无法处理该连接地址");
            }
            logger.info("JDBC_CONNECTION_OPENED", request.taskId(), request.actionType(), "driver=" + connection.getMetaData()
                .getDriverName() + " driverVersion=" + connection.getMetaData().getDriverVersion());
            try (PreparedStatement statement = connection.prepareStatement(request.sql())) {
                cancelActions.put(request.taskId(), () -> cancelStatement(statement, connection));
                statement.setQueryTimeout(queryTimeoutSeconds(request.timeoutMs()));
                statement.setMaxRows(Math.max(1, request.maxRows() == 0 ? DEFAULT_MAX_ROWS : request.maxRows()));
                bindParameters(statement, request.parameters());
                logger.info("JDBC_STATEMENT_PREPARED", request.taskId(), request.actionType(), "queryTimeoutSeconds="
                    + queryTimeoutSeconds(request.timeoutMs()));
                ExecutionOutcome outcome = switch (normalizeSqlMode(request.sqlMode())) {
                    case "query" -> executeQuery(statement, startedAt, request.maxRows());
                    case "update" -> outcome(startedAt, 0, statement.executeUpdate(), null, "", "");
                    case "call" -> executeCall(statement, startedAt, request.maxRows());
                    default -> throw new IllegalArgumentException("不支持的 sqlMode：" + request.sqlMode());
                };
                outcome = bindQueryResultIfRequested(request, outcome);
                logger.info("JDBC_STATEMENT_COMPLETED", request.taskId(), request.actionType(), "durationMs=" + outcome
                    .durationMs() + " affectedRows=" + outcome.affectedRows() + " rowCount=" + (outcome.rows() == null ? 0
                        : outcome.rows().size()));
                return outcome;
            }
        } finally {
            if (driver instanceof IsolatedDriver isolatedDriver) {
                isolatedDriver.close();
                logger.info("JDBC_DRIVER_RELEASED", request.taskId(), request.actionType(), "driverProfile=" + target
                    .driverProfile());
            }
        }
    }

    /** 查询结果仅在调用方显式声明变量名时回传，Admin 会再次裁剪后交给当前执行上下文。 */
    private ExecutionOutcome bindQueryResultIfRequested(InfrastructureTaskRequest request, ExecutionOutcome outcome)
        throws AgentExecutionException {
        if (isBlank(request.variableName()) || outcome.rows() == null) {
            return outcome;
        }
        String variableName = validateVariableName(request.variableName());
        return new ExecutionOutcome(outcome.durationMs(), outcome.exitCode(), outcome.affectedRows(), outcome.rows(),
            outcome.stdout(), outcome.stderr(), Map.of("variables", Map.of(variableName, outcome.rows())));
    }

    private ExecutionOutcome executeMongo(InfrastructureTaskRequest request, Instant startedAt) {
        if (request.mongoTarget() == null || isBlank(request.mongoTarget().connectionString())
            || isBlank(request.mongoTarget().database()) || isBlank(request.collection())) {
            throw new IllegalArgumentException("MongoDB 原生操作缺少连接、数据库或集合");
        }
        logger.info("MONGO_TARGET_VALIDATED", request.taskId(), request.actionType(), "operation=" + normalizeMongoOperation(
            request.mongoOperation()) + " collection=" + request.collection() + " maxRows=" + Math.max(1, request.maxRows()
                == 0 ? DEFAULT_MAX_ROWS : request.maxRows()) + " " + mongoConnectionDiagnostic(request.mongoTarget()));
        try (MongoClient client = MongoClients.create(request.mongoTarget().connectionString())) {
            cancelActions.put(request.taskId(), client::close);
            MongoDatabase database = client.getDatabase(request.mongoTarget().database());
            MongoCollection<Document> collection = database.getCollection(request.collection());
            Document filter = asDocument(request.filter());
            Document document = asDocument(request.document());
            ExecutionOutcome outcome = switch (normalizeMongoOperation(request.mongoOperation())) {
                case "find" -> mongoFind(collection, filter, startedAt, request.maxRows());
                case "insert" -> {
                    collection.insertOne(document);
                    yield outcome(startedAt, 0, 1, null, "", "");
                }
                case "update" -> {
                    long matched = collection.updateMany(filter, document).getModifiedCount();
                    yield outcome(startedAt, 0, Math.toIntExact(matched), null, "", "");
                }
                case "delete" -> {
                    long deleted = collection.deleteMany(filter).getDeletedCount();
                    yield outcome(startedAt, 0, Math.toIntExact(deleted), null, "", "");
                }
                default -> throw new IllegalArgumentException("不支持的 MongoDB 操作：" + request.mongoOperation());
            };
            logger.info("MONGO_OPERATION_COMPLETED", request.taskId(), request.actionType(), "durationMs=" + outcome.durationMs()
                + " affectedRows=" + outcome.affectedRows() + " rowCount=" + (outcome.rows() == null ? 0 : outcome.rows()
                    .size()));
            return outcome;
        }
    }

    /**
     * 本机命令只用于受权限控制的 Windows/CMD 或 Unix shell 自动化步骤。
     * 命令文本、标准输出和标准错误都不能写入 Agent 日志或任务响应，避免凭据随命令回显泄露。
     */
    private ExecutionOutcome executeHostCommand(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        requireCapability(request, "host_command", false);
        if (isBlank(request.command())) {
            throw new AgentExecutionException("HOST_COMMAND_REQUIRED", "本机命令不能为空");
        }
        HostCommandRisk risk = classifyHostCommand(request.command());
        if (risk == HostCommandRisk.BLOCKED) {
            throw new AgentExecutionException("HOST_COMMAND_DENIED", "本机命令命中执行节点不可覆盖的危险命令拒绝规则");
        }
        if (risk == HostCommandRisk.APPROVAL_REQUIRED) {
            requireApproval(request, "host_command");
        }
        Path workingDirectory = localActionPolicy.resolveWorkingDirectory(request.workingDirectory());
        String hostShell = normalizeHostShell(request.hostShell(), request.shell());
        Process process = null;
        CappedOutputStream stdout = new CappedOutputStream(MAX_OUTPUT_BYTES);
        CappedOutputStream stderr = new CappedOutputStream(MAX_OUTPUT_BYTES);
        Thread stdoutDrainer = null;
        Thread stderrDrainer = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(buildHostCommand(hostShell, request.command()));
            builder.directory(workingDirectory.toFile());
            process = builder.start();
            Process runningProcess = process;
            cancelActions.put(request.taskId(), () -> terminateProcess(runningProcess));
            stdoutDrainer = drainOutput(process.getInputStream(), stdout, "sakura-agent-host-stdout");
            stderrDrainer = drainOutput(process.getErrorStream(), stderr, "sakura-agent-host-stderr");
            logger.info("HOST_COMMAND_STARTED", request.taskId(), request.actionType(), "shell=" + hostShell
                + " timeoutMs=" + timeoutMillis(request.timeoutMs()) + " commandLength=" + request.command().length());
            if (!process.waitFor(timeoutMillis(request.timeoutMs()), TimeUnit.MILLISECONDS)) {
                terminateProcess(process);
                joinDrainer(stdoutDrainer);
                joinDrainer(stderrDrainer);
                throw new AgentExecutionException("HOST_COMMAND_TIMEOUT", "本机命令执行超时");
            }
            joinDrainer(stdoutDrainer);
            joinDrainer(stderrDrainer);
            int exitCode = process.exitValue();
            logger.info("HOST_COMMAND_COMPLETED", request.taskId(), request.actionType(), "durationMs="
                + Duration.between(startedAt, Instant.now()).toMillis() + " exitCode=" + exitCode + " stdoutBytes="
                + stdout.size() + " stderrBytes=" + stderr.size() + " outputTruncated="
                + (stdout.truncated() || stderr.truncated()));
            // 故意不把命令输出放入 outcome；host_command 的调用方只能获得退出码、耗时和空 result。
            return outcome(startedAt, exitCode, null, null, "", "", Map.of());
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AgentExecutionException("HOST_COMMAND_START_FAILED", "本机命令启动失败", exception);
        } finally {
            if (process != null && process.isAlive()) {
                terminateProcess(process);
            }
            joinDrainer(stdoutDrainer);
            joinDrainer(stderrDrainer);
        }
    }

    private ExecutionOutcome executeHostFileLookup(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        Path source = localActionPolicy.resolveExistingAllowedPath(request.filePath(), "host_file_lookup");
        int maxResults = normalizedFileResultLimit(request.maxResults(), request.maxRows());
        String pattern = normalizeFilePattern(request.filePattern());
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                if (matchesFilePattern(source, pattern)) {
                    files.add(fileSummary(source));
                }
            } else if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Boolean.TRUE.equals(request.recursive()) ? Files.walk(source) : Files.list(source)) {
                    paths.filter(path -> !path.equals(source))
                        // 不跟随并且不返回符号链接，防止目录扫描经由链接离开 allow root。
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> matchesFilePattern(path, pattern))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(maxResults)
                        .forEach(path -> files.add(fileSummary(path)));
                }
            } else {
                throw new AgentExecutionException("HOST_FILE_LOOKUP_TARGET_INVALID", "本机文件查询目标必须是文件或目录");
            }
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AgentExecutionException("HOST_FILE_LOOKUP_FAILED", "本机文件查询失败", exception);
        }
        logger.info("HOST_FILE_LOOKUP_COMPLETED", request.taskId(), request.actionType(), "matchCount=" + files.size()
            + " recursive=" + Boolean.TRUE.equals(request.recursive()) + " maxResults=" + maxResults);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("match_count", files.size());
        result.put("files", List.copyOf(files));
        if (!isBlank(request.variableName())) {
            result.put("variables", Map.of(validateVariableName(request.variableName()), List.copyOf(files)));
        }
        return outcome(startedAt, 0, null, null, "", "", result);
    }

    private ExecutionOutcome executeHostFileDelete(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        requireCapability(request, "host_file_delete", true);
        Path target = localActionPolicy.resolveExistingAllowedPath(request.filePath(), "host_file_delete");
        int deletedCount;
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!Boolean.TRUE.equals(request.recursive())) {
                    try (var contents = Files.list(target)) {
                        if (contents.findAny().isPresent()) {
                            throw new AgentExecutionException("HOST_FILE_DELETE_RECURSIVE_REQUIRED", "删除非空目录必须显式设置 recursive=true");
                        }
                    }
                    Files.delete(target);
                    deletedCount = 1;
                } else {
                    ensureNoSymbolicLinks(target);
                    deletedCount = deleteDirectoryTree(target);
                }
            } else if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(target);
                deletedCount = 1;
            } else {
                throw new AgentExecutionException("HOST_FILE_DELETE_TARGET_INVALID", "本机文件删除目标必须是文件或目录");
            }
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AgentExecutionException("HOST_FILE_DELETE_FAILED", "本机文件删除失败", exception);
        }
        logger.warn("HOST_FILE_DELETE_COMPLETED", request.taskId(), request.actionType(), "deletedCount=" + deletedCount
            + " recursive=" + Boolean.TRUE.equals(request.recursive()));
        return outcome(startedAt, 0, deletedCount, null, "", "", Map.of("deleted_count", deletedCount));
    }

    private ExecutionOutcome executeSystemInfo(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        String variableName = validateVariableName(request.variableName());
        String infoType = String.valueOf(request.infoType()).trim().toLowerCase(Locale.ROOT);
        String value = switch (infoType) {
            case "host_ip" -> preferredHostAddress();
            case "host_name" -> localHostName();
            case "os_name" -> systemProperty("os.name");
            case "os_version" -> systemProperty("os.version");
            case "os_arch" -> systemProperty("os.arch");
            case "system_date" -> Instant.now().toString();
            case "current_user" -> systemProperty("user.name");
            // 本机绝对路径不进入跨进程 result，避免任务响应反向暴露执行节点目录结构。
            case "user_home", "working_directory" -> "<redacted-path>";
            default -> throw new AgentExecutionException("SYSTEM_INFO_TYPE_INVALID", "不支持的系统信息项");
        };
        logger.info("SYSTEM_INFO_COMPLETED", request.taskId(), request.actionType(), "infoType=" + infoType);
        return outcome(startedAt, 0, null, null, "", "", Map.of("variables", Map.of(variableName, value)));
    }

    private ExecutionOutcome executeAvailableIpProbe(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        String variableName = validateVariableName(request.variableName());
        String prefix = validatePrivateIpv4Prefix(request.ipPrefix());
        int start = normalizeIpHostPart(request.start(), "start");
        int end = normalizeIpHostPart(request.end(), "end");
        if (start > end) {
            throw new AgentExecutionException("AVAILABLE_IP_RANGE_INVALID", "可用 IP 探测起始主机号不能大于结束主机号");
        }
        int probeCount = end - start + 1;
        int maxProbeCount = Math.max(1, Integer.getInteger("sakura.agent.available-ip.max-probes", DEFAULT_MAX_IP_PROBES));
        if (probeCount > maxProbeCount) {
            throw new AgentExecutionException("AVAILABLE_IP_RANGE_TOO_LARGE", "可用 IP 探测范围超过执行节点允许上限");
        }
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelActions.put(request.taskId(), () -> cancelled.set(true));
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis(request.timeoutMs())).toNanos();
        int perProbeTimeout = Math.min(1_000, timeoutMillis(request.timeoutMs()));
        try {
            for (int hostPart = start; hostPart <= end; hostPart++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new AgentExecutionException("TASK_CANCELLED", "可用 IP 探测已取消");
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    throw new AgentExecutionException("AVAILABLE_IP_PROBE_TIMEOUT", "可用 IP 探测超时");
                }
                InetAddress candidate = InetAddress.getByName(prefix + "." + hostPart);
                int probeTimeout = (int)Math.max(100, Math.min(perProbeTimeout, remainingMs));
                if (!candidate.isReachable(probeTimeout)) {
                    String availableIp = candidate.getHostAddress();
                    logger.info("AVAILABLE_IP_FOUND", request.taskId(), request.actionType(), "probeCount=" + probeCount
                        + " checked=" + (hostPart - start + 1));
                    return outcome(startedAt, 0, null, null, "", "", Map.of("variables", Map.of(variableName,
                        availableIp)));
                }
            }
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "可用 IP 探测失败", exception);
        }
        throw new AgentExecutionException("AVAILABLE_IP_NOT_FOUND", "指定范围内未找到可用 IP");
    }

    /**
     * 兼容旧 setproperties 的“执行节点属性”语义，但只允许部署者逐项授权的非敏感键。
     * 不读取配置文件原文、不枚举 key，也不把值写入日志。
     */
    private ExecutionOutcome executeRuntimeProperty(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        String variableName = validateVariableName(request.variableName());
        String propertyKey = String.valueOf(request.propertyKey()).trim();
        String profile = String.valueOf(request.profile()).trim().toLowerCase(Locale.ROOT);
        if (!localActionPolicy.permitsRuntimeProperty(profile, propertyKey)) {
            throw new AgentExecutionException("RUNTIME_PROPERTY_DENIED", "运行时属性未被执行节点白名单授权");
        }
        String value = System.getProperty(propertyKey);
        if (value == null) {
            value = System.getenv(propertyKey);
        }
        if (value == null) {
            throw new AgentExecutionException("RUNTIME_PROPERTY_NOT_FOUND", "运行时属性不存在或未注入执行节点");
        }
        logger.info("RUNTIME_PROPERTY_READ", request.taskId(), request.actionType(), "profile=" + profile + " key="
            + propertyKey);
        return outcome(startedAt, 0, null, null, "", "", Map.of("variables", Map.of(variableName, value)));
    }

    /**
     * 绝对坐标只能在显式启用且连接交互桌面的 Agent 上执行。无桌面、服务会话或未授权时必须明确失败，
     * 不能把浏览器 CDP 的视口坐标伪装成物理屏幕坐标。
     */
    private ExecutionOutcome executeHostPointerMove(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        if (!Boolean.getBoolean("sakura.agent.desktop-enabled") || GraphicsEnvironment.isHeadless()) {
            throw new AgentExecutionException("DESKTOP_AGENT_REQUIRED", "当前执行节点没有已启用的交互桌面 Agent，不能执行绝对坐标鼠标操作");
        }
        requireCapability(request, "host_pointer_move", false);
        if (request.x() == null || request.y() == null || request.x() < 0 || request.y() < 0) {
            throw new AgentExecutionException("HOST_POINTER_COORDINATE_INVALID", "绝对坐标 x、y 必须为非负整数");
        }
        try {
            new Robot().mouseMove(request.x(), request.y());
        } catch (Exception exception) {
            throw new AgentExecutionException("HOST_POINTER_MOVE_FAILED", "桌面鼠标移动失败", exception);
        }
        logger.info("HOST_POINTER_MOVED", request.taskId(), request.actionType(), "x=" + request.x() + " y="
            + request.y());
        return outcome(startedAt, 0, null, null, "", "", Map.of());
    }

    /**
     * OCR 命令仅由执行节点部署配置决定，浏览器传入的只是经过大小限制的截图字节。
     * 图片、识别文本和脚本标准输出均不写入日志、任务表或长期文件。
     */
    private ExecutionOutcome executeCaptchaOcr(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        requireCapability(request, "captcha_ocr", false);
        String variableName = validateVariableName(request.variableName());
        String imageBase64 = String.valueOf(request.captchaImageBase64()).trim();
        if (imageBase64.isBlank() || "null".equals(imageBase64)) {
            throw new AgentExecutionException("CAPTCHA_IMAGE_REQUIRED", "验证码 OCR 缺少截图数据");
        }
        byte[] image;
        try {
            image = Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException exception) {
            throw new AgentExecutionException("CAPTCHA_IMAGE_INVALID", "验证码 OCR 截图不是合法 base64", exception);
        }
        if (image.length == 0 || image.length > MAX_CAPTCHA_IMAGE_BYTES) {
            throw new AgentExecutionException("CAPTCHA_IMAGE_TOO_LARGE", "验证码 OCR 截图超过执行节点允许大小");
        }
        String python = System.getProperty("sakura.agent.captcha-ocr-python", "").trim();
        String scriptProperty = System.getProperty("sakura.agent.captcha-ocr-script", "").trim();
        if (python.isBlank() || scriptProperty.isBlank()) {
            throw new AgentExecutionException("CAPTCHA_OCR_NOT_CONFIGURED", "执行节点未配置验证码 OCR Python 命令或脚本");
        }
        Path script = localActionPolicy.resolveExistingAllowedPath(scriptProperty, "captcha_ocr 脚本");
        if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentExecutionException("CAPTCHA_OCR_SCRIPT_INVALID", "验证码 OCR 脚本必须是普通文件");
        }
        Path ocrDirectory = Files.createDirectories(localActionPolicy.workspaceRoot().resolve("captcha-ocr"));
        String safeTaskId = String.valueOf(request.taskId()).replaceAll("[^A-Za-z0-9._-]", "_");
        Path imageFile = ocrDirectory.resolve(safeTaskId + ".png");
        Path resultFile = ocrDirectory.resolve(safeTaskId + ".txt");
        Process process = null;
        try {
            Files.write(imageFile, image);
            Files.deleteIfExists(resultFile);
            // OCR 的标准输出和错误输出均不是契约结果，丢弃以避免异常输出填满管道造成进程阻塞。
            process = new ProcessBuilder(python, script.toString(), imageFile.toString(), resultFile.toString())
                .directory(ocrDirectory.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            Process runningProcess = process;
            cancelActions.put(request.taskId(), () -> runningProcess.destroyForcibly());
            if (!process.waitFor(timeoutMillis(request.timeoutMs()), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AgentExecutionException("CAPTCHA_OCR_TIMEOUT", "验证码 OCR 执行超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(resultFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentExecutionException("CAPTCHA_OCR_FAILED", "验证码 OCR 执行失败");
            }
            String code = Files.readString(resultFile, StandardCharsets.UTF_8).trim();
            if (!code.matches("[A-Za-z0-9]{1,64}")) {
                throw new AgentExecutionException("CAPTCHA_OCR_RESULT_INVALID", "验证码 OCR 返回了非法结果");
            }
            logger.info("CAPTCHA_OCR_COMPLETED", request.taskId(), request.actionType(), "imageBytes=" + image.length);
            return outcome(startedAt, 0, null, null, "", "", Map.of("variables", Map.of(variableName, code)));
        } finally {
            Files.deleteIfExists(imageFile);
            Files.deleteIfExists(resultFile);
        }
    }

    private ExecutionOutcome executeSftpUpload(InfrastructureTaskRequest request, Instant startedAt) throws Exception {
        requireCapability(request, "server_file_upload", false);
        String sourcePath = isBlank(request.sourcePath()) ? request.filePath() : request.sourcePath();
        Path source = localActionPolicy.resolveExistingAllowedPath(sourcePath, "server_file_upload");
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new AgentExecutionException("SFTP_SOURCE_INVALID", "SFTP 上传源必须是普通文件");
        }
        if (request.sshTarget() == null || isBlank(request.sshTarget().host()) || isBlank(request.sshTarget().username())) {
            throw new AgentExecutionException("SFTP_TARGET_REQUIRED", "SFTP 上传缺少 SSH 主机或用户名");
        }
        String requestedRemotePath = normalizeRemotePath(request.remotePath());
        InfrastructureTaskRequest.SshTarget target = request.sshTarget();
        requireKnownHosts();
        JSch jsch = new JSch();
        jsch.setKnownHosts(System.getProperty("sakura.agent.known-hosts"));
        Session session = jsch.getSession(target.username(), target.host(), target.port() == null ? 22 : target.port());
        if (!isBlank(target.password())) {
            session.setPassword(target.password());
        }
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setTimeout(timeoutMillis(request.timeoutMs()));
        ChannelSftp channel = null;
        try {
            logger.info("SFTP_CONNECTING", request.taskId(), request.actionType(), "host=" + target.host() + " port="
                + (target.port() == null ? 22 : target.port()) + " timeoutMs=" + timeoutMillis(request.timeoutMs())
                + " sourceBytes=" + Files.size(source));
            session.connect(timeoutMillis(request.timeoutMs()));
            String actualFingerprint = session.getHostKey() == null ? "unknown" : session.getHostKey().getFingerPrint(jsch);
            if (!isBlank(target.knownHostFingerprint()) && !target.knownHostFingerprint().equalsIgnoreCase(actualFingerprint)) {
                throw new AgentExecutionException("SFTP_HOST_KEY_MISMATCH", "SFTP 主机指纹与环境绑定不一致");
            }
            channel = (ChannelSftp) session.openChannel("sftp");
            ChannelSftp runningChannel = channel;
            cancelActions.put(request.taskId(), () -> closeQuietly(runningChannel, session));
            channel.connect(timeoutMillis(request.timeoutMs()));
            String destination = resolveSftpDestination(channel, requestedRemotePath, source.getFileName().toString(),
                Boolean.TRUE.equals(request.overwrite()));
            try (InputStream input = Files.newInputStream(source)) {
                channel.put(input, destination, ChannelSftp.OVERWRITE);
            }
            long uploadedBytes = Files.size(source);
            logger.info("SFTP_UPLOAD_COMPLETED", request.taskId(), request.actionType(), "uploadedBytes=" + uploadedBytes
                + " overwrite=" + Boolean.TRUE.equals(request.overwrite()));
            return outcome(startedAt, 0, null, null, "", "", Map.of("uploaded_bytes", uploadedBytes));
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (SftpException exception) {
            throw new AgentExecutionException("SFTP_UPLOAD_FAILED", "SFTP 文件上传失败", exception);
        } catch (JSchException | IOException exception) {
            throw new AgentExecutionException("SFTP_CONNECTION_FAILED", "SFTP 连接或文件读取失败", exception);
        } finally {
            closeQuietly(channel, session);
        }
    }

    private void requireCapability(InfrastructureTaskRequest request, String requiredCapability, boolean approvalRequired)
        throws AgentExecutionException {
        boolean capabilityGranted = normalizeCapabilities(request).contains(requiredCapability);
        if (!capabilityGranted) {
            String errorCode = switch (requiredCapability) {
                case "host_file_delete" -> "HOST_FILE_DELETE_CAPABILITY_REQUIRED";
                case "server_file_upload" -> "SFTP_CAPABILITY_REQUIRED";
                case "host_pointer_move" -> "HOST_POINTER_CAPABILITY_REQUIRED";
                case "captcha_ocr" -> "CAPTCHA_OCR_CAPABILITY_REQUIRED";
                default -> "HOST_COMMAND_CAPABILITY_REQUIRED";
            };
            throw new AgentExecutionException(errorCode,
                "当前任务没有 " + requiredCapability + " 执行能力声明");
        }
        if (approvalRequired) {
            requireApproval(request, requiredCapability);
        }
    }

    private void requireApproval(InfrastructureTaskRequest request, String actionType) throws AgentExecutionException {
        if (!Boolean.TRUE.equals(request.approvalGranted()) || isBlank(request.approvalId())) {
            throw new AgentExecutionException("HOST_ACTION_APPROVAL_REQUIRED", actionType + " 需要有效审批声明");
        }
    }

    private Set<String> normalizeCapabilities(InfrastructureTaskRequest request) {
        Set<String> capabilities = new java.util.HashSet<>();
        if (!isBlank(request.capability())) {
            for (String capability : request.capability().split("[,;]")) {
                if (!isBlank(capability)) {
                    capabilities.add(capability.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (request.capabilities() != null) {
            for (String capability : request.capabilities()) {
                if (!isBlank(capability)) {
                    capabilities.add(capability.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return capabilities;
    }

    private HostCommandRisk classifyHostCommand(String command) {
        String normalized = command.replaceAll("[\\r\\n]+", " ").toLowerCase(Locale.ROOT);
        // 这些命令会破坏磁盘/引导或直接关机，即使上游错误授予 approval 也不可在通用 Agent 执行。
        if (normalized.matches("(?s).*\\b(format|diskpart|bcdedit|mkfs|fdisk|parted|vssadmin|wbadmin)\\b.*")
            || normalized.matches("(?s).*\\bdd\\s+if=/dev/(zero|random|urandom).*" )
            || normalized.matches("(?s).*\\brm\\s+-[^\\r\\n]*rf\\s+/(?:\\s|$).*")
            || normalized.matches("(?s).*\\b(shutdown|reboot|poweroff|halt)\\b.*")) {
            return HostCommandRisk.BLOCKED;
        }
        if (normalized.matches("(?s).*\\b(del|erase|rd|rmdir|remove-item|rm|move|copy|taskkill|kill|sc|reg)\\b.*")
            || normalized.matches("(?s).*\\b(sudo|runas)\\b.*") || normalized.matches("(?s).*[;&|]{1,2}.*")) {
            return HostCommandRisk.APPROVAL_REQUIRED;
        }
        return HostCommandRisk.NORMAL;
    }

    private String normalizeHostShell(String hostShell, String compatibilityShell) throws AgentExecutionException {
        String requested = isBlank(hostShell) ? compatibilityShell : hostShell;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        String defaultShell = windows ? "cmd" : "sh";
        String normalized = isBlank(requested) ? defaultShell : requested.trim().toLowerCase(Locale.ROOT);
        if ("cmd.exe".equals(normalized)) {
            normalized = "cmd";
        }
        if ("pwsh".equals(normalized) || "powershell.exe".equals(normalized)) {
            normalized = "powershell";
        }
        if (windows && ("cmd".equals(normalized) || "powershell".equals(normalized))) {
            return normalized;
        }
        if (!windows && ("sh".equals(normalized) || "bash".equals(normalized))) {
            return normalized;
        }
        throw new AgentExecutionException("HOST_COMMAND_SHELL_INVALID", "当前执行节点不支持指定的本机 Shell");
    }

    private List<String> buildHostCommand(String shell, String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        if (windows && "cmd".equals(shell)) {
            String commandProcessor = System.getenv("ComSpec");
            return List.of(isBlank(commandProcessor) ? "cmd.exe" : commandProcessor, "/d", "/s", "/c", command);
        }
        if (windows) {
            String systemRoot = System.getenv("SystemRoot");
            String executable = isBlank(systemRoot) ? "powershell.exe"
                : Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
            return List.of(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command);
        }
        return "bash".equals(shell)
            ? List.of("/bin/bash", "-lc", command)
            : List.of("/bin/sh", "-c", command);
    }

    private Thread drainOutput(InputStream input, CappedOutputStream output, String threadName) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) {
                        output.write(buffer, 0, read);
                    }
                }
            } catch (IOException ignored) {
                // 进程被取消/强制终止时流会关闭；不应覆盖任务的原始结果。
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void joinDrainer(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void terminateProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private int normalizedFileResultLimit(Integer maxResults, int maxRows) {
        int requested = maxResults == null || maxResults <= 0 ? (maxRows <= 0 ? DEFAULT_MAX_FILE_RESULTS : maxRows) : maxResults;
        return Math.min(MAX_FILE_RESULTS, Math.max(1, requested));
    }

    private String normalizeFilePattern(String filePattern) throws AgentExecutionException {
        if (isBlank(filePattern)) {
            return "*";
        }
        String normalized = filePattern.trim();
        if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")
            || normalized.contains("${") || normalized.contains("{{") || normalized.contains("%")) {
            throw new AgentExecutionException("HOST_FILE_PATTERN_INVALID", "文件查询模式只能匹配文件名，不能包含目录或变量表达式");
        }
        try {
            Path.of("placeholder").getFileSystem().getPathMatcher("glob:" + normalized);
            return normalized;
        } catch (Exception exception) {
            throw new AgentExecutionException("HOST_FILE_PATTERN_INVALID", "文件查询模式不合法", exception);
        }
    }

    private boolean matchesFilePattern(Path path, String pattern) {
        return path.getFileSystem().getPathMatcher("glob:" + pattern).matches(path.getFileName());
    }

    private Map<String, Object> fileSummary(Path file) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
            return Map.of("name", file.getFileName().toString(), "size", Files.size(file), "last_modified",
                lastModified.toInstant().toString());
        } catch (IOException exception) {
            throw new IllegalStateException("读取文件元数据失败", exception);
        }
    }

    private void ensureNoSymbolicLinks(Path root) throws IOException, AgentExecutionException {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new IOException("symbolic-link");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        throw new IOException("symbolic-link");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            if ("symbolic-link".equals(exception.getMessage())) {
                throw new AgentExecutionException("HOST_PATH_SYMLINK_REJECTED", "递归删除目录不能包含符号链接");
            }
            throw exception;
        }
    }

    private int deleteDirectoryTree(Path root) throws IOException {
        final int[] deleted = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                deleted[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                deleted[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return deleted[0];
    }

    private String validateVariableName(String variableName) throws AgentExecutionException {
        if (isBlank(variableName) || !variableName.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) {
            throw new AgentExecutionException("VARIABLE_NAME_INVALID", "变量名必须以字母或下划线开头，且只能包含字母、数字、点、短横线或下划线");
        }
        return variableName;
    }

    private String preferredHostAddress() throws AgentExecutionException {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                    if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                        continue;
                    }
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            throw new AgentExecutionException("SYSTEM_INFO_UNAVAILABLE", "执行节点系统信息不可用", exception);
        }
    }

    private String localHostName() throws AgentExecutionException {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            throw new AgentExecutionException("SYSTEM_INFO_UNAVAILABLE", "执行节点系统信息不可用", exception);
        }
    }

    private String systemProperty(String key) {
        return System.getProperty(key, "");
    }

    private String validatePrivateIpv4Prefix(String rawPrefix) throws AgentExecutionException {
        if (isBlank(rawPrefix)) {
            throw new AgentExecutionException("AVAILABLE_IP_PREFIX_INVALID", "可用 IP 探测缺少 IPv4 前缀");
        }
        String[] parts = rawPrefix.trim().split("\\.", -1);
        if (parts.length != 3) {
            throw new AgentExecutionException("AVAILABLE_IP_PREFIX_INVALID", "可用 IP 探测只接受三个网段的 IPv4 前缀");
        }
        int[] octets = new int[3];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (!parts[index].matches("\\d{1,3}")) {
                    throw new NumberFormatException();
                }
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] > 255) {
                    throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException exception) {
            throw new AgentExecutionException("AVAILABLE_IP_PREFIX_INVALID", "可用 IP 探测前缀不是合法 IPv4 地址", exception);
        }
        boolean privateOrLoopback = octets[0] == 10 || octets[0] == 127
            || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
            || (octets[0] == 192 && octets[1] == 168);
        if (!privateOrLoopback) {
            throw new AgentExecutionException("AVAILABLE_IP_PREFIX_NOT_PRIVATE", "可用 IP 探测仅允许私有或回环 IPv4 网段");
        }
        return octets[0] + "." + octets[1] + "." + octets[2];
    }

    private int normalizeIpHostPart(Integer value, String name) throws AgentExecutionException {
        if (value == null || value < 1 || value > 254) {
            throw new AgentExecutionException("AVAILABLE_IP_RANGE_INVALID", "可用 IP 探测 " + name + " 必须在 1 到 254 之间");
        }
        return value;
    }

    private String normalizeRemotePath(String remotePath) throws AgentExecutionException {
        if (isBlank(remotePath)) {
            throw new AgentExecutionException("SFTP_REMOTE_PATH_REQUIRED", "SFTP 上传缺少远程路径");
        }
        String normalized = remotePath.trim();
        if (normalized.indexOf('\u0000') >= 0 || normalized.contains("${") || normalized.contains("{{")
            || normalized.matches("(?s).*(^|/)\\.\\.(/|$).*")) {
            throw new AgentExecutionException("SFTP_REMOTE_PATH_INVALID", "SFTP 远程路径不能包含变量表达式或上级目录片段");
        }
        return normalized;
    }

    private String resolveSftpDestination(ChannelSftp channel,
                                          String requestedRemotePath,
                                          String sourceFileName,
                                          boolean overwrite) throws SftpException, AgentExecutionException {
        String destination = requestedRemotePath;
        try {
            if (channel.lstat(destination).isDir()) {
                destination = destination.endsWith("/") ? destination + sourceFileName : destination + "/" + sourceFileName;
            } else if (!overwrite) {
                throw new AgentExecutionException("SFTP_REMOTE_FILE_EXISTS", "SFTP 目标文件已存在，需显式设置 overwrite=true");
            }
        } catch (SftpException exception) {
            if (exception.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw exception;
            }
            if (requestedRemotePath.endsWith("/")) {
                destination = requestedRemotePath + sourceFileName;
            }
        }
        return destination;
    }

    private enum HostCommandRisk {
        NORMAL,
        APPROVAL_REQUIRED,
        BLOCKED
    }

    private ExecutionOutcome executeQuery(PreparedStatement statement, Instant startedAt, int maxRows) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return outcome(startedAt, 0, null, readRows(resultSet, maxRows), "", "");
        }
    }

    private ExecutionOutcome executeCall(PreparedStatement statement, Instant startedAt, int maxRows) throws SQLException {
        boolean hasResultSet = statement.execute();
        if (!hasResultSet) {
            return outcome(startedAt, 0, statement.getUpdateCount(), null, "", "");
        }
        try (ResultSet resultSet = statement.getResultSet()) {
            return outcome(startedAt, 0, null, readRows(resultSet, maxRows), "", "");
        }
    }

    private ExecutionOutcome mongoFind(MongoCollection<Document> collection, Document filter, Instant startedAt, int maxRows) {
        int limit = Math.max(1, maxRows == 0 ? DEFAULT_MAX_ROWS : maxRows);
        List<Map<String, Object>> rows = collection.find(filter).limit(limit).map(document ->
            objectMapper.convertValue(document, new TypeReference<Map<String, Object>>() { })).into(new ArrayList<>());
        return outcome(startedAt, 0, null, rows, "", "");
    }

    private Driver loadDriver(InfrastructureTaskRequest request) throws Exception {
        InfrastructureTaskRequest.JdbcTarget target = request.jdbcTarget();
        if (isBlank(target.driverClass()) || isBlank(target.driverProfile())) {
            throw new IllegalArgumentException("JDBC 连接缺少驱动档案或驱动类");
        }
        Path profileDirectory = driverDirectory.resolve(target.driverProfile()).normalize();
        logger.info("JDBC_DRIVER_LOADING", request.taskId(), request.actionType(), "driverProfile=" + target.driverProfile()
            + " directory=" + profileDirectory);
        if (!profileDirectory.startsWith(driverDirectory) || !Files.isDirectory(profileDirectory)) {
            throw new IllegalArgumentException("JDBC 驱动档案未部署：" + target.driverProfile());
        }
        List<URL> urls;
        try (var paths = Files.list(profileDirectory)) {
            urls = paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted(Comparator.comparing(Path::toString)).map(this::toUrl).toList();
        }
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("JDBC 驱动档案没有可用 JAR：" + target.driverProfile());
        }
        logger.info("JDBC_DRIVER_FILES_FOUND", request.taskId(), request.actionType(), "driverProfile=" + target.driverProfile()
            + " jarCount=" + urls.size());
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        Class<?> driverClass = Class.forName(target.driverClass(), true, loader);
        Object instance = driverClass.getDeclaredConstructor().newInstance();
        if (!(instance instanceof Driver driver)) {
            loader.close();
            throw new IllegalArgumentException("配置的 JDBC 驱动类未实现 java.sql.Driver");
        }
        logger.info("JDBC_DRIVER_LOADED", request.taskId(), request.actionType(), "driverProfile=" + target.driverProfile()
            + " driverClass=" + target.driverClass());
        return new IsolatedDriver(driver, loader);
    }

    private URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 JDBC 驱动文件", exception);
        }
    }

    private void bindParameters(PreparedStatement statement, List<InfrastructureTaskRequest.JdbcParameter> parameters)
        throws SQLException {
        if (parameters == null) {
            return;
        }
        for (int index = 0; index < parameters.size(); index++) {
            InfrastructureTaskRequest.JdbcParameter parameter = parameters.get(index);
            if (parameter == null || parameter.value() == null) {
                statement.setObject(index + 1, null);
                continue;
            }
            if (isBlank(parameter.jdbcType())) {
                statement.setObject(index + 1, parameter.value());
                continue;
            }
            statement.setObject(index + 1, parameter.value(), JDBCType.valueOf(parameter.jdbcType().toUpperCase(Locale.ROOT)));
        }
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet, int requestedMaxRows) throws SQLException {
        int limit = Math.max(1, requestedMaxRows == 0 ? DEFAULT_MAX_ROWS : requestedMaxRows);
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < limit) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                row.put(metadata.getColumnLabel(column), normalizeValue(resultSet.getObject(column)));
            }
            rows.add(row);
        }
        return rows;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof byte[] bytes) {
            return "<binary:" + bytes.length + " bytes>";
        }
        String rendered = String.valueOf(value);
        return rendered.length() > 4096 ? rendered.substring(0, 4096) + "…" : value;
    }

    private Document asDocument(Map<String, Object> values) {
        return values == null ? new Document() : new Document(values);
    }

    private void requireKnownHosts() {
        String knownHosts = System.getProperty("sakura.agent.known-hosts");
        if (isBlank(knownHosts) || !Files.isRegularFile(Path.of(knownHosts))) {
            throw new IllegalStateException("SSH 执行节点未配置受信任主机文件 sakura.agent.known-hosts");
        }
    }

    private void cancelStatement(Statement statement, Connection connection) {
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // 部分旧驱动不支持 cancel，随后关闭连接以确保任务停止。
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接已经关闭时无需覆盖原始任务错误。
        }
    }

    private void closeQuietly(ChannelExec channel, Session session) {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private void closeQuietly(ChannelSftp channel, Session session) {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    private ExecutionOutcome outcome(Instant startedAt, int exitCode, Integer affectedRows,
                                     List<Map<String, Object>> rows, String stdout, String stderr) {
        return outcome(startedAt, exitCode, affectedRows, rows, stdout, stderr, Map.of());
    }

    private ExecutionOutcome outcome(Instant startedAt, int exitCode, Integer affectedRows,
                                     List<Map<String, Object>> rows, String stdout, String stderr,
                                     Map<String, Object> result) {
        long duration = Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
        return new ExecutionOutcome(duration, exitCode, affectedRows, rows, stdout, stderr,
            result == null ? Map.of() : Map.copyOf(result));
    }

    private int timeoutMillis(long timeoutMs) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1_000, timeoutMs <= 0 ? 60_000 : timeoutMs));
    }

    private int queryTimeoutSeconds(long timeoutMs) {
        return Math.max(1, (int) Math.ceil(timeoutMillis(timeoutMs) / 1000.0));
    }

    private String normalizeSqlMode(String sqlMode) {
        return isBlank(sqlMode) ? "query" : sqlMode.toLowerCase(Locale.ROOT);
    }

    private String normalizeMongoOperation(String operation) {
        return isBlank(operation) ? "find" : operation.toLowerCase(Locale.ROOT);
    }

    private String normalizePlatform(String platform) {
        if (isBlank(platform)) {
            // 兼容旧 Admin 请求；升级完成后平台始终来自服务器配置。
            return "linux";
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("windows")) {
            return "windows";
        }
        if (normalized.contains("linux") || normalized.contains("unix")) {
            return "linux";
        }
        throw new IllegalArgumentException("不支持的服务器平台：" + platform);
    }

    private String normalizeShell(String shell) {
        if (isBlank(shell)) {
            return "bash";
        }
        String normalized = shell.trim().toLowerCase(Locale.ROOT);
        if ("pwsh".equals(normalized) || "powershell.exe".equals(normalized)) {
            return "powershell";
        }
        if (!List.of("bash", "sh", "powershell").contains(normalized)) {
            throw new IllegalArgumentException("不支持的 Shell 类型：" + shell);
        }
        return normalized;
    }

    private void validateShell(String platform, String shell) {
        if ("windows".equals(platform) && !"powershell".equals(shell)) {
            throw new IllegalArgumentException("Windows 服务器仅支持 PowerShell；当前选择：" + shell);
        }
    }

    private String buildShellCommand(String platform, String shell, String command) {
        return switch (shell) {
            case "bash" -> "bash -lc " + quotePosix(command);
            case "sh" -> "sh -lc " + quotePosix(command);
            case "powershell" -> "linux".equals(platform)
                ? "pwsh -NoLogo -NoProfile -NonInteractive -Command " + quotePosix(command)
                : "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand " + encodePowerShellCommand(command);
            default -> throw new IllegalArgumentException("不支持的 Shell 类型：" + shell);
        };
    }

    private String quotePosix(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String encodePowerShellCommand(String command) {
        return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    }

    private void checkPwshInstalled(Session session, InfrastructureTaskRequest request) throws Exception {
        logger.info("SSH_PWSH_CHECK_STARTED", request.taskId(), request.actionType(),
            "platform=linux executable=pwsh");
        ChannelExec probe = null;
        try {
            probe = (ChannelExec) session.openChannel("exec");
            probe.setCommand("command -v pwsh 2>/dev/null");
            CappedOutputStream output = new CappedOutputStream(1024);
            probe.setOutputStream(output);
            probe.connect(Math.min(timeoutMillis(request.timeoutMs()), 5_000));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!probe.isClosed()) {
                if (System.nanoTime() > deadline) {
                    logger.error("SSH_PWSH_CHECK_FAILED", request.taskId(), request.actionType(),
                        "reason=检查pwsh是否安装超时 timeoutMs=5000");
                    throw new IllegalStateException("检查 Linux 目标服务器是否安装 pwsh 超时");
                }
                Thread.sleep(25);
            }
            if (probe.getExitStatus() != 0) {
                logger.error("SSH_PWSH_MISSING", request.taskId(), request.actionType(),
                    "reason=Linux目标服务器未找到pwsh executable=pwsh exitCode=" + probe.getExitStatus());
                throw new IllegalStateException("Linux 目标服务器未安装 pwsh，无法执行 PowerShell；请安装 PowerShell Core 或改选 bash/sh");
            }
            logger.info("SSH_PWSH_AVAILABLE", request.taskId(), request.actionType(),
                "executable=pwsh path=" + (output.text().isBlank() ? "pwsh" : output.text().trim()) + " exitCode=0");
        } catch (JSchException e) {
            logger.error("SSH_PWSH_CHECK_FAILED", request.taskId(), request.actionType(),
                "reason=" + sshFailureReason(e));
            throw e;
        } finally {
            closeQuietly(probe, null);
        }
    }

    private String sshFailureReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "未返回 SSH 异常描述";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        if (normalized.toLowerCase(Locale.ROOT).contains("reject hostkey")) {
            return "SSH 主机密钥校验失败：known_hosts 中没有与目标主机匹配的公钥（原因=" + normalized + "）";
        }
        if (normalized.toLowerCase(Locale.ROOT).contains("auth fail")) {
            return "SSH 用户名或密码认证失败（原因=" + normalized + "）";
        }
        if (normalized.toLowerCase(Locale.ROOT).contains("connection refused")) {
            return "SSH 连接被目标主机拒绝，请检查端口和 SSH 服务（原因=" + normalized + "）";
        }
        if (normalized.toLowerCase(Locale.ROOT).contains("timeout")) {
            return "SSH 连接超时，请检查网络、端口和防火墙（原因=" + normalized + "）";
        }
        return normalized;
    }

    /**
     * 只记录能定位目标库的端点信息，不写连接串原文及用户名/密码。
     * JDBC URL 在不同厂商间格式差异较大，因此未解析的部分以稳定指纹替代。
     */
    private String jdbcConnectionDiagnostic(InfrastructureTaskRequest.JdbcTarget target) {
        String jdbcUrl = target.jdbcUrl();
        String dialect = jdbcDialect(jdbcUrl);
        String host = extractHost(jdbcUrl);
        String port = extractPort(jdbcUrl);
        String database = extractDatabase(jdbcUrl);
        return "dialect=" + dialect + " host=" + host + " port=" + port + " database=" + database
            + " usernameConfigured=" + !isBlank(target.username()) + " jdbcUrlFingerprint=" + fingerprint(jdbcUrl);
    }

    private String jdbcDialect(String jdbcUrl) {
        if (isBlank(jdbcUrl)) {
            return "unknown";
        }
        String withoutPrefix = jdbcUrl.substring(Math.min(5, jdbcUrl.length()));
        int separator = withoutPrefix.indexOf(':');
        return separator < 0 ? withoutPrefix : withoutPrefix.substring(0, separator);
    }

    private String extractHost(String jdbcUrl) {
        java.util.regex.Matcher authority = java.util.regex.Pattern.compile(
            "(?i)^jdbc:[^:]+:(?://)?(?:[^@/?;]+@)?(\\[[^]]+]|[^:/;?]+)(?::\\d+)?")
            .matcher(jdbcUrl == null ? "" : jdbcUrl);
        if (authority.find()) {
            String candidate = authority.group(1);
            if (!candidate.equalsIgnoreCase("thin") && !candidate.equalsIgnoreCase("sqli")) {
                return candidate;
            }
        }
        java.util.regex.Matcher oracle = java.util.regex.Pattern.compile("(?i)@(?://)?(\\[[^]]+]|[^:/;?]+)(?::\\d+)?")
            .matcher(jdbcUrl == null ? "" : jdbcUrl);
        return oracle.find() ? oracle.group(1) : "unparsed";
    }

    private String extractPort(String jdbcUrl) {
        java.util.regex.Matcher port = java.util.regex.Pattern.compile("(?i)(?::|@)(?://)?(?:\\[[^]]+]|[^:/;?]+):(\\d+)")
            .matcher(jdbcUrl == null ? "" : jdbcUrl);
        return port.find() ? port.group(1) : "default-or-unparsed";
    }

    private String extractDatabase(String jdbcUrl) {
        String source = jdbcUrl == null ? "" : jdbcUrl;
        java.util.regex.Matcher namedDatabase = java.util.regex.Pattern.compile(
            "(?i)(?:[;?&]|/)database(?:Name)?=([^;?&/]+)|(?:[;?&])schema=([^;?&/]+)|(?:[;?&])DATABASE=([^,;?&/]+)")
            .matcher(source);
        if (namedDatabase.find()) {
            for (int group = 1; group <= namedDatabase.groupCount(); group++) {
                if (namedDatabase.group(group) != null) {
                    return namedDatabase.group(group);
                }
            }
        }
        java.util.regex.Matcher path = java.util.regex.Pattern.compile("(?i)://(?:[^@/?;]+@)?(?:\\[[^]]+]|[^:/;?]+)(?::\\d+)?/([^;?]+)")
            .matcher(source);
        if (path.find()) {
            return path.group(1);
        }
        java.util.regex.Matcher oracle = java.util.regex.Pattern.compile("(?i)@(?://)?(?:\\[[^]]+]|[^:/;?]+):\\d+(?:[:/])([^;?]+)")
            .matcher(source);
        return oracle.find() ? oracle.group(1) : "unparsed";
    }

    private String parameterTypes(List<InfrastructureTaskRequest.JdbcParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "none";
        }
        return parameters.stream().map(parameter -> parameter == null || isBlank(parameter.jdbcType()) ? "AUTO"
            : parameter.jdbcType().toUpperCase(Locale.ROOT)).limit(20).collect(java.util.stream.Collectors.joining(","));
    }

    /** MongoDB 仅保留服务端点和目标库；认证信息及连接选项绝不能写入日志。 */
    private String mongoConnectionDiagnostic(InfrastructureTaskRequest.MongoTarget target) {
        String connectionString = target.connectionString();
        String scheme = connectionString != null && connectionString.toLowerCase(Locale.ROOT).startsWith("mongodb+srv://")
            ? "mongodb+srv" : "mongodb";
        String withoutScheme = connectionString == null ? "" : connectionString.replaceFirst("(?i)^mongodb(?:\\+srv)?://", "");
        int authorityEnd = withoutScheme.indexOf('/');
        String authority = authorityEnd < 0 ? withoutScheme : withoutScheme.substring(0, authorityEnd);
        int credentialsEnd = authority.lastIndexOf('@');
        String endpoints = credentialsEnd < 0 ? authority : authority.substring(credentialsEnd + 1);
        return "mongoScheme=" + scheme + " endpoints=" + (endpoints.isBlank() ? "unparsed" : endpoints)
            + " database=" + target.database() + " connectionFingerprint=" + fingerprint(connectionString);
    }

    private String fingerprint(String value) {
        if (value == null) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (int index = 0; index < 8; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ExecutionOutcome(long durationMs,
                                   Integer exitCode,
                                   Integer affectedRows,
                                   List<Map<String, Object>> rows,
                                   String stdout,
                                   String stderr,
                                   Map<String, Object> result) {

        public ExecutionOutcome(long durationMs,
                                Integer exitCode,
                                Integer affectedRows,
                                List<Map<String, Object>> rows,
                                String stdout,
                                String stderr) {
            this(durationMs, exitCode, affectedRows, rows, stdout, stderr, Map.of());
        }
    }

    /** 附着隔离类加载器，连接关闭后由 JVM 在该任务对象回收时卸载厂商驱动。 */
    private record IsolatedDriver(Driver delegate, URLClassLoader loader) implements Driver {
        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        private void close() {
            try {
                loader.close();
            } catch (IOException ignored) {
                // 驱动文件句柄已释放或当前平台无需显式释放时不影响任务结果。
            }
        }
    }

    private static final class CappedOutputStream extends OutputStream {
        private final int capacity;
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private boolean truncated;

        private CappedOutputStream(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public void write(int value) {
            if (delegate.size() < capacity) {
                delegate.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            int remaining = capacity - delegate.size();
            if (remaining > 0) {
                delegate.write(values, offset, Math.min(remaining, length));
            }
            truncated |= length > remaining;
        }

        private String text() {
            return delegate.toString(StandardCharsets.UTF_8) + (truncated ? "\n[输出已截断]" : "");
        }

        private int size() {
            return delegate.size();
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
