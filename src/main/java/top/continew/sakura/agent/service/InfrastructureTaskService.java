package top.continew.sakura.agent.service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.model.InfrastructureTaskResponse;
import top.continew.sakura.agent.support.AgentExecutionException;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

/** 任务幂等、异步执行和取消的唯一入口。 */
public class InfrastructureTaskService implements AutoCloseable {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, TaskRuntime> tasks = new ConcurrentHashMap<>();
    private final InfrastructureTaskExecutor taskExecutor;
    private final AgentLogger logger;

    public InfrastructureTaskService(ObjectMapper objectMapper, Path driverDirectory, AgentLogger logger) {
        this(objectMapper, driverDirectory, logger, LocalActionPolicy.fromSystemProperties());
    }

    public InfrastructureTaskService(ObjectMapper objectMapper,
                                     Path driverDirectory,
                                     AgentLogger logger,
                                     LocalActionPolicy localActionPolicy) {
        this.taskExecutor = new InfrastructureTaskExecutor(objectMapper, driverDirectory, logger, localActionPolicy);
        this.logger = logger;
    }

    public InfrastructureTaskResponse submit(InfrastructureTaskRequest request) {
        validate(request);
        logger.info("TASK_VALIDATED", request.taskId(), request.actionType(),
            "timeoutMs=" + request.timeoutMs() + " maxRows=" + request.maxRows());
        TaskRuntime existing = tasks.get(request.taskId());
        if (existing != null) {
            logger.info("TASK_DUPLICATE", request.taskId(), request.actionType(), "status=" + existing.response.status());
            return existing.response;
        }
        TaskRuntime runtime = new TaskRuntime(request);
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
            runtime.response = response(runtime.request, "cancelled", 0, null, null, null, "TASK_CANCELLED", "任务已取消",
                runtime.startedAt, Instant.now());
        }
        logger.warn("TASK_CANCELLED", runtime.request.taskId(), runtime.request.actionType(), null);
        return runtime.response;
    }

    private void execute(TaskRuntime runtime) {
        runtime.startedAt = Instant.now();
        runtime.response = response(runtime.request, "running", 0, null, null, null, null, null, runtime.startedAt, null);
        logger.info("TASK_STARTED", runtime.request.taskId(), runtime.request.actionType(), null);
        try {
            InfrastructureTaskExecutor.ExecutionOutcome outcome = taskExecutor.execute(runtime.request);
            if (runtime.cancelRequested) {
                runtime.response = response(runtime.request, "cancelled", outcome.durationMs(), outcome.exitCode(),
                    outcome.affectedRows(), outcome, "TASK_CANCELLED", "任务已取消", runtime.startedAt, Instant.now());
                logger.warn("TASK_CANCELLED", runtime.request.taskId(), runtime.request.actionType(),
                    "durationMs=" + outcome.durationMs());
                return;
            }
            if (("server_command".equals(runtime.request.actionType()) || "host_command".equals(runtime.request.actionType()))
                && outcome.exitCode() != null && outcome.exitCode() != 0) {
                boolean hostCommand = "host_command".equals(runtime.request.actionType());
                runtime.response = response(runtime.request, "failed", outcome.durationMs(), outcome.exitCode(),
                    outcome.affectedRows(), outcome, hostCommand ? "HOST_COMMAND_EXIT_NON_ZERO" : "SERVER_COMMAND_EXIT_NON_ZERO",
                    hostCommand ? "本机命令返回非零退出码" : "服务器命令返回非零退出码",
                    runtime.startedAt, Instant.now());
                logger.error("TASK_FAILED", runtime.request.taskId(), runtime.request.actionType(),
                    "code=SERVER_COMMAND_EXIT_NON_ZERO durationMs=" + outcome.durationMs());
                return;
            }
            runtime.response = response(runtime.request, "passed", outcome.durationMs(), outcome.exitCode(),
                outcome.affectedRows(), outcome, null, null, runtime.startedAt, Instant.now());
            logger.info("TASK_PASSED", runtime.request.taskId(), runtime.request.actionType(),
                "durationMs=" + outcome.durationMs() + " exitCode=" + outcome.exitCode() + " affectedRows=" + outcome
                    .affectedRows() + " rowCount=" + (outcome.rows() == null ? 0 : outcome.rows().size()));
        } catch (Exception e) {
            String code = runtime.cancelRequested ? "TASK_CANCELLED" : executionErrorCode(e);
            String status = runtime.cancelRequested ? "cancelled" : "failed";
            String errorMessage = "server_command".equals(runtime.request.actionType()) ? sshFailureMessage(e) : sanitize(e
                .getMessage());
            runtime.response = response(runtime.request, status, elapsed(runtime.startedAt), null, null, null, code,
                errorMessage, runtime.startedAt, Instant.now());
            logger.error("TASK_FAILED", runtime.request.taskId(), runtime.request.actionType(),
                "code=" + code + " errorType=" + e.getClass().getSimpleName() + " reason=" + errorMessage);
        }
    }

    private InfrastructureTaskResponse response(InfrastructureTaskRequest request,
                                                String status,
                                                long durationMs,
                                                Integer exitCode,
                                                Integer affectedRows,
                                                InfrastructureTaskExecutor.ExecutionOutcome outcome,
                                                String errorCode,
                                                String error,
                                                Instant startedAt,
                                                Instant finishedAt) {
        return new InfrastructureTaskResponse(request.taskId(), status, request.actionType(), durationMs, exitCode,
            affectedRows, outcome == null ? null : outcome.rows(), outcome == null ? "" : outcome.stdout(),
            outcome == null ? "" : outcome.stderr(), errorCode, error, startedAt, finishedAt,
            outcome == null ? Map.of() : outcome.result());
    }

    private void validate(InfrastructureTaskRequest request) {
        if (request == null || request.taskId() == null || request.taskId().isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        if (request.actionType() == null || request.actionType().isBlank()) {
            throw new IllegalArgumentException("actionType 不能为空");
        }
        if (!Map.of("server_command", true, "database_sql", true, "database_native", true,
            "host_command", true, "host_file_lookup", true, "host_file_delete", true,
            "global_variable_system_info", true, "global_variable_available_ip", true,
            "global_variable_property", true, "captcha_ocr", true, "server_file_upload", true,
            "host_pointer_move", true)
            .containsKey(request.actionType())) {
            throw new IllegalArgumentException("不支持的 actionType：" + request.actionType());
        }
    }

    private boolean isTerminal(String status) {
        return "passed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
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
        tasks.keySet().forEach(taskExecutor::cancel);
        executor.shutdownNow();
    }

    private static final class TaskRuntime {
        private final InfrastructureTaskRequest request;
        private volatile InfrastructureTaskResponse response;
        private volatile Future<?> future;
        private volatile boolean cancelRequested;
        private volatile Instant startedAt;

        private TaskRuntime(InfrastructureTaskRequest request) {
            this.request = request;
            this.response = new InfrastructureTaskResponse(request.taskId(), "queued", request.actionType(), 0, null,
                null, null, "", "", null, null, null, null, Map.of());
        }
    }
}
