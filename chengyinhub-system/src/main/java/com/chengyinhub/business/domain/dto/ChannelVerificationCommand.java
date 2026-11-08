package com.chengyinhub.business.domain.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 交给 provider 的验证命令。provider 必须用自己持有的凭据/签名验证；
 * 本对象里的 declaredAmount 只是调用方提示，不可作为事实。
 */
public class ChannelVerificationCommand {

    public static final int MODE_PULL = 1;
    public static final int MODE_INBOUND = 2;
    public static final int MODE_REFUND = 3;

    private int mode;
    private Long accountId;
    private String channelKey;
    private String providerKey;
    private String channelOrderNo;
    private String externalReference;
    private String eventId;
    private BigDecimal declaredAmount;
    private String declaredCurrency;
    private String skuJson;
    private String rawBody;
    private Map<String, String> headers;

    public int getMode() { return mode; }
    public void setMode(int mode) { this.mode = mode; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }

    public String getChannelOrderNo() { return channelOrderNo; }
    public void setChannelOrderNo(String channelOrderNo) { this.channelOrderNo = channelOrderNo; }

    public String getExternalReference() { return externalReference; }
    public void setExternalReference(String externalReference) { this.externalReference = externalReference; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public BigDecimal getDeclaredAmount() { return declaredAmount; }
    public void setDeclaredAmount(BigDecimal declaredAmount) { this.declaredAmount = declaredAmount; }

    public String getDeclaredCurrency() { return declaredCurrency; }
    public void setDeclaredCurrency(String declaredCurrency) { this.declaredCurrency = declaredCurrency; }

    public String getSkuJson() { return skuJson; }
    public void setSkuJson(String skuJson) { this.skuJson = skuJson; }

    public String getRawBody() { return rawBody; }
    public void setRawBody(String rawBody) { this.rawBody = rawBody; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
}
