package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.util.PlayNarrativeSupport;
import com.chengyinhub.common.core.domain.AjaxResult;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.chengyinhub.common.core.domain.AjaxResult.error;

/** Companion, leaderboard, growth and achievement projections. */
@Service
public class PlayPlayerQueryApplication {
    private static final Logger logger = LoggerFactory.getLogger(PlayPlayerQueryApplication.class);

    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private com.chengyinhub.business.mapper.NpcProfileMapper npcProfileMapper;
    @Autowired
    private com.chengyinhub.business.mapper.NpcLineCacheMapper npcLineCacheMapper;
    @Autowired
    private com.chengyinhub.business.service.IGameSessionRuntimeService gameSessionRuntimeService;
    @Autowired(required = false)
    private com.chengyinhub.business.service.IOrientScoringService orientScoringService;
    @Autowired
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired
    private IPlayerProgressionService playerProgressionService;
    @Autowired
    private com.chengyinhub.business.mapper.MemberCollectibleMapper memberCollectibleMapper;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;

    /**
     * S3 自由定向途中台词(v1 纯模板,零 LLM):按玩家进度(doneCount/total,与 /nodes 同源)挑位置槽
     * (explore_first/explore_middle/explore_last),查 npc_line_cache 已审话术随机取一句气泡文案。
     * profile 解析:活动会话优先取该活动挂的剧本角色(npc_profile scope_type=1,按 sort 取主角);
     * 自玩会话(无活动挂靠)或该活动未配角色时,兜底用全局 NPC(scope_type=3)。
     * 查不到缓存/未配 NPC 时优雅降级为默认文案,不 500(前端气泡幂等只读展示)。
     */
    public AjaxResult companionLine(Long actorMemberId, Long activityId, Long topicId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");

        // 会话解析(镜像 /nodes):活动会话(activityId>0)或自玩会话(topicId,activity_id=0 主题通行证)
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
            if (topicId == null) return error("参数有误");
            tId = topicId;
            progActId = 0L;
        }

        // 进度(与 /nodes 里 doneCount/total 同源)
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        int total = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe).size();

        PlayerNodeProgress doneProbe = new PlayerNodeProgress();
        doneProbe.setMemberId(memberId);
        doneProbe.setActivityId(progActId);
        // 自玩会话 activity_id 全部=0,多主题共享同一探针会互相污染 done 计数,须按 topicId 再收窄
        // (与上面 total 的 topic 口径保持一致);活动会话 activityId 天然一对一绑定主题,无需再加
        if (selfPlay) doneProbe.setTopicId(tId);
        // R10-09 收尾:完成槽位只数 status==1 正式完成。到店/凭证 status=0 在 /nodes 有 arrived
        // 展示位,但不能推进「完成比例」槽位(否则只到店就播 middle/last 档台词)。
        int done = 0;
        for (PlayerNodeProgress doneRow : playerNodeProgressService.selectPlayerNodeProgressList(doneProbe)) {
            if (doneRow.getStatus() != null && doneRow.getStatus() == 1) done++;
        }

        String slot = PlayNarrativeSupport.pickExploreSlot(done, total);

        // profile 解析:活动会话取剧本角色(取 sort 最小=主角),否则/查不到则兜底全局 NPC
        Long profileId = null;
        try {
            if (progActId != null && progActId > 0) {
                List<com.chengyinhub.business.domain.NpcProfile> roles =
                        npcProfileMapper.selectEnabledByScope(1, progActId);
                if (roles != null && !roles.isEmpty()) profileId = roles.get(0).getId();
            }
            if (profileId == null) {
                com.chengyinhub.business.domain.NpcProfile global = npcProfileMapper.selectGlobalNpc();
                if (global != null) profileId = global.getId();
            }
        } catch (Exception ignore) {
            // 查表失败:profileId 留空,走下面的默认文案兜底
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slot", slot);
        data.put("line", defaultCompanionLine(slot));

        if (profileId != null) {
            try {
                List<com.chengyinhub.business.domain.NpcLineCache> caches =
                        npcLineCacheMapper.selectByProfileAndEvent(profileId, slot, null);
                if (caches != null && !caches.isEmpty()) {
                    com.alibaba.fastjson2.JSONArray arr =
                            com.alibaba.fastjson2.JSON.parseArray(caches.get(0).getVariantsJson());
                    if (arr != null && !arr.isEmpty()) {
                        data.put("line", arr.getString(new java.util.Random().nextInt(arr.size())));
                    }
                }
            } catch (Exception ignore) {
                // 查表/解析 variants_json 失败:保留上面的默认兜底文案,不 500
            }
        }
        return AjaxResult.success(data);
    }

    /** S3 途中台词兜底默认文案(查不到 npc_line_cache 或未配 NPC 时使用,别 500) */
    public String defaultCompanionLine(String slot)
    {
        if (PlayNarrativeSupport.SLOT_EXPLORE_MIDDLE.equals(slot)) return "走了一半啦,前面还有好东西";
        if (PlayNarrativeSupport.SLOT_EXPLORE_LAST.equals(slot)) return "就快集齐了,最后一程";
        return "路才刚开始,慢慢逛";
    }

    /**
     * 活动排行榜(升级3):分队竞速/摄影任务/集章任务共用一个口径 ——
     * score=完成节点数(status=1),同数先完成者(最后完成时间早)靠前。
     * activityId 优先(同一活动=同一场比赛);仅 topicId=自玩跨场合计。
     * D-13(N-02):分支/个人玩法主题改按「到达结局 → 结局总用时」排(见 LeaderboardRankPolicy);
     * 排序键完全相同并列同名次。返回结构 {list, me} 不变,只追加字段。
     */
    public AjaxResult leaderboard(Long actorMemberId, String activityId, String topicId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        Long actId = com.chengyinhub.common.core.text.Convert.toLong(activityId, 0L);
        Long tId = com.chengyinhub.common.core.text.Convert.toLong(topicId, 0L);
        if (actId <= 0 && tId <= 0) return error("参数有误");
        Long boardTopicId = tId;
        if (actId > 0) {
            CmsActivity activity = cmsActivityService.selectCmsActivityById(actId);
            if (activity == null || activity.getTopicId() == null) return error("活动不存在");
            if (tId > 0 && !activity.getTopicId().equals(tId)) return error("活动与主题不匹配");
            boardTopicId = activity.getTopicId();
            if (!playCompletionFacts.isRegistered(memberId, actId)) return error("请先报名并完成支付");
            if (gameSessionRuntimeService != null && gameSessionRuntimeService.isRouteLeaderboardHidden(actId)) {
                return error("主理人暂未开放本场排行榜");
            }
        } else if (playCompletionFacts.validSelfPlayPass(memberId, tId) == null) {
            return new AjaxResult(402, "请先购买自玩通行证");
        }
        com.chengyinhub.business.domain.CmsTopic boardTopic = cmsTopicService == null ? null
                : cmsTopicService.selectCmsTopicById(boardTopicId);
        boolean routeRanked = com.chengyinhub.business.service.support.LeaderboardRankPolicy.isRouteRanked(boardTopic);
        Long boardActivityId = actId > 0 ? actId : 0L;
        List<LeaderboardItemVO> list = routeRanked
                ? playerNodeProgressService.selectRouteLeaderboard(boardActivityId, boardTopicId, 50)
                : playerNodeProgressService.selectActivityLeaderboard(
                        actId > 0 ? actId : null, actId > 0 ? null : tId, 50);
        if (list == null) list = new ArrayList<>();
        com.chengyinhub.business.service.support.LeaderboardRankPolicy.assignRanks(list, routeRanked);
        LeaderboardItemVO me = null;
        for (LeaderboardItemVO row : list) {
            if (memberId.equals(row.getMemberId())) me = row;
        }
        if (me == null && routeRanked) {
            // 榜外:取本人最新一局的结局/用时,rank 留空(不猜名次);没开过局只回 0 分
            me = playerNodeProgressService.selectRouteLeaderboardRow(boardActivityId, boardTopicId, memberId);
            if (me == null) {
                me = new LeaderboardItemVO();
                me.setMemberId(memberId);
                me.setScore(0L);
                me.setReachedEnding(false);
            }
            me.setRank(null);
        }
        if (me == null) {
            // 榜外:补我的完成数,rank 留空(不猜名次)
            PlayerNodeProgress probe = new PlayerNodeProgress();
            probe.setMemberId(memberId);
            if (actId > 0) {
                probe.setActivityId(actId);
            } else {
                probe.setActivityId(0L);
                probe.setTopicId(tId);
            }
            long myDone = 0;
            for (PlayerNodeProgress p : playerNodeProgressService.selectPlayerNodeProgressList(probe)) {
                if (p.getStatus() != null && p.getStatus() == 1) myDone++; // 探针无 status 过滤,Java 侧只数完成行
            }
            me = new LeaderboardItemVO();
            me.setMemberId(memberId);
            me.setScore(myDone);
        }
        if (orientScoringService != null) {
            // 排序与名次已由 D-13 口径定完,这里只附加分数展示,不重排;附加失败不拖垮排行榜本身
            try {
                orientScoringService.decorateLeaderboard(boardTopic, boardActivityId, boardTopicId, list, me);
            } catch (RuntimeException scoringUnavailable) {
                logger.warn("城市定向计分附加失败 topicId={} activityId={}", boardTopicId, boardActivityId, scoringUnavailable);
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("me", me);
        data.put("rankMode", routeRanked ? "ROUTE" : "LINEAR");
        return AjaxResult.success(data);
    }

    /**
     * 我的成长 + 徽章墙(P2):等级/累计里程/连续打卡 + 已获徽章列表。
     */
    public AjaxResult growth(Long actorMemberId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");

        // BE-01 勋章单真源:growth 只返游玩进度,勋章统一走 /api/medal/wall(去掉此处重复的 badges/badgeCount)。
        PlayerGrowth g = playerProgressionService.getGrowth(memberId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", g.getLevel());
        data.put("totalCheckins", g.getTotalCheckins());
        data.put("totalMileage", g.getTotalMileage());
        data.put("streakDays", g.getStreakDays());
        return AjaxResult.success(data);
    }

    /**
     * FE-01 成就聚合:XP/等级 + 已收集碎片 + 已收集彩蛋。
     * 城市勋章走 /api/medal/wall、成就徽章走 /api/growth/center(两套概念不合并,前端分区展示)。
     */
    public AjaxResult achievements(Long actorMemberId)
    {
        Long memberId = actorMemberId;
        if (memberId <= 0) return error("请先登录");
        PlayerGrowth g = playerProgressionService.getGrowth(memberId);
        java.util.List<com.chengyinhub.business.domain.MemberCollectible> fragments =
                memberCollectibleMapper.selectListByMember(memberId, com.chengyinhub.business.domain.MemberCollectible.TYPE_FRAGMENT);
        java.util.List<com.chengyinhub.business.domain.MemberCollectible> eggs =
                memberCollectibleMapper.selectListByMember(memberId, com.chengyinhub.business.domain.MemberCollectible.TYPE_EGG);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", g.getLevel());
        data.put("totalCheckins", g.getTotalCheckins());
        data.put("totalMileage", g.getTotalMileage());
        data.put("streakDays", g.getStreakDays());
        data.put("fragmentCount", fragments.size());
        data.put("fragments", fragments);
        data.put("eggCount", eggs.size());
        data.put("eggs", eggs);
        return AjaxResult.success(data);
    }
}
