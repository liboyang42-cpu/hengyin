package com.chengyinhub.business.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 用户积分明细对象 ums_member_points_detail
 * 
 * @author ruoyi
 * @date 2025-08-26
 */
public class UmsMemberPointsDetail extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 用户当前积分 */
    @Excel(name = "用户当前积分")
    private Long currentPoints;

    /** 用户变化后积分 */
    @Excel(name = "用户变化后积分")
    private Long afterPoints;

    /** 变化积分 */
    @Excel(name = "变化积分")
    private Long changePoints;

    /** 变化原因 */
    @Excel(name = "变化原因")
    private String changeReason;

    /** 事件类型：1、注册账号  2、首次报名  3、参与活动 4、活动评价  5、被邀请用户注册并完成首单 6、被邀请用户每次报名  7、推荐商户注册  99 其它 */
    //@Excel(name = "事件类型：1、注册账号  2、首次报名  3、参与活动 4、活动评价  5、被邀请用户注册并完成首单 6、被邀请用户每次报名  7、推荐商户注册  99 其它")
    private Long eventType;

    /** 对应事件ID如：报名ID等 */
    @Excel(name = "事件ID")
    private Long eventId;

    /** 产生积分的命名空间业务键；资金奖励用它做 exactly-once，不能复用裸 event_type/event_id。 */
    private String rewardBusinessKey;

    /** 发奖时积分规则ID快照。 */
    private Long pointsRuleId;

    /** 发奖时积分规则版本快照。 */
    private Long pointsRuleVersion;

    /** 发奖时积分规则值快照。 */
    private BigDecimal pointsRuleValue;

    /** event 15 专用的全局主题幂等键；只有结算发奖事务可以写入。 */
    private Long publisherSettlementTopicId;

    /** 变化类型1：收入  2、支出 */
    @Excel(name = "变化类型",readConverterExp = "1=收入,2=支出")
    private Long changeType;

    /** 删除标记 0 代表存在  2删除 */
    private Integer delFlag;




    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "变化时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    /** 搜索开始时间 */
    @TableField(exist = false)
    private String startTime;

    /** 搜索结束时间 */
    @TableField(exist = false)
    private String endTime;


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

    public void setCurrentPoints(Long currentPoints) 
    {
        this.currentPoints = currentPoints;
    }

    public Long getCurrentPoints() 
    {
        return currentPoints;
    }

    public void setAfterPoints(Long afterPoints) 
    {
        this.afterPoints = afterPoints;
    }

    public Long getAfterPoints() 
    {
        return afterPoints;
    }

    public void setChangePoints(Long changePoints) 
    {
        this.changePoints = changePoints;
    }

    public Long getChangePoints() 
    {
        return changePoints;
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

    public String getRewardBusinessKey()
    {
        return rewardBusinessKey;
    }

    public void setRewardBusinessKey(String rewardBusinessKey)
    {
        this.rewardBusinessKey = rewardBusinessKey;
    }

    public Long getPointsRuleId()
    {
        return pointsRuleId;
    }

    public void setPointsRuleId(Long pointsRuleId)
    {
        this.pointsRuleId = pointsRuleId;
    }

    public Long getPointsRuleVersion()
    {
        return pointsRuleVersion;
    }

    public void setPointsRuleVersion(Long pointsRuleVersion)
    {
        this.pointsRuleVersion = pointsRuleVersion;
    }

    public BigDecimal getPointsRuleValue()
    {
        return pointsRuleValue;
    }

    public void setPointsRuleValue(BigDecimal pointsRuleValue)
    {
        this.pointsRuleValue = pointsRuleValue;
    }

    public Long getPublisherSettlementTopicId()
    {
        return publisherSettlementTopicId;
    }

    public void setPublisherSettlementTopicId(Long publisherSettlementTopicId)
    {
        this.publisherSettlementTopicId = publisherSettlementTopicId;
    }

    public void setChangeType(Long changeType) 
    {
        this.changeType = changeType;
    }

    public Long getChangeType() 
    {
        return changeType;
    }

    public void setDelFlag(Integer delFlag) 
    {
        this.delFlag = delFlag;
    }

    public Integer getDelFlag() 
    {
        return delFlag;
    }


    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
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
            .append("currentPoints", getCurrentPoints())
            .append("afterPoints", getAfterPoints())
            .append("changePoints", getChangePoints())
            .append("changeReason", getChangeReason())
            .append("eventType", getEventType())
            .append("eventId", getEventId())
            .append("rewardBusinessKey", getRewardBusinessKey())
            .append("pointsRuleId", getPointsRuleId())
            .append("pointsRuleVersion", getPointsRuleVersion())
            .append("pointsRuleValue", getPointsRuleValue())
            .append("publisherSettlementTopicId", getPublisherSettlementTopicId())
            .append("changeType", getChangeType())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
