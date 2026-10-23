package com.chengyinhub.business.domain.vo.ai;

/**
 * AI 主题草稿请求的服务端状态(按 requestId 回读)。
 *
 * <p>state:
 * PROCESSING 已受理、模型调用在途;
 * SUCCEEDED 已生成,draft 为当时返回的那一份(不会重新生成);
 * FAILED 已明确失败,客户端应换新 requestId 再试;
 * UNKNOWN 受理后超过租约仍无终态,结果不可证明 —— 不能据此认定「没生成」或「额度没扣」;
 * NOT_FOUND 服务端没有这条 requestId(从未送达或已过保留期)。
 */
public class AiThemeDraftRequestState
{
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NOT_FOUND = "NOT_FOUND";

    private String requestId;
    private String state;
    /** 仅 SUCCEEDED 时有值。 */
    private AiThemeDraftResp result;
    /** FAILED 时的用户可读原因。 */
    private String message;
    /** true = 本次是回放已有结果,没有再次调用模型。 */
    private boolean replayed;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public AiThemeDraftResp getResult() { return result; }
    public void setResult(AiThemeDraftResp result) { this.result = result; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }
}
