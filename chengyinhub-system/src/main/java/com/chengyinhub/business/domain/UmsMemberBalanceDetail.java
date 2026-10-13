package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 用户余额明细对象 ums_member_balance_detail
 * 
 * @author webxh
 * @date 2025-10-20
 */
public class UmsMemberBalanceDetail extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @Excel(name = "ID")
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 用户当前余额 */
    @Excel(name = "用户当前余额")
    private BigDecimal currentBalance;

    /** 用户变化后余额 */
    @Excel(name = "用户变化后余额")
    private BigDecimal afterBalance;

    /** 变化金额 */
    @Excel(name = "变化金额")
    private BigDecimal changeBalance;

    /** 变化原因 */
    @Excel(name = "变化原因")
    private String changeReason;

    /** 事件类型：1=主题分润 2=活动分润 3=提现申请 4=提现驳回 5=商家邀请分润 */
    //@Excel(name = "事件类型：1=主题分润 2=活动分润 3=提现申请 4=提现驳回 5=商家邀请分润")
    private Long eventType;

    /** 对应事件ID如：报名ID等 */
    @Excel(name = "对应事件ID")
    private Long eventId;

    /** 变化类型1：收入  2、支出 */
    @Excel(name = "变化类型",readConverterExp = "1=收入,2=支出")
    private Long changeType;

    /** 删除标记 0 代表存在  2删除 */
    private Long delFlag;



    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "变化时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;


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

    public void setCurrentBalance(BigDecimal currentBalance) 
    {
        this.currentBalance = currentBalance;
    }

    public BigDecimal getCurrentBalance() 
    {
        return currentBalance;
    }

    public void setAfterBalance(BigDecimal afterBalance) 
    {
        this.afterBalance = afterBalance;
    }

    public BigDecimal getAfterBalance() 
    {
        return afterBalance;
    }

    public void setChangeBalance(BigDecimal changeBalance) 
    {
        this.changeBalance = changeBalance;
    }

    public BigDecimal getChangeBalance() 
    {
        return changeBalance;
    }

    public void setChangeReason(String changeReason) 
    {
        this.changeReason = changeReason;
    }

    public String getChangeReason() 
    {
        return changeReason;
    }

    public void setEventType(Long eventType) 
    {
        this.eventType = eventType;
    }

    public Long getEventType() 
    {
        return eventType;
    }

    public void setEventId(Long eventId) 
    {
        this.eventId = eventId;
    }

    public Long getEventId() 
    {
        return eventId;
    }

    public void setChangeType(Long changeType) 
    {
        this.changeType = changeType;
    }

    public Long getChangeType() 
    {
        return changeType;
    }

    public void setDelFlag(Long delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Long getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public Date getCreateTime() {
        return createTime;
    }

    @Override
    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }
    
    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("currentBalance", getCurrentBalance())
            .append("afterBalance", getAfterBalance())
            .append("changeBalance", getChangeBalance())
            .append("changeReason", getChangeReason())
            .append("eventType", getEventType())
            .append("eventId", getEventId())
            .append("changeType", getChangeType())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
