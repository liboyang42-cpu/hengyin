package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * ③ 计酬档应付**月结批次** merchant_payable_batch(2026-07-17 拍板:自然月,次月结上月)。
 *
 * <p><b>为什么状态机在批次上,不在台账行上</b>:{@link MerchantPayable} 是**不可变事件流水**
 * (它记的是「这笔核销发生了,当时费率是 X」—— 那是历史,永不改),而待结 → 对账确认 → 已打款
 * 是**批次粒度的动作**。若把状态放行上,一次批次流转要 UPDATE N 行、还可能各行漂移到不同状态 = 对不上账。
 *
 * <p>⚠️ 更硬的理由:<b>已打款批次的事后冲正</b>。若状态在行上,一笔 PAID 的计提被冲正时无法表达
 * (钱已经打出去了,不能把行改回 PENDING)。批次模型下它自然落进**下个月的批次**变成负向净额 ——
 * 这正是「绝对值 + 方向列」范式存在的理由,也是真实会计的做法。
 *
 * <p><b>批次成员不落 batch_id,而是推导</b>:成员 = {@code merchant_payable} 里
 * (merchantMemberId 相同 且 createTime 落在 periodYm 那个自然月)的所有行。
 * 落 batch_id 就要 UPDATE 台账行 = 又破 append-only。见 {@link MerchantPayable} 类注释。
 *
 * <p>⚠️ {@link #status} 与 {@link #invoiceStatus} 是<b>两条独立轴</b>,别混成一条 ——
 * 混了就表达不了「已打款但未开票」这种真实存在的状态。
 */
public class MerchantPayableBatch extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 待结 —— 批次已生成,还没跟商家对完账 */
    public static final String STATUS_PENDING = "PENDING";
    /** 对账确认 —— 运营与商家核对完这个月的数,金额锁定 */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 已打款 —— 对公打款已出账 */
    public static final String STATUS_PAID = "PAID";

    /** 未开票 */
    public static final String INVOICE_NONE = "NONE";
    /** 已开蓝票 */
    public static final String INVOICE_ISSUED = "ISSUED";
    /** 已红冲 */
    public static final String INVOICE_RED = "RED";

    private Long id;

    /** 收款商家 memberId(供应商) */
    private Long merchantMemberId;

    /** 结算期,自然月 {@code YYYY-MM}(如 2026-07)。次月结上月 */
    private String periodYm;

    /**
     * ★生成时**冻结**的净额 = SUM(CASE entryType WHEN 'ACCRUAL' THEN amount ELSE -amount END)。
     * 它是**实际打出去的那个数**,要留痕,别每次查都重算。
     * ⚠️ <b>可能为负</b>(上月已付、本月大量冲正 = 商家反欠平台)—— 别夹到 0,那会把账做平成假的。
     */
    private BigDecimal amountTotal;

    /** PENDING / CONFIRMED / PAID。与 {@link #invoiceStatus} 是两条独立轴 */
    private String status;

    /** NONE / ISSUED / RED。★冲正路径的分支键:已开票批次的冲正必须走红字发票流程 */
    private String invoiceStatus;

    /** 蓝票号 */
    private String invoiceNo;

    /** 红票号(已开票批次被冲正时必须回填) */
    private String redInvoiceNo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date confirmedAt;

    /** 对账确认人(运营) */
    private String confirmedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date paidAt;

    /** 打款操作人(运营) */
    private String paidBy;

    /** 对公打款凭证号/流水号,留痕对账用 */
    private String payVoucherNo;

    /** P块冻结:1=冻结中(暂停对账确认/打款,不动金额),0/NULL=未冻结 */
    private Integer frozen;
    /** 冻结原因(运营填写) */
    private String frozenReason;
    /** 冻结操作人 */
    private String frozenBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date frozenAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMerchantMemberId() {
        return merchantMemberId;
    }

    public void setMerchantMemberId(Long merchantMemberId) {
        this.merchantMemberId = merchantMemberId;
    }

    public String getPeriodYm() {
        return periodYm;
    }

    public void setPeriodYm(String periodYm) {
        this.periodYm = periodYm;
    }

    public BigDecimal getAmountTotal() {
        return amountTotal;
    }

    public void setAmountTotal(BigDecimal amountTotal) {
        this.amountTotal = amountTotal;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInvoiceStatus() {
        return invoiceStatus;
    }

    public void setInvoiceStatus(String invoiceStatus) {
        this.invoiceStatus = invoiceStatus;
    }

    public String getInvoiceNo() {
        return invoiceNo;
    }

    public void setInvoiceNo(String invoiceNo) {
        this.invoiceNo = invoiceNo;
    }

    public String getRedInvoiceNo() {
        return redInvoiceNo;
    }

    public void setRedInvoiceNo(String redInvoiceNo) {
        this.redInvoiceNo = redInvoiceNo;
    }

    public Date getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Date confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public Date getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Date paidAt) {
        this.paidAt = paidAt;
    }

    public String getPaidBy() {
        return paidBy;
    }

    public void setPaidBy(String paidBy) {
        this.paidBy = paidBy;
    }

    public String getPayVoucherNo() {
        return payVoucherNo;
    }

    public void setPayVoucherNo(String payVoucherNo) {
        this.payVoucherNo = payVoucherNo;
    }

    public Integer getFrozen() {
        return frozen;
    }

    public void setFrozen(Integer frozen) {
        this.frozen = frozen;
    }

    public String getFrozenReason() {
        return frozenReason;
    }

    public void setFrozenReason(String frozenReason) {
        this.frozenReason = frozenReason;
    }

    public String getFrozenBy() {
        return frozenBy;
    }

    public void setFrozenBy(String frozenBy) {
        this.frozenBy = frozenBy;
    }

    public Date getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(Date frozenAt) {
        this.frozenAt = frozenAt;
    }
}
