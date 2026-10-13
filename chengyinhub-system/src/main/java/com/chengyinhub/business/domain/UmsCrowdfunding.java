package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 用户众筹对象 ums_crowdfunding
 * 
 * @author ruoyi
 * @date 2025-08-28
 */
public class UmsCrowdfunding extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 创意广场活动ID */
    @Excel(name = "创意广场活动ID")
    private Long activeId;

    /** 礼物ID */
    @Excel(name = "礼物ID")
    private Long giftId;

    /** 花费积分 */
    @Excel(name = "花费积分")
    private Long point;

    /** 内容 */
    @Excel(name = "内容")
    private String contents;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;

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

    public void setActiveId(Long activeId) 
    {
        this.activeId = activeId;
    }

    public Long getActiveId() 
    {
        return activeId;
    }

    public void setGiftId(Long giftId) 
    {
        this.giftId = giftId;
    }

    public Long getGiftId() 
    {
        return giftId;
    }

    public void setPoint(Long point) 
    {
        this.point = point;
    }

    public Long getPoint() 
    {
        return point;
    }

    public void setContents(String contents) 
    {
        this.contents = contents;
    }

    public String getContents() 
    {
        return contents;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("activeId", getActiveId())
            .append("giftId", getGiftId())
            .append("point", getPoint())
            .append("contents", getContents())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
