package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 主持小费意向单(host_tip_intent)。
 *
 * <p><b>顾客自愿</b>:小费是顾客主动发起的额外支付,不是订单应付项,不改变原订单金额、
 * 票券、结算或退款规则。状态机:
 * CREATED → AWAITING_PAYMENT → PAID → REFUNDED,或 CREATED/AWAITING_PAYMENT → CANCELLED;
 * 通道未接入时直接落 {@link #STATUS_CHANNEL_UNSUPPORTED},<b>不</b>落任何"已付"。
 *
 * <p><b>资金归属</b>:受益人是 {@code hostMemberId}(100%),{@code channelFeeBearer} 是下单时
 * 冻结的配置快照。本表不加余额、不写分账、不把代收 host 资金记成平台支出。
 *
 * @author chengyinhub
 */
public class HostTipIntent
{
    /** 已创建(尚未进入支付)。 */
    public static final int STATUS_CREATED = 0;
    /** 等待顾客支付。 */
    public static final int STATUS_AWAITING_PAYMENT = 1;
    /** 通道确认已支付(只能由真实回调/回执调用,本域默认不写)。 */
    public static final int STATUS_PAID = 2;
    /** 顾客或超时取消。 */
    public static final int STATUS_CANCELLED = 3;
    /** 已按原支付通道退款。 */
    public static final int STATUS_REFUNDED = 4;
    /** 未接入受支持收款通道:明确未启用,不伪造收款。 */
    public static final int STATUS_CHANNEL_UNSUPPORTED = 5;

    /** 无关联订单(纯自愿小费)。 */
    public static final int REF_NONE = 0;
    /** 关联现有商城订单(只读校验归属,不改该订单)。 */
    public static final int REF_OMS_ORDER = 1;

    private Long id;
    private Long configId;
    /** 付款顾客。 */
    private Long payerMemberId;
    /** 受益主持(100%)。 */
    private Long hostMemberId;
    private Long clubId;
    private Integer orderRefType;
    private Long orderRefId;
    private BigDecimal amount;
    private String currency;
    /** 下单时冻结的配置快照。 */
    private Integer hostSharePercent;
    private Integer channelFeeBearer;
    private String paymentChannel;
    private Integer status;
    /** 通道交易号(真实回调才有;未启用时为空)。 */
    private String externalTradeNo;
    /** 既有结算/分账引用(集成契约;未启用时为空)。 */
    private String settlementRef;
    /** 幂等键:payer|requestId。 */
    private String idempotencyKey;
    private String note;
    private Date createTime;
    private Date updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConfigId() { return configId; }
    public void setConfigId(Long configId) { this.configId = configId; }
    public Long getPayerMemberId() { return payerMemberId; }
    public void setPayerMemberId(Long payerMemberId) { this.payerMemberId = payerMemberId; }
    public Long getHostMemberId() { return hostMemberId; }
    public void setHostMemberId(Long hostMemberId) { this.hostMemberId = hostMemberId; }
    public Long getClubId() { return clubId; }
    public void setClubId(Long clubId) { this.clubId = clubId; }
    public Integer getOrderRefType() { return orderRefType; }
    public void setOrderRefType(Integer orderRefType) { this.orderRefType = orderRefType; }
    public Long getOrderRefId() { return orderRefId; }
    public void setOrderRefId(Long orderRefId) { this.orderRefId = orderRefId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Integer getHostSharePercent() { return hostSharePercent; }
    public void setHostSharePercent(Integer hostSharePercent) { this.hostSharePercent = hostSharePercent; }
    public Integer getChannelFeeBearer() { return channelFeeBearer; }
    public void setChannelFeeBearer(Integer channelFeeBearer) { this.channelFeeBearer = channelFeeBearer; }
    public String getPaymentChannel() { return paymentChannel; }
    public void setPaymentChannel(String paymentChannel) { this.paymentChannel = paymentChannel; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getExternalTradeNo() { return externalTradeNo; }
    public void setExternalTradeNo(String externalTradeNo) { this.externalTradeNo = externalTradeNo; }
    public String getSettlementRef() { return settlementRef; }
    public void setSettlementRef(String settlementRef) { this.settlementRef = settlementRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
