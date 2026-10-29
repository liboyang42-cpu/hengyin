package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.UmsMemberSettlementDebt;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

public interface UmsMemberSettlementDebtMapper {

    /** 同一来源 + 收款方只记一次(uk_debt_source)。 */
    int insertIgnore(UmsMemberSettlementDebt debt);

    UmsMemberSettlementDebt selectBySourceForUpdate(@Param("memberId") Long memberId,
            @Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    List<UmsMemberSettlementDebt> selectOpenByMemberForUpdate(@Param("memberId") Long memberId);

    /** 抵扣 amount;抵满即置已清。超抵(repaid > amount)的更新命中 0 行。 */
    int addRepaid(@Param("id") Long id, @Param("amount") BigDecimal amount);

    /** 到期未清的欠款条数。 */
    int countOverdueByMember(@Param("memberId") Long memberId);
}
