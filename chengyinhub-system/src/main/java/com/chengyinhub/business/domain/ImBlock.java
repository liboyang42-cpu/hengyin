package com.chengyinhub.business.domain;

import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * IM 拉黑对象 im_block
 */
public class ImBlock extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 拉黑发起人 */
    private Long memberId;

    /** 被拉黑人 */
    private Long blockedMemberId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getBlockedMemberId() { return blockedMemberId; }
    public void setBlockedMemberId(Long blockedMemberId) { this.blockedMemberId = blockedMemberId; }
}
