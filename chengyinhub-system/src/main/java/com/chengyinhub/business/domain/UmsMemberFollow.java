package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 用户之间关注对象 ums_member_follow
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public class UmsMemberFollow extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 关注用户ID */
    @Excel(name = "关注用户ID")
    private Long memberId;

    /** 被关注用户ID */
    @Excel(name = "被关注用户ID")
    private Long followMemberId;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setFollowMemberId(Long followMemberId) 
    {
        this.followMemberId = followMemberId;
    }

    public Long getFollowMemberId() 
    {
        return followMemberId;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("followMemberId", getFollowMemberId())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
