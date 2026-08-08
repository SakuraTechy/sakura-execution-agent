package top.continew.sakura.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.model.InfrastructureTaskResponse;
import top.continew.sakura.agent.support.AgentRequestException;

/**
 * 跨重启任务账本。
 *
 * <p>账本只保存 payload 摘要和脱敏状态，不保存命令、SQL、目标、结果正文或凭据。</p>
 */
public final class DurableTaskLedger {

    private static final int SCHEMA_VERSION = 1;
    private static final String PAYLOAD_MISMATCH = "TASK_PAYLOAD_DIGEST_MISMATCH";
    private static final String UNKNOWN_OUTCOME = "TASK_UNKNOWN_OUTCOME";

    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final Path ledgerFile;
    private final Map<String, Entry> entries;

    public DurableTaskLedger(ObjectMapper objectMapper, Path ledgerFile) {
        this.objectMapper = objectMapper.copy()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.canonicalMapper = objectMapper.copy()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.ledgerFile = ledgerFile.toAbsolutePath().normalize();
        this.entries = load();
        recoverUncertainTasks();
    }

    public synchronized Entry register(InfrastructureTaskRequest request) {
        String digest = payloadDigest(request);
        Entry existing = entries.get(request.taskId());
        if (existing != null) {
            requireMatchingDigest(existing, digest);
            return existing;
        }
        Entry queued = new Entry(request.taskId(), digest, request.actionType(), "queued", null, null, null, null,
            null);
        entries.put(request.taskId(), queued);
        persist();
        return queued;
    }

    public synchronized void update(String payloadDigest, InfrastructureTaskResponse response) {
        Entry existing = entries.get(response.taskId());
        if (existing == null) {
            throw new IllegalStateException("任务尚未登记到持久化账本：" + response.taskId());
        }
        requireMatchingDigest(existing, payloadDigest);
        entries.put(response.taskId(), new Entry(response.taskId(), payloadDigest, response.actionType(), response
            .status(), response.errorCode(), safeError(response.error()), response.startedAt(), response.finishedAt(),
            resultDigest(response.result())));
        persist();
    }

    public synchronized Map<String, Entry> snapshots() {
        return Map.copyOf(entries);
    }

    public String payloadDigest(InfrastructureTaskRequest request) {
        try {
            byte[] canonicalPayload = canonicalMapper.writeValueAsBytes(request);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalPayload));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算任务 payload 摘要", e);
        }
    }

    private Map<String, Entry> load() {
        if (!Files.exists(ledgerFile)) {
            return new LinkedHashMap<>();
        }
        try {
            LedgerFile stored = objectMapper.readValue(ledgerFile.toFile(), LedgerFile.class);
            if (stored.schemaVersion() != SCHEMA_VERSION || stored.tasks() == null) {
                throw new IllegalStateException("不支持的任务账本 schema：" + stored.schemaVersion());
            }
            return new LinkedHashMap<>(stored.tasks());
        } catch (IOException e) {
            // 损坏账本可能掩盖已执行的非幂等任务，禁止静默清空后继续启动。
            throw new IllegalStateException("无法读取任务账本：" + ledgerFile, e);
        }
    }

    private synchronized void recoverUncertainTasks() {
        boolean changed = false;
        Instant recoveredAt = Instant.now();
        for (Map.Entry<String, Entry> item : new LinkedHashMap<>(entries).entrySet()) {
            Entry entry = item.getValue();
            if (!"queued".equals(entry.status()) && !"running".equals(entry.status())) {
                continue;
            }
            // 重启后无法证明外部命令或写操作是否完成，必须冻结为未知结果并等待人工处置。
            entries.put(item.getKey(), new Entry(entry.taskId(), entry.payloadDigest(), entry.actionType(),
                "unknown_outcome", UNKNOWN_OUTCOME, "Agent 重启后无法确认任务结果，禁止自动重试", entry.startedAt(),
                recoveredAt, entry.resultDigest()));
            changed = true;
        }
        if (changed) {
            persist();
        }
    }

    private void requireMatchingDigest(Entry existing, String digest) {
        if (!existing.payloadDigest().equals(digest)) {
            throw new AgentRequestException(PAYLOAD_MISMATCH, "相同 taskId 的 payload 摘要不一致");
        }
    }

    private void persist() {
        Path parent = ledgerFile.getParent();
        Path temporary = ledgerFile.resolveSibling(ledgerFile.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new LedgerFile(SCHEMA_VERSION, new LinkedHashMap<>(entries)));
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, ledgerFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, ledgerFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法持久化任务账本：" + ledgerFile, e);
        }
    }

    private String safeError(String error) {
        if (error == null) {
            return null;
        }
        String sanitized = error.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*([=:])\\s*[^\\s,;]+", "$1$2***")
            .replaceAll("(?i)(mongodb(?:\\+srv)?://)[^\\s/@]+@", "$1***@");
        return sanitized.length() <= 400 ? sanitized : sanitized.substring(0, 400) + "...[truncated]";
    }

    private String resultDigest(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        try {
            byte[] canonicalResult = canonicalMapper.writeValueAsBytes(result);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalResult));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算任务结果摘要", e);
        }
    }

    public record Entry(String taskId,
                        String payloadDigest,
                        String actionType,
                        String status,
                        String errorCode,
                        String error,
                        Instant startedAt,
                        Instant finishedAt,
                        String resultDigest) {
    }

    private record LedgerFile(int schemaVersion, Map<String, Entry> tasks) {
    }
}
