package top.continew.sakura.agent.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 执行节点的结构化诊断日志。
 *
 * <p>日志可以记录执行阶段、耗时和驱动档案，但不得记录 SQL、命令、连接串、Token 或凭据。</p>
 */
public final class AgentLogger {

    private final Path logFile;
    private final long processId = ProcessHandle.current().pid();

    public AgentLogger(Path logFile) {
        this.logFile = logFile.toAbsolutePath().normalize();
    }

    public void info(String event, String taskId, String actionType, String detail) {
        write("INFO", event, taskId, actionType, detail);
    }

    public void warn(String event, String taskId, String actionType, String detail) {
        write("WARN", event, taskId, actionType, detail);
    }

    public void error(String event, String taskId, String actionType, String detail) {
        write("ERROR", event, taskId, actionType, detail);
    }

    /**
     * HTTP 边界错误需要保留异常类型、原因和有限栈帧，便于判断是任务失败还是响应编码失败。
     * 凭据脱敏统一由 write 的 safe 方法兜底，避免诊断日志泄露认证信息。
     */
    public void error(String event, String taskId, String actionType, Exception error) {
        StringBuilder detail = new StringBuilder("exception=").append(error.getClass().getName());
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            detail.append(" message=").append(error.getMessage());
        }
        StackTraceElement[] stackTrace = error.getStackTrace();
        int stackLimit = Math.min(stackTrace.length, 8);
        for (int index = 0; index < stackLimit; index++) {
            detail.append(" at=").append(stackTrace[index]);
        }
        write("ERROR", event, taskId, actionType, detail.toString());
    }

    private synchronized void write(String level, String event, String taskId, String actionType, String detail) {
        String line = "%s level=%s event=%s taskId=%s action=%s pid=%d thread=%s%s%n".formatted(OffsetDateTime.now(ZoneId
            .systemDefault()), level, safe(event), safe(taskId), safe(actionType), processId, safe(Thread.currentThread()
                .getName()), detail == null || detail.isBlank() ? "" : " detail=" + safe(detail));
        if ("ERROR".equals(level)) {
            System.err.print(line);
        } else {
            System.out.print(line);
        }
        try {
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写入失败不能阻断执行；标准输出仍可由前台或服务管理器收集。
        }
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        // 错误消息可能由第三方 JDBC/SSH 驱动生成，统一兜底脱敏，避免详细日志反向泄露凭据。
        normalized = normalized.replaceAll("(?i)(password|passwd|pwd|token|secret|authorization)\\s*([=:])\\s*[^\\s,;]+",
            "$1$2***").replaceAll("(?i)(mongodb(?:\\+srv)?://)[^\\s/@]+@", "$1***@")
            .replaceAll("(?i)(jdbc:[^\\s]*://)[^\\s/@]+@", "$1***@");
        return normalized.length() <= 2_000 ? normalized : normalized.substring(0, 2_000) + "…[truncated]";
    }
}
