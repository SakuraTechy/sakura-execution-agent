package top.continew.sakura.agent.support;

/** Agent HTTP 边界可安全返回的请求错误。 */
public class AgentRequestException extends IllegalArgumentException {

    private final String errorCode;

    public AgentRequestException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
