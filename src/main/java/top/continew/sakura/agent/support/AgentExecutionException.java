package top.continew.sakura.agent.support;

/**
 * 面向任务响应的受控错误。
 *
 * <p>执行器不得把本机路径、命令原文或第三方异常直接透传给 Admin；需要明确动作错误码时统一使用该异常。</p>
 */
public class AgentExecutionException extends Exception {

    private final String errorCode;

    public AgentExecutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentExecutionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
