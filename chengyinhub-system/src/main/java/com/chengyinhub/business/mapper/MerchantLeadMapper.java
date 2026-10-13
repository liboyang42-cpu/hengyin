package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantLead;
import com.chengyinhub.business.domain.vo.NearbyMerchantVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantLeadMapper {
    /** 半径内商家目录,按 Haversine 直线距离升序;radius 单位米。 */
    List<NearbyMerchantVO> selectNearbyLeads(@Param("lat") double lat, @Param("lng") double lng,
                                             @Param("radius") int radius, @Param("limit") int limit);

    int insertMerchantLead(MerchantLead lead);

    MerchantLead selectMerchantLeadById(Long id);

    List<MerchantLead> selectMerchantLeadList(MerchantLead lead);

    int updateMerchantLead(MerchantLead lead);

    int deleteMerchantLeadByIds(Long[] ids);
}
