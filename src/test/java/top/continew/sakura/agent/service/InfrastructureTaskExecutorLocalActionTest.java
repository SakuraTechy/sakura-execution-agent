package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.support.AgentExecutionException;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

class InfrastructureTaskExecutorLocalActionTest {

    @TempDir
    Path tempDirectory;

    private ObjectMapper objectMapper;
    private InfrastructureTaskExecutor executor;
    private Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        workspace = Files.createDirectories(tempDirectory.resolve("workspace"));
        executor = new InfrastructureTaskExecutor(objectMapper, tempDirectory.resolve("drivers"),
            new AgentLogger(tempDirectory.resolve("agent.log")), new LocalActionPolicy(workspace, List.of()));
    }

    @Test
    void systemInfoWritesOnlyRequestedVariableIntoResult() throws Exception {
        InfrastructureTaskExecutor.ExecutionOutcome outcome = executor.execute(request(Map.of(
            "taskId", "system-info", "actionType", "global_variable_system_info", "variableName", "nodeOs",
            "infoType", "os_name")));

        assertEquals(System.getProperty("os.name"), variables(outcome).get("nodeOs"));
        assertEquals("", outcome.stdout());
        assertEquals("", outcome.stderr());
    }

    @Test
    void runtimePropertyRequiresExplicitAllowlistAndOnlyReturnsTheDeclaredVariable() throws Exception {
        String allowlist = LocalActionPolicy.RUNTIME_PROPERTY_ALLOWLIST;
        String propertyKey = "sakura.test.safe-property";
        String previousAllowlist = System.getProperty(allowlist);
        String previousValue = System.getProperty(propertyKey);
        try {
            System.setProperty(allowlist, "app:" + propertyKey);
            System.setProperty(propertyKey, "safe-value");
            InfrastructureTaskExecutor.ExecutionOutcome outcome = executor.execute(request(Map.of(
                "taskId", "runtime-property", "actionType", "global_variable_property", "variableName", "value",
                "profile", "app", "propertyKey", propertyKey)));
            assertEquals(Map.of("value", "safe-value"), variables(outcome));

            System.clearProperty(allowlist);
            AgentExecutionException denied = assertThrows(AgentExecutionException.class,
                () -> executor.execute(request(Map.of("taskId", "runtime-property-denied", "actionType",
                    "global_variable_property", "variableName", "value", "profile", "app", "propertyKey",
                    propertyKey))));
            assertEquals("RUNTIME_PROPERTY_DENIED", denied.errorCode());
        } finally {
            restoreProperty(allowlist, previousAllowlist);
            restoreProperty(propertyKey, previousValue);
        }
    }

    @Test
    void captchaOcrRequiresNodeConfiguredCommandBeforeProcessingImage() {
        String pythonKey = "sakura.agent.captcha-ocr-python";
        String scriptKey = "sakura.agent.captcha-ocr-script";
        String previousPython = System.getProperty(pythonKey);
        String previousScript = System.getProperty(scriptKey);
        try {
            System.clearProperty(pythonKey);
            System.clearProperty(scriptKey);
            AgentExecutionException missingConfiguration = assertThrows(AgentExecutionException.class,
                () -> executor.execute(request(Map.of("taskId", "captcha", "actionType", "captcha_ocr",
                    "variableName", "captchaValue", "captchaImageBase64", "AQ==", "capability", "captcha_ocr"))));
            assertEquals("CAPTCHA_OCR_NOT_CONFIGURED", missingConfiguration.errorCode());
        } finally {
            restoreProperty(pythonKey, previousPython);
            restoreProperty(scriptKey, previousScript);
        }
    }

    @Test
    void lookupDoesNotExposeAbsolutePathsAndDeleteNeedsApproval() throws Exception {
        Path lookupDirectory = Files.createDirectories(workspace.resolve("lookup"));
        Path file = Files.writeString(lookupDirectory.resolve("visible.txt"), "safe");
        InfrastructureTaskExecutor.ExecutionOutcome lookup = executor.execute(request(Map.of(
            "taskId", "lookup", "actionType", "host_file_lookup", "filePath", lookupDirectory.toString(),
            "filePattern", "*.txt")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>)lookup.result().get("files");
        assertEquals("visible.txt", files.get(0).get("name"));
        assertFalse(files.get(0).toString().contains(workspace.toString()));

        AgentExecutionException denied = assertThrows(AgentExecutionException.class,
            () -> executor.execute(request(Map.of("taskId", "delete-denied", "actionType", "host_file_delete",
                "filePath", file.toString()))));
        assertEquals("HOST_FILE_DELETE_CAPABILITY_REQUIRED", denied.errorCode());

        InfrastructureTaskExecutor.ExecutionOutcome deleted = executor.execute(request(Map.of("taskId", "delete-ok",
            "actionType", "host_file_delete", "filePath", file.toString(), "capability", "host_file_delete",
            "approvalGranted", true, "approvalId", "approval-1", "approvalDigest", "a".repeat(64))));
        assertEquals(1L, deleted.result().get("deleted_count"));
        assertFalse(Files.exists(file));
    }

    @Test
    void hostCommandDoesNotReturnCommandOutputAndDesktopActionIsExplicitlyRejected() throws Exception {
        String command = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "echo secret-value" : "echo secret-value";
        InfrastructureTaskExecutor.ExecutionOutcome commandOutcome = executor.execute(request(Map.of("taskId", "host-cmd",
            "actionType", "host_command", "command", command, "capability", "host_command")));
        assertEquals(0, commandOutcome.exitCode());
        assertEquals("", commandOutcome.stdout());
        assertEquals("", commandOutcome.stderr());
        assertEquals(Map.of(), commandOutcome.result());

        AgentExecutionException desktop = assertThrows(AgentExecutionException.class,
            () -> executor.execute(request(Map.of("taskId", "desktop", "actionType", "host_pointer_move"))));
        assertEquals("DESKTOP_AGENT_REQUIRED", desktop.errorCode());
    }

    private InfrastructureTaskRequest request(Map<String, Object> fields) {
        return objectMapper.convertValue(fields, InfrastructureTaskRequest.class);
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> variables(InfrastructureTaskExecutor.ExecutionOutcome outcome) {
        return (Map<String, Object>)outcome.result().get("variables");
    }
}
