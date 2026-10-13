package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * A3 玩家投诉补偿提现冷静期（append-only）。
 *
 * <p>每一笔正向投诉补偿独立冷静 48h；起点是该笔余额明细<b>实际插入数据库</b>的 create_time，
 * 不是投诉创建/结案时间，也不是 JVM now。cooldown_until 在同一事务里由 SQL 算好并快照下来。
 */
public class PlayerWithdrawalCooldown {
    private Long id;
    private Long balanceDetailId;
    private Long memberId;
    private Long complaintId;
    private BigDecimal amount;
    private String policyKey;
    private Integer cooldownHours;
    private Date createdAt;
    private Date cooldownUntil;

    public Long getId() { return id; } public void setId(Long v) { id = v; }
    public Long getBalanceDetailId() { return balanceDetailId; } public void setBalanceDetailId(Long v) { balanceDetailId = v; }
    public Long getMemberId() { return memberId; } public void setMemberId(Long v) { memberId = v; }
    public Long getComplaintId() { return complaintId; } public void setComplaintId(Long v) { complaintId = v; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal v) { amount = v; }
    public String getPolicyKey() { return policyKey; } public void setPolicyKey(String v) { policyKey = v; }
    public Integer getCooldownHours() { return cooldownHours; } public void setCooldownHours(Integer v) { cooldownHours = v; }
    public Date getCreatedAt() { return createdAt; } public void setCreatedAt(Date v) { createdAt = v; }
    public Date getCooldownUntil() { return cooldownUntil; } public void setCooldownUntil(Date v) { cooldownUntil = v; }
}
