package com.chengyinhub.business.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.chengyinhub.common.core.domain.BaseEntity;

/**
 * 微信交易账单(T+1下载)本地留痕快照 wx_trade_bill_snapshot(P1-6 N块)。
 *
 * <p>对应微信官方「交易账单」(download_bill)/「资金账单」(download_fund_flow_bill)接口,
 * T+1 早10点后可下载 T 日完整账单。本轮只建表结构,真实下载/解析逻辑见 {@link com.chengyinhub.business.service.IWxTradeBillService}
 * 的骨架方法注释——不在本轮实现,属 P1-7 范围。
 *
 * <p>⚠️ {@link #amount} 是绝对值,方向由 {@link #bizType} 承载,与 {@code MerchantPayable} 同一范式。
 */
public class WxTradeBillSnapshot extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 支付成功 */
    public static final int BIZ_TYPE_PAYMENT = 1;
    /** 退款成功 */
    public static final int BIZ_TYPE_REFUND = 2;

    private Long id;

    /** 账单归属日期(T日),非落库时间 */
    private Date billDate;

    /** 微信侧单号:支付行=transaction_id,退款行=refund_id */
    private String wxTradeNo;

    /** 商户侧业务单号,对应 cms_registration.registrationNo */
    private String outTradeNo;

    /** 商户侧退款单号,仅退款行有值 */
    private String outRefundNo;

    /** 1=支付成功 2=退款成功 */
    private Integer bizType;

    /** 金额(元),绝对值,方向由 bizType 承载 */
    private BigDecimal amount;

    /** 微信侧交易时间(账单文件"交易时间"列) */
    private Date tradeTime;

    /** 本行从账单文件解析落库的时间 */
    private Date rawIngestedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Date getBillDate() {
        return billDate;
    }

    public void setBillDate(Date billDate) {
        this.billDate = billDate;
    }

    public String getWxTradeNo() {
        return wxTradeNo;
    }

    public void setWxTradeNo(String wxTradeNo) {
        this.wxTradeNo = wxTradeNo;
    }

    public String getOutTradeNo() {
        return outTradeNo;
    }

    public void setOutTradeNo(String outTradeNo) {
        this.outTradeNo = outTradeNo;
    }

    public String getOutRefundNo() {
        return outRefundNo;
    }

    public void setOutRefundNo(String outRefundNo) {
        this.outRefundNo = outRefundNo;
    }

    public Integer getBizType() {
        return bizType;
    }

    public void setBizType(Integer bizType) {
        this.bizType = bizType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Date getTradeTime() {
        return tradeTime;
    }

    public void setTradeTime(Date tradeTime) {
        this.tradeTime = tradeTime;
    }

    public Date getRawIngestedAt() {
        return rawIngestedAt;
    }

    public void setRawIngestedAt(Date rawIngestedAt) {
        this.rawIngestedAt = rawIngestedAt;
    }
}
