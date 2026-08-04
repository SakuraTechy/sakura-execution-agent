package top.continew.sakura.agent.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.service.InfrastructureTaskService;
import top.continew.sakura.agent.support.AgentLogger;

/** 本机 HTTP 边界：认证、JSON 解码和任务路由全部在这里收敛。 */
public class AgentHttpHandler implements HttpHandler {

    private final ObjectMapper objectMapper;
    private final String token;
    private final InfrastructureTaskService taskService;
    private final AgentLogger logger;
    private final Map<String, Object> healthSnapshot;

    public AgentHttpHandler(ObjectMapper objectMapper,
                            String token,
                            InfrastructureTaskService taskService,
                            AgentLogger logger,
                            Map<String, Object> healthSnapshot) {
        this.objectMapper = objectMapper;
        this.token = token;
        this.taskService = taskService;
        this.logger = logger;
        this.healthSnapshot = Map.copyOf(healthSnapshot);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String remote = remoteEndpoint(exchange);
        String requestContext = "requestId=" + requestId + " method=" + method + " path=" + path + " remote=" + remote
            + " contentLength=" + exchange.getRequestHeaders().getFirst("Content-Length");
        try {
            logger.info("HTTP_RECEIVED", null, null, requestContext + " reason=收到HTTP请求");
            if ("/health".equals(path)) {
                logger.info("HEALTH_CHECK", null, null, requestContext + " reason=健康检查");
                respond(exchange, 200, healthSnapshot, null, null, requestId, "健康检查通过");
                return;
            }
            String authorizationFailureReason = authorizationFailureReason(exchange);
            if (authorizationFailureReason != null) {
                logger.warn("UNAUTHORIZED", null, null, requestContext + " reason=" + authorizationFailureReason);
                respond(exchange, 401, Map.of("error", "UNAUTHORIZED", "message", authorizationFailureReason), null, null,
                    requestId, "请求认证失败：" + authorizationFailureReason);
                return;
            }
            logger.info("REQUEST_AUTHORIZED", null, null, requestContext + " reason=Bearer令牌校验通过");
            if ("POST".equals(method) && "/v1/tasks".equals(path)) {
                InfrastructureTaskRequest request = read(exchange.getRequestBody(), InfrastructureTaskRequest.class);
                if (request == null) {
                    throw new IllegalArgumentException("请求体不能为空");
                }
                logger.info("TASK_REQUEST_DECODED", request.taskId(), request.actionType(), requestContext
                    + " reason=任务请求JSON解析成功 timeoutMs=" + request.timeoutMs() + " maxRows=" + request.maxRows());
                respond(exchange, 202, taskService.submit(request), request.taskId(), request.actionType(), requestId,
                    "任务已接收，进入异步执行队列");
                return;
            }
            if (path.startsWith("/v1/tasks/")) {
                String taskId = path.substring("/v1/tasks/".length());
                if (taskId.isBlank() || taskId.contains("/")) {
                    respond(exchange, 404, Map.of("error", "TASK_NOT_FOUND"), null, null, requestId,
                        "任务路径中的taskId为空或包含非法斜杠");
                    return;
                }
                if ("GET".equals(method)) {
                    logger.info("TASK_STATUS_REQUESTED", taskId, null, requestContext + " reason=查询任务当前状态");
                    respond(exchange, 200, taskService.get(taskId), taskId, null, requestId, "任务状态查询成功");
                    return;
                }
                if ("DELETE".equals(method)) {
                    logger.warn("TASK_CANCEL_REQUESTED", taskId, null, requestContext + " reason=收到任务取消请求");
                    respond(exchange, 200, taskService.cancel(taskId), taskId, null, requestId, "任务取消请求已处理");
                    return;
                }
            }
            respond(exchange, 404, Map.of("error", "NOT_FOUND"), null, null, requestId,
                "请求方法或路径不支持");
        } catch (IllegalArgumentException e) {
            String invalidMessage = e.getMessage() == null || e.getMessage().isBlank() ? "请求参数非法" : e.getMessage();
            logger.warn("INVALID_REQUEST", null, null, requestContext + " reason=请求参数或任务参数校验失败 exception="
                + e.getClass().getSimpleName() + " message=" + invalidMessage);
            respond(exchange, 400, Map.of("error", "INVALID_REQUEST", "message", invalidMessage), null, null, requestId,
                "请求参数校验失败");
        } catch (Exception e) {
            // HTTP 解码异常可能携带原始 JSON 片段；不能把命令、路径或凭据写入结构化日志。
            logger.error("HTTP_ERROR", null, null, "errorType=" + e.getClass().getSimpleName() + " message="
                + safeMessage(e));
            respond(exchange, 500, Map.of("error", "AGENT_INTERNAL_ERROR", "message", safeMessage(e)), null, null,
                requestId, "Agent处理HTTP请求时发生内部异常");
        } finally {
            exchange.close();
        }
    }

    private String authorizationFailureReason(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return "未提供Authorization请求头";
        }
        if (!authorization.startsWith("Bearer ")) {
            return "Authorization格式错误，应为Bearer <Token>";
        }
        if (authorization.length() <= "Bearer ".length()) {
            return "Bearer令牌为空";
        }
        return ("Bearer " + token).equals(authorization) ? null : "Bearer令牌与Agent配置不匹配";
    }

    private <T> T read(InputStream input, Class<T> type) throws IOException {
        return objectMapper.readValue(input, type);
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void respond(HttpExchange exchange, int status, Object body, String taskId, String actionType,
                         String requestId, String reason) throws IOException {
        write(exchange, status, body);
        logger.info("HTTP_RESPONSE", taskId, actionType, "requestId=" + requestId + " status=" + status + " reason="
            + reason);
    }

    private String remoteEndpoint(HttpExchange exchange) {
        InetSocketAddress address = exchange.getRemoteAddress();
        if (address == null) {
            return "unknown";
        }
        String host = address.getAddress() == null ? address.getHostString() : address.getAddress().getHostAddress();
        return host + ":" + address.getPort();
    }

    private String safeMessage(Exception error) {
        if (error instanceof JsonProcessingException) {
            return "请求 JSON 格式非法";
        }
        String message = error.getMessage();
        String safe = message == null || message.isBlank() ? error.getClass().getSimpleName()
            : message.replaceAll("(?i)(password|passwd|pwd|token|secret)\\s*([=:])\\s*[^\\s,;]+", "$1$2***")
                .replaceAll("(?i)(mongodb(?:\\+srv)?://)[^\\s/@]+@", "$1***@");
        safe = safe.replaceAll("(?i)(?:[A-Z]:\\\\|/)(?:[^\\s,;]+)", "<path>");
        return safe.length() <= 400 ? safe : safe.substring(0, 400) + "…[truncated]";
    }
}
