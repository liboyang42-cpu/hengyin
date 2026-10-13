package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.vo.MerchantCandidateVO;

/**
 * AI 助手 Mapper(只读候选商家查询等)。
 *
 * @author chengyinhub
 */
public interface ApiAiMapper
{
    /**
     * Haversine 查询附近已审核可接待的商家报名节点 Top-N。
     * LEFT JOIN mms_merchant + sms_coupon 补齐商家档案与券信息。
     *
     * @param lng    查询点经度
     * @param lat    查询点纬度
     * @param radius 搜索半径(米)
     * @param limit  返回上限(建议 15)
     */
    List<MerchantCandidateVO> selectNearbyMerchantCandidates(@Param("lng") double lng,
                                                              @Param("lat") double lat,
                                                              @Param("radius") double radius,
                                                              @Param("limit") int limit);
}
