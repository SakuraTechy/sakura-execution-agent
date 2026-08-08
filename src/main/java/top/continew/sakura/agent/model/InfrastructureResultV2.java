package top.continew.sakura.agent.model;

import java.util.List;
import java.util.Map;

/** 基础设施执行结果 v2；results 是唯一的有序结果事实来源。 */
public record InfrastructureResultV2(int schemaVersion,
                                     String kind,
                                     Integer exitCode,
                                     long durationMs,
                                     List<Map<String, Object>> results,
                                     List<String> warnings,
                                     String stdout,
                                     String stderr,
                                     boolean truncated,
                                     Map<String, Object> artifact) {
}
