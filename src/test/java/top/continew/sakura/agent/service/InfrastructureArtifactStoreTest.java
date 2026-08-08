package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InfrastructureArtifactStoreTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storesValidJsonAndShrinksRowsToTenMegabytes() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        InfrastructureArtifactStore store = new InfrastructureArtifactStore(objectMapper, tempDirectory
            .resolve("artifacts"));
        List<List<Object>> rows = new ArrayList<>();
        String largeValue = "x".repeat(256 * 1024);
        for (int index = 0; index < 48; index++) {
            rows.add(new ArrayList<>(List.of(index, largeValue)));
        }
        Map<String, Object> rowSet = new LinkedHashMap<>();
        rowSet.put("type", "ROW_SET");
        rowSet.put("columns", List.of(Map.of("label", "id"), Map.of("label", "payload")));
        rowSet.put("rows", rows);
        rowSet.put("rowCount", 48L);
        rowSet.put("truncated", false);

        InfrastructureArtifactStore.ArtifactMetadata metadata = store.store("task-artifact", "DATABASE_QUERY",
            rowSet, List.of("preview truncated"));
        InfrastructureArtifactStore.ArtifactContent content = store.load("task-artifact");

        assertTrue(metadata.available());
        assertTrue(content.bytes().length <= InfrastructureArtifactStore.MAX_ARTIFACT_BYTES);
        assertEquals(metadata.sha256(), content.sha256());
        Map<String, Object> envelope = objectMapper.readValue(content.bytes(), new TypeReference<>() { });
        @SuppressWarnings("unchecked")
        Map<String, Object> storedResult = (Map<String, Object>)envelope.get("result");
        assertEquals(true, storedResult.get("truncated"));
        assertTrue(((List<?>)storedResult.get("rows")).size() < 48);
    }

    @Test
    void rejectsPathTraversalHandles() {
        InfrastructureArtifactStore store = new InfrastructureArtifactStore(new ObjectMapper(), tempDirectory);
        assertThrows(IllegalArgumentException.class, () -> store.load("../task"));
    }

    @Test
    void deletesExpiredArtifactsAtStartupAndRejectsExpiredDownloads() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path artifactDirectory = tempDirectory.resolve("expired-artifacts");
        Files.createDirectories(artifactDirectory);
        Path expiredPath = artifactDirectory.resolve("expired.json");
        Files.writeString(expiredPath, objectMapper.writeValueAsString(Map.of(
            "schemaVersion", 2,
            "taskId", "expired",
            "expiresAt", Instant.now().minusSeconds(1).toString(),
            "result", Map.of("rows", List.of(Map.of("id", 1))))));

        new InfrastructureArtifactStore(objectMapper, artifactDirectory);
        assertTrue(Files.notExists(expiredPath));

        Path lateExpiredPath = artifactDirectory.resolve("late-expired.json");
        Files.writeString(lateExpiredPath, objectMapper.writeValueAsString(Map.of(
            "schemaVersion", 2,
            "taskId", "late-expired",
            "expiresAt", Instant.now().minusSeconds(1).toString(),
            "result", Map.of("rows", List.of(Map.of("id", 2))))));
        InfrastructureArtifactStore store = new InfrastructureArtifactStore(objectMapper, artifactDirectory);
        assertThrows(IllegalArgumentException.class, () -> store.load("late-expired"));
        assertTrue(Files.notExists(lateExpiredPath));
    }
}
