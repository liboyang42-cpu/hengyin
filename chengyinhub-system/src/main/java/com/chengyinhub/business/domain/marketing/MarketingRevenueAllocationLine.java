package com.chengyinhub.business.domain.marketing;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 分配分录(表 marketing_revenue_allocation_line)。
 *
 * <p>应计与退款冲销共用本表：{@code entryType=1} 为正向应计，{@code entryType=2} 为反向分录，
 * 通过 {@code reversalOfLineId} 指向被冲的原分录。退款只按原分录反向，不按当前规则重算。
 *
 * <p><b>金额以整数分为真源</b>（{@link #amountFen} / {@link #basisFen}）：分笔退款若逐笔按比例取整，
 * 尾差会累积丢失，所以冲销额由"累计退款目标 − 已冲销"得出，整数分相减无残差。
 * {@link #getAmount()} 只是给展示层的派生视图，不参与计算。
 *
 * <p>反向分录携带 {@link #eventRef} 与该事件的金额/币种/原单，用于两件事：
 * 同一退款事件重放时先命中幂等事实（不再谈累计封顶），以及同一 refundId 却换了
 * 金额/币种/原单时明确拒绝。
 */
public class MarketingRevenueAllocationLine {

    public static final int ENTRY_ACCRUE = 1;
    public static final int ENTRY_REVERSE = 2;

    public static final String ROLE_AUTHOR = "author";
    public static final String ROLE_REFERRER = "referrer";
    public static final String ROLE_PLATFORM = "platform";

    private Long id;
    private Long allocationId;
    private Integer entryType;
    private String lineRole;
    private Long recipientMemberId;
    private Integer fundingSide;
    /** 计算基数(净额)，整数分。 */
    private Long basisFen;
    private BigDecimal rate;
    /** 正向为正、冲销为负，整数分。真源。 */
    private Long amountFen;
    private String currency;
    private String entryKey;
    /** 退款/chargeback 事件标识；应计行为 null。 */
    private String eventRef;
    private Long eventAmountFen;
    private String eventCurrency;
    private Integer eventOrderType;
    private Long eventOrderId;
    private Long reversalOfLineId;
    private String sourceFact;
    private Date createTime;

    /** 展示派生：不做计算，计算一律走 {@link #getAmountFen()}。 */
    public BigDecimal getAmount() {
        return amountFen == null ? null : BigDecimal.valueOf(amountFen, 2);
    }

    public BigDecimal getBasisAmount() {
        return basisFen == null ? null : BigDecimal.valueOf(basisFen, 2);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAllocationId() { return allocationId; }
    public void setAllocationId(Long allocationId) { this.allocationId = allocationId; }

    public Integer getEntryType() { return entryType; }
    public void setEntryType(Integer entryType) { this.entryType = entryType; }

    public String getLineRole() { return lineRole; }
    public void setLineRole(String lineRole) { this.lineRole = lineRole; }

    public Long getRecipientMemberId() { return recipientMemberId; }
    public void setRecipientMemberId(Long recipientMemberId) { this.recipientMemberId = recipientMemberId; }

    public Integer getFundingSide() { return fundingSide; }
    public void setFundingSide(Integer fundingSide) { this.fundingSide = fundingSide; }

    public Long getBasisFen() { return basisFen; }
    public void setBasisFen(Long basisFen) { this.basisFen = basisFen; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public Long getAmountFen() { return amountFen; }
    public void setAmountFen(Long amountFen) { this.amountFen = amountFen; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getEntryKey() { return entryKey; }
    public void setEntryKey(String entryKey) { this.entryKey = entryKey; }

    public String getEventRef() { return eventRef; }
    public void setEventRef(String eventRef) { this.eventRef = eventRef; }

    public Long getEventAmountFen() { return eventAmountFen; }
    public void setEventAmountFen(Long eventAmountFen) { this.eventAmountFen = eventAmountFen; }

    public String getEventCurrency() { return eventCurrency; }
    public void setEventCurrency(String eventCurrency) { this.eventCurrency = eventCurrency; }

    public Integer getEventOrderType() { return eventOrderType; }
    public void setEventOrderType(Integer eventOrderType) { this.eventOrderType = eventOrderType; }

    public Long getEventOrderId() { return eventOrderId; }
    public void setEventOrderId(Long eventOrderId) { this.eventOrderId = eventOrderId; }

    public Long getReversalOfLineId() { return reversalOfLineId; }
    public void setReversalOfLineId(Long reversalOfLineId) { this.reversalOfLineId = reversalOfLineId; }

    public String getSourceFact() { return sourceFact; }
    public void setSourceFact(String sourceFact) { this.sourceFact = sourceFact; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
