package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * F0572 优惠券模板规则版本(只追加)。
 *
 * <p>BASELINE = 首次改动前补记的原规则;RULE_UPDATE = 白名单字段变更;
 * STOP_ISSUE = 停发(只拦新领取);PLATFORM_INVALIDATE = 平台失效(独立原因 + 受影响持券数)。
 */
public class SmsCouponRuleVersion
{
    public static final String BASELINE = "BASELINE";
    public static final String RULE_UPDATE = "RULE_UPDATE";
    public static final String STOP_ISSUE = "STOP_ISSUE";
    public static final String PLATFORM_INVALIDATE = "PLATFORM_INVALIDATE";

    private Long id;
    private Long couponId;
    private Integer ruleVersion;
    private String changeType;
    private String changedFields;
    private String name;
    private BigDecimal amount;
    private BigDecimal minPoint;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date enableTime;
    private Long publishCount;
    private Integer status;
    private String reason;
    private Integer affectedHolderCount;
    private Long operatorId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;

    public static SmsCouponRuleVersion of(SmsCoupon coupon, int version, String changeType)
    {
        SmsCouponRuleVersion row = new SmsCouponRuleVersion();
        row.setCouponId(coupon.getId());
        row.setRuleVersion(version);
        row.setChangeType(changeType);
        row.setName(coupon.getName());
        row.setAmount(coupon.getAmount());
        row.setMinPoint(coupon.getMinPoint());
        row.setStartTime(coupon.getStartTime());
        row.setEndTime(coupon.getEndTime());
        row.setEnableTime(coupon.getEnableTime());
        row.setPublishCount(coupon.getPublishCount());
        row.setStatus(coupon.getStatus());
        return row;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String changeType) { this.changeType = changeType; }
    public String getChangedFields() { return changedFields; }
    public void setChangedFields(String changedFields) { this.changedFields = changedFields; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getMinPoint() { return minPoint; }
    public void setMinPoint(BigDecimal minPoint) { this.minPoint = minPoint; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
    public Date getEnableTime() { return enableTime; }
    public void setEnableTime(Date enableTime) { this.enableTime = enableTime; }
    public Long getPublishCount() { return publishCount; }
    public void setPublishCount(Long publishCount) { this.publishCount = publishCount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getAffectedHolderCount() { return affectedHolderCount; }
    public void setAffectedHolderCount(Integer affectedHolderCount) { this.affectedHolderCount = affectedHolderCount; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
