package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

class InfrastructureTaskExecutorJdbcResultTest {

    @TempDir
    Path tempDirectory;

    @Test
    void queryKeepsDuplicateLabelsColumnMetadataAndOrderedRows() throws Exception {
        Path driverRoot = Files.createDirectories(tempDirectory.resolve("drivers"));
        Path profile = Files.createDirectories(driverRoot.resolve("h2"));
        Path h2Jar = Path.of(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(h2Jar, profile.resolve("h2.jar"));
        ObjectMapper objectMapper = new ObjectMapper();
        InfrastructureTaskExecutor executor = new InfrastructureTaskExecutor(objectMapper, driverRoot,
            new AgentLogger(tempDirectory.resolve("agent.log")), new LocalActionPolicy(tempDirectory.resolve("workspace"),
                List.of()));
        InfrastructureTaskRequest request = objectMapper.convertValue(Map.of(
            "taskId", "jdbc-duplicate-labels",
            "actionType", "database_sql",
            "sqlMode", "query",
            "riskLevel", "read",
            "readOnlyEnforced", true,
            "sql", "SELECT CAST(X AS INT) AS duplicate_label, CAST(X * 2 AS INT) AS duplicate_label FROM SYSTEM_RANGE(1, 5)",
            "maxRows", 2,
            "jdbcTarget", Map.of(
                "driverProfile", "h2",
                "driverClass", "org.h2.Driver",
                "jdbcUrl", "jdbc:h2:mem:result_contract;DB_CLOSE_DELAY=-1")), InfrastructureTaskRequest.class);

        InfrastructureTaskExecutor.ExecutionOutcome outcome = executor.execute(request);

        assertEquals(1, outcome.results().size());
        Map<String, Object> rowSet = outcome.results().get(0);
        assertEquals("ROW_SET", rowSet.get("type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>)rowSet.get("columns");
        assertEquals(2, columns.size());
        assertEquals("DUPLICATE_LABEL", columns.get(0).get("label"));
        assertEquals("DUPLICATE_LABEL", columns.get(1).get("label"));
        assertFalse("UNKNOWN".equals(columns.get(0).get("typeName")));
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>)rowSet.get("rows");
        assertEquals(List.of(1, 2), rows.get(0));
        assertEquals(2, rows.size());
        assertEquals(true, rowSet.get("truncated"));
        @SuppressWarnings("unchecked")
        List<List<Object>> artifactRows = (List<List<Object>>)outcome.artifactResult().get("rows");
        assertEquals(5, artifactRows.size());
        assertEquals(false, outcome.artifactResult().get("truncated"));
        assertEquals(Map.of("DUPLICATE_LABEL", 1, "DUPLICATE_LABEL#2", 2), outcome.rows().get(0));
    }
}
