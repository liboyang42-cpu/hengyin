package com.chengyinhub.business.domain;

import java.util.Date;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * IM 会话参与者 im_conversation_member
 */
public class ImConversationMember extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 会话id */
    private Long conversationId;

    /** 会员id */
    private Long memberId;

    /** 角色 0普通 1商家 2系统 */
    private Integer role;

    /** 未读数 */
    private Integer unreadCount;

    /** 已读到的消息id */
    private Long lastReadMsgId;

    /** 免打扰 */
    private Integer muted;

    /** 0正常 1删除会话 */
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Integer getRole() { return role; }
    public void setRole(Integer role) { this.role = role; }

    public Integer getUnreadCount() { return unreadCount; }
    public void setUnreadCount(Integer unreadCount) { this.unreadCount = unreadCount; }

    public Long getLastReadMsgId() { return lastReadMsgId; }
    public void setLastReadMsgId(Long lastReadMsgId) { this.lastReadMsgId = lastReadMsgId; }

    public Integer getMuted() { return muted; }
    public void setMuted(Integer muted) { this.muted = muted; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
