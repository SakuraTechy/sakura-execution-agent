package top.continew.sakura.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 返回给 Runner 的脱敏任务事实。 */
public record InfrastructureTaskResponse(String taskId,
                                         String status,
                                         String actionType,
                                         long durationMs,
                                         Integer exitCode,
                                         Long affectedRows,
                                         List<Map<String, Object>> rows,
                                         String stdout,
                                         String stderr,
                                         String errorCode,
                                         String error,
                                         Instant startedAt,
                                         Instant finishedAt,
                                         Map<String, Object> result) {
}
