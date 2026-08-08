package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.model.InfrastructureTaskResponse;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.AgentRequestException;
import top.continew.sakura.agent.support.LocalActionPolicy;

class InfrastructureTaskServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void submitAcceptsServerAndDatabaseActions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InfrastructureTaskService service = new InfrastructureTaskService(objectMapper,
            tempDirectory.resolve("drivers"), new AgentLogger(tempDirectory.resolve("agent.log")),
            new LocalActionPolicy(tempDirectory.resolve("workspace"), List.of()))) {
            for (String actionType : List.of("server_command", "database_sql", "database_native")) {
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("taskId", "task-" + actionType);
                payload.put("actionType", actionType);
                payload.put("riskLevel", "server_command".equals(actionType) ? "host-privileged" : "read");
                if ("server_command".equals(actionType)) {
                    payload.put("approvalGranted", true);
                    payload.put("approvalId", "approval-1");
                    payload.put("approvalDigest", "a".repeat(64));
                }
                InfrastructureTaskRequest request = objectMapper.convertValue(payload, InfrastructureTaskRequest.class);

                InfrastructureTaskResponse response = assertDoesNotThrow(() -> service.submit(request));
                assertEquals(actionType, response.actionType());
            }
            assertResultEnvelope(service, "task-server_command");
            assertResultEnvelope(service, "task-database_sql");
            assertResultEnvelope(service, "task-database_native");
        }
    }

    @Test
    void submitRejectsUnsupportedActionBeforeQueueing() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InfrastructureTaskService service = new InfrastructureTaskService(objectMapper,
            tempDirectory.resolve("drivers"), new AgentLogger(tempDirectory.resolve("agent.log")),
            new LocalActionPolicy(tempDirectory.resolve("workspace"), List.of()))) {
            InfrastructureTaskRequest request = objectMapper.convertValue(Map.of(
                "taskId", "task-unsupported",
                "actionType", "unsupported_action"), InfrastructureTaskRequest.class);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.submit(request));
            assertEquals("不支持的 actionType：unsupported_action", error.getMessage());
        }
    }

    @Test
    void submitRejectsSameTaskIdWithDifferentPayloadWithoutPersistingSensitivePayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path ledgerFile = tempDirectory.resolve("ledger/task-ledger.json");
        InfrastructureTaskRequest first = objectMapper.convertValue(Map.of(
            "taskId", "task-digest",
            "actionType", "server_command",
            "riskLevel", "host-privileged",
            "approvalGranted", true,
            "approvalId", "approval-1",
            "approvalDigest", "a".repeat(64),
            "command", "echo first-secret",
            "sshTarget", Map.of("host", "127.0.0.1", "username", "tester", "password", "password-secret")),
            InfrastructureTaskRequest.class);
        InfrastructureTaskRequest changed = objectMapper.convertValue(Map.of(
            "taskId", "task-digest",
            "actionType", "server_command",
            "riskLevel", "host-privileged",
            "approvalGranted", true,
            "approvalId", "approval-1",
            "approvalDigest", "a".repeat(64),
            "command", "echo changed-secret",
            "sshTarget", Map.of("host", "127.0.0.1", "username", "tester", "password", "password-secret")),
            InfrastructureTaskRequest.class);

        try (InfrastructureTaskService service = new InfrastructureTaskService(objectMapper,
            tempDirectory.resolve("drivers"), new AgentLogger(tempDirectory.resolve("agent.log")),
            new LocalActionPolicy(tempDirectory.resolve("workspace"), List.of()), ledgerFile)) {
            service.submit(first);
            AgentRequestException error = assertThrows(AgentRequestException.class, () -> service.submit(changed));
            assertEquals("TASK_PAYLOAD_DIGEST_MISMATCH", error.errorCode());
        }

        String storedLedger = Files.readString(ledgerFile);
        assertFalse(storedLedger.contains("first-secret"));
        assertFalse(storedLedger.contains("changed-secret"));
        assertFalse(storedLedger.contains("password-secret"));
    }

    @Test
    void restartConvertsRunningTaskToUnknownOutcome() {
        ObjectMapper objectMapper = new ObjectMapper();
        Path ledgerFile = tempDirectory.resolve("recovery/task-ledger.json");
        InfrastructureTaskRequest request = objectMapper.convertValue(Map.of(
            "taskId", "task-running-before-restart",
            "actionType", "database_sql",
            "riskLevel", "read"), InfrastructureTaskRequest.class);
        DurableTaskLedger ledger = new DurableTaskLedger(objectMapper, ledgerFile);
        DurableTaskLedger.Entry entry = ledger.register(request);
        ledger.update(entry.payloadDigest(), new InfrastructureTaskResponse(request.taskId(), "running",
            request.actionType(), 0, null, null, null, "", "", null, null, Instant.now(), null,
            Map.of("infrastructure", Map.of("schemaVersion", 2))));
        assertEquals(64, ledger.snapshots().get(request.taskId()).resultDigest().length());

        try (InfrastructureTaskService restarted = new InfrastructureTaskService(objectMapper,
            tempDirectory.resolve("drivers"), new AgentLogger(tempDirectory.resolve("agent.log")),
            new LocalActionPolicy(tempDirectory.resolve("workspace"), List.of()), ledgerFile)) {
            InfrastructureTaskResponse recovered = restarted.get(request.taskId());
            assertEquals("unknown_outcome", recovered.status());
            assertEquals("TASK_UNKNOWN_OUTCOME", recovered.errorCode());
            assertEquals("unknown_outcome", restarted.submit(request).status());
        }
    }

    private void assertResultEnvelope(InfrastructureTaskService service, String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            InfrastructureTaskResponse response = service.get(taskId);
            if ("passed".equals(response.status()) || "failed".equals(response.status()) || "cancelled".equals(response.status())) {
                assertFalse(response.result().get("infrastructure") == null);
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("任务未在测试时限内结束：" + taskId);
    }
}
