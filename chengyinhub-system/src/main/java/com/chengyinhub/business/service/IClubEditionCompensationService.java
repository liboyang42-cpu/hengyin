package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.service.support.FinalCompensationFacts;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

// 2026-09-10 用户裁决:探店日不再交质量证据 —— submitQualityEvidence / qualityEvidenceState
// 两个方法整条删除。平台仍在后台人工确认 Q,只是不再要俱乐部上传那份摘要。
public interface IClubEditionCompensationService {

    /**
     * 开售同事务冻结条款；重复开售读回原条款，不覆盖。
     *
     * <p>EXTRA 审批人<b>不由调用方传</b>：它是开售前在当期成本版本上显式登记的<b>项目负责人</b>
     * （{@code topic_edition_cost.extra_approver_user_id}），由本方法自己读、自己做可达校验、
     * 自己冻进条款与指纹。没登记或按不动预批按钮一律拒售 —— 冻错人就是本期 EXTRA 永久批不了，
     * 而条款没有更正路径。
     *
     * @param fulfillmentEndAt   本期履约窗结束的权威瞬间（EXTRA 上界 + FINAL「期已结束」事实门）
     */
    ClubEditionCompensationTerms freezeTermsForSale(Long topicId, Long clubId, Date frozenAt, Date evidenceDueAt,
                                                    Date fulfillmentEndAt);

    /** 开售冻结前登记计划工时；冻结后永久拒绝。 */
    void planHours(Long topicId, Long clubId, String hourKind, BigDecimal plannedHours, Long operatorId);

    /* ═══════════════════════════════════════════════════════════════════════
     * ★★ 下面五个方法【2026-08-25 整条退役】：一律抛
     *    IllegalStateException("EDITION_ACTUAL_HOURS_RETIRED")，且拒绝发生在
     *    任何 mapper 写入之前（见 ClubEditionCompensationServiceImpl#rejectRetiredHoursWrite）。
     *
     *    产品裁决：「工时暂时不需要做，这个线下没办法确认的」。实际工时是自陈数字，
     *    平台没有任何能证伪它的服务端事实，而 EXTRA 单价是 ¥40／人小时 ——
     *    自报即计酬等于开了一条「凭空写小时数换钱」的资损通道。
     *
     *    签名与实现体保留，是为了「暂时」二字：恢复 = revert 那一个提交。
     *    ⚠️ 新写调用方前先看这段：它们现在必然抛。
     * ═══════════════════════════════════════════════════════════════════════ */

    /** @deprecated 已退役（2026-08-25）：俱乐部自报实际工时。调用必抛 EDITION_ACTUAL_HOURS_RETIRED。 */
    @Deprecated
    void reportHours(Long topicId, Long clubId, String hourKind, BigDecimal actualHours, Long reporterId);

    /** @deprecated 已退役（2026-08-25）：平台确认实际工时。调用必抛 EDITION_ACTUAL_HOURS_RETIRED。 */
    @Deprecated
    void confirmActualHours(Long topicId, Long clubId, String hourKind, BigDecimal actualHours, Long operatorId);

    /** @deprecated 已退役（2026-08-25）：更正已确认的实际工时。调用必抛 EDITION_ACTUAL_HOURS_RETIRED。 */
    @Deprecated
    void correctConfirmedHours(Long topicId, Long clubId, String hourKind, String reason, Long operatorId);

    /** @deprecated 已退役（2026-08-25）：EXTRA 事前预审批。调用必抛 EDITION_ACTUAL_HOURS_RETIRED。 */
    @Deprecated
    void preapproveExtraHours(Long topicId, Long clubId, String category, BigDecimal approvedHours,
                              Date workStartedAt, String idempotencyKey, Long approverId);

    /** @deprecated 已退役（2026-08-25）：EXTRA 更正。调用必抛 EDITION_ACTUAL_HOURS_RETIRED。 */
    @Deprecated
    void correctExtraHours(Long topicId, Long clubId, Long correctionOf, BigDecimal approvedHours,
                           String idempotencyKey, Long approverId);

    /** 当前项目运营人员持专用权限，一次确认；并发只有一个成功。判过必须已有证据。 */
    void confirmQualityOnce(Long topicId, Long clubId, String dimension, boolean pass, String reason,
                            Long operatorId);

    /**
     * 已证明由商家或平台造成的过错，该维排除计酬。
     *
     * <p>写的是<b>新版本</b>（旧版本置非 current + correction_of 指回去），历史判定不被改写；
     * faultParty 只允许 MERCHANT / PLATFORM，并<b>落进 club_quality_decision.fault_party 列</b> ——
     * 本仓 {@code LogAspect} 不写请求体，指望 {@code @Log} 留归责方是假的。
     *
     * <p><b>方向按总纲 §2.1.2 字面</b>：「商家或平台故障导致的未完成<b>从俱乐部绩效责任中剔除</b>」——
     * 剔除的宾语是失分事件，不是报酬。所以该维<b>不扣分、照贡献 200</b>，
     * 绝不让商家/平台的过错变成俱乐部少拿 200。
     *
     * <p><b>reason 必填</b>：这是全仓唯一能推翻一个已确认 Q 决定的动作，方向是<b>加钱</b>
     * （每维 +200，一期最多 +600），触发条件只是操作者自行断言归责方。
     * 「是哪一笔事故、依据什么定的责」这层事实只有落进 {@code reason} 列才留得下来。
     */
    void excludeQualityByFault(Long topicId, Long clubId, String dimension, String faultParty,
                               String reason, Long operatorId);

    /**
     * 该俱乐部能自报的期次列表（小程序选期用）。
     *
     * <p>数据源是<b>开售冻结条款里的执行俱乐部</b>（{@code executing_club_id}），
     * 不是 {@code cms_topic.club_id} —— 探店日 ③ 由平台建期，那一列恒为 NULL，
     * 按它取候选的话俱乐部一个期次都选不出来，E1「俱乐部自报 + 平台确认」整条链走不通。
     *
     * <p>空列表是<b>合法结果</b>（这个俱乐部还没有已开售的探店日期次），调用方要按空态展示，
     * 不要当成网络错误。
     */
    List<Map<String, Object>> editionsForExecutingClub(Long clubId);

    /** 期次非空。 */
    String EDITIONS_REASON_HAS_EDITIONS = "HAS_EDITIONS";
    /** 本俱乐部没有作为执行俱乐部(ORGANIZER)的有效合作单。 */
    String EDITIONS_REASON_NOT_UNDERTAKEN = "NOT_UNDERTAKEN";
    /** 有有效 ORGANIZER 合作单,但还没有任何一期开售冻结出以本俱乐部为执行方的条款。 */
    String EDITIONS_REASON_UNDERTAKEN_NOT_ON_SALE = "UNDERTAKEN_NOT_ON_SALE";
    /** 调用方不是本俱乐部负责人(群主或 role=1 管理员);由 Controller 判定,不读任何承接数据。 */
    String EDITIONS_REASON_NO_PERMISSION = "NO_PERMISSION";

    /**
     * 解释 {@link #editionsForExecutingClub} 为什么为空(W10a #26)。
     *
     * <p>只读服务端真源:冻结条款 + 有效 ORGANIZER 合作单。调用方必须先过归属闸。
     */
    String editionsEmptyReason(Long clubId);

    /** FINAL 只消费已确认事实；未到 T+7 且 Q 未决 ⇒ PENDING，不得 FINAL。 */
    FinalCompensationFacts computeFinalCompensationFacts(Long topicId, Date now);

    /** 开售冻结的条款；没有就抛 EDITION_TERMS_MISSING。指纹漂移也在这里挡。 */
    ClubEditionCompensationTerms requireFrozenTerms(Long topicId);

    /**
     * 后台只读操作面：本期冻结条款 + 三维质量决定 + EXTRA 审批链。
     *
     * <p>返回 {@code terms / qualityDecisions / extraApprovals} 三个键。
     * 与 {@link #requireFrozenTerms} 的区别：条款还没冻结（尚未开售 / 历史豁免期）时
     * <b>返回空视图而不是抛</b> —— 这是给人看的读接口，不是资金写动作的前置闸。
     * 两处读都按 {@code (topic, 冻结的执行俱乐部)} 收窄，请求体里的 clubId 不参与。
     */
    java.util.Map<String, Object> compensationView(Long topicId);
}
