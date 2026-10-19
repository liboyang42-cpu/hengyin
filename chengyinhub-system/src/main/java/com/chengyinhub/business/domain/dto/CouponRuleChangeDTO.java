package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * F0572 后台优惠券规则变更请求。
 *
 * <p>只认白名单字段:名称、说明、面额、门槛、开始/结束时间、可领取日期、发放总量。
 * 其余字段(券类型、范围、归属、状态、领取/核销计数……)一律进 {@link #getRejectedFields()},
 * 由服务端整单拒绝,而不是静默丢弃 —— 客户端得知道这次提交没有按它以为的那样生效。
 * {@code expectedVersion} 是表单读取时的规则版本,用于乐观锁冲突检测。
 */
public class CouponRuleChangeDTO
{
    private Long id;
    private Integer expectedVersion;
    private String name;
    private String description;
    private BigDecimal amount;
    private BigDecimal minPoint;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date enableTime;
    private Long publishCount;
    private String reason;

    @JsonIgnore
    private final Set<String> rejectedFields = new LinkedHashSet<>();

    @JsonAnySetter
    public void rejectField(String field, Object ignored)
    {
        rejectedFields.add(field);
    }

    @JsonIgnore
    public Set<String> getRejectedFields() { return rejectedFields; }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Integer expectedVersion) { this.expectedVersion = expectedVersion; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
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
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
