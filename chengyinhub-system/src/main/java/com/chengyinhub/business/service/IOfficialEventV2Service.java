package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2PublishCheck;

/**
 * 官方活动 V2 深模块。调用者只认识草稿、发布检查、受控状态命令与证据结果；
 * 绑定/任务/承接/证据的复杂性集中在实现内部。
 */
public interface IOfficialEventV2Service {
    /** 将后台账号解析为显式授权的白名单官方会员；无授权或任一主体失效时返回 null。 */
    Long resolveAuthorizedOfficialMember(Long sysUserId);

    /** 当前后台账号的授权投影，不暴露其他账号的绑定。 */
    Map<String, Object> operatorIdentity(Long sysUserId);

    /** 仅具备独立授权权限的后台操作者可建立/替换后台账号代理的官方会员身份。 */
    String authorizeOperator(Long sysUserId, Long memberId, Long grantedBy, String reason);

    /** 撤销后台账号的活动代理身份，历史行保留用于调证。 */
    String revokeOperatorAuthorization(Long sysUserId, Long revokedBy, String reason);

    OfficialEvent saveDraft(OfficialEventV2DraftRequest request, Long operatorId);

    /**
     * 受控活动操作者：有效主办/协办，或尚无有效主办时的草稿创建者。
     * 草稿创建者并不因此获得运行态主办权限。
     */
    boolean canOperateControlledEvent(Long eventId, Long memberId);

    /** 受控只读：操作者，或活动结束后身份归档为 COMPLETED 的原主办/协办（复盘回看，不含写权限）。 */
    boolean canReadControlledEvent(Long eventId, Long memberId);

    /** 仅受控活动操作者可读取发布检查，防止普通后台查询权限横向读取。 */
    OfficialEventV2PublishCheck checkPublishableForOperator(Long eventId, Long memberId);

    /** 仅受控活动操作者可读取草稿中的任务、主体和履约明细。 */
    Map<String, Object> draftForOperator(Long eventId, Long memberId);

    /** 仅本场已接受的官方主办/协办关系成员可读取受控发布检查。 */
    boolean canManage(Long eventId, Long memberId);

    String schedule(Long eventId, Long operatorId);

    String signup(Long eventId, Long memberId);

    Map<String, Object> detail(Long eventId, Long memberId);

    /** 玩家公开活动列表/详情可展示的汇总，绝不返回运营 360 的名单、承接与风险明细。 */
    Map<String, Object> publicProgress(Long eventId);

    /** 仅受控活动操作者可读取名单、奖励、审计和通知等运营 360 明细。 */
    Map<String, Object> activity360ForOperator(Long eventId, Long memberId);

    /** V2 活动通知必须由受控活动操作者发起，服务端覆盖发布者身份。 */
    OfficialBroadcast publishBroadcastForOperator(OfficialBroadcast broadcast, Long memberId);

    /** V2 活动通知复盘仅返回给受控活动操作者。 */
    Map<String, Object> broadcastStatsForOperator(Long broadcastId, Long memberId);

    List<Map<String, Object>> listPublic(String city, Long memberId);

    List<Map<String, Object>> myEvents(Long memberId);

    Map<String, Object> verifyArrival(Long eventId, Long memberId, OfficialEventV2ArrivalRequest request);

    void recordThemeCompletion(Long memberId, Long topicId, String sourceFactId, java.util.Date occurredAt);

    void revokeThemeCompletion(Long registrationId, Long topicId, String reason, Long operatorId);

    String revokeEvidence(Long evidenceId, Long operatorId, String reason);

    String invalidateParticipation(Long eventId, Long memberId, String state, Long operatorId, String reason);

    String respondParty(Long partyId, Long memberId, String action, String reason);

    /** 仅当前有效主办可邀请另一白名单会员成为待接受的继任主办。 */
    String inviteOrganizer(Long eventId, Long operatorMemberId, Long invitedMemberId, String reason);

    /** 受邀官方会员确认主办邀约；若当前无有效主办则直接成为唯一有效主办。 */
    String acceptOrganizerInvitation(Long partyId, Long memberId, String reason);

    /** 受邀官方会员拒绝主办邀约；不走通用承接方接口。 */
    String declineOrganizerInvitation(Long partyId, Long memberId, String reason);

    /** 当前有效主办将职责原子转交给已接受的继任主办。 */
    String transferOrganizer(Long eventId, Long operatorMemberId, Long successorPartyId, String reason);

    List<Map<String, Object>> partyInbox(Long memberId);

    String pause(Long eventId, Long operatorId, String reason, String impactScope,
                 String alternativePlan, String notificationPlan);

    String resume(Long eventId, Long operatorId, String reason);

    int settle(Long eventId);

    void runLifecycle();

    List<Map<String, Object>> activeForRoam(String city, Long memberId);
}
