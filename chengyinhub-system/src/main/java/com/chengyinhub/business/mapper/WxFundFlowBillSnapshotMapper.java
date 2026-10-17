package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.WxFundFlowBillSnapshot;

public interface WxFundFlowBillSnapshotMapper {
    int insertSnapshot(WxFundFlowBillSnapshot row);
    int countMatchingSnapshot(WxFundFlowBillSnapshot row);
}
