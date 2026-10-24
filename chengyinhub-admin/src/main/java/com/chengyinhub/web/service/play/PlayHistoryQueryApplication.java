package com.chengyinhub.web.service.play;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.util.PlayNarrativeSupport;
import com.chengyinhub.common.core.domain.AjaxResult;

import org.springframework.stereotype.Service;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Authorized ending and completed-play projections. */
@Service
public class PlayHistoryQueryApplication {

    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private com.chengyinhub.business.service.support.CompletedRouteFactReader completedRouteFactReader;
    @Autowired
    private ICommunityPostService communityPostService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private ICmsRegistrationService cmsRegistrationService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;

    public AjaxResult ending(Long actorMemberId, Long activityId, Long topicId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        // 会话解析(镜像 /nodes:活动会话 or 自玩会话)
        boolean selfPlay = (activityId == null || activityId == 0L) && topicId != null;
        Long tId;
        Long progActId;
        if (!selfPlay) {
            if (activityId == null) return error("参数有误");
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) return error("活动或主题不存在");
            tId = activity.getTopicId();
            progActId = activityId;
        } else {
            tId = topicId;
            progActId = 0L;
        }
        // R10-09 收尾:主题探针(与其它读侧一致)。历史履约:不按公开状态/报名时效/下架字段挡
        // 已有参与者——碎片唯一真源是本人完成行;只有主题行取不到(参数/数据损坏)才清晰失败。
        com.chengyinhub.business.domain.CmsTopic historyTopic = cmsTopicService.selectCmsTopicById(tId);
        if (historyTopic == null) return error("主题不存在");
        // 节点表(sort_id asc)→ 建 id 索引
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> nodeList = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
        Map<Long, CmsTopicNode> byId = new HashMap<>();
        for (CmsTopicNode n : nodeList) byId.put(n.getId(), n);
        // 完成进度:只认 status==1。到店/凭证 status=0 是独立事实(在 /nodes 有 arrived 展示位),
        // 不得拼进「按完成顺序」的碎片;自玩(activity_id=0)按 topicId 收窄,避免多主题共享探针。
        PlayerNodeProgress progProbe = new PlayerNodeProgress();
        progProbe.setMemberId(memberId);
        progProbe.setActivityId(progActId);
        if (selfPlay) progProbe.setTopicId(tId);
        List<PlayerNodeProgress> doneList = playerNodeProgressService.selectPlayerNodeProgressList(progProbe);
        // 访问判断:只看本人事实。无参与事实(无事前通行证/报名、也无本人进度行)→ 空信,
        // 不泄露任何未获得碎片;有过通行证(哪怕已过期)或报名即历史参与者,不被公开资格挡。
        // 治理处置/违规下架的主题:历史读取不得把处置内容重新开放(与普通下架区分)。
        boolean historyBlocked = playCompletionFacts.topicHistoryBlocked(historyTopic);
        boolean participated = !historyBlocked && (!doneList.isEmpty() || (selfPlay
                ? playCompletionFacts.hasSelfPlayRegistration(memberId, tId) : playCompletionFacts.isRegistered(memberId, progActId)));
        List<CmsTopicNode> completedInOrder = new ArrayList<>();
        if (participated) {
            doneList.sort(Comparator.comparing(PlayerNodeProgress::getCheckinTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            Set<Long> seenNodes = new HashSet<>();
            for (PlayerNodeProgress p : doneList) {
                if (p.getStatus() == null || p.getStatus() != 1) continue;
                if (p.getNodeId() == null || !seenNodes.add(p.getNodeId())) continue;
                CmsTopicNode n = byId.get(p.getNodeId());
                if (n == null) {
                    // 规则16:普通下架(节点 status=0,行仍在)不挡历史参与者读自己的履约内容;
                    // 只对本人已完成节点按 id 复用已存内容,陌生人/未完成玩家仍不可达。
                    n = playCompletionFacts.historyReadableNode(p.getNodeId(), historyTopic);
                }
                if (n != null) completedInOrder.add(n);
            }
        }
        return AjaxResult.success(PlayNarrativeSupport.buildEnding(completedInOrder));
    }

    /**
     * 我已通关的活动列表(广场发布「选择活动」用)：
     * 取当前用户全部节点打卡进度,按 activityId 聚合 done,与主题节点总数比对,
     * 仅返回按主题节点规则已通关的活动,带活动名/封面/主题ID/节点进度。
     */
    public AjaxResult myCompleted(Long actorMemberId, boolean forPublicReference)
    {
        AjaxResult result = myCompleted(actorMemberId);
        if (!forPublicReference || !result.isSuccess()) return result;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> completed = (List<Map<String, Object>>) result.get(AjaxResult.DATA_TAG);
        result.put(AjaxResult.DATA_TAG, communityPostService.filterPublicCompletedReferences(actorMemberId, completed));
        return result;
    }

    public AjaxResult myCompleted(Long actorMemberId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");

        // 当前用户全部打卡进度
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        List<PlayerNodeProgress> all = playerNodeProgressService.selectPlayerNodeProgressList(probe);

        // 完成节点按本人 activity/topic 双维归属并去重，不能把另一主题的旧行计入当前活动。
        Map<String, java.util.LinkedHashSet<Long>> doneByScope = new LinkedHashMap<>();
        java.util.Set<Long> progressActivities = new java.util.LinkedHashSet<>();
        Map<Long, java.util.LinkedHashSet<Long>> selfPlayNodesByTopic = new LinkedHashMap<>();
        for (PlayerNodeProgress p : all) {
            if (p == null || !memberId.equals(p.getMemberId()) || p.getActivityId() == null
                    || p.getActivityId() < 0 || p.getTopicId() == null || p.getTopicId() <= 0
                    || p.getNodeId() == null || p.getNodeId() <= 0) continue;
            // F1:完成事实只认 status==1。mode2 自由收集的到店/凭证行 status=0(markGameDone 0→1),
            // 按全部行数聚合会让「只到店没玩」的主题被列成已完成(与 advanceProgress/nodes/route 同口径)。
            if (p.getStatus() == null || p.getStatus() != 1) continue;
            doneByScope.computeIfAbsent(ITopicRouteRuntimeService.sessionKey(memberId, p.getTopicId(), p.getActivityId()),
                    k -> new java.util.LinkedHashSet<>()).add(p.getNodeId());
            if (p.getActivityId() == 0L) {
                // R10-09:自玩会话 activity_id 恒 0,必须按 topicId 收窄聚合(与 /nodes、advanceProgress 同口径),
                // 否则多主题互相污染;同节点重复行只算一次。
                if (p.getTopicId() != null && p.getNodeId() != null) {
                    selfPlayNodesByTopic.computeIfAbsent(p.getTopicId(), k -> new java.util.LinkedHashSet<>())
                            .add(p.getNodeId());
                }
                continue;
            }
            progressActivities.add(p.getActivityId());
        }

        // 一次批量读取原已付探索票。不能先压成一场一张：首张无效/未完成不应吞掉另一张合法完成票。
        CmsRegistration exploreProbe = new CmsRegistration();
        exploreProbe.setMemberId(memberId); exploreProbe.setOwnerType(2L);
        exploreProbe.setRegistrationStatus(2); exploreProbe.setPaymentStatus(2);
        exploreProbe.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        Map<Long, List<CmsRegistration>> exploreByActivity = new LinkedHashMap<>();
        for (CmsRegistration registration : cmsRegistrationService.selectCmsRegistrationList(exploreProbe)) {
            if (registration != null && registration.getOwnerId() != null && registration.getOwnerId() > 0
                    && playCompletionFacts.completedExplorePassOwnedBy(memberId, registration.getOwnerId(), registration)) {
                exploreByActivity.computeIfAbsent(registration.getOwnerId(), k -> new ArrayList<>()).add(registration);
            }
        }

        // G 的只读窄投影仅补发现来源，不补写 progress、权益或奖励，也不自动启动/切换 runtime。
        List<TopicRouteSession> completedRoutes = completedRouteFactReader.findCurrentCompletedRoutes(memberId);
        Map<String, Integer> completedRouteStatuses = new LinkedHashMap<>();
        java.util.Set<Long> routeActivities = new java.util.LinkedHashSet<>();
        for (TopicRouteSession route : completedRoutes) {
            if (route == null || !memberId.equals(route.getMemberId()) || route.getId() == null
                    || route.getId() <= 0 || route.getActivityId() == null || route.getActivityId() < 0
                    || route.getTopicId() == null || route.getTopicId() <= 0
                    || !Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(route.getStatus())) continue;
            completedRouteStatuses.put(ITopicRouteRuntimeService.sessionKey(memberId, route.getTopicId(), route.getActivityId()),
                    route.getStatus());
            if (route.getActivityId() == 0L) {
                selfPlayNodesByTopic.computeIfAbsent(route.getTopicId(), k -> new java.util.LinkedHashSet<>());
            } else {
                routeActivities.add(route.getActivityId());
            }
        }

        // ★ 驱动集合为「完成节点 ∪ 有效探索票 ∪ 当前已完成个人路线」。
        // ③ 认核销不认打卡，只核销没打卡的玩家也必须进入候选。
        // 连循环都进不去 ⇒ 他永远看不到自己已通关的路线。这是切口径时最容易漏的一处。
        java.util.Set<Long> candidates = new java.util.LinkedHashSet<>(progressActivities);
        candidates.addAll(exploreByActivity.keySet());
        candidates.addAll(routeActivities);

        Map<Long, CmsActivity> candidateActivities = new LinkedHashMap<>();
        List<TopicRouteSession> routeScopes = new ArrayList<>();
        for (Long activityId : candidates) {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
            if (activity == null || activity.getTopicId() == null) continue;
            String actualScope = ITopicRouteRuntimeService.sessionKey(memberId, activity.getTopicId(), activityId);
            if (!doneByScope.containsKey(actualScope) && !completedRouteStatuses.containsKey(actualScope)
                    && !exploreByActivity.containsKey(activityId)) continue;
            candidateActivities.put(activityId, activity);
            TopicRouteSession scope = new TopicRouteSession();
            scope.setMemberId(memberId);
            scope.setTopicId(activity.getTopicId());
            scope.setActivityId(activityId);
            routeScopes.add(scope);
        }
        // 自玩(activity_id=0)路线状态与活动共用同一次 sessionStatuses 批量读回(不新增 N+1)
        for (Long topicId : selfPlayNodesByTopic.keySet()) {
            TopicRouteSession scope = new TopicRouteSession();
            scope.setMemberId(memberId);
            scope.setTopicId(topicId);
            scope.setActivityId(0L);
            routeScopes.add(scope);
        }
        Map<String, Integer> routeStatuses = new LinkedHashMap<>(completedRouteStatuses);
        if (topicRouteRuntimeService != null) {
            // 后续批量读到更新的 RUNNING/ENDED 时覆盖早先候选，不能复活旧局成功。
            routeStatuses.putAll(topicRouteRuntimeService.sessionStatuses(routeScopes));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long activityId : candidates) {
            CmsActivity activity = candidateActivities.get(activityId);
            // 活动候选必须能回到原 topic；自玩在下面的独立 topic 循环投影。
            if (activity == null || activity.getTopicId() == null) continue;
            java.util.Set<Long> completedNodes = doneByScope.get(ITopicRouteRuntimeService.sessionKey(
                    memberId, activity.getTopicId(), activityId));
            int done = completedNodes == null ? 0 : completedNodes.size();

            CmsTopicNode nodeProbe = new CmsTopicNode();
            nodeProbe.setTopicId(activity.getTopicId());
            nodeProbe.setStatus(1L);
            int total = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe).size();

            com.chengyinhub.business.domain.CmsTopic completionTopic =
                    cmsTopicService.selectCmsTopicById(activity.getTopicId());
            Integer routeStatus = routeStatuses.get(ITopicRouteRuntimeService.sessionKey(
                    memberId, activity.getTopicId(), activityId));
            boolean routeCompleted = playCompletionFacts.isCompletedCompletionFact(completionTopic, routeStatus,
                    exploreByActivity.get(activityId), activity.getTopicId(), total, done);
            if (!routeCompleted) {
                continue; // 仅已通关
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activityId", activityId);
            m.put("topicId", activity.getTopicId());
            m.put("dataType", 1);          // 1=活动场次
            m.put("dataId", activityId);   // 统一关联 ID:dataType=1 时指向活动
            m.put("name", activity.getName());
            m.put("cover", activity.getImgUrl());
            m.put("total", total);
            m.put("doneCount", done);
            m.put("completed", true);
            result.add(m);
        }

        // R10-09:自玩(activity_id=0)已完成主题,与活动完成对象并列返回,类型/ID 明确。
        // 完成判定与 /nodes、/route-state、advanceProgress 同口径:分支图看会话状态,线性看主题通关规则。
        // 未完成、主题不可读(含已删/已下架取不到)一律不返回;不伪造名称/封面/activityId。
        for (Map.Entry<Long, java.util.LinkedHashSet<Long>> entry : selfPlayNodesByTopic.entrySet()) {
            Long topicId = entry.getKey();
            int done = entry.getValue().size();
            com.chengyinhub.business.domain.CmsTopic selfTopic = cmsTopicService.selectCmsTopicById(topicId);
            if (selfTopic == null) {
                continue;
            }
            CmsTopicNode selfNodeProbe = new CmsTopicNode();
            selfNodeProbe.setTopicId(topicId);
            selfNodeProbe.setStatus(1L);
            int total = cmsTopicNodeService.selectCmsTopicNodeList(selfNodeProbe).size();

            Integer selfRouteStatus = routeStatuses.get(ITopicRouteRuntimeService.sessionKey(
                    memberId, topicId, 0L));
            boolean selfCompleted = playCompletionFacts.isCompletedCompletionFact(selfTopic, selfRouteStatus, null,
                    topicId, total, done);
            if (!selfCompleted) {
                continue; // 仅已通关
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activityId", null);     // 自玩没有活动场次:明确 null,不伪造
            m.put("topicId", topicId);
            m.put("dataType", 2);          // 2=主题(自玩)
            m.put("dataId", topicId);      // 统一关联 ID:dataType=2 时指向主题
            m.put("name", selfTopic.getName());
            m.put("cover", selfTopic.getImgUrl());
            m.put("total", total);
            m.put("doneCount", done);
            m.put("completed", true);
            result.add(m);
        }
        return AjaxResult.success(result);
    }
}
