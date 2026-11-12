package com.chengyinhub.business.domain.marketing;

import java.util.Date;

/**
 * 收入分配投递任务(表 marketing_revenue_posting_task) —— **可靠队列**，不是账本。
 *
 * <p>为什么需要它：支付回调/退款审批在<b>它们自己的事务里</b>调用本域。
 * 那时原支付/退款事实还没提交，直接开新事务去读同一张单会读到不可见数据，
 * 甚至和调用方持有的行锁互相等待。
 *
 * <p>正确做法（本表的存在理由）：
 * <ol>
 *   <li>调用方在**原事务**里 {@code enqueue}，任务行与支付/退款事实同事务提交；
 *       调用方回滚 → 任务也回滚，不会出现孤儿任务。</li>
 *   <li>原事实提交之后，consumer 在**独立事务**里取任务、执行应计/冲销。</li>
 *   <li>失败更新本行（status/attempts/reason_code），可重试、可审计、可补录。
 *       不是"catch 了只写日志"，也不会丢。</li>
 * </ol>
 *
 * <p>唯一键 {@code (task_kind, ref_id)}：同一原单/同一退款事件重复入队只有一行。
 * 本表不含金额、余额、钱包、打款状态列。
 */
public class MarketingRevenuePostingTask {

    public static final int KIND_ACCRUE = 1;
    public static final int KIND_REVERSE = 2;
    /**
     * 渠道退款冲销。refId = 渠道退款不可变事实主键（≠ refund_application.id，两套命名空间）。
     *
     * <p>独立一种 kind 是为了让「同一笔渠道退款」与「同一笔微信退款」不会因为 id 恰好相同
     * 而互相顶掉唯一键 (task_kind, ref_id)。
     */
    public static final int KIND_CHANNEL_REVERSE = 3;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DONE = 1;
    public static final int STATUS_FAILED = 2;
    /** 已被某消费者按租约领取；租约过期会被回收回 PENDING。 */
    public static final int STATUS_CLAIMED = 3;

    private Long id;
    private Integer taskKind;
    /** ACCRUE → registrationId；REVERSE → refundId；CHANNEL_REVERSE → 渠道退款事实 id。 */
    private Long refId;
    private Long orderId;
    private Integer status;
    private Integer attempts;
    private String reasonCode;
    private String detail;
    private Date nextAttemptAt;
    /** 领取者标识；租约过期后由回收流程清空。 */
    private String leaseOwner;
    private Date leaseExpiresAt;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getTaskKind() { return taskKind; }
    public void setTaskKind(Integer taskKind) { this.taskKind = taskKind; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public Date getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Date nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }

    public Date getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Date leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
