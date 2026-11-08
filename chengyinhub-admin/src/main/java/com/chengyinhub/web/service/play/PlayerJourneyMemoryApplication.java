package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.PlayerJourneyMemoryVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * 本局私人回忆投影（P8）。
 *
 * <p>分组只来自本场会话/报名事实，不查好友关系；只返回本人已完成的节点与本人照片。
 * 队友身份、他人照片与未同意分享的媒体一律不返回；现有模型没有私照分享同意事实，
 * 因此分享能力如实标为 UNAVAILABLE，而不是默认全部可见。
 */
@Service
public class PlayerJourneyMemoryApplication {

    static final String VIEWER_SELF_PLAY = "SELF_PLAY";
    static final String VIEWER_PARTICIPANT = "PARTICIPANT";

    @Autowired
    private ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired
    private ICmsRegistrationService cmsRegistrationService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private TopicRouteSessionMapper topicRouteSessionMapper;

    public AjaxResult memory(Long actorMemberId, Long activityId, Long topicId) {
        return memory(actorMemberId, activityId, topicId, null);
    }

    /**
     * @param runId 可选的本局身份（{@code topic_route_session.id}）。点名后只读这一局；
     *              不传才回到该作用域最新一局。
     */
    public AjaxResult memory(Long actorMemberId, Long activityId, Long topicId, Long runId) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0L) return error(403, "请先登录");
        boolean selfPlay = (activityId == null || activityId <= 0L) && topicId != null && topicId > 0L;
        if (!selfPlay && (activityId == null || activityId <= 0L)) return error("参数有误");

        Long tId;
        Long actId = 0L;
        if (selfPlay) {
            tId = topicId;
        } else {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            if (topicId != null && topicId > 0L && !topicId.equals(activity.getTopicId())) {
                return error("该主题不属于本次活动");
            }
            tId = activity.getTopicId();
            actId = activityId;
        }
        CmsTopic topic = cmsTopicService.selectCmsTopicById(tId);
        if (topic == null) return error("主题不存在");
        if (playCompletionFacts.topicHistoryBlocked(topic)) return error(403, "该局内容已不可查看");

        PlayerRunScope.Resolution scope = PlayerRunScope.resolve(
                topicRouteSessionMapper, memberId, actId, tId, runId);
        if (!scope.ok()) return refusal(scope.reasonCode());
        TopicRouteSession session = scope.session();
        JsonNode frozen = PlayerRunFrozenView.snapshot(session);
        Map<Long, JsonNode> frozenStations = PlayerRunFrozenView.stations(frozen);
        // 会话存在但没有实例值：进度行无法归属本局，不查、不拿主题历史行凑数。
        boolean attributable = session == null || scope.instanceBound();
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(actId);
        if (selfPlay) probe.setTopicId(tId);
        if (scope.instanceBound()) {
            probe.setPlayInstanceId(session.getPlayInstanceId()); // 同一主题重开的局不合并
        }
        List<PlayerNodeProgress> ownRows = attributable
                ? playerNodeProgressService.selectPlayerNodeProgressList(probe) : new ArrayList<>();
        if (ownRows == null) ownRows = new ArrayList<>();
        String boundInstance = probe.getPlayInstanceId();
        if (boundInstance != null) {
            List<PlayerNodeProgress> ownRun = new ArrayList<>();
            for (PlayerNodeProgress row : ownRows) {
                if (row != null && boundInstance.equals(row.getPlayInstanceId())) ownRun.add(row);
            }
            ownRows = ownRun;
        }
        boolean participant = session != null || !ownRows.isEmpty()
                || (selfPlay ? playCompletionFacts.hasSelfPlayRegistration(memberId, tId)
                             : playCompletionFacts.isRegistered(memberId, actId));
        if (!participant) return error(403, "无权查看该局");

        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> activeNodes = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
        Map<Long, CmsTopicNode> nodeById = new HashMap<>();
        if (activeNodes != null) for (CmsTopicNode node : activeNodes) nodeById.put(node.getId(), node);

        List<PlayerNodeProgress> done = new ArrayList<>();
        long rowActivity = actId == null ? 0L : actId;
        for (PlayerNodeProgress row : ownRows) {
            if (row == null || !memberId.equals(row.getMemberId())) continue;
            if (row.getStatus() == null || row.getStatus() != 1) continue;
            // 读侧若误返回别的作用域行，这里再挡一次：回忆只能来自本主题/本活动。
            if (row.getTopicId() != null && !row.getTopicId().equals(tId)) continue;
            long rowAct = row.getActivityId() == null ? 0L : row.getActivityId();
            if (rowAct != rowActivity) continue;
            done.add(row);
        }
        done.sort(Comparator.comparing(PlayerNodeProgress::getCheckinTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Set<Long> seen = new LinkedHashSet<>();
        List<PlayerJourneyMemoryVO.Moment> moments = new ArrayList<>();
        for (PlayerNodeProgress row : done) {
            if (row.getNodeId() == null || !seen.add(row.getNodeId())) continue;
            CmsTopicNode node = nodeById.get(row.getNodeId());
            if (node == null) node = playCompletionFacts.historyReadableNode(row.getNodeId(), topic);
            PlayerJourneyMemoryVO.Moment moment = new PlayerJourneyMemoryVO.Moment();
            moment.setNodeId(row.getNodeId());
            String name;
            String fragment;
            if (session != null) {
                // 本局快照存在时只读冻结内容；作者改稿后的实时节点不得改写这一局的回忆。
                name = PlayerRunFrozenView.nodeName(frozen, frozenStations.get(row.getNodeId()), row.getNodeId());
                fragment = PlayerRunFrozenView.fragmentText(frozen, row.getNodeId());
            } else {
                // 没有本局快照只能给公开节点名；碎片正文不读实时节点，避免暴露改稿后的私密线索。
                name = node == null ? null : node.getName();
                fragment = null;
            }
            moment.setName(name);
            moment.setFragmentText(fragment);
            // 只回本人进度行上的照片；队友照片从未进入本次查询范围。
            moment.setPicUrl(row.getPicUrl());
            moment.setCheckinTime(row.getCheckinTime());
            moments.add(moment);
        }

        PlayerJourneyMemoryVO vo = new PlayerJourneyMemoryVO();
        vo.setActivityId(actId);
        vo.setTopicId(tId);
        boolean boundRun = scope.instanceBound();
        vo.setRunId(session == null ? null : session.getId());
        vo.setRunKind(actId > 0L ? "ACTIVITY" : "SELF_PLAY");
        vo.setScopeKind(session == null ? PlayerJourneyMemoryVO.SCOPE_LEGACY_TOPIC : PlayerJourneyMemoryVO.SCOPE_RUN);
        vo.setPlayInstanceId(session == null ? null : session.getPlayInstanceId());
        vo.setProgressScope(session == null ? PlayerJourneyMemoryVO.PROGRESS_LEGACY_TOPIC
                : (boundRun ? PlayerJourneyMemoryVO.PROGRESS_RUN_INSTANCE
                            : PlayerJourneyMemoryVO.PROGRESS_UNATTRIBUTED));
        vo.setParticipantCount(participantCount(selfPlay, tId, actId));
        vo.setViewerRole(selfPlay ? VIEWER_SELF_PLAY : VIEWER_PARTICIPANT);
        vo.setIdentityPolicy(PlayerJourneyMemoryVO.IDENTITY_SELF_ONLY);
        vo.setPhotoShareCapability(PlayerJourneyMemoryVO.PHOTO_SHARE_UNAVAILABLE);
        vo.setPhotoShareReason(PlayerJourneyMemoryVO.PHOTO_SHARE_NO_CONSENT_FACT);
        vo.setReadOnly(Boolean.TRUE);
        vo.setMoments(moments);
        return AjaxResult.success(vo);
    }

    /** 点名局不存在/越权/错作用域：拒绝并给稳定理由码，绝不回落成当前局。 */
    private static AjaxResult refusal(String reasonCode) {
        AjaxResult result = error(403, PlayerRunScope.REASON_RUN_NOT_FOUND.equals(reasonCode)
                ? "本局不存在或无权查看" : "本局与当前主题/活动不匹配");
        result.put("reasonCode", reasonCode);
        return result;
    }

    /** 自玩局只有本人；活动局只数已支付成功的参与者，不返回任何身份。 */
    private Integer participantCount(boolean selfPlay, Long topicId, Long activityId) {
        if (selfPlay) return Integer.valueOf(1);
        try {
            CmsRegistration probe = new CmsRegistration();
            probe.setOwnerType(2L);
            probe.setOwnerId(activityId);
            probe.setRegistrationStatus(2);
            probe.setPaymentStatus(2);
            List<CmsRegistration> rows = cmsRegistrationService.selectCmsRegistrationList(probe);
            return rows == null ? null : rows.size();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
