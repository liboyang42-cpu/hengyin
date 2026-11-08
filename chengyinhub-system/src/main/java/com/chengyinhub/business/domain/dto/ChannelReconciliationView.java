package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 渠道对账汇总。{@code verifiedOrders - voucherCount} 是发码缺口，
 * {@code voucherCount - redeemedCount} 是未兑换存量；差异行可定位到单。
 */
public class ChannelReconciliationView {

    private Long accountId;
    private String channelKey;
    private Date from;
    private Date to;
    private Integer verifiedOrderCount;
    private BigDecimal verifiedAmount;
    private Integer pendingVerificationCount;
    private Integer voucherCount;
    private Integer redeemedCount;
    private Integer activeVoucherCount;
    private Integer revokedVoucherCount;
    private Integer unredeemedCount;
    private List<ChannelReconciliationIssue> issues;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public Date getFrom() { return from; }
    public void setFrom(Date from) { this.from = from; }

    public Date getTo() { return to; }
    public void setTo(Date to) { this.to = to; }

    public Integer getVerifiedOrderCount() { return verifiedOrderCount; }
    public void setVerifiedOrderCount(Integer verifiedOrderCount) { this.verifiedOrderCount = verifiedOrderCount; }

    public BigDecimal getVerifiedAmount() { return verifiedAmount; }
    public void setVerifiedAmount(BigDecimal verifiedAmount) { this.verifiedAmount = verifiedAmount; }

    public Integer getPendingVerificationCount() { return pendingVerificationCount; }
    public void setPendingVerificationCount(Integer pendingVerificationCount) { this.pendingVerificationCount = pendingVerificationCount; }

    public Integer getVoucherCount() { return voucherCount; }
    public void setVoucherCount(Integer voucherCount) { this.voucherCount = voucherCount; }

    public Integer getRedeemedCount() { return redeemedCount; }
    public void setRedeemedCount(Integer redeemedCount) { this.redeemedCount = redeemedCount; }

    public Integer getActiveVoucherCount() { return activeVoucherCount; }
    public void setActiveVoucherCount(Integer activeVoucherCount) { this.activeVoucherCount = activeVoucherCount; }

    public Integer getRevokedVoucherCount() { return revokedVoucherCount; }
    public void setRevokedVoucherCount(Integer revokedVoucherCount) { this.revokedVoucherCount = revokedVoucherCount; }

    public Integer getUnredeemedCount() { return unredeemedCount; }
    public void setUnredeemedCount(Integer unredeemedCount) { this.unredeemedCount = unredeemedCount; }

    public List<ChannelReconciliationIssue> getIssues() { return issues; }
    public void setIssues(List<ChannelReconciliationIssue> issues) { this.issues = issues; }
}
