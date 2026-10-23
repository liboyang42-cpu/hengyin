package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubEditionHours;
import com.chengyinhub.business.domain.ClubEditionHoursCorrection;
import com.chengyinhub.business.domain.ClubExtraHoursApproval;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.domain.TopicEditionFundsAttribution;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * A1/A2 补偿域。所有写语句都是窄语句 + 旧状态条件，没有一条能覆盖冻结计划或直改确认状态
 * （那正是旧 upsertHours 的后门形状）。
 */
public interface ClubEditionCompensationMapper {

    int insertTerms(ClubEditionCompensationTerms terms);

    ClubEditionCompensationTerms selectTerms(@Param("topicId") Long topicId);

    /**
     * 该期是否被显式登记为「四个 A 的历史豁免」。
     *
     * <p>只在 terms 缺失这条错误路径上查一次：为的是把「开售早于本机制、按历史豁免处理」
     * 与「条款该有却没有 = 配置没落」区分开，别让六个写入口报同一个错把排障带偏
     * （审计 2：历史 SELLING 主题的读策略要<b>明确标为历史豁免</b>）。
     */
    int countLegacyExemption(@Param("topicId") Long topicId);

    /**
     * 开售时刻的 F 固定补偿真源：当前成本版本。
     *
     * <p>★没有成本行返回 null，<b>调用方必须 fail-closed 抛错，绝不冻 0</b>
     * （见 {@code ClubEditionCompensationServiceImpl.freezeTermsForSale} 的
     * {@code EDITION_TERMS_FIXED_COMPENSATION_MISSING}）。「少付不多付」只对平台是安全侧，
     * 对合同对手方是违约，而且零告警、事后不可救 —— terms 幂等读回原件，全仓没有更正方法。
     */
    java.math.BigDecimal selectCurrentFixedCompensation(@Param("topicId") Long topicId);

    /**
     * HEADCOUNT 的两个配置项（每已赚票单价 {@code headcountRate}、本期封顶 {@code headcountCap}），
     * 与 F 同源自当期成本版本，开售当场冻进 terms。
     *
     * <p>⚠️ 返回整行而不是拆成两个标量查询：这两个数必须来自<b>同一个成本版本</b>。
     * 分两次查会在成本改版的窗口里冻出「rate 来自新版、cap 来自旧版」的组合 —— 那种钱算错了
     * 也不会有任何报错。缺当期成本行时返回 {@code null}，调用方按「本期不按人头计酬」处理。
     */
    java.util.Map<String, Object> selectCurrentHeadcountConfig(@Param("topicId") Long topicId);

    /**
     * 开售时刻的 EXTRA 审批人真源：当前成本版本上<b>显式登记</b>的项目负责人 {@code sys_user.user_id}。
     *
     * <p>★不是 {@code ProductRole.declaredBy} —— 那个字段自己的契约写明「用于冻结 actor 变更审计，
     * <b>不参与主体归属判断</b>」，产生面只要 {@code business:topic:content-slot:edit}，
     * 而且幂等重试保留的是<b>最初</b>那个声明人，与「本期项目负责人」没有语义关系。
     *
     * <p>没有成本行 / 该项为空 / ≤0 都返回 null ⇒ 调用方 fail-closed 拒售，绝不猜一个人。
     */
    Long selectCurrentExtraApprover(@Param("topicId") Long topicId);

    /**
     * 票源奖励的唯一金额基数：当前 FINAL 六量快照的 {@code g_earned}。
     * 无 FINAL 行返回 null，调用方必须 fail-closed，不得退回 PROVISIONAL 或重算第二套口径。
     */
    BigDecimal selectCurrentFinalGEarned(@Param("topicId") Long topicId);

    /** 与当前 FINAL 同事务固化的逐票金额归因，不回头读可变报名表。 */
    List<TopicEditionFundsAttribution> selectCurrentFinalAttributions(@Param("topicId") Long topicId);

    /** 开售冻结前才允许写计划工时；只碰 planned_hours，不碰自报/确认/审计列。 */
    int upsertPlannedHours(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                           @Param("hourKind") String hourKind, @Param("plannedHours") BigDecimal plannedHours);

    /** 俱乐部自报实际工时；只允许从未确认态出发，确认后永久拒绝。 */
    int reportActualHoursCas(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                             @Param("hourKind") String hourKind,
                             @Param("reportedActualHours") BigDecimal reportedActualHours,
                             @Param("reportedBy") Long reportedBy);

    /** 平台确认实际工时；旧状态必须是 REPORTED，确认时刻由数据库写。 */
    int confirmActualHoursCas(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                              @Param("hourKind") String hourKind,
                              @Param("confirmedActualHours") BigDecimal confirmedActualHours,
                              @Param("confirmedBy") Long confirmedBy);

    List<ClubEditionHours> selectHoursForClub(@Param("topicId") Long topicId, @Param("clubId") Long clubId);

    /**
     * 更正路径的<b>锁定读</b>：要先把这条确认事实整份读出来快照进审计表，再退回它。
     *
     * <p>快照读不行：「读到 CONFIRMED=40 → 写审计 → 退回」三步之间，并发的另一次更正可以
     * 先退回并被重新确认成别的值，本事务照旧按 40 写审计并退回 ⇒ 审计说「当初是 40」，
     * 而它推翻的其实是新那条。行锁让第二个请求阻塞到本事务提交，届时它自己的
     * {@code confirmed_at} 比对不上 ⇒ 已有的 CAS 接住。
     */
    ClubEditionHours selectHoursForUpdate(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                                         @Param("hourKind") String hourKind);

    /** append-only 更正留痕；reopened_at 由数据库时钟写，Java 不传（防「事后补更正、时间写早」）。 */
    int insertHoursCorrection(ClubEditionHoursCorrection correction);

    /**
     * 带理由的退回：CONFIRMED → REPORTED，并<b>清空</b> confirmed_*。
     *
     * <p>为什么清空而不是留着：留着就是第二个真值 —— 状态说「待确认」、金额列还挂着被推翻的那个数。
     * 为什么不是放宽 {@code confirmActualHoursCas} 的旧状态：那会把「一次确认」变成「随便改」，
     * 而写已确认工时的路径必须始终只有一条。
     * where 里的 {@code confirmed_at = #{previousConfirmedAt}} 是乐观锁：它把这次退回钉在
     * 审计行快照的**那一条**确认事实上，退错一条即命中 0 行。
     */
    int reopenConfirmedHoursCas(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                                @Param("hourKind") String hourKind,
                                @Param("previousConfirmedAt") java.util.Date previousConfirmedAt,
                                @Param("reopenedBy") Long reopenedBy);

    /** append-only；approved_at 在 XML 里用 CURRENT_TIMESTAMP(3)，不接受 Java 传时间。 */
    int insertExtraApproval(ClubExtraHoursApproval approval);

    List<ClubExtraHoursApproval> selectExtraApprovals(@Param("topicId") Long topicId,
                                                      @Param("clubId") Long clubId);

    ClubExtraHoursApproval selectExtraApprovalById(@Param("id") Long id);

    /** 该审批已被更正过几次；>0 即拒绝再开一条兄弟更正（DB 的 uk_club_extra_correction_of 是第二道）。 */
    int countCorrectionsOf(@Param("correctionOf") Long correctionOf);

    /**
     * 该俱乐部作为<b>执行俱乐部</b>的全部已冻结期次（小程序自报页的选期读模型）。
     *
     * <p>真源是 {@code executing_club_id}，不是 {@code cms_topic.club_id} —— 探店日 ③ 由平台建期，
     * 那一列恒为 NULL，按它过滤的话俱乐部一个期次都选不出来。
     */
    List<java.util.Map<String, Object>> selectEditionsForExecutingClub(@Param("clubId") Long clubId);

    /**
     * 本俱乐部作为 ORGANIZER(执行俱乐部)的有效合作单条数。只用来解释「期次为空」的原因:
     * 0 ⇒ 还没承接;&gt;0 但没有冻结条款 ⇒ 已承接、尚未开售。口径与开售时
     * {@code resolveExecutingClub} 读的阵容一致(status=0、未删、排除旧 invite_type=2)。
     */
    int countEffectiveOrganizerOrdersForClub(@Param("clubId") Long clubId);

    int insertQualityDecision(ClubQualityDecision decision);

    int supersedeCurrentQuality(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                                @Param("dimension") String dimension);

    /**
     * 一次确认的 CAS：只有 current 且仍 PENDING 的那一行会被改到。
     *
     * <p>{@code reason} 与金额同一条语句落列 —— 判过/判不过都是不可逆动作，
     * 「依据什么这么判」不落库就只剩操作者的记忆（本仓 @Log 不写请求体）。
     */
    int confirmQualityCas(@Param("topicId") Long topicId, @Param("clubId") Long clubId,
                          @Param("dimension") String dimension, @Param("amount") BigDecimal amount,
                          @Param("reason") String reason,
                          @Param("confirmedBy") Long confirmedBy, @Param("confirmedAt") Date confirmedAt);

    // 2026-09-10 用户裁决:探店日不再交质量证据,attachQualityEvidence 整条删除。
    List<ClubQualityDecision> selectCurrentQualityDecisions(@Param("topicId") Long topicId,
                                                            @Param("clubId") Long clubId);
}
