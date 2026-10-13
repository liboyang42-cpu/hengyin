package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CoopCreditLog;

/**
 * 协作信用记录 Mapper 接口(D-5)
 */
public interface CoopCreditLogMapper {

    public int insertCoopCreditLog(CoopCreditLog coopCreditLog);

    /** 某会员自 since 以来的信用事件(近90天履约率统计用) */
    public List<CoopCreditLog> selectRecentByMember(@Param("memberId") Long memberId, @Param("since") Date since);
}
