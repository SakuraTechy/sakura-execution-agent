package top.continew.sakura.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.support.AgentExecutionException;

class ServerCommandResultProcessorTest {

    static Stream<Arguments> replacements() {
        return Stream.of(
            Arguments.of("123\n", "\\n", "", "123"),
            Arguments.of("123\r\n", "[\\r\\n]+", "", "123"),
            Arguments.of("a,b,c", ",", " ", "a b c"),
            Arguments.of("a b", " ", "", "ab"),
            Arguments.of("abc", "z", "", "abc"),
            Arguments.of("abc", ".+", "", ""),
            Arguments.of("", "\\n", "", ""),
            Arguments.of("size=0012", "^size=(\\d+)$", "$1", "0012"),
            Arguments.of("x", "x", "\\$", "$"),
            Arguments.of("x", "x", "\\\\", "\\"),
            Arguments.of(" a\n", "", "", " a\n"));
    }

    @ParameterizedTest
    @MethodSource("replacements")
    void keepsJavaReplacementSemantics(String input, String regex, String replacement, String expected) throws Exception {
        assertEquals(Map.of("variables", Map.of("disk", expected)),
            ServerCommandResultProcessor.process(request(regex, replacement), 0, input, false));
    }

    @Test
    void noBindingAndNonzeroExitDoNotPublish() throws Exception {
        InfrastructureTaskRequest legacy = new ObjectMapper().convertValue(Map.of("actionType", "server_command"), InfrastructureTaskRequest.class);
        assertEquals(Map.of(), ServerCommandResultProcessor.process(legacy, 0, "raw", true));
        assertEquals(Map.of(), ServerCommandResultProcessor.process(request("\\n", ""), 1, "raw", false));
    }

    @Test
    void rejectsInvalidDefinitionsBeforeSsh() {
        for (String regex : new String[] {"[", "a".repeat(513), "${input}", "{{input}}"}) {
            assertThrows(AgentExecutionException.class, () -> ServerCommandResultProcessor.validate(request(regex, "")));
        }
        for (String replacement : new String[] {"$9", "$", "\\", "${name}", "x".repeat(4097)}) {
            assertThrows(AgentExecutionException.class, () -> ServerCommandResultProcessor.validate(request("(x)", replacement)));
        }
    }

    @Test
    void rejectsTruncationAndOversizedOutputWithoutChangingContent() throws Exception {
        assertEquals("SERVER_RESULT_TOO_LARGE", assertThrows(AgentExecutionException.class,
            () -> ServerCommandResultProcessor.process(request("x", ""), 0, "x", true)).errorCode());
        assertEquals("SERVER_RESULT_TOO_LARGE", assertThrows(AgentExecutionException.class,
            () -> ServerCommandResultProcessor.process(request("x", ""), 0, "x".repeat(65537), false)).errorCode());
        assertEquals("SERVER_RESULT_TOO_LARGE", assertThrows(AgentExecutionException.class,
            () -> ServerCommandResultProcessor.process(request("", ""), 0, "x".repeat(4097), false)).errorCode());
        assertEquals(Map.of("variables", Map.of("disk", "token=exact-test-value")),
            ServerCommandResultProcessor.process(request("", ""), 0, "token=exact-test-value", false));
    }

    @Test
    void boundsCatastrophicBacktrackingAndAllowsSubsequentRequests() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            AgentExecutionException error = assertThrows(AgentExecutionException.class,
                () -> ServerCommandResultProcessor.process(request("(a+)+$", ""), 0, "a".repeat(8000) + "!", false));
            assertEquals("SERVER_RESULT_REGEX_LIMIT", error.errorCode());
            assertEquals(Map.of("variables", Map.of("disk", "123")),
                ServerCommandResultProcessor.process(request("\\n", ""), 0, "123\n", false));
        });
    }

    @Test
    void cancellationDoesNotPublishCandidate() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertEquals("TASK_CANCELLED", assertThrows(AgentExecutionException.class,
                () -> ServerCommandResultProcessor.process(request("\\n", ""), 0, "123\n", false)).errorCode());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void boundsCaptureExpansionAndEmptyMatchReplacementWork() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertEquals("SERVER_RESULT_TOO_LARGE", assertThrows(AgentExecutionException.class,
                () -> ServerCommandResultProcessor.process(request("(x+)", "$1".repeat(2048)), 0, "x".repeat(60000), false)).errorCode());
            assertEquals("SERVER_RESULT_REGEX_LIMIT", assertThrows(AgentExecutionException.class,
                () -> ServerCommandResultProcessor.process(request("()", "$1".repeat(2048)), 0, "x".repeat(60000), false)).errorCode());
        });
    }

    @Test
    void optionalFieldsDoNotChangeSerializedLegacyPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InfrastructureTaskRequest legacy = mapper.convertValue(Map.of("actionType", "server_command"), InfrastructureTaskRequest.class);
        String json = mapper.writeValueAsString(legacy);
        assertFalse(json.contains("replaceRegex"));
        assertFalse(json.contains("replaceValue"));
        assertFalse(json.contains("valueMasked"));
    }

    private InfrastructureTaskRequest request(String regex, String replacement) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action_type", "server_command");
        values.put("replace_regex", regex);
        values.put("replace_value", replacement);
        values.put("variable_name", "disk");
        return new ObjectMapper().convertValue(values, InfrastructureTaskRequest.class);
    }
}
