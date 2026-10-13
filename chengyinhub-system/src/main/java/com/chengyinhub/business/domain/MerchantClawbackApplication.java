package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * M块:商家违规倒扣申请(2026-07-25 拍板)。
 *
 * <p><b>解决什么</b>:商家违规经运营核实投诉后,已打款的结算款需要追回。此前
 * {@code CoopComplaintMapper} 建单只挡得住 {@code payoutDueCoopSettlements} 的打款<b>前</b>放款
 * (未结算的钱不放),打款<b>后</b>完全没有倒扣路径 —— 钱已经离开平台就再没人管。
 *
 * <p><b>两条互斥路径</b>(发起时由系统判定,不让人工去猜):
 * <ul>
 *   <li>{@link #SETTLE_PATH_OFFSET_BATCH} —— 默认路径:商家有未来未打款批次
 *       ({@code merchant_payable_batch.status} 为 PENDING/CONFIRMED)时,从其中最近一个
 *       (period_ym 最小)的 {@code amount_total} 里直接扣减,不碰已到账的零钱/余额。
 *       扣减后金额可能变负(留痕说明扣了什么),不做同步防负校验。</li>
 *   <li>{@link #SETTLE_PATH_BALANCE} —— 兜底路径:商家没有任何未来可冲抵的批次时
 *       (已停止合作/所有批次都已 PAID),直接扣 {@code ums_member.balance}(允许扣成负数,
 *       复用 {@code MerchantReferralBountyService.clawbackBalance} 的 CAS 范式)。</li>
 * </ul>
 *
 * <p><b>必须人工介入</b>:倒扣金额由运营人工核实投诉后手动填写,不是系统自动计算;
 * 走「发起 → 复核确认」两步,复核人须≠发起人(与 {@code MerchantPayableServiceImpl.markBatchPaid}
 * 的 maker-checker 同型)。
 *
 * @author chengyinhub
 */
public class MerchantClawbackApplication
{
    /** 结算路径:冲抵商家下一个未打款批次的 amount_total */
    public static final String SETTLE_PATH_OFFSET_BATCH = "OFFSET_BATCH";
    /** 结算路径:直接扣 ums_member.balance(兜底,仅当无未来可冲抵批次时) */
    public static final String SETTLE_PATH_BALANCE = "BALANCE";

    /** 状态:待复核(已发起,尚未执行) */
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    /** 状态:已执行(复核通过并已完成扣款/冲抵) */
    public static final String STATUS_EXECUTED = "EXECUTED";
    /** 状态:已驳回 */
    public static final String STATUS_REJECTED = "REJECTED";

    private Long id;

    /** 被追回的商家 ums_member.id */
    private Long merchantMemberId;

    /** 违规/投诉依据说明(运营人工填写,如:客诉工单号 #123,查实虚假核销) */
    private String reason;

    /** 倒扣金额(正数,运营人工核实后填写,不由系统计算) */
    private BigDecimal amount;

    /** OFFSET_BATCH / BALANCE。发起时由系统按商家是否有未来未打款批次自动判定 */
    private String settlePath;

    /** settlePath=OFFSET_BATCH 时的目标批次 merchant_payable_batch.id;BALANCE 路径为 null */
    private Long targetBatchId;

    /** PENDING_REVIEW / EXECUTED / REJECTED */
    private String status;

    /** 发起人(sys_user.user_name) */
    private String initiatedBy;

    /** 复核人(sys_user.user_name)。执行前校验 ≠ initiatedBy */
    private String confirmedBy;

    private Date confirmedAt;

    /** 实际执行(扣款/冲抵)完成时刻 */
    private Date executedAt;

    /** 驳回原因(复核人填写) */
    private String rejectReason;

    private Date createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantMemberId() { return merchantMemberId; }
    public void setMerchantMemberId(Long merchantMemberId) { this.merchantMemberId = merchantMemberId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getSettlePath() { return settlePath; }
    public void setSettlePath(String settlePath) { this.settlePath = settlePath; }
    public Long getTargetBatchId() { return targetBatchId; }
    public void setTargetBatchId(Long targetBatchId) { this.targetBatchId = targetBatchId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(String initiatedBy) { this.initiatedBy = initiatedBy; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
    public Date getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Date confirmedAt) { this.confirmedAt = confirmedAt; }
    public Date getExecutedAt() { return executedAt; }
    public void setExecutedAt(Date executedAt) { this.executedAt = executedAt; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
