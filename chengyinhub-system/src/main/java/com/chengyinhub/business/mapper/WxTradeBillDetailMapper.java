package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.WxTradeBillDetail;

public interface WxTradeBillDetailMapper {
    int insertDetail(WxTradeBillDetail row);
    int countMatchingDetail(WxTradeBillDetail row);
}
