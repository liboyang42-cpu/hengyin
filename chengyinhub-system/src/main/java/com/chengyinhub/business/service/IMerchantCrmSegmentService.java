package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantCrmSegmentRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmSegmentVO;
import java.util.List;

public interface IMerchantCrmSegmentService {
    MerchantCrmSegmentVO save(Long principalMemberId, MerchantCrmSegmentRequest request);
    List<MerchantCrmSegmentVO> list(Long principalMemberId);
}
