package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.model.InfrastructureTaskResponse;
import top.continew.sakura.agent.support.AgentLogger;
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
                InfrastructureTaskRequest request = objectMapper.convertValue(Map.of(
                    "taskId", "task-" + actionType,
                    "actionType", actionType), InfrastructureTaskRequest.class);

                InfrastructureTaskResponse response = assertDoesNotThrow(() -> service.submit(request));
                assertEquals(actionType, response.actionType());
            }
            awaitTerminal(service, "task-server_command");
            awaitTerminal(service, "task-database_sql");
            awaitTerminal(service, "task-database_native");
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

    private void awaitTerminal(InfrastructureTaskService service, String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            String status = service.get(taskId).status();
            if ("passed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("任务未在测试时限内结束：" + taskId);
    }
}
