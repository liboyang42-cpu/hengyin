package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IMerchantNodeDiscoveryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MerchantNodeDiscoveryServiceImpl implements IMerchantNodeDiscoveryService {

    @Autowired
    private RoamMapper roamMapper;

    @Override
    public CityNodeVO findActiveNode(Long merchantId) {
        if (merchantId == null || merchantId <= 0) return null;
        return roamMapper.selectActiveMerchantNode(merchantId);
    }

    @Override
    public int syncTags(Long merchantId, String tags) {
        if (merchantId == null || merchantId <= 0) return 0;
        return roamMapper.updateMerchantPoiTags(merchantId, tags);
    }
}
