package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientScoreAppeal;
import com.chengyinhub.business.domain.OrientScoreRecovery;
import com.chengyinhub.business.domain.OrientScoreEntry;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;

import java.util.List;
import java.util.Map;

/**
 * 实施文档二 F1b 城市定向计分(product_type=1)。
 * <ul>
 *   <li>计分流水只追加,单位 1/100 分(_centi);纠错 = 反向条目,从不改写。</li>
 *   <li>局终一次性投影到积分/XP:orient_score_settlement uk(session_id),同一局奖励恰好一次。</li>
 *   <li>可复算:SUM(流水 id &lt;= 水位) == 投影 total_centi;水位后的冲正只影响展示分,不追发/追扣奖励。</li>
 *   <li>总开关 orient.scoring.enabled 默认关闭;非 product_type=1 主题一律不生效(旧主题不变)。</li>
 * </ul>
 * F1a(会话生命周期)/F1c(反作弊)的接入点:{@link #syncSession}、{@link #appendEntry}、{@link #settleBySystem}。
 */
public interface IOrientScoringService {
    String KIND_SYNC = "SYNC";
    String KIND_SETTLE = "SETTLE";
    String KIND_APPEAL = "APPEAL";
    String KIND_DISPUTE = "DISPUTE";

    boolean isEnabled();

    /** 开关开启且主题是城市定向(product_type=1)。 */
    boolean isActiveFor(CmsTopic topic);

    /** 玩家:本局计分分维度汇总 + 时间线 + 投影回执 + 申诉/争议。只读,校验本人归属。 */
    Map<String, Object> breakdown(Long memberId, Long sessionId);

    /** 玩家/F1a:把本局已通过任务入账(幂等;已结算的局冻结不再入账)。 */
    Map<String, Object> syncSession(Long memberId, Long sessionId);

    /** 玩家同步命令：原号持久回执；两参数版本保留给既有内部调用。 */
    Map<String, Object> syncSession(Long memberId, Long sessionId, String idempotencyKey);

    /** 玩家:局终结算(幂等;已结算返回首份回执)。 */
    Map<String, Object> settle(Long memberId, Long sessionId, String idempotencyKey);

    /** 原session锁内的终态写前检查；待审任务不得进入正常结算。 */
    void assertCompletionAllowed(Long sessionId);

    /** F1a 生命周期在会话终态时调用;不校验玩家身份,只校验会话存在。 */
    Map<String, Object> settleBySystem(Long sessionId, String idempotencyKey);

    /** F1a/F1c:追加一条系统流水(如反作弊处罚)。同键同指纹回放,同键异指纹冲突。 */
    OrientScoreEntry appendEntry(Long sessionId, String dimension, int deltaCenti, String reasonCode,
                                 String sourceType, String sourceRef, Long nodeId, String idempotencyKey);

    /** 后台:整条冲正(追加反向条目)。 */
    OrientScoreEntry reverseEntry(Long entryId, String reasonCode, Long operatorId, String idempotencyKey);

    OrientScoreAppeal submitAppeal(Long memberId, Long sessionId, Long entryId, Long nodeId, String reason,
                                   String idempotencyKey);

    OrientScoreAppeal submitDispute(Long memberId, Long sessionId, String reason, String idempotencyKey);

    /** 后台:处理申诉。采纳时 adjustCenti 为空 = 整条冲正被申诉条目;非空 = 追加 ADJUSTMENT。 */
    OrientScoreAppeal resolveAppeal(Long appealId, boolean accept, Integer adjustCenti, String note,
                                    String handledBy, Long operatorId);

    OrientScoreAppeal resolveDispute(Long disputeId, boolean accept, String note, String handledBy);

    List<OrientScoreAppeal> listAppeals(Integer status, int limit);

    List<OrientScoreAppeal> listDisputes(Integer status, int limit);

    /** UNKNOWN 结果回读:按 kind + 幂等键取首份回执;未查到返回 state=NOT_FOUND；不能据此证明未写或解除 UNKNOWN。 */
    Map<String, Object> receipt(Long memberId, String kind, Long sessionId, String idempotencyKey);

    OrientScoreRecovery submitRecovery(Long memberId, Long sessionId, String kind);
    List<OrientScoreRecovery> listRecoveries(Integer status, int limit);
    OrientScoreRecovery reviewRecovery(Long id, Long referenceId, String note, String handledBy);

    /** 本人对局对应的完整计分/分支榜，先排名后截取，同时返回榜外本人。 */
    Map<String, Object> leaderboard(Long memberId, Long sessionId);

    /** 复算:流水求和 vs 投影。 */
    Map<String, Object> verify(Long sessionId);

    /** D-13 排行榜附加 scoreCenti(只展示,不改排序与名次)。开关关或非定向主题时不做任何事。 */
    void decorateLeaderboard(CmsTopic topic, Long activityId, Long topicId, List<LeaderboardItemVO> list,
                             LeaderboardItemVO me);
}
