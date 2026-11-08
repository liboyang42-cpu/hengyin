package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 渠道主体登记/启用请求。合同编号必填，防止无合同先发码。 */
public class ChannelAccountRequest {

    private String channelKey;
    private String displayName;
    private String contractRef;
    private Integer settlementMode;
    private Date effectiveFrom;
    private Date effectiveTo;
    private String requestId;

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }

    public Integer getSettlementMode() { return settlementMode; }
    public void setSettlementMode(Integer settlementMode) { this.settlementMode = settlementMode; }

    public Date getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Date effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public Date getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Date effectiveTo) { this.effectiveTo = effectiveTo; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
