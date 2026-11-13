package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.PlayerRunReportVO;
import com.chengyinhub.business.domain.vo.ScopedLeaderboardEntryVO;
import com.chengyinhub.business.mapper.PlayerRunSessionMapper;
import com.chengyinhub.business.mapper.ScopedLeaderboardMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayJourneyReadService;
import com.chengyinhub.business.service.IPlayerNodeProgressService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * 玩家本局战报（P7）只读投影。
 *
 * <p>事实来源全部是既有的本人事实：有效报名/通行证、{@code player_node_progress}
 * （服务端持久化的 puzzleScore/completionMode）、{@code topic_route_session}（路线终态）、
 * {@code player_run_session}（暂停中的已用时）与 BE-14 quest 榜。本类没有任何写语句：
 * 不建局、不结算、不发奖、不落回执；读战报不会制造通关。
 *
 * <p>成绩口径：只认进度行里服务端写下的属性分。没有持久化分数 ⇒ {@code NOT_SCORED} 且
 * score/penalties 均为 null；不默认 300，不把提示扣的积分余额当成绩。
 */
@Service
public class PlayerRunReportApplication {

    static final String RANK_SCOPE_QUEST = "quest";
    static final String RANK_PERIOD_ALL = "all";
    static final String RANK_BASIS_COMPLETED_NODES = "COMPLETED_NODES";
    static final String RANK_BASIS_NOTE = "COMPLETED_NODES_COUNT_NOT_NET_SCORE";
    static final String SCORE_BASIS_PERSISTED = "SERVER_PERSISTED_PUZZLE_SCORE";

    @Autowired
    private ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired
    private IPlayerNodeProgressService playerNodeProgressService;
    @Autowired
    private TopicRouteSessionMapper topicRouteSessionMapper;
    @Autowired
    private PlayerRunSessionMapper playerRunSessionMapper;
    @Autowired
    private ScopedLeaderboardMapper scopedLeaderboardMapper;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    /** 战报的 teamId（队内榜入口）；测试直接 new 时为 null。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.team.pool.TeamResourcePoolService teamPool;
    @Autowired
    private PlayNodeContext playNodeContext;
    @Autowired
    private ICmsMemberTemplateService cmsMemberTemplateService;
    @Autowired(required = false)
    private IPlayJourneyReadService playJourneyReadService;

    public AjaxResult report(Long actorMemberId, Long activityId, Long topicId) {
        return report(actorMemberId, activityId, topicId, null);
    }

    /**
     * @param runId 可选的本局身份（{@code topic_route_session.id}）。传了就只读这一局，
     *              必须属于本人且与该主题/活动完全一致；不传才回落到最新一局。
     */
    public AjaxResult report(Long actorMemberId, Long activityId, Long topicId, Long runId) {
        Long memberId = actorMemberId;
        if (memberId == null || memberId <= 0L) return error(403, "请先登录");
        boolean selfPlay = (activityId == null || activityId <= 0L) && topicId != null && topicId > 0L;
        if (!selfPlay && (activityId == null || activityId <= 0L)) return error("参数有误");

        Long tId;
        Long actId;
        if (selfPlay) {
            tId = topicId;
            actId = 0L;
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

        // 会话存在但没有实例值时，进度行无法与本局绑定：不查、不用主题历史行凑数。
        boolean attributable = session == null || scope.instanceBound();
        PlayerNodeProgress probe = new PlayerNodeProgress();
        probe.setMemberId(memberId);
        probe.setActivityId(actId);
        if (selfPlay) probe.setTopicId(tId);
        if (scope.instanceBound()) {
            // 局身份沿用既有 play_instance_id，不建第二套 run 主键；重开的局不互相合并。
            probe.setPlayInstanceId(session.getPlayInstanceId());
        }
        List<PlayerNodeProgress> allRows = attributable
                ? playerNodeProgressService.selectPlayerNodeProgressList(probe) : new ArrayList<>();
        if (allRows == null) allRows = new ArrayList<>();
        String boundInstance = probe.getPlayInstanceId();
        if (boundInstance != null) {
            // 即使读侧误返回多局行，也只投影本局实例；同主题重开的两局不合并。
            List<PlayerNodeProgress> ownRun = new ArrayList<>();
            for (PlayerNodeProgress row : allRows) {
                if (row != null && boundInstance.equals(row.getPlayInstanceId())) ownRun.add(row);
            }
            allRows = ownRun;
        }

        boolean participated = session != null || !allRows.isEmpty()
                || (selfPlay ? playCompletionFacts.hasSelfPlayRegistration(memberId, tId)
                             : playCompletionFacts.isRegistered(memberId, actId));
        if (!participated) return error(403, "无权查看该局");
        CmsTopicNode nodeProbe = new CmsTopicNode();
        nodeProbe.setTopicId(tId);
        nodeProbe.setStatus(1L);
        List<CmsTopicNode> activeNodes = cmsTopicNodeService.selectCmsTopicNodeList(nodeProbe);
        if (activeNodes == null) activeNodes = new ArrayList<>();
        Map<Long, CmsTopicNode> nodeById = new HashMap<>();
        for (CmsTopicNode node : activeNodes) nodeById.put(node.getId(), node);

        List<PlayerNodeProgress> doneRows = completedRows(memberId, allRows);
        Set<Long> seen = new LinkedHashSet<>();
        List<PlayerRunReportVO.NodeScore> nodeScores = new ArrayList<>();
        int scoredNodes = 0;
        int historicalNodes = 0;
        int unscoredNodes = 0;
        int scoreSum = 0;
        int penaltySum = 0;
        boolean anyScore = false;
        boolean assisted = false;
        int mode = resolveMode(actId, tId);
        for (PlayerNodeProgress row : doneRows) {
            if (row.getNodeId() == null || !seen.add(row.getNodeId())) continue;
            CmsTopicNode node = nodeById.get(row.getNodeId());
            if (node == null) node = playCompletionFacts.historyReadableNode(row.getNodeId(), topic);
            if (session == null && !nodeById.containsKey(row.getNodeId())) historicalNodes++;
            PlayerRunReportVO.NodeScore view = new PlayerRunReportVO.NodeScore();
            view.setNodeId(row.getNodeId());
            // 有本局快照时节点名/内容只读冻结值；当前节点表在作者改稿后不再代表这一局。
            view.setName(session == null
                    ? (node == null ? null : node.getName())
                    : PlayerRunFrozenView.nodeName(frozen, frozenStations.get(row.getNodeId()), row.getNodeId()));
            view.setCompletionMode(row.getCompletionMode());
            view.setScore(row.getPuzzleScore());
            view.setScored(row.getPuzzleScore() != null);
            view.setCheckinTime(row.getCheckinTime());
            nodeScores.add(view);
            String completionMode = row.getCompletionMode();
            if (PuzzleScorePolicy.COMPLETION_REVEALED.equals(completionMode)
                    || PuzzleScorePolicy.COMPLETION_SKIPPED.equals(completionMode)) {
                assisted = true;
            }
            if (row.getPuzzleScore() == null) {
                unscoredNodes++;
                continue;
            }
            anyScore = true;
            scoredNodes++;
            scoreSum += row.getPuzzleScore();
            Integer base = baseScoreOf(memberId, actId, tId, node, mode,
                    session == null ? null : session.getConfigSnapshotJson());
            if (base != null) penaltySum += Math.max(0, base - row.getPuzzleScore());
        }

        int completed = nodeScores.size();
        int total;
        if (session != null) {
            total = PlayerRunFrozenView.nodeCount(frozen);
            // 老局快照若没有 routeStations/nodeAnswers，分母才回落实时节点表；可归因且冻结节点集存在时绝不补实时值。
            // 无法归因本局的会话（无实例值）连分母也不猜：保持 0 = UNKNOWN。
            if (total == 0 && attributable) total = Math.max(activeNodes.size(), completed);
            if (total > 0) total = Math.max(total, completed);
        } else {
            // 没有本局会话 = 旧局/仅进度事实：明确按主题历史口径展示，不伪造一局。
            total = activeNodes.size() + historicalNodes;
        }
        boolean allDone = total > 0 && completed >= total
                && playCompletionFacts.isTopicCompleted(memberId, actId, tId, total, completed);
        boolean routeCompleted = session != null && Integer.valueOf(TopicRouteSession.STATUS_COMPLETED).equals(session.getStatus());
        boolean routeEnded = session != null && Integer.valueOf(TopicRouteSession.STATUS_ENDED).equals(session.getStatus());
        // 有本局会话时只有该局自己声明的 COMPLETED 才算通关；没有会话才用既有主题完成规则投影历史。
        boolean completedRun = routeCompleted || (session == null && allDone);

        PlayerRunReportVO vo = new PlayerRunReportVO();
        vo.setActivityId(actId);
        vo.setTopicId(tId);
        boolean boundRun = scope.instanceBound();
        vo.setScopeKind(boundRun ? PlayerRunReportVO.SCOPE_RUN : PlayerRunReportVO.SCOPE_LEGACY_TOPIC);
        vo.setProgressScope(session == null ? PlayerRunReportVO.PROGRESS_LEGACY_TOPIC
                : (boundRun ? PlayerRunReportVO.PROGRESS_RUN_INSTANCE
                            : PlayerRunReportVO.PROGRESS_UNATTRIBUTED));
        vo.setRunId(session == null ? null : session.getId());
        vo.setTeamId(PlayRunTeam.of(teamPool, memberId, actId, tId));
        vo.setPlayInstanceId(boundRun ? session.getPlayInstanceId() : null);
        vo.setRunKind(actId > 0L ? "ACTIVITY" : "SELF_PLAY");
        vo.setCompletedNodes(completed);
        vo.setTotalNodes(total);
        vo.setNodes(nodeScores);
        if (completedRun) {
            vo.setRunState(PlayerRunReportVO.RUN_COMPLETED);
            vo.setCompletionKind(assisted ? PlayerRunReportVO.COMPLETION_ASSISTED
                    : PlayerRunReportVO.COMPLETION_LEGITIMATE);
            vo.setLegitimate(!assisted);
            vo.setCompletedAt(session == null ? null : session.getCompletedAt());
        } else if (routeEnded) {
            vo.setRunState(PlayerRunReportVO.RUN_ENDED);
            vo.setCompletionKind(PlayerRunReportVO.COMPLETION_ENDED);
            vo.setLegitimate(false);
            vo.setCompletedAt(null);
        } else if (session != null || !allRows.isEmpty()) {
            vo.setRunState(PlayerRunReportVO.RUN_IN_PROGRESS);
            vo.setCompletionKind(PlayerRunReportVO.COMPLETION_IN_PROGRESS);
            vo.setLegitimate(false);
            vo.setCompletedAt(null);
        } else {
            vo.setRunState(PlayerRunReportVO.RUN_NOT_STARTED);
            vo.setCompletionKind(PlayerRunReportVO.COMPLETION_NOT_STARTED);
            vo.setLegitimate(false);
            vo.setCompletedAt(null);
        }

        applyElapsed(vo, memberId, actId, tId, selfPlay, session, scope.explicit());
        PlayerRunReportVO.Score score = new PlayerRunReportVO.Score();
        if (anyScore) {
            score.setState(PlayerRunReportVO.SCORE_SCORED);
            score.setScore(scoreSum);
            score.setPenalties(penaltySum);
        } else {
            score.setState(PlayerRunReportVO.SCORE_NOT_SCORED);
            score.setScore(null);
            score.setPenalties(null);
            score.setReason(attributable ? "NO_AUTHORITATIVE_BASE_SCORE" : "RUN_INSTANCE_MISSING");
        }
        score.setScoredNodes(scoredNodes);
        score.setUnscoredNodes(unscoredNodes);
        score.setBasis(SCORE_BASIS_PERSISTED);
        vo.setScore(score);
        vo.setRank(rankOf(memberId, tId));
        applyRewards(vo, memberId, tId, actId);
        return AjaxResult.success(vo);
    }

    private List<PlayerNodeProgress> completedRows(Long memberId, List<PlayerNodeProgress> rows) {
        List<PlayerNodeProgress> done = new ArrayList<>();
        for (PlayerNodeProgress row : rows) {
            if (row == null || !memberId.equals(row.getMemberId())) continue;
            if (row.getStatus() == null || row.getStatus() != 1) continue;
            done.add(row);
        }
        done.sort(Comparator.comparing(PlayerNodeProgress::getCheckinTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return done;
    }

    /**
     * 已用时来自 {@code player_run_session}，该表只有 member+activity+topic 作用域，没有局身份。
     * 所以点名历史局时不能借主题级暂停行冒充这一局耗时：只有该作用域最新一局能认领它。
     */
    private void applyElapsed(PlayerRunReportVO vo, Long memberId, Long actId, Long topicId, boolean selfPlay,
                              TopicRouteSession session, boolean explicit) {
        if (explicit && session != null
                && !PlayerRunScope.ownsPauseRow(topicRouteSessionMapper, memberId, actId, topicId, session)) {
            vo.setElapsedSeconds(null);
            vo.setElapsedSource(PlayerRunReportVO.ELAPSED_OTHER_RUN);
            return;
        }
        Map<String, Object> run = playerRunSessionMapper.selectScope(memberId, actId,
                selfPlay ? topicId : null);
        if (run == null) {
            vo.setElapsedSeconds(null);
            vo.setElapsedSource(null);
            return;
        }
        String runState = run.get("runState") == null ? null : String.valueOf(run.get("runState"));
        if ("ENDED".equals(runState)) {
            // clear 会把已用时归零留墓碑；0 不是本局真实耗时，如实返回 UNKNOWN。
            vo.setElapsedSeconds(null);
            vo.setElapsedSource("UNAVAILABLE_AFTER_END");
            return;
        }
        Object raw = run.get("elapsedSeconds");
        if (raw instanceof Number) {
            vo.setElapsedSeconds(((Number) raw).intValue());
            vo.setElapsedSource("PLAYER_RUN_SESSION_PAUSED");
        } else {
            vo.setElapsedSeconds(null);
            vo.setElapsedSource(null);
        }
    }

    private PlayerRunReportVO.Rank rankOf(Long memberId, Long topicId) {
        PlayerRunReportVO.Rank rank = new PlayerRunReportVO.Rank();
        rank.setScope(RANK_SCOPE_QUEST);
        rank.setScopeId(topicId);
        rank.setPeriod(RANK_PERIOD_ALL);
        rank.setScoreBasis(RANK_BASIS_COMPLETED_NODES);
        rank.setBasisNote(RANK_BASIS_NOTE);
        String scopeId = String.valueOf(topicId);
        ScopedLeaderboardEntryVO me = scopedLeaderboardMapper.selectScopedMemberRow(
                RANK_SCOPE_QUEST, scopeId, topicId, null, null, memberId);
        if (me == null) {
            rank.setRank(null);
            rank.setScore(null);
            rank.setQualified(false);
        } else {
            rank.setRank(scopedLeaderboardMapper.selectScopedMemberRank(
                    RANK_SCOPE_QUEST, scopeId, topicId, null, null, memberId));
            rank.setScore(me.getScore());
            rank.setQualified(true);
        }
        rank.setRankedMembers(scopedLeaderboardMapper.countRankedMembers(
                RANK_SCOPE_QUEST, scopeId, topicId, null, null));
        return rank;
    }

    private void applyRewards(PlayerRunReportVO vo, Long memberId, Long topicId, Long actId) {
        // 背包是主题/活动范围内的既有权益，不是"这一局挣到的"；标签如实说明，避免跨局误解。
        vo.setRewardsScope(PlayerRunReportVO.REWARDS_SCOPE_TOPIC_ACTIVITY);
        if (playJourneyReadService == null) {
            vo.setRewards(null);
            vo.setRewardsState("UNAVAILABLE");
            return;
        }
        try {
            vo.setRewards(playJourneyReadService.backpack(memberId, topicId, actId > 0L ? actId : null));
            vo.setRewardsState("AVAILABLE");
        } catch (RuntimeException unavailable) {
            // 背包读侧不可用不阻断战报；只是没有已得奖励可展示，不补造。
            vo.setRewards(null);
            vo.setRewardsState("UNAVAILABLE");
        }
    }

    /** 点名局不存在/越权/错作用域：拒绝并给稳定理由码，绝不回落成当前局。 */
    private static AjaxResult refusal(String reasonCode) {
        AjaxResult result = error(403, PlayerRunScope.REASON_RUN_NOT_FOUND.equals(reasonCode)
                ? "本局不存在或无权查看" : "本局与当前主题/活动不匹配");
        result.put("reasonCode", reasonCode);
        return result;
    }

    private int resolveMode(Long actId, Long topicId) {
        try {
            return playNodeContext.resolveMode(actId, topicId);
        } catch (RuntimeException unresolved) {
            return PlayMode.LINEAR_ORIENTEERING;
        }
    }

    /**
     * 与完成链路同源的基准分：只有线性城市定向 + 模板验真 1/3 才有基准。
     * 冻结答案优先；只有 I2 认定的“答案冻结上线前老局”({@code predatesAnswerFreeze}) 才回落当前模板。
     * 冻结答案存在但模板字段缺失 ⇒ 无权威基准，返回 null（NOT_SCORED），不借实时模板补分数。
     */
    private Integer baseScoreOf(Long memberId, Long actId, Long topicId, CmsTopicNode node, int mode,
            String configSnapshotJson) {
        if (mode != PlayMode.LINEAR_ORIENTEERING) return null;
        try {
            Long nodeId = node == null ? null : node.getId();
            JsonNode frozenAnswer = nodeId == null ? null
                    : playNodeContext.frozenAnswer(memberId, topicId, actId, nodeId);
            CmsMemberTemplate frozenTemplate = NodeJourneySnapshot.contentTemplate(frozenAnswer);
            if (scoredTemplate(frozenTemplate)) return Integer.valueOf(PuzzleScorePolicy.solvedScore(0));
            if (!NodeJourneySnapshot.predatesAnswerFreeze(configSnapshotJson)) return null;
            if (node == null || node.getTemplateId() == null) return null;
            CmsMemberTemplate liveTemplate = cmsMemberTemplateService.selectCmsMemberTemplateById(node.getTemplateId());
            return scoredTemplate(liveTemplate) ? Integer.valueOf(PuzzleScorePolicy.solvedScore(0)) : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private boolean scoredTemplate(CmsMemberTemplate template) {
        if (template == null || template.getValidationMethod() == null) return false;
        int method = template.getValidationMethod().intValue();
        return method == 1 || method == 3;
    }
}
