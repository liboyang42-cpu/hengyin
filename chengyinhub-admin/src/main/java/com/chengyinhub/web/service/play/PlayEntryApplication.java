package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.vo.SpoilerAccessSnapshot;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ISpoilerProtectionService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.domain.AjaxResult;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Entry command orchestration for legacy mixed GETs; projection stages never initialize runtime state. */
@Service
public class PlayEntryApplication {
    private static final Logger logger = LoggerFactory.getLogger(PlayEntryApplication.class);
    @Autowired
    private com.chengyinhub.business.access.club.ClubEventLeadResolver clubEventLeadResolver;
    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ISpoilerProtectionService spoilerProtectionService;
    @Autowired
    private com.chengyinhub.business.service.IPuzzlePlayService puzzlePlayService;
    @Autowired
    private com.chengyinhub.business.mapper.CmsRegistrationMapper cmsRegistrationMapper;
    @Autowired
    private com.chengyinhub.business.mapper.CmsActivityMapper currentPlayActivityMapper;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired
    private com.chengyinhub.business.service.IPlayerRoleService playerRoleService;
    @Autowired
    private com.chengyinhub.business.service.IUmsMemberPointsDetailService umsMemberPointsDetailService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private PlayNodeQueryApplication playNodeQueryApplication;

    /** Compatibility GET /nodes still performs original entry commands at their original points. */
    public AjaxResult nodes(Long actorMemberId, Long activityId, Long topicId) {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");

        // M2 会话解析:活动会话(activityId>0)或自玩会话(topicId,activity_id=0 主题通行证)
        boolean selfPlay = (activityId == null || activityId == 0L) && topicId != null;
        Long tId;
        Long progActId;
        String timeErr;
        boolean registered;
        boolean leadSpectator = false;
        Date passExpiresAt = null;
        Long clubId = null; // 升级2:俱乐部局判据,响应尾部按它发 myRole
        if (!selfPlay) {
            if (activityId == null) return error("参数有误");
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            tId = activity.getTopicId();
            progActId = activityId;
            timeErr = playNodeContext.activityTimeError(activity);
            registered = playCompletionFacts.isRegistered(memberId, activityId);
            clubId = activity.getClubId();
            // [F-37] 俱乐部领队可能没报名本场(运营身份,不是玩家)。原先它和普通路人一样被
            // 前端的「你还没有报名这个场次」空态挡在门外,于是发车/广播/放行章节都够不着。
            // 这里只给一个「旁观带队」标记让页面能渲染;registered 仍保持 false ——
            // 奖励、完成度、名额这些都挂在它身上,运营权不等于玩家领奖权。
            leadSpectator = !registered && clubId != null
                    && memberId.equals(clubEventLeadResolver.resolveLeaderMemberId(activity));
            // O-08:未报名、非带队的旁人只能拿到对他公开可见主题的节点坐标/地址。草稿、下架、
            // 仅俱乐部成员可见的主题按不存在处理(判据 = 主题详情同一条 SQL:在架+圈层闸或作者本人,且过成员闸)。
            if (!registered && !leadSpectator && cmsTopicService.selectCmsTopicDetailById(tId, memberId) == null) {
                return error("活动或主题不存在");
            }
        } else {
            tId = topicId;
            progActId = 0L;
            CmsRegistration pass = playCompletionFacts.validSelfPlayPass(memberId, tId);
            // 已开局的本局：通行证开局时有效即可打完，节点列表照常下发。
            if (pass == null && topicRouteRuntimeService != null && topicRouteRuntimeService.hasRunningSession(memberId, tId, 0L)) {
                try { pass = com.chengyinhub.business.service.support.CurrentPlayRegistration.requireToContinue(
                        currentPlayActivityMapper,cmsRegistrationMapper,memberId,1L,tId,null,new Date()); }
                catch (IllegalStateException denied) { pass = null; }
            }
            // C-26:没有(或过期的)自玩通行证不是「未报名」——自玩本来就没有报名这个动作。
            // 回 402 让前端落 needPass 空态并给出获取通行证的出路(与 /api/play 下各写入口同一语义),
            // 不再落到 registered=false 的「你还没有报名这个场次」+「去报名」错误指引。
            if (pass == null) return new AjaxResult(402, "请先购买自玩通行证");
            registered = true;
            passExpiresAt = pass.getExpiresAt();
            timeErr = null; // 自玩无场次时效;有效期由通行证 expires_at 管
        }

        // 节点列表
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> nodeList = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);

        // 玩家已打卡集合
        PlayerNodeProgress progProbe = new PlayerNodeProgress();
        progProbe.setMemberId(memberId);
        progProbe.setActivityId(progActId);
        List<PlayerNodeProgress> doneList = playerNodeProgressService.selectPlayerNodeProgressList(progProbe);
        List<Long> doneNodeIds = new ArrayList<>();
        Map<Long, Date> doneAtMap = new LinkedHashMap<>(); // P0 剧情成书:完成时间(前端按"我的完成顺序"排手记)
        Map<Long, PlayerNodeProgress> progressByNode = new HashMap<>();
        Set<Long> completedNodeIds = new HashSet<>(); // 仅 status=1 完成(mode2 进店 status=0 行不算)
        for (PlayerNodeProgress p : doneList) {
            // 完成事实只认 status=1(与 F1/R10-09 同口径):拍照待审(photo_review=1 提交的 status=0 行)
            // 不是完成,不得进 done/doneCount,也不得点亮 pin —— 解锁下一站由 completedNodeIds 管。
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            doneNodeIds.add(p.getNodeId());
            progressByNode.put(p.getNodeId(), p);
            if (p.getCheckinTime() != null) doneAtMap.put(p.getNodeId(), p.getCheckinTime());
            completedNodeIds.add(p.getNodeId());
        }
        // 待审/驳回行仍要进 progressByNode(下发 picUrl 与审核态),但不计入完成
        for (PlayerNodeProgress p : doneList) {
            if (p.getStatus() == null || p.getStatus() != 1) {
                progressByNode.putIfAbsent(p.getNodeId(), p);
            }
        }

        // 积分解锁提示:规则(type=1,event_type=14)分值>0 时对未解锁节点扣发 hint 明文;
        // 已解锁节点(有 eventType=14 消费流水)照常下发。无规则/0分=未开闸,保持免费明文
        // (老客户端提示按钮由 !!hint1 驱动,扣发后按钮自然隐藏=优雅降级,发版窗口不炸)。
        int hintCost = puzzlePlayService.hintUnlockCost();
        java.util.Set<Long> hintUnlockedNodeIds = new java.util.HashSet<>();
        if (hintCost > 0) {
            UmsMemberPointsDetail hp = new UmsMemberPointsDetail();
            hp.setMemberId(memberId);
            hp.setEventType(14L);
            List<UmsMemberPointsDetail> unlockRows = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(hp);
            if (unlockRows != null) {
                for (UmsMemberPointsDetail r : unlockRows) {
                    if (r.getEventId() != null) hintUnlockedNodeIds.add(r.getEventId());
                }
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        SpoilerAccessSnapshot spoilerAccess = spoilerProtectionService == null ? null
                : spoilerProtectionService.snapshot(memberId, ISpoilerProtectionService.CONTENT_NODE);
        int playMode = playNodeContext.resolveMode(progActId, tId); // 复用既有 helper,循环内 + 出参共用
        TopicRouteState routeState = null;
        com.chengyinhub.business.domain.CmsTopic routeTopic = cmsTopicService.selectCmsTopicById(tId);
        com.fasterxml.jackson.databind.JsonNode contentSnapshot = contentSnapshotForRead(memberId,tId,progActId,registered,routeTopic);
        String contentRules = com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContentSnapshots(contentSnapshot)
                ? com.chengyinhub.business.service.support.NodeJourneySnapshot.journeyRules(contentSnapshot.toString())
                : routeTopic == null ? null : routeTopic.getJourneyRulesJson();
        if (com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContentSnapshots(contentSnapshot) && contentRules == null)
            throw new IllegalStateException("本局数值规则快照不可用");
        boolean hasJourneyState = com.chengyinhub.business.domain.JourneyRules.parse(contentRules).hasRuntimeState();
        // RV4 S533-3:check.bargain 报价只在本局规则开了魔鬼交易时下发;老主题(无旅程状态)不多查库。
        boolean bargainOffered = hasJourneyState && topicRouteRuntimeService != null
                && topicRouteRuntimeService.bargainOffered(memberId, tId, progActId);
        if (registered && topicRouteRuntimeService != null
                && (playMode == PlayMode.LINEAR_ORIENTEERING || hasJourneyState)) {
            try {
                routeState = topicRouteRuntimeService.state(memberId, tId, progActId);
                if (routeState != null && routeState.getSessionId() != null)
                    contentSnapshot = contentSnapshotForRead(memberId,tId,progActId,true,routeTopic);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return error(e.getMessage());
            }
        }
        PlayNodeQueryApplication.NodeProjection read = new PlayNodeQueryApplication.NodeProjection(
                memberId, selfPlay, tId, progActId, timeErr, registered, leadSpectator, passExpiresAt,
                nodeList, doneNodeIds, doneAtMap, progressByNode, completedNodeIds, hintCost,
                hintUnlockedNodeIds, nodes, spoilerAccess, playMode, routeTopic, bargainOffered, routeState);
        read.contentSnapshot = com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContentSnapshots(contentSnapshot)
                ? contentSnapshot : null;
        AjaxResult denied = playNodeQueryApplication.prepareNodes(read);
        if (denied != null) return denied;
        boolean versionedContent = com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContentSnapshots(contentSnapshot);
        boolean hasNodeGames = contentSnapshot != null && contentSnapshot.path("originalNodeGames").isObject()
                && !contentSnapshot.path("originalNodeGames").isEmpty();
        boolean sharedContent = read.registered && topicRouteRuntimeService != null
                && contentSnapshot != null && contentSnapshot.hasNonNull("gameSessionId");
        if (versionedContent && read.routeState != null && read.routeState.getSessionId() != null) {
            com.fasterxml.jackson.databind.JsonNode originalRun = contentSnapshot.has("gameSessionId")
                    ? contentSnapshot.get("gameSessionId") : contentSnapshot.get("contentRouteSessionId");
            if (originalRun != null && originalRun.asLong() != read.routeState.getSessionId())
                return error("路线状态已更新，请重试");
        }
        List<CmsTopicNode> presentedNodes = new ArrayList<>();
        for (CmsTopicNode n : read.nodeList) {
            com.fasterxml.jackson.databind.JsonNode nodeSource = com.chengyinhub.business.service.support.NodeJourneySnapshot.nodeContentSource(contentSnapshot,n.getId());
            boolean legacyNodeGame = nodeSource != null && nodeSource.path("legacyNodeContent").asBoolean();
            boolean nodeVersioned = com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContentSnapshots(nodeSource);
            if (nodeVersioned && !nodeSource.path("nodeAnswers").has(String.valueOf(n.getId()))) continue;
            com.fasterxml.jackson.databind.JsonNode content = nodeVersioned
                    ? com.chengyinhub.business.service.support.NodeJourneySnapshot.answerFromSnapshot(nodeSource,n.getId()) : null;
            if (nodeVersioned && !com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContent(content))
                throw new IllegalStateException("本局内容快照不可用");
            if (nodeVersioned && !sharedContent && content.path("nodePresentation").path("id").asLong() != n.getId())
                throw new IllegalStateException("本局内容归属不可用");
            PlayNodeQueryApplication.NodeTask task = playNodeQueryApplication.projectNodeBeforeRuntime(read, n, content);
            presentedNodes.add(task.node());
            if (task.template() != null) {
                // New content uses this request's captured config. The old compatibility path
                // keeps its original Entry-owned shared refresh outside pure Query.
                com.fasterxml.jackson.databind.JsonNode themeNodeConfig = nodeVersioned || legacyNodeGame
                        ? nodeSource.path("nodeConfigs").get(String.valueOf(n.getId()))
                        : topicRouteRuntimeService == null ? null : topicRouteRuntimeService.nodeRuntimeConfig(memberId, n.getTopicId(), n.getId(), progActId);
                playNodeQueryApplication.projectFrozenTemplate(read, task, themeNodeConfig);
            }
            playNodeQueryApplication.finishNode(read, task, n);
        }
        if (versionedContent || hasNodeGames) read.nodeList = presentedNodes;
        Map<String, Object> data = playNodeQueryApplication.finishNodes(read);
        // 升级2:俱乐部局自动分配角色(已报名才发;自玩/公域局不发)。best-effort,不拖垮 /nodes
        if (!selfPlay && clubId != null && registered
                && (gameSessionRuntimeService == null || gameSessionRuntimeService.routeSessionStatus(progActId) == null)) {
            try {
                com.chengyinhub.business.domain.PlayerActivityRole myRole =
                        playerRoleService.assignOrGet(memberId, progActId);
                if (myRole != null) {
                    PlayNodeQueryApplication.AssignedRole role = new PlayNodeQueryApplication.AssignedRole(
                            myRole.getRoleCode(), playerRoleService.roleName(myRole.getRoleCode()));
                    playNodeQueryApplication.appendAssignedRole(data, role);
                }
            } catch (Exception e) {
                logger.warn("俱乐部局角色分配失败 activity={} member={}", progActId, memberId, e);
            }
        }
        return AjaxResult.success(data);
    }

    /** The caller has already performed the original public/spectator/player admission. */
    private com.fasterxml.jackson.databind.JsonNode contentSnapshotForRead(Long member, Long topicId, Long activity,
            boolean registered, com.chengyinhub.business.domain.CmsTopic topic) {
        if (registered && topicRouteRuntimeService != null)
            return topicRouteRuntimeService.queryNodeContentSnapshot(member,topicId,activity,null);
        if (topic == null || topic.getPublishedConfigVersion() == null || topic.getPublishedConfigVersion() <= 0
                || topic.getPublishedRouteGraphJson() == null || topic.getPublishedRouteGraphJson().trim().isEmpty()) return null;
        com.fasterxml.jackson.databind.JsonNode snapshot = new com.chengyinhub.business.service.support.TopicRouteConfigValidator()
                .readTree(topic.getPublishedRouteGraphJson());
        if (registered && com.chengyinhub.business.service.support.NodeJourneySnapshot.hasContentSnapshots(snapshot))
            throw new IllegalStateException("原局内容读取服务暂时不可用");
        if (snapshot.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode)snapshot).remove(java.util.Arrays.asList(
                "contentRouteSessionId","contentTags","gameSessionId","teamId","authorityEpoch","runtimeInstance"));
        com.chengyinhub.business.service.support.NodeJourneySnapshot.requireContentSnapshot(snapshot);
        return snapshot;
    }

    /** Compatibility GET entry: preserves state initialization/shared refresh; no target or outcome input. */
    public AjaxResult routeState(Long actorMemberId, Long topicId,
            Long activityId,
            Long actId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        if (topicRouteRuntimeService == null) return error("参数有误");
        try {
            Long effectiveActivityId = activityId != null ? activityId : actId;
            Long effectiveTopicId = topicId;
            if ((effectiveTopicId == null || effectiveTopicId <= 0) && effectiveActivityId != null && effectiveActivityId > 0) {
                CmsActivity activity = cmsActivityService.selectCmsActivityById(effectiveActivityId);
                effectiveTopicId = activity == null ? null : activity.getTopicId();
            }
            if (effectiveTopicId == null || effectiveTopicId <= 0) return error("参数有误");
            return AjaxResult.success(topicRouteRuntimeService.state(memberId, effectiveTopicId,
                    effectiveActivityId == null ? 0L : effectiveActivityId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    public AjaxResult routeState(Long actorMemberId, Long topicId, Long actId) {
        return routeState(actorMemberId, topicId, null, actId);
    }

}
