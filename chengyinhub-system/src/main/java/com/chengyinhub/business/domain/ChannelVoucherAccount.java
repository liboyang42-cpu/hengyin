package com.chengyinhub.business.domain;

import java.util.Date;

/**
 * OTA/渠道发码主体 marketing_channel_voucher_account。
 *
 * <p>本表回答「哪一家渠道、依据哪份合同、在什么窗口内可以发码」。
 * 只有 {@link #STATUS_ACTIVE} + 非空 {@code contractRef} + 生效窗口内才允许发行；
 * 其余状态一律拒绝，不靠前端或运营口头说明。
 *
 * <p><b>不含资金字段</b>：渠道结算模式只表示合同口径（不结算/预付/月结），
 * 不写平台应付、可提现余额或结算明细。真实通道资金未接通前，本域不产生任何结算事实。
 */
public class ChannelVoucherAccount {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_SUSPENDED = 2;
    public static final int STATUS_REVOKED = 3;

    public static final int SETTLEMENT_NONE = 0;
    public static final int SETTLEMENT_PREPAID = 1;
    public static final int SETTLEMENT_INVOICE = 2;

    private Long id;
    private String channelKey;
    private String displayName;
    private String contractRef;
    private Integer settlementMode;
    private Integer status;
    private Date effectiveFrom;
    private Date effectiveTo;
    private Long createBy;
    private Date createTime;
    private Date updateTime;

    /** 发行闸：状态、合同、窗口三者同时成立。 */
    public boolean issuableAt(Date now) {
        if (!Integer.valueOf(STATUS_ACTIVE).equals(status)) return false;
        if (contractRef == null || contractRef.trim().isEmpty()) return false;
        if (effectiveFrom != null && effectiveFrom.after(now)) return false;
        return effectiveTo == null || effectiveTo.after(now);
    }

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

    public Long getCreateBy() { return createBy; }
    public void setCreateBy(Long createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
