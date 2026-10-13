package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.List;

/** 批次详情显式分开收入与调整，以便在客户端校验净额守恒。 */
public class PublicTransferBatchDetail {
    private PublicTransferBatch batch;
    private List<MerchantSettlementEntry> earningEntries = new ArrayList<MerchantSettlementEntry>();
    private List<MerchantSettlementEntry> adjustments = new ArrayList<MerchantSettlementEntry>();

    public PublicTransferBatch getBatch() { return batch; }
    public void setBatch(PublicTransferBatch value) { batch = value; }
    public List<MerchantSettlementEntry> getEarningEntries() { return earningEntries; }
    public void setEarningEntries(List<MerchantSettlementEntry> value) { earningEntries = value; }
    public List<MerchantSettlementEntry> getAdjustments() { return adjustments; }
    public void setAdjustments(List<MerchantSettlementEntry> value) { adjustments = value; }
}
