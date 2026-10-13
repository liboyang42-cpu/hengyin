package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmSegment;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface MerchantCrmSegmentMapper {
    MerchantCrmSegment selectOwnedSegmentById(@Param("merchantId") Long merchantId,
                                               @Param("segmentId") Long segmentId);
    MerchantCrmSegment selectByRequestId(@Param("merchantId") Long merchantId,
                                         @Param("requestId") String requestId);
    int insertSegment(MerchantCrmSegment segment);
    List<MerchantCrmSegment> selectActiveSegments(@Param("merchantId") Long merchantId,
                                                  @Param("limit") Integer limit);
}
