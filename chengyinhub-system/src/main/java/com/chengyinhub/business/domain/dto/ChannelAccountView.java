package com.chengyinhub.business.domain.dto;

import java.util.Date;

/** 渠道主体只读投影。 */
public class ChannelAccountView {

    private Long id;
    private String channelKey;
    private String displayName;
    private String contractRef;
    private Integer settlementMode;
    private Integer status;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Date createTime;
    private Date updateTime;
    private Boolean replayed;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getContractRef() { return contractRef; }
    public void setContractRef(String contractRef) { this.contractRef = contractRef; }

    public Integer getSettlementMode() { return settlementMode; }
    public void setSettlementMode(Integer settlementMode) { this.settlementMode = settlementMode; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Date getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Date effectiveFrom) { this.effectiveFrom = effectiveFrom; }

    public Date getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(Date effectiveTo) { this.effectiveTo = effectiveTo; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    public Boolean getReplayed() { return replayed; }
    public void setReplayed(Boolean replayed) { this.replayed = replayed; }
}
