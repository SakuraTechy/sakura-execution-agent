package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证 Agent 的实际路由动作与 Admin 统一 63 条目录 fixture 的基础设施子集一致。 */
class OperationCatalogFixtureTest {

    private static final Set<String> EXPECTED_AGENT_ACTIONS = Set.of(
        "captcha_ocr",
        "global_variable_system_info",
        "global_variable_available_ip",
        "global_variable_property",
        "host_command",
        "host_pointer_move",
        "host_file_lookup",
        "host_file_delete",
        "server_command",
        "server_file_upload",
        "database_sql");

    @Test
    void agentActionsMustBePresentInTheSharedFixture() throws Exception {
        Path fixturePath = Path.of("..", "sakura-admin", "continew-automation", "src", "test", "resources",
            "automation", "automation-operation-63-fixture.json");
        JsonNode fixture = new ObjectMapper().readTree(Files.readString(fixturePath));
        assertEquals("2026-08-07.1", fixture.path("catalog_version").asText());
        assertEquals(63, fixture.path("methods").size());

        Set<String> fixtureActions = new HashSet<>();
        fixture.path("methods").forEach(method -> fixtureActions.add(method.path("action_type").asText()));
        Set<String> supportedFixtureActions = new HashSet<>(fixtureActions);
        supportedFixtureActions.retainAll(InfrastructureTaskService.supportedActions());
        assertEquals(EXPECTED_AGENT_ACTIONS, supportedFixtureActions);
        assertTrue(InfrastructureTaskService.supportedActions().containsAll(EXPECTED_AGENT_ACTIONS));
    }
}
