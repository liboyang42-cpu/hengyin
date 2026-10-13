package com.chengyinhub.business.domain;

import java.math.BigDecimal;

/** FINAL 六量快照对应的票源金额归因；一行代表平台自然流量或一个冻结俱乐部。 */
public class TopicEditionFundsAttribution {
    private Long id;
    private Long fundsSnapshotId;
    private Long sourceClubId;
    private Long ticketCount;
    /**
     * 【已扣退款】的票数：净额仍 &gt; 0 的报名张数。与 {@link #ticketCount} 刻意分列两个字段。
     *
     * <p>{@code ticketCount} 是 {@code count(*)} over {@code payment_status = 2} —— <b>毛计数</b>，
     * 全额退款的报名照样算 1 张（它要与六量里的 {@code n_cash} 对得上，那条对账不能动）。
     * 拿它计酬会出现「整场退光了还照付承接费」。承接费只认这一列。
     *
     * <p>{@code null} = 这份 FINAL 快照是本列落库<b>之前</b>冻的，没有这个事实
     * —— 调用方必须判 PENDING，不许当 0 也不许退回读毛计数。
     */
    private Long netTicketCount;
    private BigDecimal earnedAmount;
    private Long unfrozenCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFundsSnapshotId() { return fundsSnapshotId; }
    public void setFundsSnapshotId(Long fundsSnapshotId) { this.fundsSnapshotId = fundsSnapshotId; }
    public Long getSourceClubId() { return sourceClubId; }
    public void setSourceClubId(Long sourceClubId) { this.sourceClubId = sourceClubId; }
    public Long getNetTicketCount() { return netTicketCount; }
    public void setNetTicketCount(Long v) { this.netTicketCount = v; }
    public Long getTicketCount() { return ticketCount; }
    public void setTicketCount(Long ticketCount) { this.ticketCount = ticketCount; }
    public BigDecimal getEarnedAmount() { return earnedAmount; }
    public void setEarnedAmount(BigDecimal earnedAmount) { this.earnedAmount = earnedAmount; }
    public Long getUnfrozenCount() { return unfrozenCount; }
    public void setUnfrozenCount(Long unfrozenCount) { this.unfrozenCount = unfrozenCount; }
}
