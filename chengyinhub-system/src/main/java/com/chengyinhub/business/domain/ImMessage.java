package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * IM 消息对象 im_message
 */
public class ImMessage extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    public static final int MSG_TEXT = 1;
    public static final int MSG_IMAGE = 2;
    public static final int MSG_CARD = 3;
    public static final int MSG_POLL = 4;

    public static final int STATUS_NORMAL = 0;
    public static final int STATUS_RECALLED = 1;
    public static final int STATUS_BLOCKED = 2;

    /** 主键 */
    private Long id;

    /** 会话id */
    private Long conversationId;

    /** 发送者会员id(0=系统) */
    private Long senderId;

    /** 接收者会员id */
    private Long receiverId;

    /** 1文本 2图片 3系统卡片 4投票卡片 */
    private Integer msgType;

    /** 内容 */
    private String content;

    /** 卡片扩展 */
    private String extraJson;

    /** 0正常 1撤回 2违规屏蔽 */
    private Integer status;

    /** 内容安全 0待检 1通过 2违规 */
    private Integer checkStatus;

    /** 图片异步检测trace_id */
    private String traceId;

    /** 一次发送的客户端标识，重试保持不变。 */
    private String clientMessageId;
    private String requestHash;

    public String getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }
    @JsonIgnore
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }

    /** 发送者昵称(瞬态:仅消息列表回填供群聊渲染,不入库) */
    private String senderName;
    /** 发送者头像(瞬态:仅消息列表回填供群聊渲染,不入库) */
    private String senderAvatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSenderAvatar() { return senderAvatar; }
    public void setSenderAvatar(String senderAvatar) { this.senderAvatar = senderAvatar; }

    public Long getReceiverId() { return receiverId; }
    public void setReceiverId(Long receiverId) { this.receiverId = receiverId; }

    public Integer getMsgType() { return msgType; }
    public void setMsgType(Integer msgType) { this.msgType = msgType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getCheckStatus() { return checkStatus; }
    public void setCheckStatus(Integer checkStatus) { this.checkStatus = checkStatus; }

    @JsonIgnore
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
