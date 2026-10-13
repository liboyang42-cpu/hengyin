package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.RoamRevisitFact;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 独立只读 mapper；禁止混入 RoamMapper 的状态写路径。 */
public interface RoamRevisitReportMapper {
    Date selectShopDataCutoffAt();

    Date selectRoamDataCutoffAt();

    List<RoamRevisitFact> selectShopVisitFacts(@Param("from") Date from, @Param("to") Date to);

    List<RoamRevisitFact> selectRoamSessionFacts(@Param("from") Date from, @Param("to") Date to);
}
