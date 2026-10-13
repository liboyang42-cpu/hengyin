package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ITopicRouteRuntimeService {
    default List<Map<String,Object>> themeHistory(Long memberId,Long topicId,Long beforeSessionId,Integer limit) {return themeHistory(memberId,topicId);}
    default Map<String,Object> themeCard(Long memberId,Long sessionId) {throw new UnsupportedOperationException();}
    default JsonNode nodeRuntimeConfig(Long memberId,Long topicId,Long nodeId,Long activityId) {return nodeRuntimeConfig(memberId,topicId,nodeId);}
    default void recordNodeResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String actionId,Boolean passed,boolean mistake,int outcome,List<String[]> choices) {throw new UnsupportedOperationException();}
    default Map<String, Object> themeView(Long memberId, Long topicId, Long activityId, Long sessionId) { throw new UnsupportedOperationException(); }
    default Map<String, Object> themeAction(Long memberId, Long sessionId, Integer version, String actionId, String operation, String contentId) { throw new UnsupportedOperationException(); }
    default Map<String, Object> replayTheme(Long memberId, Long sessionId) { throw new UnsupportedOperationException(); }
    default List<Map<String, Object>> themeHistory(Long memberId, Long topicId) { throw new UnsupportedOperationException(); }
    default JsonNode nodeRuntimeConfig(Long memberId, Long topicId, Long nodeId) { return null; }
    default String playInstance(Long memberId, Long topicId, Long activityId) { return "default"; }
    TopicRouteState state(Long memberId, Long topicId, Long activityId);
    TopicRouteState completeOpening(Long memberId, Long topicId, Long activityId, Long chapterId, Integer expectedVersion);
    void requireOpeningAccess(Long memberId, Long topicId, Long activityId, Long nodeId);

    /**
     * 思维阁:到站时同步一次步数。{@code todaySteps} 必须是服务端解密微信运动拿到的今日累计,
     * 调用方负责解密 —— 客户端报的任何步数都不采信。{@code claim} 是玩家在故事里刚读到的
     * 念头 key(thought 块),没得到的念头不替玩家累积。
     */
    TopicRouteState syncThoughts(Long memberId, Long topicId, Long activityId,
                                 java.util.List<String> claim, int todaySteps);
    TopicRouteState syncThoughts(Long memberId, Long topicId, Long activityId,
                                 java.util.List<String> claim, int todaySteps, long readingDay);
    TopicRouteState advance(Long memberId, Long topicId, Long activityId, Integer expectedVersion,
                            NodeOutcome outcome);
    TopicRouteState advance(Long memberId, Long topicId, Long activityId, Integer expectedVersion,
                            String trustedTriggerType, NodeOutcome outcome);
    TopicRouteState advance(Long memberId, Long topicId, Long activityId, Integer expectedVersion,
                            String routeActionId, String trustedTriggerType, NodeOutcome outcome);
    boolean canPlay(Long memberId, Long topicId, Long activityId, Long nodeId);
    boolean canPlay(Long memberId, Long topicId, Long activityId, Long nodeId, String routeActionId);
    boolean isCompleted(Long memberId, Long topicId, Long activityId, int total, int done);
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default PlayCheckReceipt roll(Long memberId, Long topicId, Long nodeId, String checkId) {
        return roll(memberId, topicId, null, nodeId, checkId);
    }
    PlayCheckReceipt roll(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId);
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default PlayCheckReceipt reroll(Long memberId, Long topicId, Long nodeId, String checkId) {
        return reroll(memberId, topicId, null, nodeId, checkId);
    }
    PlayCheckReceipt reroll(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId);
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default PlayCheckReceipt settle(Long memberId, Long topicId, Long nodeId, String checkId) {
        return settle(memberId, topicId, null, nodeId, checkId);
    }
    PlayCheckReceipt settle(Long memberId, Long topicId, Long activityId, Long nodeId, String checkId);
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default PlayCheckReceipt rest(Long memberId, Long topicId, Long nodeId) {
        return rest(memberId, topicId, null, nodeId);
    }
    PlayCheckReceipt rest(Long memberId, Long topicId, Long activityId, Long nodeId);
    Map<String, Integer> sessionStatuses(List<TopicRouteSession> scopes);
    Set<Long> participantMemberIds(Long topicId);
    Set<Long> completedMemberIds(Long topicId);
    int countParticipants(Long topicId);
    int countCompletedParticipants(Long topicId);

    /** 玩法判错(R9-ACTIONS):该节点第一次免罚,之后每次扣 1 血;同 id 重放不重复扣。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default void recordMistake(Long memberId, Long topicId, Long nodeId) {
        recordMistake(memberId, topicId, null, nodeId);
    }
    void recordMistake(Long memberId, Long topicId, Long activityId, Long nodeId);

    /**
     * 判定型玩法成败写状态(阶段 1 / 链路 D):把这一站「答对没有」落成可引用的事实
     * {@code sys.passed.{nodeId}} = 1(通过)/ 0(失败),供后续节点的 requires / mods / 叙事 when 引用。
     *
     * <p>只有真正的判定型动作会被调用(判据在玩法运行时一侧:属于判定型清单且 lastCorrect / lastHit 有值),
     * 非判定型动作(扫码 / 留言 / 建档)一律不写。动作 id 由服务端拼 {@code passed:{actionId}},
     * 同 id 重放不二次写;同一节点换 actionId 重答则后写覆盖先写(最后一次为准)。
     * 非旅程主题(没有本局 / 本局已结束)no-op,行为零变化。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default void recordPassed(Long memberId, Long topicId, Long nodeId, String actionId, boolean passed) {
        recordPassed(memberId, topicId, null, nodeId, actionId, passed);
    }
    void recordPassed(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, boolean passed);

    /**
     * 随机类玩法的结果写本局状态:sys.outcome.{nodeId} = 结果序号(抛硬币 1 正 / 2 反、掷骰子点数和、
     * 抽卡为抽中那张在卡池里的序号,1 起)。与 {@link #recordPassed} 同一套 gate 与幂等:
     * 动作 id {@code outcome:{actionId}},同 id 重放不二次写,换 actionId 则后写覆盖先写。
     * 非旅程主题 / 外来 nodeId / 主题没开检定一律 no-op。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default void recordOutcome(Long memberId, Long topicId, Long nodeId, String actionId, int outcome) {
        recordOutcome(memberId, topicId, null, nodeId, actionId, outcome);
    }
    void recordOutcome(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, int outcome);

    /**
     * 按本局状态求值一条条件(阶段 4 自适应难度用)。
     *
     * <p>为什么是这个形状而不是把 state 导出去:条件求值只能有一个真源。导出 state 等于
     * 把状态形状和求值规则同时复制一份到玩法运行时,两边迟早漂移。这里只答「成不成立」。
     *
     * <p>**fail-closed**:没有本局、本局不在跑、条件读不了 —— 一律 false。
     * 判不了的时候不放宽,而不是默认放宽。
     */
    default boolean conditionPasses(Long memberId, Long topicId, com.fasterxml.jackson.databind.JsonNode condition) {
        return conditionPasses(memberId, topicId, null, condition);
    }
    boolean conditionPasses(Long memberId, Long topicId, Long activityId, com.fasterxml.jackson.databind.JsonNode condition);

    /**
     * 选择结果当场写状态(R14):分支选项 / 选项问答选项 / 排序·连线·分类·找东西·猜图通过时,
     * 把配置的 effects(与节点块同一套路线图写法)立即写进本局 state,不等节点完成。
     *
     * <p>动作 id 由服务端生成 {@code choice:{nodeId}:{stepOrKit}:{optionId|pass}},同 id 重放
     * 不重复写;分支玩法额外把选项序号(1 起)写进 sys.outcome.{nodeId}。
     * 非旅程主题 / 没配 effects 的块配置一律 no-op,行为零变化。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default void recordChoice(Long memberId, Long topicId, Long nodeId, String stepOrKit, String optionId) {
        recordChoice(memberId, topicId, null, nodeId, stepOrKit, optionId);
    }
    void recordChoice(Long memberId, Long topicId, Long activityId, Long nodeId, String stepOrKit, String optionId);

    /** 问问题(R9-ACTIONS):须已到店、问题在该节点 enter.questions 内;重放返回原回执。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default Map<String, Object> ask(Long memberId, Long topicId, Long nodeId, String questionId) {
        return ask(memberId, topicId, null, nodeId, questionId);
    }
    Map<String, Object> ask(Long memberId, Long topicId, Long activityId, Long nodeId, String questionId);

    /** 回应开场通话(R9-ACTIONS):选项在 journey_story_json.call.options 内;只允许一次。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    default Map<String, Object> call(Long memberId, Long topicId, String optionId) {
        return call(memberId, topicId, null, optionId);
    }
    Map<String, Object> call(Long memberId, Long topicId, Long activityId, String optionId);

    /**
     * 本局开局的模板判定字段快照(R18-FREEZE):validationMethod 与各验证方式的答案 / 揭示 /
     * 偏好 / 传感器配置。没有本局或老快照没有 nodeAnswers 键时返回 null,调用方回退实时模板。
     */
    default JsonNode nodeAnswers(Long memberId, Long topicId, Long nodeId) {
        return nodeAnswers(memberId, topicId, null, nodeId);
    }
    JsonNode nodeAnswers(Long memberId, Long topicId, Long activityId, Long nodeId);

    /**
     * R18-RECEIPT 收口:本局当前 hp / luck(当前值,数值规则冻结优先,老局回退实时主题规则)。
     * 没有本局 / 读不到状态时返回 null,回执缺省这两项。
     */
    default Map<String, Integer> vitals(Long memberId, Long topicId) {
        return vitals(memberId, topicId, null);
    }
    Map<String, Integer> vitals(Long memberId, Long topicId, Long activityId);

    /**
     * 本局当前状态是否满足一组条件(与 requires 同一套写法)。没有本局 / 读不到状态一律 false ——
     * 调用方拿它决定「要不要改写」这类剧情效果,判不了就不改写。
     */
    default boolean conditionsPass(Long memberId, Long topicId, JsonNode conditions) {
        return conditionsPass(memberId, topicId, null, conditions);
    }
    boolean conditionsPass(Long memberId, Long topicId, Long activityId, JsonNode conditions);

    /** 本局已有的标签(选项「记住这个选择」、伏笔应验 thought.*.done 等)。没有本局 / 读不到 = 空集合。 */
    default java.util.Set<String> tags(Long memberId, Long topicId) {
        return tags(memberId, topicId, null);
    }
    java.util.Set<String> tags(Long memberId, Long topicId, Long activityId);

    static String sessionKey(Long memberId, Long topicId, Long activityId) {
        return String.valueOf(memberId) + ':' + String.valueOf(topicId) + ':'
                + String.valueOf(activityId == null ? 0L : activityId);
    }
}
