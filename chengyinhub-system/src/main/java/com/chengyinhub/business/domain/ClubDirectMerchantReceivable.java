package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/** 每次核销的俱乐部直付事实。金额和付款方不可变；撤销保留已付款与退回凭证。 */
public class ClubDirectMerchantReceivable {
    private Long id, claimId, redemptionId, offerId, topicId, chapterId, payerClubId, merchantMemberId;
    private BigDecimal perHeadFeeSnapshot;
    private String status, settledBy, payVoucherNo, refundedBy, refundVoucherNo;
    private Date settledAt, reversedAt, refundedAt, createTime;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long v) { claimId = v; }
    public Long getRedemptionId() { return redemptionId; }
    public void setRedemptionId(Long v) { redemptionId = v; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long v) { offerId = v; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long v) { topicId = v; }
    public Long getChapterId() { return chapterId; }
    public void setChapterId(Long v) { chapterId = v; }
    public Long getPayerClubId() { return payerClubId; }
    public void setPayerClubId(Long v) { payerClubId = v; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long v) { merchantMemberId = v; }
    public BigDecimal getPerHeadFeeSnapshot() { return perHeadFeeSnapshot; }
    public void setPerHeadFeeSnapshot(BigDecimal v) { perHeadFeeSnapshot = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getSettledBy() { return settledBy; }
    public void setSettledBy(String v) { settledBy = v; }
    public String getPayVoucherNo() { return payVoucherNo; }
    public void setPayVoucherNo(String v) { payVoucherNo = v; }
    public String getRefundedBy() { return refundedBy; }
    public void setRefundedBy(String v) { refundedBy = v; }
    public String getRefundVoucherNo() { return refundVoucherNo; }
    public void setRefundVoucherNo(String v) { refundVoucherNo = v; }
    public Date getSettledAt() { return settledAt; }
    public void setSettledAt(Date v) { settledAt = v; }
    public Date getReversedAt() { return reversedAt; }
    public void setReversedAt(Date v) { reversedAt = v; }
    public Date getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Date v) { refundedAt = v; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date v) { createTime = v; }
}
