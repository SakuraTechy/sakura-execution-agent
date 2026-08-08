package top.continew.sakura.agent.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.model.InfrastructureTaskResponse;
import top.continew.sakura.agent.model.InfrastructureResultV2;
import top.continew.sakura.agent.support.AgentExecutionException;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

/** 任务幂等、异步执行和取消的唯一入口。 */
public class InfrastructureTaskService implements AutoCloseable {

    private static final Set<String> SUPPORTED_ACTIONS = Set.of("server_command", "database_sql", "database_native",
        "host_command", "host_file_lookup", "host_file_delete", "global_variable_system_info",
        "global_variable_available_ip", "global_variable_property", "captcha_ocr", "server_file_upload",
        "host_pointer_move");

    /**
     * 返回 Agent 实际接受并路由到执行器的动作集合，供能力握手和统一目录契约测试读取。
     */
    public static Set<String> supportedActions() {
        return Set.copyOf(SUPPORTED_ACTIONS);
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, TaskRuntime> tasks = new ConcurrentHashMap<>();
    private final InfrastructureTaskExecutor taskExecutor;
    private final AgentLogger logger;
    private final DurableTaskLedger taskLedger;
    private final InfrastructureArtifactStore artifactStore;
    private volatile boolean shuttingDown;

    public InfrastructureTaskService(ObjectMapper objectMapper, Path driverDirectory, AgentLogger logger) {
        this(objectMapper, driverDirectory, logger, LocalActionPolicy.fromSystemProperties());
    }

    public InfrastructureTaskService(ObjectMapper objectMapper,
                                     Path driverDirectory,
                                     AgentLogger logger,
                                     LocalActionPolicy localActionPolicy) {
        this(objectMapper, driverDirectory, logger, localActionPolicy, defaultLedgerPath(driverDirectory));
    }

    public InfrastructureTaskService(ObjectMapper objectMapper,
                                     Path driverDirectory,
                                     AgentLogger logger,
                                     LocalActionPolicy localActionPolicy,
                                     Path ledgerFile) {
        this.taskExecutor = new InfrastructureTaskExecutor(objectMapper, driverDirectory, logger, localActionPolicy);
        this.logger = logger;
        this.taskLedger = new DurableTaskLedger(objectMapper, ledgerFile);
        this.artifactStore = new InfrastructureArtifactStore(objectMapper, artifactDirectory(ledgerFile));
        taskLedger.snapshots().values().forEach(entry -> tasks.put(entry.taskId(), new TaskRuntime(entry)));
    }

    public InfrastructureTaskResponse submit(InfrastructureTaskRequest request) {
        validate(request);
        logger.info("TASK_VALIDATED", request.taskId(), request.actionType(),
            "timeoutMs=" + request.timeoutMs() + " maxRows=" + request.maxRows());
        DurableTaskLedger.Entry ledgerEntry = taskLedger.register(request);
        TaskRuntime existing = tasks.get(request.taskId());
        if (existing != null) {
            logger.info("TASK_DUPLICATE", request.taskId(), request.actionType(), "status=" + existing.response.status());
            return existing.response;
        }
        TaskRuntime runtime = new TaskRuntime(request, ledgerEntry.payloadDigest());
        TaskRuntime raced = tasks.putIfAbsent(request.taskId(), runtime);
        if (raced != null) {
            logger.info("TASK_DUPLICATE", request.taskId(), request.actionType(), "status=" + raced.response.status());
            return raced.response;
        }
        logger.info("TASK_ACCEPTED", request.taskId(), request.actionType(), null);
        runtime.future = executor.submit(() -> execute(runtime));
        return runtime.response;
    }

    public InfrastructureTaskResponse get(String taskId) {
        TaskRuntime runtime = tasks.get(taskId);
        if (runtime == null) {
            throw new IllegalArgumentException("基础设施任务不存在：" + taskId);
        }
        return runtime.response;
    }

    public InfrastructureArtifactStore.ArtifactContent getArtifact(String taskId) {
        if (!tasks.containsKey(taskId)) {
            throw new IllegalArgumentException("基础设施任务不存在：" + taskId);
        }
        return artifactStore.load(taskId);
    }

    public InfrastructureTaskResponse cancel(String taskId) {
        TaskRuntime runtime = tasks.get(taskId);
        if (runtime == null) {
            throw new IllegalArgumentException("基础设施任务不存在：" + taskId);
        }
        runtime.cancelRequested = true;
        taskExecutor.cancel(taskId);
        Future<?> future = runtime.future;
        if (future != null) {
            future.cancel(true);
        }
        if (!isTerminal(runtime.response.status())) {
            transition(runtime, response(runtime.request, "cancelled", 0, null, null, null, "TASK_CANCELLED", "任务已取消",
                runtime.startedAt, Instant.now()));
        }
        logger.warn("TASK_CANCELLED", runtime.taskId, runtime.actionType, null);
        return runtime.response;
    }

    private void execute(TaskRuntime runtime) {
        runtime.startedAt = Instant.now();
        transition(runtime, response(runtime.request, "running", 0, null, null, null, null, null, runtime.startedAt, null));
        logger.info("TASK_STARTED", runtime.request.taskId(), runtime.request.actionType(), null);
        try {
            InfrastructureTaskExecutor.ExecutionOutcome outcome = taskExecutor.execute(runtime.request);
            if (runtime.cancelRequested) {
                transition(runtime, response(runtime.request, "cancelled", outcome.durationMs(), outcome.exitCode(),
                    outcome.affectedRows(), outcome, "TASK_CANCELLED", "任务已取消", runtime.startedAt, Instant.now()));
                logger.warn("TASK_CANCELLED", runtime.request.taskId(), runtime.request.actionType(),
                    "durationMs=" + outcome.durationMs());
                return;
            }
            if (("server_command".equals(runtime.request.actionType()) || "host_command".equals(runtime.request.actionType()))
                && outcome.exitCode() != null && outcome.exitCode() != 0) {
                boolean hostCommand = "host_command".equals(runtime.request.actionType());
                transition(runtime, response(runtime.request, "failed", outcome.durationMs(), outcome.exitCode(),
                    outcome.affectedRows(), outcome, hostCommand ? "HOST_COMMAND_EXIT_NON_ZERO" : "SERVER_COMMAND_EXIT_NON_ZERO",
                    hostCommand ? "本机命令返回非零退出码" : "服务器命令返回非零退出码",
                    runtime.startedAt, Instant.now()));
                logger.error("TASK_FAILED", runtime.request.taskId(), runtime.request.actionType(),
                    "code=SERVER_COMMAND_EXIT_NON_ZERO durationMs=" + outcome.durationMs());
                return;
            }
            transition(runtime, response(runtime.request, "passed", outcome.durationMs(), outcome.exitCode(),
                outcome.affectedRows(), outcome, null, null, runtime.startedAt, Instant.now()));
            logger.info("TASK_PASSED", runtime.request.taskId(), runtime.request.actionType(),
                "durationMs=" + outcome.durationMs() + " exitCode=" + outcome.exitCode() + " affectedRows=" + outcome
                    .affectedRows() + " rowCount=" + (outcome.rows() == null ? 0 : outcome.rows().size()));
        } catch (Exception e) {
            boolean uncertain = shuttingDown && !runtime.cancelRequested;
            String code = uncertain ? "TASK_UNKNOWN_OUTCOME" : runtime.cancelRequested ? "TASK_CANCELLED" : executionErrorCode(e);
            String status = uncertain ? "unknown_outcome" : runtime.cancelRequested ? "cancelled" : "failed";
            String errorMessage = "server_command".equals(runtime.request.actionType()) ? sshFailureMessage(e) : sanitize(e
                .getMessage());
            if (uncertain) {
                errorMessage = "Agent 关闭时无法确认任务结果，禁止自动重试";
            }
            transition(runtime, response(runtime.request, status, elapsed(runtime.startedAt), null, null, null, code,
                errorMessage, runtime.startedAt, Instant.now()));
            logger.error("TASK_FAILED", runtime.request.taskId(), runtime.request.actionType(),
                "code=" + code + " errorType=" + e.getClass().getSimpleName() + " reason=" + errorMessage);
        }
    }

    private InfrastructureTaskResponse response(InfrastructureTaskRequest request,
                                                String status,
                                                long durationMs,
                                                Integer exitCode,
                                                Long affectedRows,
                                                InfrastructureTaskExecutor.ExecutionOutcome outcome,
                                                String errorCode,
                                                String error,
                                                Instant startedAt,
                                                Instant finishedAt) {
        Map<String, Object> result = outcome == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(outcome.result());
        result.put("infrastructure", infrastructureResult(request, status, durationMs, exitCode, affectedRows, outcome,
            error));
        return new InfrastructureTaskResponse(request.taskId(), status, request.actionType(), durationMs, exitCode,
            affectedRows, outcome == null ? null : outcome.rows(), outcome == null ? "" : outcome.stdout(),
            outcome == null ? "" : outcome.stderr(), errorCode, error, startedAt, finishedAt,
            Map.copyOf(result));
    }

    private InfrastructureResultV2 infrastructureResult(InfrastructureTaskRequest request,
                                                         String status,
                                                         long durationMs,
                                                         Integer exitCode,
                                                         Long affectedRows,
                                                         InfrastructureTaskExecutor.ExecutionOutcome outcome,
                                                         String error) {
        String stdout = safeOutput(outcome == null ? "" : outcome.stdout());
        String stderr = safeOutput(outcome == null ? "" : outcome.stderr());
        boolean truncated = (outcome != null && outcome.truncated()) || stdout.length() >= RESULT_OUTPUT_LIMIT
            || stderr.length() >= RESULT_OUTPUT_LIMIT;
        String kind = resultKind(request);
        Map<String, Object> artifactPayload = new java.util.LinkedHashMap<>();
        if (outcome != null) {
            if (outcome.artifactResult() != null && !outcome.artifactResult().isEmpty()) {
                artifactPayload.putAll(outcome.artifactResult());
            } else if (outcome.results() != null && !outcome.results().isEmpty()) {
                artifactPayload.put("type", "RESULTS");
                artifactPayload.put("results", outcome.results());
            }
            String artifactStdout = safeArtifactOutput(outcome.stdout());
            String artifactStderr = safeArtifactOutput(outcome.stderr());
            if (!artifactStdout.isEmpty()) artifactPayload.put("stdout", artifactStdout);
            if (!artifactStderr.isEmpty()) artifactPayload.put("stderr", artifactStderr);
        }
        Map<String, Object> artifact = outcome == null
            ? new InfrastructureArtifactStore.ArtifactMetadata(false, null, null, null, 0L, null, null).toMap()
            : artifactStore.store(request.taskId(), kind, artifactPayload, outcome.warnings()).toMap();
        return new InfrastructureResultV2(2, kind, exitCode, durationMs, outcome == null
            ? List.of()
            : outcome.results(), outcome == null ? List.of() : outcome.warnings(), stdout, stderr, truncated, artifact);
    }

    private String safeOutput(String value) {
        return sanitizedOutput(value, RESULT_OUTPUT_LIMIT);
    }

    private String safeArtifactOutput(String value) {
        return sanitizedOutput(value, ARTIFACT_OUTPUT_LIMIT);
    }

    private String sanitizedOutput(String value, int limit) {
        if (value == null || value.isBlank()) return "";
        String sanitized = value.replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1***")
            .replaceAll("(?i)(password|passwd|pwd|token|secret|api[_-]?key)\\s*([=:])\\s*[^\\s,;]+", "$1$2***");
        return sanitized.length() <= limit ? sanitized : sanitized.substring(0, limit) + "\n[输出已截断]";
    }

    private String resultKind(InfrastructureTaskRequest request) {
        String action = String.valueOf(request.actionType()).toLowerCase(java.util.Locale.ROOT);
        if ("database_native".equals(action)) return "DATABASE_NATIVE";
        if ("database_sql".equals(action)) {
            return switch (String.valueOf(request.sqlMode()).toLowerCase(java.util.Locale.ROOT)) {
                case "update" -> "DATABASE_UPDATE";
                case "call" -> "DATABASE_CALL";
                default -> "DATABASE_QUERY";
            };
        }
        return "SERVER_COMMAND";
    }

    private static final int RESULT_OUTPUT_LIMIT = 64 * 1024;
    private static final int ARTIFACT_OUTPUT_LIMIT = 4 * 1024 * 1024;

    private void validate(InfrastructureTaskRequest request) {
        if (request == null || request.taskId() == null || request.taskId().isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (request.actionType() == null || request.actionType().isBlank()) {
            throw new IllegalArgumentException("actionType 不能为空");
        }
        if (!SUPPORTED_ACTIONS.contains(request.actionType())) {
            throw new IllegalArgumentException("不支持的 actionType：" + request.actionType());
        }
        String riskLevel = request.riskLevel() == null ? "" : request.riskLevel().trim().toLowerCase();
        if (!Set.of("read", "write", "destructive", "host-privileged").contains(riskLevel)) {
            throw new IllegalArgumentException("riskLevel 必须由 Admin 明确声明");
        }
        if (!"read".equals(riskLevel) && (!Boolean.TRUE.equals(request.approvalGranted())
            || request.approvalId() == null || request.approvalId().isBlank()
            || request.approvalDigest() == null || !request.approvalDigest().matches("[0-9a-fA-F]{64}"))) {
            throw new IllegalArgumentException("写入、破坏性或主机高权限任务缺少有效审批摘要");
        }
        if ("database_sql".equals(request.actionType()) && "query".equalsIgnoreCase(request.sqlMode())
            && (!"read".equals(riskLevel) || !Boolean.TRUE.equals(request.readOnlyEnforced()))) {
            throw new IllegalArgumentException("database_sql query 必须声明 read 风险并强制只读事务");
        }
    }

    private boolean isTerminal(String status) {
        return "passed".equals(status) || "failed".equals(status) || "cancelled".equals(status)
            || "unknown_outcome".equals(status);
    }

    private void transition(TaskRuntime runtime, InfrastructureTaskResponse response) {
        runtime.response = response;
        taskLedger.update(runtime.payloadDigest, response);
    }

    private static Path defaultLedgerPath(Path driverDirectory) {
        Path absoluteDrivers = driverDirectory.toAbsolutePath().normalize();
        Path parent = absoluteDrivers.getParent();
        return (parent == null ? absoluteDrivers : parent).resolve("task-ledger.json");
    }

    private static Path artifactDirectory(Path ledgerFile) {
        Path absoluteLedger = ledgerFile.toAbsolutePath().normalize();
        Path parent = absoluteLedger.getParent();
        return (parent == null ? absoluteLedger : parent).resolve("task-artifacts");
    }

    private long elapsed(Instant startedAt) {
        return startedAt == null ? 0 : Math.max(0, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
    }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "基础设施任务执行失败";
        }
        return message.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*([=:])\\s*[^\\s,;]+", "$1$2***")
            .replaceAll("(?i)(mongodb(?:\\+srv)?://)[^\\s/@]+@", "$1***@");
    }

    private String diagnosticMessage(Exception error) {
        if (error instanceof java.sql.SQLException sqlException) {
            return "exception=" + sqlException.getClass().getSimpleName() + " sqlState=" + sqlException.getSQLState()
                + " vendorCode=" + sqlException.getErrorCode() + sqlIssue(sqlException);
        }
        String message = error.getMessage();
        String reason = message == null || message.isBlank() ? "未返回异常描述" : sanitize(message);
        return "exception=" + error.getClass().getSimpleName() + " reason=" + reason;
    }

    private String executionErrorCode(Exception error) {
        if (error instanceof AgentExecutionException agentExecutionException) {
            return agentExecutionException.errorCode();
        }
        String message = error.getMessage();
        if (message != null && message.contains("未安装 pwsh")) {
            return "SSH_PWSH_NOT_INSTALLED";
        }
        return "INFRA_EXECUTION_FAILED";
    }

    private String sshFailureMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "SSH 执行失败：未返回异常描述";
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("reject hostkey")) {
            return "SSH 主机密钥校验失败：known_hosts 中没有与目标主机匹配的公钥（原始原因=" + normalized + "）";
        }
        if (lower.contains("auth fail")) {
            return "SSH 用户名或密码认证失败（原始原因=" + normalized + "）";
        }
        if (lower.contains("connection refused")) {
            return "SSH 连接被目标主机拒绝，请检查端口和 SSH 服务（原始原因=" + normalized + "）";
        }
        if (lower.contains("timeout")) {
            return "SSH 连接超时，请检查网络、端口和防火墙（原始原因=" + normalized + "）";
        }
        return sanitize(normalized);
    }

    /** SQL 原文和参数可能包含敏感数据，仅从厂商错误文本提取可安全定位的对象名。 */
    private String sqlIssue(java.sql.SQLException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return "";
        }
        java.util.regex.Matcher unknownColumn = java.util.regex.Pattern.compile("(?i)unknown column ['`]?([^'` ]+)")
            .matcher(message);
        if (unknownColumn.find()) {
            return " diagnostic=UNKNOWN_COLUMN column=" + unknownColumn.group(1);
        }
        return "";
    }

    @Override
    public void close() {
        shuttingDown = true;
        tasks.values().forEach(runtime -> {
            if (!isTerminal(runtime.response.status())) {
                transition(runtime, new InfrastructureTaskResponse(runtime.taskId, "unknown_outcome", runtime.actionType,
                    elapsed(runtime.startedAt), null, null, null, "", "", "TASK_UNKNOWN_OUTCOME",
                    "Agent 关闭时无法确认任务结果，禁止自动重试", runtime.startedAt, Instant.now(), Map.of()));
            }
            taskExecutor.cancel(runtime.taskId);
        });
        executor.shutdownNow();
        try {
            // close 返回前等待工作线程退出，避免重启或测试清理期间仍写入旧账本和日志。
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("AGENT_SHUTDOWN_TIMEOUT", null, null, "activeTasks=" + tasks.size());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class TaskRuntime {
        private final InfrastructureTaskRequest request;
        private final String taskId;
        private final String actionType;
        private final String payloadDigest;
        private volatile InfrastructureTaskResponse response;
        private volatile Future<?> future;
        private volatile boolean cancelRequested;
        private volatile Instant startedAt;

        private TaskRuntime(InfrastructureTaskRequest request, String payloadDigest) {
            this.request = request;
            this.taskId = request.taskId();
            this.actionType = request.actionType();
            this.payloadDigest = payloadDigest;
            this.response = new InfrastructureTaskResponse(request.taskId(), "queued", request.actionType(), 0, null,
                null, null, "", "", null, null, null, null, Map.of());
        }

        private TaskRuntime(DurableTaskLedger.Entry entry) {
            this.request = null;
            this.taskId = entry.taskId();
            this.actionType = entry.actionType();
            this.payloadDigest = entry.payloadDigest();
            this.startedAt = entry.startedAt();
            this.response = new InfrastructureTaskResponse(entry.taskId(), entry.status(), entry.actionType(), 0, null,
                null, null, "", "", entry.errorCode(), entry.error(), entry.startedAt(), entry.finishedAt(), Map.of());
        }
    }
}
