package top.continew.sakura.agent.service;

import top.continew.sakura.agent.support.AgentExecutionException;

import java.util.List;

@FunctionalInterface
interface AvailableIpProbe {
    ProbeResult findAvailable(String prefix, int start, int end, int timeoutMs) throws AgentExecutionException;

    default String source() {
        return "local-reachability";
    }

    record ProbeResult(String availableIp, List<String> respondingIps) {
        public ProbeResult {
            respondingIps = List.copyOf(respondingIps);
        }
    }
}
