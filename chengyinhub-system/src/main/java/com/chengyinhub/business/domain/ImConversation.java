package com.chengyinhub.business.domain;

import java.util.Date;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * IM 会话对象 im_conversation
 */
public class ImConversation extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    public static final int TYPE_SINGLE = 1;
    public static final int TYPE_SYSTEM = 2;
    public static final int TYPE_MERCHANT = 3;
    public static final int TYPE_GROUP = 4;

    /** 主键 */
    private Long id;

    /** 类型 1单聊 2系统 3商家 */
    private Integer type;

    /** 业务唯一键 */
    private String bizKey;

    /** 最后一条消息类型 */
    private Integer lastMsgType;

    /** 最后一条消息摘要 */
    private String lastMsgText;

    /** 最后一条消息时间 */
    private Date lastMsgAt;

    /** 状态 0正常 1封禁 */
    private Integer status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }

    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }

    public Integer getLastMsgType() { return lastMsgType; }
    public void setLastMsgType(Integer lastMsgType) { this.lastMsgType = lastMsgType; }

    public String getLastMsgText() { return lastMsgText; }
    public void setLastMsgText(String lastMsgText) { this.lastMsgText = lastMsgText; }

    public Date getLastMsgAt() { return lastMsgAt; }
    public void setLastMsgAt(Date lastMsgAt) { this.lastMsgAt = lastMsgAt; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
}
