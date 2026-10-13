package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 参数设置对象 cms_setting
 * 
 * @author ruoyi
 * @date 2025-11-17
 */
public class CmsSetting extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 活动平台佣金（百分比） */
    @Excel(name = "活动平台佣金", readConverterExp = "百=分比")
    private BigDecimal activityPlatformCommission;

    /** 活动商户佣金（百分比） */
    @Excel(name = "活动商户佣金", readConverterExp = "百=分比")
    private BigDecimal activityMerchantCommission;

    /** 活动主题设计者佣金（百分比） */
    @Excel(name = "活动设计者佣金", readConverterExp = "百=分比")
    private BigDecimal activityDesignerCommission;

    /** 平台账户会员ID（接收平台分润） */
    @Excel(name = "平台账户会员ID")
    private Long platformMemberId;

    /** 主题平台佣金（百分比） */
    @Excel(name = "主题平台佣金", readConverterExp = "百=分比")
    private BigDecimal topicPlatformCommission;

    /** 主题商户佣金（百分比） */
    @Excel(name = "主题商户佣金", readConverterExp = "百=分比")
    private BigDecimal topicMerchantCommission;

    /** 主题发布者佣金（百分比） */
    @Excel(name = "主题发布者佣金", readConverterExp = "百=分比")
    private BigDecimal topicPublisherCommission;

    /** 主题发布者佣金（百分比） */
    @Excel(name = "主题邀请人佣金", readConverterExp = "百=分比")
    private BigDecimal topicInviteCommission;

    /** 普通用户主题发布数 */
    @Excel(name = "普通用户主题发布数")
    private Long user1TopicNum;

    /** 俱乐部主题发布数 */
    @Excel(name = "俱乐部主题发布数")
    private Long user2TopicNum;

    /** 畅玩VIP主题发布数 */
    @Excel(name = "畅玩VIP主题发布数")
    private Long user3TopicNum;

    /** 商户承接主题数 */
    @Excel(name = "商户承接主题数")
    private Long merchantTopicNum;

    /** 商户模板数 */
    @Excel(name = "商户模板数")
    private Long merchantTemplateNum;

    /** 商户优惠券数 */
    @Excel(name = "商户优惠券数")
    private Long merchantCouponNum;

    public BigDecimal getTopicInviteCommission() {
        return topicInviteCommission;
    }

    public void setTopicInviteCommission(BigDecimal topicInviteCommission) {
        this.topicInviteCommission = topicInviteCommission;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setActivityPlatformCommission(BigDecimal activityPlatformCommission) 
    {
        this.activityPlatformCommission = activityPlatformCommission;
    }

    public BigDecimal getActivityPlatformCommission() 
    {
        return activityPlatformCommission;
    }

    public void setActivityMerchantCommission(BigDecimal activityMerchantCommission) 
    {
        this.activityMerchantCommission = activityMerchantCommission;
    }

    public BigDecimal getActivityMerchantCommission()
    {
        return activityMerchantCommission;
    }

    public void setActivityDesignerCommission(BigDecimal activityDesignerCommission)
    {
        this.activityDesignerCommission = activityDesignerCommission;
    }

    public BigDecimal getActivityDesignerCommission()
    {
        return activityDesignerCommission;
    }

    public void setPlatformMemberId(Long platformMemberId)
    {
        this.platformMemberId = platformMemberId;
    }

    public Long getPlatformMemberId()
    {
        return platformMemberId;
    }

    public void setTopicPlatformCommission(BigDecimal topicPlatformCommission) 
    {
        this.topicPlatformCommission = topicPlatformCommission;
    }

    public BigDecimal getTopicPlatformCommission() 
    {
        return topicPlatformCommission;
    }

    public void setTopicMerchantCommission(BigDecimal topicMerchantCommission) 
    {
        this.topicMerchantCommission = topicMerchantCommission;
    }

    public BigDecimal getTopicMerchantCommission() 
    {
        return topicMerchantCommission;
    }

    public void setTopicPublisherCommission(BigDecimal topicPublisherCommission) 
    {
        this.topicPublisherCommission = topicPublisherCommission;
    }

    public BigDecimal getTopicPublisherCommission() 
    {
        return topicPublisherCommission;
    }

    public void setUser1TopicNum(Long user1TopicNum) 
    {
        this.user1TopicNum = user1TopicNum;
    }

    public Long getUser1TopicNum() 
    {
        return user1TopicNum;
    }

    public void setUser2TopicNum(Long user2TopicNum) 
    {
        this.user2TopicNum = user2TopicNum;
    }

    public Long getUser2TopicNum() 
    {
        return user2TopicNum;
    }

    public void setUser3TopicNum(Long user3TopicNum) 
    {
        this.user3TopicNum = user3TopicNum;
    }

    public Long getUser3TopicNum() 
    {
        return user3TopicNum;
    }

    public void setMerchantTopicNum(Long merchantTopicNum) 
    {
        this.merchantTopicNum = merchantTopicNum;
    }

    public Long getMerchantTopicNum() 
    {
        return merchantTopicNum;
    }

    public void setMerchantTemplateNum(Long merchantTemplateNum) 
    {
        this.merchantTemplateNum = merchantTemplateNum;
    }

    public Long getMerchantTemplateNum() 
    {
        return merchantTemplateNum;
    }

    public void setMerchantCouponNum(Long merchantCouponNum) 
    {
        this.merchantCouponNum = merchantCouponNum;
    }

    public Long getMerchantCouponNum() 
    {
        return merchantCouponNum;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("activityPlatformCommission", getActivityPlatformCommission())
            .append("activityMerchantCommission", getActivityMerchantCommission())
            .append("activityDesignerCommission", getActivityDesignerCommission())
            .append("platformMemberId", getPlatformMemberId())
            .append("topicPlatformCommission", getTopicPlatformCommission())
            .append("topicMerchantCommission", getTopicMerchantCommission())
            .append("topicPublisherCommission", getTopicPublisherCommission())
            .append("user1TopicNum", getUser1TopicNum())
            .append("user2TopicNum", getUser2TopicNum())
            .append("user3TopicNum", getUser3TopicNum())
            .append("merchantTopicNum", getMerchantTopicNum())
            .append("merchantTemplateNum", getMerchantTemplateNum())
            .append("merchantCouponNum", getMerchantCouponNum())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
