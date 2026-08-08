package top.continew.sakura.agent.service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 基础设施大结果的 Agent 私有附件存储。 */
public final class InfrastructureArtifactStore {

    static final int MAX_ARTIFACT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SHRINK_ATTEMPTS = 12;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path artifactDirectory;

    public InfrastructureArtifactStore(ObjectMapper objectMapper, Path artifactDirectory) {
        this.objectMapper = objectMapper.copy();
        this.artifactDirectory = artifactDirectory.toAbsolutePath().normalize();
        cleanupExpiredArtifacts();
    }

    public ArtifactMetadata store(String taskId,
                                  String kind,
                                  Map<String, Object> result,
                                  List<String> warnings) {
        if (result == null || result.isEmpty()) {
            return ArtifactMetadata.unavailable();
        }
        String safeTaskId = requireTaskId(taskId);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 2);
        envelope.put("taskId", safeTaskId);
        envelope.put("kind", kind);
        envelope.put("result", deepCopy(result));
        envelope.put("warnings", warnings == null ? List.of() : List.copyOf(warnings));
        envelope.put("generatedAt", Instant.now().toString());
        envelope.put("expiresAt", expiresAt.toString());
        byte[] bytes = fitToLimit(envelope);
        if (bytes == null) {
            return ArtifactMetadata.unavailable();
        }
        Path target = artifactPath(safeTaskId);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(artifactDirectory);
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new ArtifactMetadata(true, safeTaskId, target.getFileName().toString(), "application/json",
                bytes.length, sha256(bytes), expiresAt.toString());
        } catch (IOException e) {
            throw new IllegalStateException("无法保存基础设施结果附件", e);
        }
    }

    public ArtifactContent load(String taskId) {
        String safeTaskId = requireTaskId(taskId);
        Path target = artifactPath(safeTaskId);
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("基础设施结果附件不存在：" + safeTaskId);
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length > MAX_ARTIFACT_BYTES) {
                throw new IllegalStateException("基础设施结果附件超过大小限制");
            }
            if (isExpired(bytes)) {
                Files.deleteIfExists(target);
                throw new IllegalArgumentException("基础设施结果附件已过期：" + safeTaskId);
            }
            return new ArtifactContent(target.getFileName().toString(), "application/json", bytes, sha256(bytes));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取基础设施结果附件", e);
        }
    }

    /** 启动时清理过期附件，避免只依赖请求触发清理而长期占用 Agent 磁盘。 */
    private void cleanupExpiredArtifacts() {
        if (!Files.isDirectory(artifactDirectory)) {
            return;
        }
        try (var files = Files.list(artifactDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        if (isExpired(Files.readAllBytes(path))) {
                            Files.deleteIfExists(path);
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // 单个损坏附件不能阻断 Agent 启动，读取时仍会按不存在/非法附件拒绝。
                    }
                });
        } catch (IOException ignored) {
            // 清理失败不影响任务执行；附件下载仍受大小、路径和过期校验保护。
        }
    }

    private boolean isExpired(byte[] bytes) throws IOException {
        String expiresAt = objectMapper.readTree(bytes).path("expiresAt").asText("");
        if (expiresAt.isBlank()) {
            return true;
        }
        try {
            return !Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private byte[] fitToLimit(Map<String, Object> envelope) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(envelope);
            for (int attempt = 0; bytes.length > MAX_ARTIFACT_BYTES && attempt < MAX_SHRINK_ATTEMPTS; attempt++) {
                List<List<Object>> rowLists = new ArrayList<>();
                collectRows(envelope.get("result"), rowLists);
                int totalRows = rowLists.stream().mapToInt(List::size).sum();
                if (totalRows == 0) {
                    return null;
                }
                double ratio = Math.max(0.0, Math.min(0.9,
                    (double)MAX_ARTIFACT_BYTES / (double)bytes.length * 0.9));
                for (List<Object> rows : rowLists) {
                    int keep = Math.max(0, (int)Math.floor(rows.size() * ratio));
                    if (keep < rows.size()) {
                        rows.subList(keep, rows.size()).clear();
                    }
                }
                markTruncated(envelope.get("result"));
                bytes = objectMapper.writeValueAsBytes(envelope);
            }
            return bytes.length <= MAX_ARTIFACT_BYTES ? bytes : null;
        } catch (IOException e) {
            throw new IllegalStateException("无法序列化基础设施结果附件", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void collectRows(Object value, List<List<Object>> rowLists) {
        if (value instanceof Map<?, ?> map) {
            Object rows = map.get("rows");
            if (rows instanceof List<?> list) {
                rowLists.add((List<Object>)list);
            }
            Object nativeValue = map.get("value");
            if ("NATIVE_RESULT".equals(map.get("type")) && nativeValue instanceof List<?> list) {
                rowLists.add((List<Object>)list);
            }
            map.values().forEach(child -> collectRows(child, rowLists));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> collectRows(child, rowLists));
        }
    }

    @SuppressWarnings("unchecked")
    private void markTruncated(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> mutable = (Map<String, Object>)map;
            if (mutable.get("rows") instanceof List<?> rows) {
                mutable.put("rowCount", (long)rows.size());
                mutable.put("truncated", true);
            }
            if ("NATIVE_RESULT".equals(mutable.get("type")) && mutable.get("value") instanceof List<?> rows) {
                mutable.put("rowCount", (long)rows.size());
                mutable.put("truncated", true);
            }
            map.values().forEach(this::markTruncated);
        } else if (value instanceof List<?> list) {
            list.forEach(this::markTruncated);
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> value) {
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private Path artifactPath(String taskId) {
        Path target = artifactDirectory.resolve(taskId + ".json").normalize();
        if (!target.startsWith(artifactDirectory)) {
            throw new IllegalArgumentException("基础设施附件句柄非法");
        }
        return target;
    }

    private String requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("基础设施附件句柄非法");
        }
        return taskId;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    public record ArtifactMetadata(boolean available,
                                   String handle,
                                   String fileName,
                                   String contentType,
                                   long sizeBytes,
                                   String sha256,
                                   String expiresAt) {

        private static ArtifactMetadata unavailable() {
            return new ArtifactMetadata(false, null, null, null, 0L, null, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("available", available);
            value.put("handle", handle);
            value.put("fileName", fileName);
            value.put("contentType", contentType);
            value.put("sizeBytes", sizeBytes);
            value.put("sha256", sha256);
            value.put("expiresAt", expiresAt);
            return value;
        }
    }

    public record ArtifactContent(String fileName, String contentType, byte[] bytes, String sha256) {
    }
}
