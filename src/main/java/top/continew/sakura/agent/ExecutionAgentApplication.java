package top.continew.sakura.agent;

import java.awt.GraphicsEnvironment;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import top.continew.sakura.agent.http.AgentHttpHandler;
import top.continew.sakura.agent.service.InfrastructureTaskService;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

/**
 * Runner 节点上的本机基础设施执行 Agent。
 *
 * <p>Agent 只接受已经由 admin 解析和授权的短时任务，不持久化场景和长期凭据。</p>
 */
public final class ExecutionAgentApplication {

    private ExecutionAgentApplication() {
    }

    public static void main(String[] args) throws Exception {
        String bindAddress = System.getProperty("sakura.agent.bind", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("sakura.agent.port", "19091"));
        String token = System.getenv("SAKURA_AGENT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("必须配置 SAKURA_AGENT_TOKEN，Agent 拒绝无认证启动");
        }
        Path driverDirectory = Path.of(System.getProperty("sakura.agent.driver-dir", "drivers"))
            .toAbsolutePath()
            .normalize();
        Path logFile = Path.of(System.getProperty("sakura.agent.log-file", "logs/agent.log"));
        // 任务状态中使用 Instant，必须以 ISO-8601 写入 JSON；否则状态轮询会因序列化失败反复返回 500。
        ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AgentLogger logger = new AgentLogger(logFile);
        LocalActionPolicy localActionPolicy = LocalActionPolicy.fromSystemProperties();
        InfrastructureTaskService taskService = new InfrastructureTaskService(objectMapper, driverDirectory, logger,
            localActionPolicy);
        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        Map<String, Object> healthSnapshot = createHealthSnapshot(localActionPolicy);
        server.createContext("/", new AgentHttpHandler(objectMapper, token, taskService, logger, healthSnapshot));
        server.setExecutor(Executors.newCachedThreadPool());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            taskService.close();
            server.stop(0);
        }, "sakura-agent-shutdown"));
        server.start();
        System.out.printf("Sakura Execution Agent 已监听 http://%s:%d，driverDirectory=%s，workspace=%s%n", bindAddress, port,
            driverDirectory, localActionPolicy.workspaceRoot());
        logger.info("AGENT_STARTED", null, null, "reason=Agent启动完成 bind=" + bindAddress + ":" + port
            + " driverDirectory=" + driverDirectory + " workspaceConfigured=true logFile=" + logFile.toAbsolutePath().normalize());
    }

    private static Map<String, Object> createHealthSnapshot(LocalActionPolicy localActionPolicy) {
        Set<String> agentTypes = new LinkedHashSet<>(Set.of("server", "runner-host"));
        Set<String> features = new LinkedHashSet<>(Set.of("sftp", "host_command", "host_file", "host_file_delete"));
        if (localActionPolicy.hasRuntimePropertyAllowlist()) {
            features.add("runtime_property");
        }
        if (!GraphicsEnvironment.isHeadless()) {
            agentTypes.add("desktop");
            features.add("interactive_desktop");
        }
        if (localActionPolicy.hasCaptchaOcrConfiguration()) {
            agentTypes.add("ocr");
            features.add("screenshot");
            features.add("ocr");
        }
        // 健康响应只发布能力标识，不返回 workspace、脚本路径或任何凭据。
        return Map.of("status", "ok", "agent_types", Set.copyOf(agentTypes), "features", Set.copyOf(features));
    }
}
