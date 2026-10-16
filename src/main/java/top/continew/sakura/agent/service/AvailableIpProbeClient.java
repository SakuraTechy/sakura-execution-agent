package top.continew.sakura.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.continew.sakura.agent.support.AgentExecutionException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AvailableIpProbeClient implements AvailableIpProbe {
    private static final String URL_ENV = "SAKURA_AGENT_AVAILABLE_IP_PROBE_URL";
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String token;

    AvailableIpProbeClient(ObjectMapper objectMapper, String endpoint, String token) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.endpoint = URI.create(endpoint);
        this.token = token;
    }

    static AvailableIpProbe fromEnvironment(ObjectMapper objectMapper) {
        String endpoint = System.getenv(URL_ENV);
        if (endpoint == null || endpoint.isBlank()) {
            if (Files.exists(Path.of("/.dockerenv"))) {
                // Docker 中必须从宿主机网络命名空间探测，不能退回容器视角的 isReachable。
                return new AvailableIpProbe() {
                    @Override
                    public String source() {
                        return "host-arp";
                    }

                    @Override
                    public ProbeResult findAvailable(String prefix, int start, int end, int timeoutMs)
                        throws AgentExecutionException {
                        throw new AgentExecutionException("AVAILABLE_IP_PROBE_NOT_CONFIGURED",
                            "Docker 执行节点未配置宿主机 IP 探测服务");
                    }
                };
            }
            return (prefix, start, end, timeoutMs) -> {
                long deadline = System.nanoTime() + Duration.ofMillis(Math.max(1_000, timeoutMs)).toNanos();
                List<String> respondingIps = new ArrayList<>();
                for (int hostPart = start; hostPart <= end; hostPart++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new AgentExecutionException("TASK_CANCELLED", "可用 IP 探测已取消");
                    }
                    long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
                    if (remainingMs <= 0) {
                        throw new AgentExecutionException("AVAILABLE_IP_PROBE_TIMEOUT", "可用 IP 探测超时");
                    }
                    try {
                        var candidate = java.net.InetAddress.getByName(prefix + "." + hostPart);
                        if (!candidate.isReachable((int)Math.max(100, Math.min(1_000, remainingMs)))) {
                            return new ProbeResult(candidate.getHostAddress(), respondingIps);
                        }
                        respondingIps.add(candidate.getHostAddress());
                    } catch (IOException exception) {
                        throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "本地 IP 探测失败", exception);
                    }
                }
                return new ProbeResult(null, respondingIps);
            };
        }
        return new AvailableIpProbeClient(objectMapper, endpoint, System.getenv("SAKURA_AGENT_TOKEN"));
    }

    @Override
    public String source() {
        return "host-arp";
    }

    @Override
    public ProbeResult findAvailable(String prefix, int start, int end, int timeoutMs) throws AgentExecutionException {
        if (token == null || token.isBlank()) {
            throw new AgentExecutionException("AVAILABLE_IP_PROBE_NOT_CONFIGURED", "宿主机 IP 探测服务缺少认证 Token");
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of("prefix", prefix, "start", start, "end", end,
                "timeout_ms", timeoutMs));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(Math.max(100, timeoutMs)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "宿主机 IP 探测服务拒绝或无法完成探测");
            }
            Map<?, ?> result = objectMapper.readValue(response.body(), Map.class);
            Object respondingIpsValue = result.get("responding_ips");
            if (!result.containsKey("available_ip") || !(respondingIpsValue instanceof List<?> respondingIpsRaw)) {
                throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "宿主机 IP 探测服务返回了无效结果");
            }
            Object availableIp = result.get("available_ip");
            if (availableIp != null && !(availableIp instanceof String)) {
                throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "宿主机 IP 探测服务返回了无效结果");
            }
            List<String> respondingIps = new ArrayList<>();
            for (Object respondingIp : respondingIpsRaw) {
                if (!(respondingIp instanceof String ip)) {
                    throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "宿主机 IP 探测服务返回了无效结果");
                }
                respondingIps.add(ip);
            }
            String method = result.get("probe_method") instanceof String probeMethod ? probeMethod : "arp";
            if (!List.of("arp", "icmp").contains(method)) {
                throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "宿主机 IP 探测服务返回了无效探测方式");
            }
            int unknownCount = 0;
            Object unknownCountValue = result.get("unknown_count");
            if (unknownCountValue instanceof Number count && count.intValue() >= 0) {
                unknownCount = count.intValue();
            }
            return new ProbeResult((String)availableIp, respondingIps, method, unknownCount);
        } catch (HttpTimeoutException exception) {
            throw new AgentExecutionException("AVAILABLE_IP_PROBE_TIMEOUT", "宿主机 IP 探测超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AgentExecutionException("TASK_CANCELLED", "可用 IP 探测已取消", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new AgentExecutionException("AVAILABLE_IP_PROBE_FAILED", "无法连接宿主机 IP 探测服务", exception);
        }
    }
}
