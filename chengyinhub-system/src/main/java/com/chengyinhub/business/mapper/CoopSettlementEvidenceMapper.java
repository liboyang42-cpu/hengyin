package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 选集事实窄 seam；查询必须与 claim/detail 写入同事务并锁 registration。 */
public interface CoopSettlementEvidenceMapper {
    List<CoopSettlementCandidate> selectCandidatesForUpdate(
            @Param("snapshot") CoopSettlementSnapshot snapshot,
            @Param("settlementCutoff") Date settlementCutoff);

    /**
     * 活动壳证据专用 seam。activityIds 必须由受控主题查询先解析；本查询只锁 registration，
     * 不在 FOR UPDATE 内读取 cms_activity，避免 activity/registration 反向锁序。
     */
    List<CoopSettlementCandidate> selectTopicEvidenceCandidatesForUpdate(
            @Param("snapshot") CoopSettlementSnapshot snapshot,
            @Param("activityIds") List<Long> activityIds,
            @Param("settlementCutoff") Date settlementCutoff);
}
