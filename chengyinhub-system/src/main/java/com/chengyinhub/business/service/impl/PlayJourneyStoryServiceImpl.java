package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.TopicRouteSessionScope;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.business.domain.JourneyStory;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.service.IPlayEncounterService;
import com.chengyinhub.business.service.IPlayJourneyStoryService;
import com.chengyinhub.business.service.support.NodeJourneySnapshot;
import com.chengyinhub.business.service.support.JourneyInitialState;
import com.chengyinhub.business.service.support.ThemeStateKeyService;
import com.chengyinhub.business.service.support.TopicRouteEngine;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主题级剧情配置的玩家只读求值(R8-STORY)。
 *
 * <p>裁决沿用共享契约 v3/v4:一局状态只在 {@code topic_route_session.state_json}
 * ({@link TopicRouteEngine.State} 的 variables / tags / completedNodeIds),一个会员一个主题只有一局;
 * 条件一律走 {@link TopicRouteEngine#conditionsPass}。只读,不写任何表。
 *
 * <p>stages[].chapterId 属于本主题与 endings/badges 恰好一个 fallback 等配置约束在保存期与
 * 发布期已拦(JourneyStoryValidator);这里只做运行期求值。
 */
@Service
public class PlayJourneyStoryServiceImpl implements IPlayJourneyStoryService {

    private static final Logger LOG = LoggerFactory.getLogger(PlayJourneyStoryServiceImpl.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TopicRouteEngine ENGINE = new TopicRouteEngine();

    private static final String SYS_HP = "sys.hp";
    private static final String SYS_LUCK = "sys.luck";
    private static final String TAG_PREFIX = "tag.";

    private final CmsTopicMapper topicMapper;
    private final CmsTopicChapterMapper chapterMapper;
    private final CmsTopicNodeMapper nodeMapper;
    private final TopicRouteSessionMapper sessionMapper;
    private final ThemeStateKeyService themeStateKeyService;
    private final NpcProfileService npcProfileService;

    /**
     * M1 回执自带新世界:阶段结算回包追加本局 stateVersion 与这一站刷新后的 Encounter。
     * 可选注入:直接 new 的单测/缺装配时不追加,既有行为不变。
     */
    @Autowired(required = false)
    private IPlayEncounterService playEncounterService;

    /** 结局页「这一生的档案」读本局各站的个人玩法会话;可选注入,缺装配时结局照常只是没有档案。 */
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.GameRuntimeSessionMapper gameSessionMapper;

    @Autowired
    public PlayJourneyStoryServiceImpl(CmsTopicMapper topicMapper, CmsTopicChapterMapper chapterMapper,
                                       CmsTopicNodeMapper nodeMapper, TopicRouteSessionMapper sessionMapper,
                                       ThemeStateKeyService themeStateKeyService,
                                       NpcProfileService npcProfileService) {
        this.topicMapper = topicMapper;
        this.chapterMapper = chapterMapper;
        this.nodeMapper = nodeMapper;
        this.sessionMapper = sessionMapper;
        this.themeStateKeyService = themeStateKeyService;
        this.npcProfileService = npcProfileService;
    }

    @Override
    public Map<String, Object> stageEnd(Long memberId, Long topicId, Long activityId, Long chapterId) {
        if (memberId == null || memberId <= 0L) {
            throw new ServiceException("请先登录");
        }
        if (chapterId == null || chapterId <= 0L) {
            throw new ServiceException("参数有误");
        }
        CmsTopic topic = requireTopic(topicId);
        // 剧情优先读本局建局冻结值(R11-A);老局快照没有冻结键时回退实时主题配置。
        TopicRouteSession session = TopicRouteSessionScope.find(sessionMapper, memberId, topicId, activityId);
        JourneyStory story = requireStory(topic, session);
        if (story.getStages() == null || story.getStages().isEmpty()) {
            throw new ServiceException("本主题未配置剧情阶段");
        }
        JourneyStory.Stage stage = null;
        for (JourneyStory.Stage item : story.getStages()) {
            if (chapterId.equals(item.getChapterId())) {
                stage = item;
                break;
            }
        }
        if (stage == null) {
            throw new ServiceException("本章节未配置阶段结算");
        }
        CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(chapterId);
        if (chapter == null || !topicId.equals(chapter.getTopicId())) {
            throw new ServiceException("章节不存在");
        }
        requireSession(session, memberId);
        TopicRouteEngine.State state = readState(session);
        assertChapterNodesCompleted(topicId, chapterId, state);

        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("changed", passingTexts(stage.getEnd() == null ? null : stage.getEnd().getChanged(), state));
        view.put("open", stage.getEnd() == null ? null : stage.getEnd().getOpen());
        view.put("costs", costs(topicId, state, rulesOf(topic, session), session == null ? null : session.getConfigSnapshotJson()));
        view.put("hp", hpOf(state, rulesOf(topic, session)));
        view.put("luck", luckOf(state, rulesOf(topic, session)));
        view.put("stateVersion", session.getVersion());
        Map<String, Object> encounter = encounterView(memberId, topicId, session.getActivityId(), session.getCurrentNodeId());
        if (encounter != null) view.put("encounter", encounter);
        return view;
    }

    /** M1:这一拍的 Encounter 由只读解析统一组装;解析不可用(节点没配玩法)时缺省不追加。 */
    private Map<String, Object> encounterView(Long memberId, Long topicId, Long activityId, Long nodeId) {
        if (playEncounterService == null || nodeId == null || nodeId <= 0L) return null;
        try {
            return playEncounterService.resolve(memberId, topicId, activityId, nodeId);
        } catch (ServiceException unusable) {
            // R22-SEC S6:方向不变(缺省不追加,动作本身已成立),但缺 Encounter 要留一条 warn ——
            // 阶段结算回包少一段,排查时至少能看见为什么。
            LOG.warn("阶段结算回包缺 Encounter(memberId={}, topicId={}, nodeId={}):{}",
                    memberId, topicId, nodeId, unusable.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> ending(Long memberId, Long topicId, Long activityId) {
        if (memberId == null || memberId <= 0L) {
            throw new ServiceException("请先登录");
        }
        CmsTopic topic = requireTopic(topicId);
        // 剧情优先读本局建局冻结值(R11-A);老局快照没有冻结键时回退实时主题配置。
        TopicRouteSession session = TopicRouteSessionScope.find(sessionMapper, memberId, topicId, activityId);
        if (session != null && session.getStatus() != null && session.getStatus() != TopicRouteSession.STATUS_RUNNING) {
            TopicRouteEngine.State frozenState = readState(session);
            if (frozenState.getThemeRuntime().has("result")) {
                JsonNode archived = frozenState.getThemeRuntime().get("result");
                // 缺键旧局仍走下方兼容路径；显式损坏值不能冒充可用结局。
                // endingId 在无候选的合法终态中可以缺省，不要求新版本字段齐全。
                if (!archived.isObject() || archived.size() == 0) {
                    throw new ServiceException("本局结局快照不可用");
                }
                Map<String,Object> result = JSON.convertValue(archived, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                result.put("code",result.get("endingId")); return result;
            }
        }
        JourneyStory story = requireStory(topic, session);
        JourneyStory.Ending ending = story.getEnding();
        if (ending == null) {
            throw new ServiceException("本主题未配置结局");
        }
        requireSession(session, memberId);
        if (session.getStatus() == null
                || session.getStatus().intValue() != TopicRouteSession.STATUS_COMPLETED) {
            throw new ServiceException("本局尚未完成");
        }
        TopicRouteEngine.State state = readState(session);

        return evaluateEnding(topicId,story,state,topic,session,memberId,true);
    }

    /** 同一结局求值用于结束事务的不可变存档；不要求已落库为完成状态。 */
    public Map<String,Object> freezeEnding(Long topicId,String raw,TopicRouteEngine.State state) {
        return freezeEnding(topicId, raw, state, null);
    }

    /** 终态归档沿用本局正文及数值快照；三参旧入口仍保留实时兼容读取。 */
    public Map<String,Object> freezeEnding(Long topicId,String raw,TopicRouteEngine.State state,
                                           TopicRouteSession session) {
        JourneyStory story=JourneyStory.parse(raw);
        if(story==null || story.getEnding()==null)return java.util.Collections.emptyMap();
        return evaluateEnding(topicId,story,state,topicMapper.selectCmsTopicById(topicId),session,
                session == null ? null : session.getMemberId(),false);
    }
    private Map<String,Object> evaluateEnding(Long topicId,JourneyStory story,TopicRouteEngine.State state,CmsTopic topic,TopicRouteSession session,Long memberId,boolean includeArchive) {
        JourneyStory.Ending ending=story.getEnding();
        JourneyStory.EndingCase chosen = pickEnding(ending.getEndings(), state);
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("code", chosen == null ? null : chosen.getCode());
        view.put("title", chosen == null ? null : chosen.getTitle());
        view.put("summary", chosen == null ? null : chosen.getSummary());
        view.put("epilogues", epilogues(ending.getEpilogues(), topicId, state));
        view.put("exhibits", exhibitTexts(ending.getExhibits(), state));
        view.put("badge", badgeView(ending.getBadges(), state));
        view.put("costs", costs(topicId, state, rulesOf(topic, session), session == null ? null : session.getConfigSnapshotJson()));
        int[] found = foundClues(ending.getOptionalClues(), state);
        view.put("found", found[0]);
        view.put("total", found[1]);
        if(includeArchive && session != null) view.put("archive", archive(memberId, topicId, session));
        view.put("chapter", endingChapter(topicId, chosen, state, session));
        return view;
    }

    /** 新快照只读取本局结局正文；缺键的历史局保留既有读取行为。 */
    Map<String, Object> endingChapter(Long topicId, JourneyStory.EndingCase chosen,
                                      TopicRouteEngine.State state, TopicRouteSession session) {
        if (chosen == null || chosen.getChapterId() == null) return null;
        if (session != null && StringUtils.isNotEmpty(session.getConfigSnapshotJson())) {
            try {
                JsonNode snapshot = JSON.readTree(session.getConfigSnapshotJson());
                if (snapshot != null && snapshot.has("endingChapters")) {
                    JsonNode chapter = snapshot.path("endingChapters").path(String.valueOf(chosen.getChapterId()));
                    if (!chapter.isObject() || !chapter.path("blocks").isArray()) return null;
                    List<Object> blocks = JSON.convertValue(chapter.path("blocks"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
                    Map<String, Object> out = new LinkedHashMap<String, Object>();
                    out.put("id", chosen.getChapterId());
                    for (String field : new String[]{"name", "atmospherePreset", "audioUrl"})
                        out.put(field, chapter.path(field).isNull() ? null : chapter.path(field).asText(null));
                    out.put("blocks", com.chengyinhub.business.util.ChapterBlockReadSupport.projectForPlayer(
                            blocks, new java.util.HashSet<Long>(state.getCompletedNodeIds()),
                            new java.util.HashSet<String>(state.getTags())));
                    return out;
                }
            } catch (Exception unreadable) {
                throw new ServiceException("本局结局快照不可用");
            }
        }
        return endingChapter(topicId, chosen, state);
    }

    /**
     * 结局章(2026-09-24):命中的结局指向一章时,把那一章的正文块按本局状态投影给玩家
     * (条件正文 / 内心声音按本局标签取舍,与故事流同一套口径)。老数据没有 chapterId,
     * 或那一章已不属于本主题,返回 null —— 玩家端退回只出标题 + 一句话。
     */
    Map<String, Object> endingChapter(Long topicId, JourneyStory.EndingCase chosen, TopicRouteEngine.State state) {
        if (chosen == null || chosen.getChapterId() == null || chapterMapper == null) return null;
        com.chengyinhub.business.domain.CmsTopicChapter chapter = chapterMapper.selectCmsTopicChapterById(chosen.getChapterId());
        if (chapter == null || !topicId.equals(chapter.getTopicId())) return null;
        List<Object> blocks = com.chengyinhub.business.util.ChapterBlockReadSupport.projectForPlayer(
                com.chengyinhub.business.util.ChapterBlockReadSupport.resolve(chapter, java.util.Collections.<com.chengyinhub.business.domain.CmsTopicNode>emptyList()),
                new java.util.HashSet<Long>(state.getCompletedNodeIds()), new java.util.HashSet<String>(state.getTags()));
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", chapter.getId());
        out.put("name", chapter.getName());
        out.put("atmospherePreset", chapter.getAtmospherePreset());
        out.put("audioUrl", chapter.getAudioUrl());
        out.put("blocks", blocks);
        return out;
    }

    /**
     * 「这一生的档案」(2026-09-24 用户:结局要回收这一局)。只读玩家本人在本局各站留下的东西:
     * 建档答案(选择题回写成选项文案)、拍下的照片、写下的话。数值 / 标签这类暗的状态一律不露。
     */
    Map<String, Object> archive(Long memberId, Long topicId, TopicRouteSession session) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> identity = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> photos = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> notes = new ArrayList<Map<String, Object>>();
        out.put("identity", identity);
        out.put("photos", photos);
        out.put("notes", notes);
        if (gameSessionMapper == null || session == null) return out;
        Long activityId = session.getActivityId() == null ? Long.valueOf(0L) : session.getActivityId();
        List<com.chengyinhub.business.domain.GameRuntimeSession> rows =
                gameSessionMapper.selectPlayerSessionsByTopic(activityId, topicId, memberId);
        if (rows == null) return out;
        for (com.chengyinhub.business.domain.GameRuntimeSession row : rows) {
            JsonNode config;
            JsonNode state;
            try {
                config = JSON.readTree(StringUtils.defaultString(row.getConfigSnapshotJson(), "{}"));
                state = JSON.readTree(StringUtils.defaultString(row.getStateJson(), "{}"));
            } catch (Exception unreadable) {
                continue;
            }
            JsonNode profile = state.path("profile");
            if (profile.path("done").asBoolean(false)) {
                String avatar = profile.path("avatarUrl").asText("");
                if (!avatar.isEmpty() && !out.containsKey("avatarUrl")) out.put("avatarUrl", avatar);
                for (JsonNode question : config.path("profile").path("questions")) {
                    String answer = profile.path("answers").path(question.path("key").asText()).asText("");
                    if (answer.isEmpty()) continue;
                    for (JsonNode option : question.path("options")) {
                        if (answer.equals(option.path("key").asText())) { answer = option.path("label").asText(answer); break; }
                    }
                    Map<String, Object> one = new LinkedHashMap<String, Object>();
                    one.put("label", question.path("label").asText(""));
                    one.put("value", answer);
                    identity.add(one);
                }
            }
            addPhoto(photos, state.path("photoCheck").path("lastUrl").asText(""), config.path("photoCheck").path("title").asText(""));
            addPhoto(photos, state.path("qa").path("lastImageUrl").asText(""), config.path("qa").path("title").asText(""));
            String mine = state.path("note").path("text").asText("");
            if (!mine.isEmpty()) {
                Map<String, Object> note = new LinkedHashMap<String, Object>();
                note.put("title", config.path("note").path("title").asText(""));
                note.put("text", mine);
                notes.add(note);
            }
        }
        return out;
    }

    private static void addPhoto(List<Map<String, Object>> photos, String url, String title) {
        if (url == null || url.isEmpty()) return;
        Map<String, Object> photo = new LinkedHashMap<String, Object>();
        photo.put("url", url);
        photo.put("title", title);
        photos.add(photo);
    }

    // ---------- 本局定位与状态 ----------

    private CmsTopic requireTopic(Long topicId) {
        if (topicId == null || topicId <= 0L) {
            throw new ServiceException("参数有误");
        }
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag())) {
            throw new ServiceException("主题不存在");
        }
        return topic;
    }

    /**
     * 本局适用的主题剧情:建局冻结的 journey_story_json 优先(R11-A),老快照没有冻结键时
     * 回退实时主题配置。快照里冻结空串 = 建局时没配剧情,不回退。
     */
    private JourneyStory requireStory(CmsTopic topic, TopicRouteSession session) {
        String frozen = NodeJourneySnapshot.journeyStory(
                session == null ? null : session.getConfigSnapshotJson());
        String raw = frozen != null ? frozen : topic.getJourneyStoryJson();
        JourneyStory story;
        try {
            story = JourneyStory.parse(raw);
        } catch (RuntimeException e) {
            throw new ServiceException("本主题剧情配置不可用");
        }
        if (story == null) {
            throw new ServiceException("本主题未配置剧情");
        }
        return story;
    }

    /** 一个会员一个主题只有一局;查不到 = 还没开始,不是「无内容可读」。 */
    private void requireSession(TopicRouteSession session, Long memberId) {
        if (session == null) {
            throw new ServiceException("本局尚未开始");
        }
        if (session.getMemberId() != null && !memberId.equals(session.getMemberId())) {
            throw new ServiceException("无权访问这一局");
        }
    }

    private TopicRouteEngine.State readState(TopicRouteSession session) {
        if (StringUtils.isBlank(session.getStateJson())) {
            return new TopicRouteEngine.State();
        }
        try {
            TopicRouteEngine.State state = JSON.readValue(session.getStateJson(), TopicRouteEngine.State.class);
            return state == null ? new TopicRouteEngine.State() : state;
        } catch (Exception e) {
            throw new ServiceException("本局状态不可用");
        }
    }

    /** 本章节启用节点全部完成才可取结算;章节下没有启用节点时视为无可阻挡。 */
    private void assertChapterNodesCompleted(Long topicId, Long chapterId, TopicRouteEngine.State state) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setChapterId(chapterId);
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = nodeMapper.selectCmsTopicNodeList(probe);
        if (nodes == null) {
            return;
        }
        for (CmsTopicNode node : nodes) {
            if (node.getId() != null && !state.getCompletedNodeIds().contains(node.getId())) {
                throw new ServiceException("本章节还有站点未完成");
            }
        }
    }

    // ---------- 求值 ----------

    /** changed[]:when 通过(null = 无条件)的 text,按配置顺序全量下发。 */
    private List<String> passingTexts(List<JourneyStory.Change> changes, TopicRouteEngine.State state) {
        List<String> texts = new ArrayList<String>();
        if (changes == null) {
            return texts;
        }
        for (JourneyStory.Change change : changes) {
            if (change == null) {
                continue;
            }
            if (conditionsPass(change.getWhen(), state)) {
                texts.add(change.getText());
            }
        }
        return texts;
    }

    /** 结局按配置顺序取第一个 when 全通过的;都不中取 fallback(保存期已保证恰好一条)。 */
    private JourneyStory.EndingCase pickEnding(List<JourneyStory.EndingCase> endings,
                                              TopicRouteEngine.State state) {
        if (endings == null) {
            return null;
        }
        JourneyStory.EndingCase fallback = null;
        for (JourneyStory.EndingCase item : endings) {
            if (item == null) {
                continue;
            }
            if (Boolean.TRUE.equals(item.getFallback())) {
                fallback = item;
                continue;
            }
            if (conditionsPass(item.getWhen(), state)) {
                return item;
            }
        }
        return fallback;
    }

    /** 尾声按 relationKey 指向的变量值选句:≥1 ally、=0 或缺 neutral、≤-1 guarded。 */
    private List<Map<String, Object>> epilogues(List<JourneyStory.Epilogue> epilogues, Long topicId,
                                                TopicRouteEngine.State state) {
        List<Map<String, Object>> view = new ArrayList<Map<String, Object>>();
        if (epilogues == null) {
            return view;
        }
        Map<Long, String> names = npcNames(topicId);
        for (JourneyStory.Epilogue item : epilogues) {
            if (item == null || item.getNpcId() == null) {
                continue;
            }
            String relation = relationState(state, item.getRelationKey());
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("npcId", item.getNpcId());
            row.put("name", names.get(item.getNpcId()));
            row.put("state", relation);
            row.put("line", "ally".equals(relation) ? item.getAlly()
                    : "guarded".equals(relation) ? item.getGuarded() : item.getNeutral());
            view.add(row);
        }
        return view;
    }

    private String relationState(TopicRouteEngine.State state, String relationKey) {
        if (relationKey == null || state.getVariables() == null) {
            return "neutral";
        }
        Number value = state.getVariables().get(relationKey);
        if (value == null) {
            return "neutral";
        }
        double number = value.doubleValue();
        if (number >= 1d) {
            return "ally";
        }
        if (number <= -1d) {
            return "guarded";
        }
        return "neutral";
    }

    /** exhibits:when 通过(null = 无条件)的 text 全量下发。 */
    private List<String> exhibitTexts(List<JourneyStory.Exhibit> exhibits, TopicRouteEngine.State state) {
        List<String> texts = new ArrayList<String>();
        if (exhibits == null) {
            return texts;
        }
        for (JourneyStory.Exhibit item : exhibits) {
            if (item == null) {
                continue;
            }
            if (conditionsPass(item.getWhen(), state)) {
                texts.add(item.getText());
            }
        }
        return texts;
    }

    /** badge 与结局同口径:第一个 when 全通过的,否则 fallback。 */
    private Map<String, Object> badgeView(List<JourneyStory.Badge> badges, TopicRouteEngine.State state) {
        if (badges == null) {
            return null;
        }
        JourneyStory.Badge fallback = null;
        for (JourneyStory.Badge item : badges) {
            if (item == null) {
                continue;
            }
            if (Boolean.TRUE.equals(item.getFallback())) {
                fallback = item;
                continue;
            }
            if (conditionsPass(item.getWhen(), state)) {
                return badgeRow(item);
            }
        }
        return badgeRow(fallback);
    }

    private Map<String, Object> badgeRow(JourneyStory.Badge badge) {
        if (badge == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("title", badge.getTitle());
        return row;
    }

    /** found = optionalClues 中本局已持有的个数(变量 ≥1);total = 其长度。 */
    private int[] foundClues(List<String> optionalClues, TopicRouteEngine.State state) {
        if (optionalClues == null || optionalClues.isEmpty()) {
            return new int[]{0, 0};
        }
        int found = 0;
        for (String key : optionalClues) {
            Number value = state.getVariables() == null ? null : state.getVariables().get(key);
            if (value != null && value.doubleValue() >= 1d) {
                found++;
            }
        }
        return new int[]{found, optionalClues.size()};
    }

    /** 本局的 tag.* 花费:只下发在主题注册表里注册过的;标签为空回落键名去掉前缀的可读名(R22-C3)。 */
    private List<Map<String, Object>> costs(Long topicId, TopicRouteEngine.State state, JourneyRules rules, String snapshot) {
        List<Map<String, Object>> view = new ArrayList<Map<String, Object>>();
        if (state.getTags() == null || state.getTags().isEmpty()) {
            return view;
        }
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (ThemeStateKey row : JourneyInitialState.visibleKeys(themeStateKeyService.listByTopic(topicId), rules, snapshot)) {
            if (row == null || StringUtils.isBlank(row.getStateKey())) {
                continue;
            }
            labels.put(row.getStateKey(), row.displayLabel());
        }
        for (String tag : state.getTags()) {
            if (tag == null || !tag.startsWith(TAG_PREFIX)) {
                continue;
            }
            String label = labels.get(tag);
            if (label == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("tag", tag);
            row.put("label", label);
            view.add(row);
        }
        return view;
    }

    /** 本主题各节点可出场 NPC 的 id→name,口径与 Encounter 的节点出场一致。 */
    private Map<Long, String> npcNames(Long topicId) {
        Map<Long, String> names = new LinkedHashMap<Long, String>();
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        probe.setStatus(1L);
        List<CmsTopicNode> nodes = nodeMapper.selectCmsTopicNodeList(probe);
        if (nodes == null) {
            return names;
        }
        for (CmsTopicNode node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            try {
                NpcProfileVO profile = npcProfileService.resolveForNodeOrMerchant(node.getId(), node.getMerchantMemberId());
                if (profile != null && profile.getProfileId() != null && !names.containsKey(profile.getProfileId())) {
                    names.put(profile.getProfileId(), profile.getName());
                }
            } catch (RuntimeException ignore) {
                // 名称只是展示字段;解析失败不阻断结局主体
            }
        }
        return names;
    }

    /** 条件求值:数组按 conditionsPass 全通过;单条 cond 包一层;null = 无条件成立。 */
    private boolean conditionsPass(JsonNode conditions, TopicRouteEngine.State state) {
        if (conditions == null || conditions.isNull()) {
            return true;
        }
        if (conditions.isArray()) {
            return ENGINE.conditionsPass(conditions, state);
        }
        ArrayNode single = JSON.createArrayNode();
        single.add(conditions);
        return ENGINE.conditionsPass(single, state);
    }

    private JourneyRules rulesOf(CmsTopic topic, TopicRouteSession session) {
        String frozen = session == null ? null : NodeJourneySnapshot.journeyRules(session.getConfigSnapshotJson());
        return JourneyRules.parse(frozen != null ? frozen : topic.getJourneyRulesJson());
    }

    private int hpOf(TopicRouteEngine.State state, JourneyRules rules) {
        Number hp = state.getVariables() == null ? null : state.getVariables().get(SYS_HP);
        return hp == null ? rules.getHp().getInit() : hp.intValue();
    }

    private int luckOf(TopicRouteEngine.State state, JourneyRules rules) {
        Number luck = state.getVariables() == null ? null : state.getVariables().get(SYS_LUCK);
        return luck == null ? rules.getLuck().getInit() : luck.intValue();
    }
}
