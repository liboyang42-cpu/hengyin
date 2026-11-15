package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GameRandomAudit;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 统一可审计随机流水 Mapper。
 */
public interface GameRandomAuditMapper {

    int insertAudit(GameRandomAudit audit);

    GameRandomAudit selectByDomainAndReceipt(@Param("domain") String domain,
                                             @Param("receiptId") String receiptId);

    List<GameRandomAudit> selectAuditList(GameRandomAudit filter);
}
