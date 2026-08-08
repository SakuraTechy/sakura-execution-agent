package top.continew.sakura.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.model.InfrastructureTaskResponse;
import top.continew.sakura.agent.model.InfrastructureResultV2;
import top.continew.sakura.agent.service.InfrastructureTaskService;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

class AgentHttpHandlerArtifactTest {

    @TempDir
    Path tempDirectory;

    @Test
    void artifactRequiresBearerTokenAndReturnsCompleteStoredRows() throws Exception {
        Path driverRoot = Files.createDirectories(tempDirectory.resolve("drivers"));
        Path profile = Files.createDirectories(driverRoot.resolve("h2"));
        Path h2Jar = Path.of(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(h2Jar, profile.resolve("h2.jar"));
        ObjectMapper objectMapper = new ObjectMapper();
        AgentLogger logger = new AgentLogger(tempDirectory.resolve("agent.log"));
        InfrastructureTaskRequest request = objectMapper.convertValue(Map.of(
            "taskId", "artifact-http",
            "actionType", "database_sql",
            "sqlMode", "query",
            "riskLevel", "read",
            "readOnlyEnforced", true,
            "sql", "SELECT CAST(X AS INT) AS id FROM SYSTEM_RANGE(1, 5)",
            "maxRows", 1,
            "jdbcTarget", Map.of(
                "driverProfile", "h2",
                "driverClass", "org.h2.Driver",
                "jdbcUrl", "jdbc:h2:mem:artifact_http;DB_CLOSE_DELAY=-1")), InfrastructureTaskRequest.class);
        try (InfrastructureTaskService service = new InfrastructureTaskService(objectMapper, driverRoot, logger,
            new LocalActionPolicy(tempDirectory.resolve("workspace"), List.of()), tempDirectory
                .resolve("data/task-ledger.json"))) {
            service.submit(request);
            InfrastructureTaskResponse completed = awaitPassed(service, request.taskId());
            InfrastructureResultV2 infrastructure = (InfrastructureResultV2)completed.result().get("infrastructure");
            assertEquals(true, infrastructure.artifact().get("available"));

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new AgentHttpHandler(objectMapper, "test-token", service, logger,
                Map.of("status", "ok")));
            server.start();
            try {
                URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/v1/tasks/artifact-http/artifact");
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<byte[]> unauthorized = client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(401, unauthorized.statusCode());

                HttpRequest authorizedRequest = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer test-token")
                    .GET()
                    .build();
                HttpResponse<byte[]> authorized = client.send(authorizedRequest, HttpResponse.BodyHandlers
                    .ofByteArray());
                assertEquals(200, authorized.statusCode());
                assertTrue(authorized.headers().firstValue("X-Content-Sha256").orElse("")
                    .matches("[0-9a-f]{64}"));
                Map<String, Object> envelope = objectMapper.readValue(authorized.body(), new TypeReference<>() { });
                @SuppressWarnings("unchecked")
                Map<String, Object> storedResult = (Map<String, Object>)envelope.get("result");
                assertEquals(5, ((List<?>)storedResult.get("rows")).size());
            } finally {
                server.stop(0);
            }
        }
    }

    private InfrastructureTaskResponse awaitPassed(InfrastructureTaskService service,
                                                    String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            InfrastructureTaskResponse response = service.get(taskId);
            if ("passed".equals(response.status())) return response;
            if ("failed".equals(response.status())) {
                throw new AssertionError("任务执行失败：" + response.error());
            }
            Thread.sleep(20);
        }
        throw new AssertionError("任务未在测试时限内完成");
    }
}
