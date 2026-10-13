package com.chengyinhub.business.domain.dto.ai;

/**
 * NPC 自由追问请求。
 *
 * @author chengyinhub
 */
public class NpcChatReq
{
    /** 客户端生成 UUID；幂等作用域由服务端限定为 userId + requestId。 */
    private String requestId;
    /** 漫游会话 ID；服务端校验归属和进行中状态。 */
    private Long bizId;
    private String message;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getBizId() { return bizId; }
    public void setBizId(Long bizId) { this.bizId = bizId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
