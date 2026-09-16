package top.continew.sakura.agent.service;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.support.AgentExecutionException;

/** 仅供跨执行器回归：通过标准输入传入非生产样本，不连接 SSH、不写原值文件。 */
public final class ServerCommandResultFixture {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var input = mapper.readTree(System.in);
        InfrastructureTaskRequest request = mapper.treeToValue(input.path("request"), InfrastructureTaskRequest.class);
        int exitCode = input.path("exitCode").asInt(0);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("exitCode", exitCode);
        try {
            response.put("result", ServerCommandResultProcessor.process(request, exitCode,
                input.path("stdout").asText(), input.path("truncated").asBoolean()));
            response.put("status", exitCode == 0 ? "passed" : "failed");
            if (exitCode != 0) {
                response.put("errorCode", "SERVER_COMMAND_EXIT_NON_ZERO");
            }
        } catch (AgentExecutionException e) {
            response.put("status", "failed");
            response.put("errorCode", e.errorCode());
            response.put("error", e.getMessage());
        }
        System.out.print(mapper.writeValueAsString(response));
    }
}
