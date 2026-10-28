package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ClubEventSeriesRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.domain.dto.ClubEventCancellationRequest;
import com.chengyinhub.business.domain.vo.ClubEventCancellationResult;
import com.chengyinhub.business.domain.vo.ClubEventCancellationStateVO;
import com.chengyinhub.business.domain.vo.ClubEventSeriesView;

public interface IClubEventOpsService {
    java.util.List<CmsTopic> listOpenableTopics(Long actorMemberId, Long clubId);
    java.util.List<ClubEventSeriesView> listSeries(Long actorMemberId, Long clubId);
    ClubEventSeriesView getSeries(Long actorMemberId, Long clubId, Long seriesId);
    ClubEventSeries createSeries(Long actorMemberId, String username, ClubEventSeriesRequest request);
    ClubEventSeries updateFuture(Long actorMemberId, String username, ClubEventSeriesRequest request);

    /**
     * O5b-7(开关默认关):关闭系列 → CLOSED 终态。关闭后不再往该系列排新场次(update-future 拒),
     * 系列从列表/详情消失;已排场次、已付报名、票与资金一律不动。重复关闭同一系列幂等返回。
     */
    ClubEventSeries closeSeries(Long actorMemberId, String username, ClubEventSeriesRequest request);
    ClubEventCancellationResult cancelOccurrence(Long actorMemberId, String username,
                                                  ClubEventCancellationRequest request);

    /**
     * [6-17] 「我的项目 → 取消活动」入口:与 {@link #cancelOccurrence} 走同一套收尾逻辑。
     *
     * <p>活动取消有两条路。原先 {@code /api/activity/cancel} 只调退款核心:属于俱乐部系列场次的活动
     * 不终止候补、不发站内取消通知、不回收领队;只有 {@code /event-ops/cancel} 收尾完整。
     * 本方法把「领队回收 → 退款核心 → 场次 CAS → 候补终止 → 站内通知」整段收口,
     * 非系列场次保持原行为只做退款；系列场次的操作人必须通过当前俱乐部权限与 owner 锁后重验。
     *
     * <p>⚠️ 退款金额与派发逻辑仍完全走 {@code teamGroupService} 的取消核心，本方法不改一分钱。
     */
    ActivityCancelResult cancelActivityByOwnerWithClubWrapUp(Long actorMemberId, Long activityId, String reason);

    /**
     * 主题取消已在主题层完成授权后的系统收尾。系列场次以当前俱乐部归属为真源，
     * 不再要求场次历史创建人仍持有俱乐部管理权。
     * 调用方必须传入已授权的 topic/club/activity 完整范围，本方法锁行后再校验归属。
     */
    ActivityCancelResult cancelActivityForAuthorizedTopicCancellation(
            Long actorMemberId, Long topicId, Long clubId, Long activityId, String reason);
    /** Already-authorized topic cancellation: capture under the original scope transaction, no SDK. */
    com.chengyinhub.business.domain.dto.RegistrationCancellationPayments captureAuthorizedTopicCancellationPayments(
            Long actorMemberId, Long topicId, Long clubId, Long activityId, String reason);

    /** Join the original full topic cancellation transaction with the exact earlier observations. */
    ActivityCancelResult cancelAuthorizedTopicWithObservedPayments(Long actorMemberId, Long topicId,
            Long clubId, Long activityId, String reason,
            com.chengyinhub.business.domain.dto.RegistrationCancellationPayments observed);

    ClubEventCancellationStateVO getCancellationState(Long actorMemberId, Long clubId, Long activityId);

    /**
     * Figma E1「日期清单」:某系列的全部场次(含已过去、含已取消)与每场的可编辑性。
     * 与 getSeries 不同 —— 那个只回未来 ACTIVE 的日期字符串,画不出「已锁定 / 本场已取消」。
     */
    java.util.List<java.util.Map<String, Object>> listSeriesOccurrences(
            Long actorMemberId, Long clubId, Long seriesId);
}
