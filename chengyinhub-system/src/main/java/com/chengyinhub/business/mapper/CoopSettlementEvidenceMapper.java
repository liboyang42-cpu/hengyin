package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.CoopSettlementCandidate;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 选集事实窄 seam；查询必须与 claim/detail 写入同事务并锁 registration。 */
public interface CoopSettlementEvidenceMapper {
    /** 旧主题报名，按 id 当前锁读；销售额与审计投影必须使用同一批返回行。 */
    List<com.chengyinhub.business.domain.CmsRegistration> selectTopicCashRowsForUpdate(@Param("topicId") Long topicId);

    /** 包括已删活动壳；当前锁读真实父关系及报名，不复用普通 COUNT 的 RR/缓存结果。 */
    List<com.chengyinhub.business.domain.CmsRegistration> selectTopicActivityCashRowsForUpdate(@Param("topicId") Long topicId);

    /**
     * 第十二轮 k5:主题下除「在世俱乐部场次」以外的活动报名(锁读)。在世俱乐部场次(del_flag=0 且 club_id>0)
     * 由活动结算按场次结(含商家核销费),不进主题现金基数;其余活动壳上的已付钱仍按
     * ACTIVITY_CASH_BASIS_UNSUPPORTED 拒结。调用前须已用 selectTopicActivityCashRowsForUpdate 锁过全部场次报名。
     */
    List<com.chengyinhub.business.domain.CmsRegistration> selectTopicNonClubSessionCashRowsForUpdate(@Param("topicId") Long topicId);

    /** 第十二轮 k5:本主题在世俱乐部场次的 id(按场次结算、商家费在活动结算里付)。只读。 */
    List<Long> selectLiveClubSessionIdsByTopicId(@Param("topicId") Long topicId);


    /** 旧只读证据计数；不能用作结算现金准入。 */
    int countPaidActivityRegistrations(@Param("topicId") Long topicId);
    List<Long> selectCashBasisBlockingRefundIdsForUpdate(@Param("topicId") Long topicId);

    List<CoopSettlementCandidate> selectCandidatesForUpdate(
            @Param("snapshot") CoopSettlementSnapshot snapshot,
            @Param("settlementCutoff") Date settlementCutoff);

    /**
     * 活动壳证据专用 seam。activityIds 必须由受控主题查询先解析；锁报名并联查票归属/履约窗，
     * 不在 FOR UPDATE 内读取 cms_activity，避免 activity/registration 反向锁序。
     */
    List<CoopSettlementCandidate> selectTopicEvidenceCandidatesForUpdate(
            @Param("snapshot") CoopSettlementSnapshot snapshot,
            @Param("activityIds") List<Long> activityIds,
            @Param("settlementCutoff") Date settlementCutoff);
}
