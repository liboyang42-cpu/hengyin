package com.chengyinhub.business.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

/** F0535 订单支付事实渠道查询回执(只追加)。 */
public class OmsOrderPaymentCheck
{
    public static final String VERDICT_UNCONFIRMED = "UNCONFIRMED";
    public static final String VERDICT_CONFIRMED = "CONFIRMED";
    public static final String VERDICT_CONFLICT = "CONFLICT";

    public static final String QUERY_OK = "OK";
    public static final String QUERY_NOT_EXIST = "NOT_EXIST";
    public static final String QUERY_FAILED = "FAILED";

    private Long id;
    private Long orderId;
    private String channel;
    private String outTradeNo;
    private String queryStatus;
    private String channelTradeState;
    private String channelTransactionId;
    private Integer channelTotalFen;
    private Integer savedStatus;
    private String savedTransactionId;
    private Integer savedPayAmountFen;
    private String verdict;
    private String detail;
    private String errorMessage;
    private Long operatorId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date queryTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getOutTradeNo() { return outTradeNo; }
    public void setOutTradeNo(String outTradeNo) { this.outTradeNo = outTradeNo; }
    public String getQueryStatus() { return queryStatus; }
    public void setQueryStatus(String queryStatus) { this.queryStatus = queryStatus; }
    public String getChannelTradeState() { return channelTradeState; }
    public void setChannelTradeState(String channelTradeState) { this.channelTradeState = channelTradeState; }
    public String getChannelTransactionId() { return channelTransactionId; }
    public void setChannelTransactionId(String channelTransactionId) { this.channelTransactionId = channelTransactionId; }
    public Integer getChannelTotalFen() { return channelTotalFen; }
    public void setChannelTotalFen(Integer channelTotalFen) { this.channelTotalFen = channelTotalFen; }
    public Integer getSavedStatus() { return savedStatus; }
    public void setSavedStatus(Integer savedStatus) { this.savedStatus = savedStatus; }
    public String getSavedTransactionId() { return savedTransactionId; }
    public void setSavedTransactionId(String savedTransactionId) { this.savedTransactionId = savedTransactionId; }
    public Integer getSavedPayAmountFen() { return savedPayAmountFen; }
    public void setSavedPayAmountFen(Integer savedPayAmountFen) { this.savedPayAmountFen = savedPayAmountFen; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Date getQueryTime() { return queryTime; }
    public void setQueryTime(Date queryTime) { this.queryTime = queryTime; }
}
