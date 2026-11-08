package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.domain.vo.PlayerJourneyChoicesVO;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.support.ThemeRuntimeConfig;
import com.chengyinhub.business.service.support.TopicRouteModePolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.chengyinhub.common.core.domain.AjaxResult.error;

/**
 * 本主题真实支持的游玩选择（P4）。
 *
 * <p>配置来源只有一个出口，但按是否已开局分两种：
 * <ul>
 *   <li>有进行中的局 ⇒ 只读该局 {@code config_snapshot_json}（冻结的 {@code themeConfig} /
 *       {@code routeMode} / {@code requiredCapabilities}）；快照缺失即 {@code UNAVAILABLE}，
 *       <b>绝不</b>拿当前发布顶替这一局；</li>
 *   <li>没有进行中的局 ⇒ 读当前已发布修订（{@code PUBLISHED}）。</li>
 * </ul>
 * 未声明的模式不出现；PLAYER 下即使残留 worldMode 也不展示共享世界。
 * {@code team_mode} 只有 {@code cms_topic} 列一个真源，不参与发布/冻结，故标 {@code teamModeSource}。
 * 不新建玩法引擎，不改已开局的局，不按好友关系推算队伍。
 */
@Service
public class PlayerJourneyChoicesApplication {

    static final String SCOPE_PER_MEMBER = "PER_MEMBER";
    static final String SCOPE_PER_TEAM = "PER_TEAM";
    static final String SCOPE_SHARED_WORLD = "SHARED_WORLD";
    static final String RUNTIME_PLAYER = "PLAYER";
    static final String RUNTIME_TEAM = "TEAM";
    static final String WORLD_TEAM_INDEPENDENT = "TEAM_INDEPENDENT";
    static final String WORLD_SHARED = "WORLD_SHARED";
    static final String REASON_TEAM_MODE_DISABLED = "TEAM_MODE_DISABLED";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ICmsTopicService cmsTopicService;
    @Autowired
    private ICmsActivityService cmsActivityService;
    @Autowired
    private PlayCompletionFacts playCompletionFacts;
    @Autowired(required = false)
    private IPlayTeamService playTeamService;
    @Autowired
    private TopicRouteSessionMapper topicRouteSessionMapper;

    public AjaxResult choices(Long actorMemberId, Long activityId, Long topicId) {
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

        boolean participant = selfPlay
                ? playCompletionFacts.hasSelfPlayRegistration(memberId, tId)
                : playCompletionFacts.isRegistered(memberId, actId);
        boolean published = Integer.valueOf(1).equals(topic.getStatus())
                && (topic.getDelFlag() == null || topic.getDelFlag() == 0);
        if (!published && !participant) return error(403, "主题未发布");

        // 已开局按本局冻结配置；冻结缺失 = 不可用，绝不拿当前发布顶替这一局的选择。
        TopicRouteSession session = topicRouteSessionMapper.selectCurrentByOwner(actId, tId, memberId);
        boolean runningRun = session != null
                && Integer.valueOf(TopicRouteSession.STATUS_RUNNING).equals(session.getStatus());
        JsonNode theme;
        String configState;
        if (runningRun) {
            theme = frozenTheme(session);
            configState = theme == null
                    ? PlayerJourneyChoicesVO.CONFIG_UNAVAILABLE : PlayerJourneyChoicesVO.CONFIG_FROZEN_RUN;
        } else {
            theme = publishedTheme(topic);
            configState = PlayerJourneyChoicesVO.CONFIG_PUBLISHED;
        }
        if (PlayerJourneyChoicesVO.CONFIG_UNAVAILABLE.equals(configState)) {
            return AjaxResult.success(unavailableChoices(tId, actId, topic));
        }
        JsonNode game = theme == null ? null : theme.path("gameplayRuntime");
        String runtimeScope = game != null && game.isObject()
                ? game.path("runtimeScope").asText(RUNTIME_PLAYER) : RUNTIME_PLAYER;
        String worldMode = game != null && game.isObject()
                ? game.path("worldMode").asText(null) : null;
        boolean teamModeEnabled = topic.getTeamMode() != null && topic.getTeamMode() != 0;

        PlayerJourneyChoicesVO vo = new PlayerJourneyChoicesVO();
        vo.setTopicId(tId);
        vo.setActivityId(actId);
        vo.setConfigState(configState);
        vo.setRuntimeScope(runtimeScope);
        vo.setWorldMode(worldMode);
        // 路线模式/端能力与运行配置同源：有本局快照就只读快照，不混用新发布。
        vo.setRouteMode(routeMode(configState, session, topic));
        vo.setTeamModeEnabled(teamModeEnabled);
        // team_mode 只有 cms_topic 列这一个真源（不在发布快照里），标签如实说明来源。
        vo.setTeamModeSource(PlayerJourneyChoicesVO.TEAM_MODE_SOURCE_CURRENT_TOPIC);

        List<PlayerJourneyChoicesVO.Choice> choices = new ArrayList<>();
        if (RUNTIME_TEAM.equals(runtimeScope)) {
            PlayerJourneyChoicesVO.Choice shared = new PlayerJourneyChoicesVO.Choice();
            shared.setKey(PlayerJourneyChoicesVO.Choice.SHARED);
            boolean world = WORLD_SHARED.equals(worldMode);
            shared.setProgressScope(world ? SCOPE_SHARED_WORLD : SCOPE_PER_TEAM);
            // 提示事实按会员+局记录（play_attempt_log），共享世界也不把别人的帮助算给本人。
            shared.setHintScope(SCOPE_PER_MEMBER);
            shared.setScoreScope(SCOPE_PER_MEMBER);
            shared.setRequiresPaidTicket(true);
            shared.setRequiresTeam(true);
            // 已开的 TEAM 局不会被后来关闭的 teamMode 判成不可用。
            shared.setAvailable(teamModeEnabled || runningRun);
            if (!teamModeEnabled && !runningRun) shared.setUnavailableReason(REASON_TEAM_MODE_DISABLED);
            int[] slots = questSlots(game);
            shared.setExclusiveQuestSlots(slots[0]);
            shared.setAdditionalQuestSlots(slots[1]);
            shared.setCompetition(world && slots[0] > 0);
            shared.setTeamCapacity(viewerTeam(memberId, actId));
            choices.add(shared);
        } else {
            PlayerJourneyChoicesVO.Choice independent = new PlayerJourneyChoicesVO.Choice();
            independent.setKey(PlayerJourneyChoicesVO.Choice.INDEPENDENT);
            independent.setProgressScope(SCOPE_PER_MEMBER);
            independent.setHintScope(SCOPE_PER_MEMBER);
            independent.setScoreScope(SCOPE_PER_MEMBER);
            independent.setRequiresPaidTicket(true);
            independent.setRequiresTeam(false);
            independent.setAvailable(true);
            independent.setCompetition(false);
            independent.setExclusiveQuestSlots(0);
            independent.setAdditionalQuestSlots(0);
            choices.add(independent);
        }
        vo.setChoices(choices);
        vo.setRequiredCapabilities(requiredCapabilities(configState, session, topic));
        return AjaxResult.success(vo);
    }

    /** 已发布运行配置；没有发布过就返回 null（老主题按 PLAYER 兜底）。 */
    private JsonNode publishedTheme(CmsTopic topic) {
        JsonNode root = ThemeRuntimeConfig.parse(topic.getPublishedThemeRuntimeConfigJson());
        if (root != null && root.has("gameplayRuntime")) return root;
        if (topic.getPublishedRouteGraphJson() == null || topic.getPublishedRouteGraphJson().isBlank()) return null;
        try {
            JsonNode graph = JSON.readTree(topic.getPublishedRouteGraphJson());
            JsonNode theme = graph == null ? null : graph.path("themeConfig");
            return theme != null && theme.isObject() ? theme : null;
        } catch (Exception unusableSnapshot) {
            return null;
        }
    }

    /** 进行中局的冻结主题配置；缺失由调用方判为不可用，不再回落当前发布。 */
    private JsonNode frozenTheme(TopicRouteSession session) {
        if (session.getConfigSnapshotJson() == null || session.getConfigSnapshotJson().isBlank()) return null;
        try {
            JsonNode snapshot = JSON.readTree(session.getConfigSnapshotJson());
            JsonNode theme = snapshot == null ? null : snapshot.path("themeConfig");
            return theme != null && theme.isObject() ? theme : null;
        } catch (Exception unusableSnapshot) {
            return null;
        }
    }

    /** [互斥任务槽数, 额外任务槽数]；只数已发布配置真实存在的槽，不猜默认值。 */
    private int[] questSlots(JsonNode game) {
        Set<String> exclusive = new LinkedHashSet<>();
        Set<String> extra = new LinkedHashSet<>();
        if (game != null && game.path("quests").isArray()) {
            for (JsonNode quest : game.path("quests")) {
                if (quest == null || !quest.isObject()) continue;
                if (!quest.path("extraOnly").asBoolean(false) && quest.path("exclusiveGroup").isTextual()
                        && !quest.path("exclusiveGroup").asText().isBlank()) {
                    exclusive.add(quest.path("exclusiveGroup").asText());
                }
                if (quest.path("extraSlot").isTextual() && !quest.path("extraSlot").asText().isBlank()) {
                    extra.add(quest.path("extraSlot").asText());
                }
            }
        }
        return new int[]{exclusive.size(), extra.size()};
    }

    /** 查看者本场已存在的队伍容量；没有队伍时返回 null，不猜默认人数。 */
    private PlayerJourneyChoicesVO.TeamCapacity viewerTeam(Long memberId, Long activityId) {
        if (playTeamService == null || activityId == null || activityId <= 0L) return null;
        try {
            List<PlayTeam> teams = playTeamService.my(memberId);
            if (teams == null) return null;
            for (PlayTeam team : teams) {
                if (team == null || !Integer.valueOf(2).equals(team.getOwnerType())
                        || !activityId.equals(team.getOwnerId())) continue;
                PlayerJourneyChoicesVO.TeamCapacity capacity = new PlayerJourneyChoicesVO.TeamCapacity();
                capacity.setTeamId(team.getId());
                capacity.setJoinedCount(team.getJoinedCount());
                capacity.setMaxMembers(team.getMaxMembers());
                return capacity;
            }
        } catch (RuntimeException unavailable) {
            return null;
        }
        return null;
    }

    /**
     * 端能力与运行配置同源：本局冻结时只读冻结快照根节点（发布修订原样冻结），
     * 无本局才读当前已发布修订。未声明返回 null（不是空数组冒充"无要求"）。
     */
    private List<PlayerJourneyChoicesVO.Capability> requiredCapabilities(String configState,
                                                                       TopicRouteSession session, CmsTopic topic) {
        String source = PlayerJourneyChoicesVO.CONFIG_FROZEN_RUN.equals(configState)
                ? (session == null ? null : session.getConfigSnapshotJson())
                : topic.getPublishedRouteGraphJson();
        if (source == null || source.isBlank()) return null;
        try {
            JsonNode graph = JSON.readTree(source);
            JsonNode rows = graph == null ? null : graph.path("requiredCapabilities");
            if (rows == null || !rows.isArray()) return null;
            List<PlayerJourneyChoicesVO.Capability> capabilities = new ArrayList<>();
            for (JsonNode row : rows) {
                if (row == null || !row.path("key").isTextual()) continue;
                PlayerJourneyChoicesVO.Capability capability = new PlayerJourneyChoicesVO.Capability();
                capability.setKey(row.path("key").asText());
                capability.setMinVersion(row.path("minVersion").isNumber() ? row.path("minVersion").asInt() : null);
                capabilities.add(capability);
            }
            return capabilities;
        } catch (Exception unusableSnapshot) {
            return null;
        }
    }

    /** 路线模式与运行配置同源：有冻结快照按冻结值，否则按当前已发布。 */
    private String routeMode(String configState, TopicRouteSession session, CmsTopic topic) {
        if (PlayerJourneyChoicesVO.CONFIG_FROZEN_RUN.equals(configState)) {
            return TopicRouteModePolicy.publishedMode(session == null ? null : session.getConfigSnapshotJson());
        }
        return TopicRouteModePolicy.publishedMode(topic.getPublishedRouteGraphJson());
    }

    /**
     * 进行中局的冻结配置不可用：不展示任何可选择模式，也不拿当前发布补齐。
     * team_mode 仍如实报告当前列值（它不是发布快照的一部分）。
     */
    private PlayerJourneyChoicesVO unavailableChoices(Long topicId, Long activityId, CmsTopic topic) {
        PlayerJourneyChoicesVO vo = new PlayerJourneyChoicesVO();
        vo.setTopicId(topicId);
        vo.setActivityId(activityId);
        vo.setConfigState(PlayerJourneyChoicesVO.CONFIG_UNAVAILABLE);
        vo.setConfigUnavailableReason(PlayerJourneyChoicesVO.CONFIG_MISSING_RUN_SNAPSHOT);
        vo.setTeamModeEnabled(topic.getTeamMode() != null && topic.getTeamMode() != 0);
        vo.setTeamModeSource(PlayerJourneyChoicesVO.TEAM_MODE_SOURCE_CURRENT_TOPIC);
        vo.setChoices(new ArrayList<PlayerJourneyChoicesVO.Choice>());
        return vo;
    }
}
