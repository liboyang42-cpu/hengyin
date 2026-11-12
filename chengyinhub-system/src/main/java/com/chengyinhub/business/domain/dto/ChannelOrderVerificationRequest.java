package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;

/**
 * 拉单验证请求。{@code amount/currency} 只作交叉核对提示，
 * 最终事实一律取验证 provider 返回值，不信任调用方填写。
 */
public class ChannelOrderVerificationRequest {

    private Long accountId;
    private String providerKey;
    private String channelOrderNo;
    private String externalReference;
    private BigDecimal amount;
    private String currency;
    private String skuJson;
    private String requestId;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getSkuJson() { return skuJson; }
    public void setSkuJson(String skuJson) { this.skuJson = skuJson; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
