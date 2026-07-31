package top.continew.sakura.agent.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalActionPolicyTest {

    @TempDir
    Path tempDirectory;

    @Test
    void allowsOnlyNormalizedDescendantOfWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDirectory.resolve("agent-workspace"));
        Path nestedFile = Files.writeString(Files.createDirectories(workspace.resolve("data")).resolve("case.txt"), "ok");
        LocalActionPolicy policy = new LocalActionPolicy(workspace, List.of());

        assertEquals(nestedFile.toRealPath(), policy.resolveExistingAllowedPath(nestedFile.toString(), "lookup"));
    }

    @Test
    void rejectsOutsideRootAndVariableExpression() throws Exception {
        Path workspace = Files.createDirectories(tempDirectory.resolve("agent-workspace"));
        Path outsideFile = Files.writeString(tempDirectory.resolve("outside.txt"), "outside");
        LocalActionPolicy policy = new LocalActionPolicy(workspace, List.of());

        AgentExecutionException outside = assertThrows(AgentExecutionException.class,
            () -> policy.resolveExistingAllowedPath(outsideFile.toString(), "lookup"));
        assertEquals("HOST_PATH_NOT_ALLOWED", outside.errorCode());

        AgentExecutionException expression = assertThrows(AgentExecutionException.class,
            () -> policy.resolveExistingAllowedPath("${WORKSPACE}\\case.txt", "lookup"));
        assertEquals("HOST_PATH_EXPRESSION_REJECTED", expression.errorCode());

        AgentExecutionException root = assertThrows(AgentExecutionException.class,
            () -> policy.resolveExistingAllowedPath(workspace.toString(), "lookup"));
        assertEquals("HOST_PATH_NOT_ALLOWED", root.errorCode());
    }
}
