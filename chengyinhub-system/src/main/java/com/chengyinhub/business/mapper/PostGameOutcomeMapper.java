package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.OutcomeFactRow;
import com.chengyinhub.business.domain.dto.RegistrationCohortFact;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 完局维护与有效复报的只读事实源。
 *
 * <p>只读：本 mapper 无 insert/update/delete。所有查询只认服务端事实
 * （报名/支付/退款/完成/发奖/媒体/帖文/事件行），不接受客户端自报成交；
 * 客户端观测单独走 {@link #selectClientObservedOutcomeFacts}，输出标 serverFinal=false。
 * 查询失败或返回 null 由调用方判为「未知」，不得回落成 0。
 */
public interface PostGameOutcomeMapper {

    /** cohort 当天有过付费报名的会员（含其后被退掉的单，有效性由服务层判）。 */
    List<Long> selectCohortMemberIds(@Param("from") Date from, @Param("toExclusive") Date toExclusive);

    /** 候选会员在窗末之前的全部付费报名历史（含已退款/取消/过期），用于判「全历史首笔有效报名」。 */
    List<RegistrationCohortFact> selectPaidRegistrationHistory(@Param("memberIds") Collection<Long> memberIds,
                                                               @Param("toExclusive") Date toExclusive);

    /** 正式完成：通关勋章 ∪ 漫游结算 ∪ 高级玩法完成（均服务端事实）。 */
    List<OutcomeFactRow> selectCompletionFacts(@Param("from") Date from,
                                               @Param("toExclusive") Date toExclusive);

    /** 奖励发放：通关券 ∪ 通关 XP ∪ 据点领券（均发放事实，不含客户端声明）。 */
    List<OutcomeFactRow> selectRewardFacts(@Param("from") Date from,
                                           @Param("toExclusive") Date toExclusive);

    /** 私人照片：挂在 PRIVATE 帖文上的已附加媒体；默认不公开，不进公开口径。 */
    List<OutcomeFactRow> selectPrivatePhotoFacts(@Param("from") Date from,
                                                 @Param("toExclusive") Date toExclusive);

    /** 公开分享：PUBLIC 可见且已发布的帖文。 */
    List<OutcomeFactRow> selectPublicShareFacts(@Param("from") Date from,
                                                @Param("toExclusive") Date toExclusive);

    /** 推荐点击：完局/漫游推荐位的服务端事件行，按 idempotency_key 去重。 */
    List<OutcomeFactRow> selectRecommendationClickFacts(@Param("from") Date from,
                                                        @Param("toExclusive") Date toExclusive);

    /** 客户端自报的完成/到点/推荐曝光事件：仅对照观测，不得当业务终局。 */
    List<OutcomeFactRow> selectClientObservedOutcomeFacts(@Param("from") Date from,
                                                          @Param("toExclusive") Date toExclusive);

    /** 各来源水位（sourceName → 最新时间）。 */
    List<Map<String, Object>> selectSourceWatermarks();
}
