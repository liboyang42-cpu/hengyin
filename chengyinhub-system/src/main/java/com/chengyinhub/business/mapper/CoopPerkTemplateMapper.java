package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopPerkTemplate;

/**
 * 商家常备权益模板 Mapper 接口(F1-3)
 */
public interface CoopPerkTemplateMapper {

    public int insertCoopPerkTemplate(CoopPerkTemplate t);

    public List<CoopPerkTemplate> selectByMerchant(@Param("merchantId") Long merchantId);

    public CoopPerkTemplate selectById(@Param("id") Long id);

    /** 软删(仅本人):del_flag=2 */
    public int softDelete(@Param("id") Long id, @Param("merchantId") Long merchantId);
}
