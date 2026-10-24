package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.TopicCompletionRulePolicy;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 供给侧数据看板（dataScope 网关）。
 * 俱乐部：theme+city 维度——主题完成率 / 节点漏斗 / 参与人数。
 * 商家 store 维度沿用现有 /api/merchant/dashboard。
 */
@RestController
@Tag(name = "供给侧数据看板")
@RequestMapping("/api/stats")
public class ApiStatsController extends BaseController {

    private final TopicCompletionRulePolicy topicCompletionRulePolicy = new TopicCompletionRulePolicy();

    @Autowired
    private IClubService clubService;

    /** [P0-17] ③ 通关口径:认核销不认打卡;批量口径,与玩家数无关。 */
    @Autowired
    private com.chengyinhub.business.service.IPerkLedgerService perkLedgerService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired(required = false)
    private ITopicRouteRuntimeService topicRouteRuntimeService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;

    @Operation(summary = "俱乐部数据看板:主题完成率+节点漏斗")
    @PostMapping("/club")
    public AjaxResult club(@RequestBody(required = false) Map<String, Object> body) {
        Long memberId;
        try { memberId = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        Long clubId = positiveLong(body == null ? null : body.get("clubId"));
        if (clubId == null) return error("缺少俱乐部ID");
        Club club = clubService.selectClubById(clubId);
        if (club == null || club.getId() == null || club.getMemberId() == null
                || !memberId.equals(club.getMemberId())
                || !Long.valueOf(1L).equals(club.getStatus())
                || !Integer.valueOf(0).equals(club.getDelFlag())) {
            return error("仅当前俱乐部主理人可查看数据看板");
        }

        // 只统计本次显式选中的俱乐部；不再按 owner memberId 汇总其他俱乐部。
        CmsTopic topicProbe = new CmsTopic();
        topicProbe.setClubId(clubId);
        topicProbe.setActiveClubOnly(true);
        List<CmsTopic> topics = cmsTopicService.selectCmsTopicList(topicProbe);
        if (topics == null) return error("俱乐部数据暂时不可用");

        List<Map<String, Object>> topicStats = new ArrayList<>();
        int sumParticipants = 0, sumCompleted = 0;

        for (CmsTopic t : topics) {
            // 主题节点
            CmsTopicNode nodeProbe = new CmsTopicNode();
            nodeProbe.setTopicId(t.getId());
            nodeProbe.setStatus(1L);
            List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
            int totalNodes = nodes.size();

            // 该主题的全部游玩进度
            PlayerNodeProgress progProbe = new PlayerNodeProgress();
            progProbe.setTopicId(t.getId());
            List<PlayerNodeProgress> progs = playerNodeProgressService.selectPlayerNodeProgressList(progProbe);

            // 按玩家去重统计已完成节点；按节点统计完成人次（漏斗）
            // R10-09 收尾:通关分子只认 status==1 正式完成;status=0 到店/凭证行只算参与与漏斗,
            // 不得把「只到店没玩」的成员算成已通关(与 /nodes、advanceProgress 同口径)。
            Map<Long, Set<Long>> doneByMember = new HashMap<>();
            Map<Long, Set<Long>> completedNodesByMember = new HashMap<>();
            Map<Long, Integer> doneByNode = new HashMap<>();
            for (PlayerNodeProgress p : progs) {
                doneByMember.computeIfAbsent(p.getMemberId(), k -> new HashSet<>()).add(p.getNodeId());
                doneByNode.merge(p.getNodeId(), 1, Integer::sum);
                if (Integer.valueOf(1).equals(p.getStatus())) {
                    completedNodesByMember.computeIfAbsent(p.getMemberId(), k -> new HashSet<>())
                            .add(p.getNodeId());
                }
            }
            // [P0-17] ③ 认核销不认打卡。
            //
            // ★ 这是唯一「一次算 M 玩家 × N 主题」的场景:手上既没 registrationId,也**没有任何一个
            //   member 是固定的** ⇒ 前两处的批量探针口径彻底不适用,循环调 isExploreCompleted
            //   = 2 × M玩家 × N主题(20 主题 × 200 玩家 = 8000 次)会把看板拖垮。
            //   ⇒ 必须走批量 SQL:**每个 ③ 主题恒 2 次查询,与玩家数无关**。
            //
            // ★ participants 必须与 completed 取**同一口径**,否则 completionRate 会算出畸形值:
            //   - 只核销没打卡的 ③ 玩家 ⇒ participants=0 / completed=1 ⇒ rate(1,0)=0
            //     ⇒ 看板显示「通关 1 人、完成率 0%」(rate 分母 0 返 0);
            //   - 1 人打卡、2 人核销通关 ⇒ rate(2,1) = **200%**。
            //   ⇒ ③ 主题的 participants 取「打卡 ∪ 核销」并集(核销侧那半个集合由同一次批量查询顺带返回,
            //     零额外成本)。
            //
            // ⚠️ funnel 不受影响:它按节点计打卡人次,与通关口径无关,别动。
            boolean isExplore = t.getProductType() != null
                    && t.getProductType() == ProductType.FREE_EXPLORE;
            Set<Long> routeParticipants = topicRouteRuntimeService == null
                    ? Collections.<Long>emptySet() : topicRouteRuntimeService.participantMemberIds(t.getId());
            Set<Long> routeCompleted = topicRouteRuntimeService == null
                    ? Collections.<Long>emptySet() : topicRouteRuntimeService.completedMemberIds(t.getId());
            Set<Long> allParticipants = new HashSet<>(doneByMember.keySet());
            allParticipants.addAll(routeParticipants);
            boolean freeLinear = isExplore && !com.chengyinhub.business.service.support.TopicRouteModePolicy
                    .isEffectiveBranchGraph(t, null);
            Set<Long> allCompleted = freeLinear ? new HashSet<>() : new HashSet<>(routeCompleted);
            if (!isExplore) {
                for (Map.Entry<Long, Set<Long>> entry : completedNodesByMember.entrySet()) {
                    // 有冻结分支 session 的玩家只认 session，避免按全节点数把未完成分支误判完赛。
                    if (!routeParticipants.contains(entry.getKey()) && topicCompletionRulePolicy.isCompleted(
                            t.getCompleteRuleJson(), totalNodes, entry.getValue().size())) {
                        allCompleted.add(entry.getKey());
                    }
                }
            } else {
                // ③:**一次**批量查询同时拿分子与分母(恒 2 次查询/主题,与玩家数无关)。
                // ⚠️ 别拆成两个方法各查一遍 —— 那是 4 次查询、2 次重复,还给了「只更新一个」的口子。
                IPerkLedgerService.ExploreCompletion ec = perkLedgerService.exploreCompletion(t.getId());
                allParticipants.addAll(ec.participants);
                for (Long completedMemberId : ec.completed) {
                    if (freeLinear || !routeParticipants.contains(completedMemberId)) allCompleted.add(completedMemberId);
                }
            }
            int participants = allParticipants.size();
            int completed = allCompleted.size();
            sumParticipants += participants;
            sumCompleted += completed;

            // 节点漏斗（按 sortId 顺序）
            List<Map<String, Object>> funnel = new ArrayList<>();
            for (CmsTopicNode n : nodes) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("nodeId", n.getId());
                fm.put("name", n.getName());
                fm.put("sortId", n.getSortId());
                fm.put("doneCount", doneByNode.getOrDefault(n.getId(), 0));
                funnel.add(fm);
            }

            Map<String, Object> ts = new LinkedHashMap<>();
            ts.put("topicId", t.getId());
            ts.put("name", t.getName());
            ts.put("nodeCount", totalNodes);
            ts.put("participants", participants);
            ts.put("completed", completed);
            ts.put("completionRate", rate(completed, participants));
            ts.put("funnel", funnel);
            topicStats.add(ts);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("clubId", clubId);
        data.put("topicCount", topics.size());
        data.put("participants", sumParticipants);
        data.put("completed", sumCompleted);
        data.put("overallRate", rate(sumCompleted, sumParticipants));
        data.put("topics", topicStats);
        return AjaxResult.success(data);
    }

    private Long positiveLong(Object value) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 完成率百分比，保留一位小数;无参与者时为 null(D4:端上显示「—」,不再给 0%) */
    private Double rate(int part, int total) {
        return com.chengyinhub.common.utils.SafeRatio.percent(part, total, 1);
    }
}
