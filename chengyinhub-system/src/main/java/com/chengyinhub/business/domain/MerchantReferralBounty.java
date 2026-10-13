package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 拓展人激活赏金(冷启动)。
 *
 * <p>有人帮平台拓来商家,该商家真跑起来后给拓展人一次性赏金。
 * 参数见《后台发主题与商家报名_拆除复制层_施工图_20260717.md》§3.3.1(2026-07-17 拍板):
 * ¥30 一次性 / 商家注册起 30 天内自己扫码核销人头数 ≥ 3 / 单拓展人封顶 20 家 /
 * 退款致核销 &lt; 3 则追回 / 入账后 48h 冻结不可提现。
 *
 * @author chengyinhub
 */
public class MerchantReferralBounty
{
    /** 状态:待激活 */
    public static final int STATUS_PENDING = 0;
    /** 状态:已发放 */
    public static final int STATUS_PAID = 1;
    /** 状态:已追回 */
    public static final int STATUS_CLAWED_BACK = 2;
    /** 状态:已失效(超 30 天窗口未达标) */
    public static final int STATUS_EXPIRED = 3;

    private Long id;
    private Long merchantMemberId;
    private Long inviterMemberId;
    private Date merchantRegisteredAt;
    private Date windowExpiresAt;
    private Integer status;
    private Date activatedAt;
    /** 判定快照:构成激活的 cms_registration.id,逗号分隔。clawback 的唯一依据。 */
    private String verifiedHeadsSnapshot;
    private BigDecimal bountyAmount;
    private Date paidAt;
    private Date frozenUntil;
    private Date clawedBackAt;
    private String clawbackReason;
    private String delFlag;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
    public Long getInviterMemberId() { return inviterMemberId; }
    public void setInviterMemberId(Long inviterMemberId) { this.inviterMemberId = inviterMemberId; }
    public Date getMerchantRegisteredAt() { return merchantRegisteredAt; }
    public void setMerchantRegisteredAt(Date merchantRegisteredAt) { this.merchantRegisteredAt = merchantRegisteredAt; }
    public Date getWindowExpiresAt() { return windowExpiresAt; }
    public void setWindowExpiresAt(Date windowExpiresAt) { this.windowExpiresAt = windowExpiresAt; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Date getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Date activatedAt) { this.activatedAt = activatedAt; }
    public String getVerifiedHeadsSnapshot() { return verifiedHeadsSnapshot; }
    public void setVerifiedHeadsSnapshot(String verifiedHeadsSnapshot) { this.verifiedHeadsSnapshot = verifiedHeadsSnapshot; }
    public BigDecimal getBountyAmount() { return bountyAmount; }
    public void setBountyAmount(BigDecimal bountyAmount) { this.bountyAmount = bountyAmount; }
    public Date getPaidAt() { return paidAt; }
    public void setPaidAt(Date paidAt) { this.paidAt = paidAt; }
    public Date getFrozenUntil() { return frozenUntil; }
    public void setFrozenUntil(Date frozenUntil) { this.frozenUntil = frozenUntil; }
    public Date getClawedBackAt() { return clawedBackAt; }
    public void setClawedBackAt(Date clawedBackAt) { this.clawedBackAt = clawedBackAt; }
    public String getClawbackReason() { return clawbackReason; }
    public void setClawbackReason(String clawbackReason) { this.clawbackReason = clawbackReason; }
    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
