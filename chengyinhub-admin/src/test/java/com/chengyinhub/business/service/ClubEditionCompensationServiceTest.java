package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubEditionHours;
import com.chengyinhub.business.domain.ClubExtraHoursApproval;
import com.chengyinhub.business.domain.ClubQualityDecision;
import com.chengyinhub.business.domain.TopicEditionFundsAttribution;
import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
import com.chengyinhub.business.service.impl.ClubEditionCompensationServiceImpl;
import com.chengyinhub.business.service.support.ExtraApproverReachability;
import com.chengyinhub.business.service.support.FinalCompensationFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A1 Q(0/200 三维)与 A2 EXTRA(40 元/人小时、四类不借额度、事前审批)的服务契约。 */
class ClubEditionCompensationServiceTest {
    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long OPERATOR = 71L;
    /** 开售前显式登记的项目负责人（EXTRA 事前审批人），与「谁点了 ORGANIZER 槽」无关。 */
    private static final long PROJECT_LEAD = 88L;
    private static final long DAY_MILLIS = 24L * 3600L * 1000L;
    /**
     * 证据截止取「相对现在的未来」，不是硬编码的 2023 年常量。
     *
     * <p>2026-09-10 用户裁决「探店日不交证据」之后，写侧那道截止闸随证据链路一并删除；
     * due_at 仍是「未决转 0」的分水岭，所以这个常量继续取相对未来 ——
     * 固定在过去会让每一条走这条路径的用例恒落进已过期分支。
     */
    private static final Date DUE = new Date(System.currentTimeMillis() + 30L * DAY_MILLIS);
    /** 履约窗结束 = 证据截止 T+7 往前推 7 天。 */
    private static final Date FULFILLMENT_END = new Date(DUE.getTime() - 7L * DAY_MILLIS);
    private static final Date BEFORE_DUE = new Date(DUE.getTime() - 1000L);
    private static final Date AT_DUE = new Date(DUE.getTime());

    private ClubEditionCompensationMapper mapper;
    private ExtraApproverReachability approverReachability;
    private ClubEditionCompensationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEditionCompensationMapper.class);
        approverReachability = mock(ExtraApproverReachability.class);
        when(mapper.insertTerms(any())).thenReturn(1);
        when(mapper.insertExtraApproval(any())).thenReturn(1);
        when(mapper.insertQualityDecision(any())).thenReturn(1);
        when(mapper.selectTerms(TOPIC)).thenReturn(terms());
        // 默认：本期已显式登记一个按得动预批按钮的项目负责人。
        // ⚠️ 这一层只证接线；「什么算按得动」由 ExtraApproverReachabilityTest 跑真逻辑证。
        when(mapper.selectCurrentExtraApprover(TOPIC)).thenReturn(PROJECT_LEAD);
        when(approverReachability.canApproveExtraHours(PROJECT_LEAD)).thenReturn(true);
        // FINAL 六量快照存在 —— 这是 settled 的前置事实（TICKET_SOURCE 退役后单独立起来的那道闸）。
        when(mapper.selectCurrentFinalGEarned(TOPIC)).thenReturn(new BigDecimal("1481.50"));
        service = new ClubEditionCompensationServiceImpl(mapper, approverReachability);
    }

    /** 开售前四类计划工时齐全（H_plan 完整）——freezeTermsForSale 的前置事实。 */
    private void givenAllFourPlannedHoursRegistered() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(plannedOnlyHours());
    }

    // ── 开售冻结 ───────────────────────────────────────────────────────────
    @Test
    void saleFreezeWritesTheFortyYuanRateAndTwoHundredPerDimension() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        // ¥2,000 是合同下界（max(2000, 40×ΣH_plan)，此处 ΣH_plan=16 ⇒ 下界 2000）。
        // 旧 fixture 用的 ¥1,500 按真源本就签不出来，换成一个真的合规的数。
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2600.00"));
        givenAllFourPlannedHoursRegistered();

        ClubEditionCompensationTerms frozen =
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END);

        assertEquals(new BigDecimal("40"), frozen.getExtraHourRate());
        assertEquals(new BigDecimal("200"), frozen.getQualityDimensionAmount());
        assertEquals("DELIVERY_SAFETY,PLAYER_EXPERIENCE,CONTENT_REPORT", frozen.getQualityDimensions());
        assertEquals(CLUB, frozen.getExecutingClubId());
        assertEquals(new BigDecimal("2600.00"), frozen.getFixedCompensation(),
                "F 必须来自当期成本版本，不是凭空的常量（下界只兜底，不覆盖真实签约额）");
        assertEquals(DUE, frozen.getEvidenceDueAt());
        assertNotNull(frozen.getConfigHash());
        // 三维必须在开售当场落成 PENDING 行：没有行，后面的一次确认 CAS 就无处可 CAS，
        // 「T+7 前是 PENDING 而不是默认 600」也就没有承载它的事实。
        org.mockito.ArgumentCaptor<ClubQualityDecision> captor =
                org.mockito.ArgumentCaptor.forClass(ClubQualityDecision.class);
        verify(mapper, times(3)).insertQualityDecision(captor.capture());
        assertEquals(Arrays.asList("DELIVERY_SAFETY", "PLAYER_EXPERIENCE", "CONTENT_REPORT"),
                captor.getAllValues().stream().map(ClubQualityDecision::getDimension)
                        .collect(java.util.stream.Collectors.toList()));
        for (ClubQualityDecision seeded : captor.getAllValues()) {
            assertEquals(ClubQualityDecision.STATUS_PENDING, seeded.getDecisionStatus());
            assertNull(seeded.getAmount(), "开售当场不得预置任何金额");
            assertEquals(DUE, seeded.getDueAt());
        }
    }

    /**
     * ★EXTRA 的审批人必须是<b>显式指定的项目负责人</b>，不是「谁点了 ORGANIZER 角色槽那个按钮」。
     *
     * <p>真源：总纲 §2.1.2 末句「超范围劳动的小时单价和<b>审批人</b>必须在开售快照中冻结」+
     * 协调者锁定裁决「EXTRA_HOURS 40 元/人小时 + <b>项目负责人</b>事前审批」。
     *
     * <p>上一版取 {@code ProductRole.declaredBy}，而该字段自己的契约写明「用于冻结 actor 变更审计，
     * <b>不参与主体归属判断</b>」；它的产生面只要 {@code business:topic:content-slot:edit}、
     * 与消费面（{@code business:clubextra:approve}）授权集不相交，而且
     * {@code declareRequiredOrganizerSlot} 幂等重试保留<b>最初</b>那个声明人 ——
     * 离「项目负责人」更远。现在改成开售前在成本版本上显式登记，缺 / 0 一律拒售。
     */
    @Test
    void theSaleRefusesWhenNoProjectLeadHasBeenDesignatedAsTheExtraApprover() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        givenAllFourPlannedHoursRegistered();
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2600.00"));
        when(mapper.selectCurrentExtraApprover(TOPIC)).thenReturn(null);

        assertEquals("EDITION_TERMS_EXTRA_APPROVER_MISSING", assertThrows(IllegalStateException.class,
                () -> service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END)).getMessage());
        verify(mapper, never()).insertTerms(any());
    }

            private static ClubQualityDecision qualityDecision(String dimension, String hash, int current) {
        ClubQualityDecision decision = new ClubQualityDecision();
        decision.setId(31L);
        decision.setTopicId(TOPIC);
        decision.setClubId(CLUB);
        decision.setDimension(dimension);
        decision.setDecisionStatus(ClubQualityDecision.STATUS_PENDING);
        decision.setEvidenceHash(hash);
        decision.setVersion(1L);
        decision.setCurrent(current);
        return decision;
    }

    /**
     * ★指定的项目负责人必须<b>真的按得动那个按钮</b>。
     *
     * <p>不校验可达性时的死局：开售冻结一个不持 {@code business:clubextra:approve} 的 userId ⇒
     * {@code requireFrozenApprover} 恒抛 {@code EDITION_EXTRA_APPROVER_NOT_FROZEN} ⇒ 本期的 EXTRA
     * 预批与更正<b>永久</b>不可用，而 {@code freezeTermsForSale} 幂等读回原件、全仓没有 terms
     * 更正方法 ⇒ 只能改库。方向是少付，但那是一条没有截止日期的阻断。
     */
    @Test
    void theDesignatedProjectLeadMustActuallyBeAbleToReachTheApproveEndpoint() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        givenAllFourPlannedHoursRegistered();
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2600.00"));
        when(mapper.selectCurrentExtraApprover(TOPIC)).thenReturn(PROJECT_LEAD);
        when(approverReachability.canApproveExtraHours(PROJECT_LEAD)).thenReturn(false);

        assertEquals("EDITION_TERMS_EXTRA_APPROVER_UNREACHABLE:" + PROJECT_LEAD,
                assertThrows(IllegalStateException.class,
                        () -> service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END))
                        .getMessage());
        verify(mapper, never()).insertTerms(any());
    }

    /** 可达的项目负责人被原样冻进条款并进指纹：开售后再改成本行也改不动它。 */
    @Test
    void theDesignatedProjectLeadIsFrozenIntoTheTermsAndItsFingerprint() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        givenAllFourPlannedHoursRegistered();
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2600.00"));
        when(mapper.selectCurrentExtraApprover(TOPIC)).thenReturn(PROJECT_LEAD);
        when(approverReachability.canApproveExtraHours(PROJECT_LEAD)).thenReturn(true);

        ClubEditionCompensationTerms frozen =
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END);

        assertEquals(PROJECT_LEAD, frozen.getExtraApproverUserId());
        assertEquals(ClubEditionCompensationServiceImpl.configHash(frozen), frozen.getConfigHash());
    }

    /**
     * ★F 有<b>合同下界</b>：{@code F_contract = max(¥2,000, ¥40 × ΣH_plan)}。
     *
     * <p>真源：总纲 §2.1.2 的公式；裁决表 §三「偏离 3」把四档阶梯表改成「一次性签一份」时，
     * 特意把这个数原样记了下来（「⚠️ 把数记在这里：F_contract = max(¥2000, ¥40×H_plan)，最低 ¥2000／期」）。
     *
     * <p>为什么必须是硬闸而不是提示：{@code topic_edition_cost.fixed_compensation} 的
     * DB CHECK 只要求 ≥0，而后台成本弹窗把未填项<b>一律置 0</b> —— 「操作者没动那个输入框」
     * 是最常走的一条路，走完就是一期合同金额静默归零、零告警、事后不可救
     * （terms 幂等读回原件，全仓没有更正方法）。这和「缺成本行拒售」是同一件事的另一半。
     *
     * <p>期望值不是把实现算一遍：¥2,000 与 ¥3,200（= 40 × 80 人时）都是按真源公式手算的常量。
     */
    @Test
    void aFixedCompensationBelowTheContractFloorBlocksTheSale() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        givenAllFourPlannedHoursRegistered();   // 工时不再参与下界（恒 ¥2,000），这里只是把环境铺成常态
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("1999.99"));

        assertEquals("EDITION_TERMS_FIXED_COMPENSATION_BELOW_CONTRACT:2000",
                assertThrows(IllegalStateException.class,
                        () -> service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END))
                        .getMessage());
        verify(mapper, never()).insertTerms(any());
    }

    /**
     * ★负控：2026-08-25 工时退役后，下界是固定的 ¥2,000，<b>不再</b>跟着 40×ΣH_plan 走。
     *
     * <p>旧实现下界 = max(2000, 40×ΣH_plan)；工时既然线下核实不了、已停掉自报写入，
     * 再拿它反推合同下界就是拿一个没人维护的数字当资金闸。这条盯着它真的被拆干净：
     * 每类 20 小时（旧口径下界 3200）配 ¥2,000 的 F，现在必须能开售。
     */
    @Test
    void theContractFloorNoLongerFollowsThePlannedHours() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(plannedHoursOf("20"));  // 旧口径会把下界抬到 3200
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2000.00"));

        assertEquals(new BigDecimal("2000.00"),
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END)
                        .getFixedCompensation());
    }

    /**
     * ★缺当期成本行不得静默冻成 ¥0。
     *
     * <p>「少付不多付 = 安全侧」只对平台成立，对合同对手方是违约；而且 freezeTermsForSale
     * 幂等读回原件、全仓没有第二个写入点、也没有任何 terms 更正方法 ⇒ 那一期的 F 永久为 0，
     * 零告警、事后只能手工改库。与本类第 3 条纪律（缺事实一律 PENDING 或抛错）冲突。
     */
    @Test
    void missingCostRowBlocksTheSaleInsteadOfSilentlyFreezingZero() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(null);
        givenAllFourPlannedHoursRegistered();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END));

        assertEquals("EDITION_TERMS_FIXED_COMPENSATION_MISSING", error.getMessage());
        verify(mapper, never()).insertTerms(any());
    }

    /**
     * ★负控：H_plan 没登齐也<b>不再</b>拦开售。
     *
     * <p>旧闸存在的理由是「H_plan 缺一类 ⇒ confirmedHoursByKind 恒 null ⇒ FINAL 永久 PENDING」；
     * 工时退役后 F/Q/HEADCOUNT 都不再读工时，那条因果链断了，再拦就是拿一个没人填的
     * 字段堵住真实商单。一行工时都没有也得能开售。
     */
    @Test
    void saleIsNoLongerBlockedByIncompletePlannedHours() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2000.00"));
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(new ArrayList<ClubEditionHours>());

        assertEquals(new BigDecimal("2000.00"),
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END)
                        .getFixedCompensation());
    }

    /** 履约窗结束与审批人都进 configHash：只落列不进指纹 = 冻了但改了没人知道。 */
    @Test
    void theFrozenFulfillmentEndAndApproverAreCoveredByTheConfigFingerprint() {
        ClubEditionCompensationTerms frozen = terms();
        String original = frozen.getConfigHash();

        frozen.setFulfillmentEndAt(new Date(FULFILLMENT_END.getTime() + 1000L));
        assertNotEquals(original, ClubEditionCompensationServiceImpl.configHash(frozen),
                "改了履约窗结束，指纹必须变");

        frozen.setFulfillmentEndAt(FULFILLMENT_END);
        frozen.setExtraApproverUserId(OPERATOR + 1);
        assertNotEquals(original, ClubEditionCompensationServiceImpl.configHash(frozen),
                "换了审批人，指纹必须变");
    }

    @Test
    void repeatedSaleFinalizationReadsBackTheFrozenTermsWithoutRewriting() {
        ClubEditionCompensationTerms again =
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, new Date(1L), FULFILLMENT_END);

        assertEquals(DUE, again.getEvidenceDueAt(), "FINAL 的证据截止必须还是开售冻结的那个");
        verify(mapper, never()).insertTerms(any());
    }

    // ── §5.2-8 计划工时在开售后永久冻结 ────────────────────────────────────
    @Test
    void plannedHoursCannotBeChangedAfterSaleFreeze() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.planHours(TOPIC, CLUB, "PREP", new BigDecimal("99"), OPERATOR));

        assertEquals("EDITION_HOURS_PLAN_FROZEN", error.getMessage());
        verify(mapper, never()).upsertPlannedHours(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void plannedHoursAreWritableBeforeSaleFreeze() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4"))).thenReturn(1);

        service.planHours(TOPIC, CLUB, "PREP", new BigDecimal("4"), OPERATOR);

        verify(mapper).upsertPlannedHours(TOPIC, CLUB, "PREP", new BigDecimal("4"));
    }

    /**
     * ★历史豁免与「配置没落」必须报不同的错。
     *
     * <p>开售闸上线之前就已经 SELLING 的 ③ 期，条款与收款人快照都是空 —— 六个写入口
     * 报同一个 EDITION_TERMS_MISSING，现场排障会一路去查开售链路，而那一期根本救不回来
     * （开售当时的收款人版本与成本版本今天无法证明，绝不补伪快照）。
     */
        @Test
    void anEditionThatShouldHaveTermsButDoesNotStillSaysTermsMissing() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.countLegacyExemption(TOPIC)).thenReturn(0);

        assertEquals("EDITION_TERMS_MISSING", assertThrows(IllegalStateException.class,
                () -> service.requireFrozenTerms(TOPIC)).getMessage());
    }

    // ── 已确认工时的更正链：留审计、不改旧事实、不放宽「确认一次」 ───────────────
    //
    // 病：confirmActualHoursCas 的 where 写死 confirm_status='REPORTED'，
    //   reportActualHoursCas 又不收 CONFIRMED ⇒ 平台把 ONSITE 确认成 40（实际 4）之后，
    //   两条 CAS 各命中 0 行，该类工时**永久锁死在错值**，只能改库。
    //   反向（确认成偏小）更糟：那是无补救的少付。同批的 A1 有版本化更正、A2 有 append-only
    //   更正链，唯独工时没有 —— 三个同族事实两个有路一个没有。
    //
    // 修法不是放宽 confirmActualHoursCas 的旧状态（那会把「一次确认」变成「随便改」），
    // 而是加一条**带理由的退回**：先把被推翻的那条确认事实整份快照进 append-only 审计表，
    // 再把当前行退回 REPORTED 并清空 confirmed_*；重新确认仍然走原来那条唯一的 CAS。
    // 退回期间四类不齐 ⇒ confirmedHoursByKind 返 null ⇒ EXTRA 整体 PENDING，不会拿错值付款。

    private static ClubEditionHours confirmedHours(String confirmed) {
        ClubEditionHours row = new ClubEditionHours();
        row.setTopicId(TOPIC);
        row.setClubId(CLUB);
        row.setHourKind("ONSITE");
        row.setPlannedHours(new BigDecimal("4"));
        row.setReportedActualHours(new BigDecimal(confirmed));
        row.setConfirmedActualHours(new BigDecimal(confirmed));
        row.setConfirmStatus("CONFIRMED");
        row.setConfirmedBy(9L);
        row.setConfirmedAt(new Date(1_700_000_000_000L));
        return row;
    }

    @Test
    void anUnknownHourKindIsRefusedInsteadOfSilentlyCreatingAFifthCategory() {
        // 2026-08-25：原来用 reportHours 做样本，那条已随自报退役（会先抛 RETIRED）。
        // 换成仍然活着的 planHours —— 四类工时是不是封闭集合，跟谁来写无关。
        assertEquals("EDITION_HOURS_KIND_INVALID", assertThrows(IllegalArgumentException.class,
                () -> service.planHours(TOPIC, CLUB, "TRAVEL", new BigDecimal("1"), OPERATOR)).getMessage());
    }

    // ── §5.2-5 事前审批：审批晚于开工 ⇒ 自动应付 0 ─────────────────────────
    @Test
    void approvalRecordedAfterWorkStartedPaysZeroAndIsNotSilentlyDropped() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "10", DUE.getTime() - 5000L, DUE.getTime() - 4000L)));
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("6", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertEquals(new BigDecimal("0.00"), facts.getExtraByCategory().get("PREP"),
                "预审批晚于开工 ⇒ 该笔不计入可付额度");
        assertEquals(new BigDecimal("0.00"), facts.getExtraHours());
    }

    // ── §5.2-1/2/3 单价 40、公式 max(actual-plan,0)、上限 min(actual,approved) ──
    @Test
    void extraIsFortyYuanTimesTheLesserOfActualExtraAndApprovedExtra() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "3", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        // PREP 计划 4、确认 6 ⇒ 实际额外 2；批准 3 ⇒ 取 2 ⇒ 40 × 2 = 80
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("6", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertEquals(new BigDecimal("80.00"), facts.getExtraByCategory().get("PREP"));
        assertEquals(new BigDecimal("80.00"), facts.getExtraHours());
    }

    /**
     * ★缺 FINAL 六量快照 ⇒ 整体 PENDING。
     *
     * <p>2026-08-25 TICKET_SOURCE 退役前，这道闸是「算票源奖励顺带发现缺 FINAL」的<b>副作用</b>
     * （{@code FinalTicketSourceRewardSource.read} 里的 {@code gEarned == null} 分支）。
     * 副作用会跟着被算的东西一起消失，所以退役时把它显式立成 {@code hasCurrentFinalSnapshot}。
     * 这条测试就是盯着它没有跟着一起被删掉。
     */
    @Test
    void missingFinalFundsKeepsTheWholeCompensationPending() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());
        when(mapper.selectCurrentFinalGEarned(TOPIC)).thenReturn(null);   // 这一期还没结出 FINAL

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertTrue(facts.isPending());
        assertEquals("EDITION_FUNDS_FINAL_MISSING", facts.getPendingReason());
    }

    /**
     * ★★缺 FINAL 且配了人头计酬时，绝不能静默结 0。
     *
     * <p>这是 TICKET_SOURCE 退役最危险的一处：没有 FINAL 快照时，
     * {@code selectCurrentFinalAttributions} 读回的是<b>空集合</b>（MyBatis 对 List 恒不返 null），
     * 于是净票数算出 0 ⇒ 承接费 0 ⇒ <b>settled 且金额缺一块，零告警</b>。
     * 闸必须在到达 headcountAmount 之前就拦住，而不是靠它自己发现。
     */
    @Test
    void missingFinalFundsDoesNotSilentlySettleHeadcountAsZero() {
        givenSettledFinalWithHeadcount(new BigDecimal("30.00"), null);
        when(mapper.selectCurrentFinalGEarned(TOPIC)).thenReturn(null);
        when(mapper.selectCurrentFinalAttributions(TOPIC))
                .thenReturn(new ArrayList<TopicEditionFundsAttribution>());   // 缺 FINAL 时真实的读回值

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertTrue(facts.isPending(), "缺 FINAL 必须 PENDING，不许 settled");
        assertEquals("EDITION_FUNDS_FINAL_MISSING", facts.getPendingReason());
        assertNull(facts.getHeadcount(), "PENDING 的事实不带金额");
    }

    /**
     * ★「F 已到达的付款节点」在当前施工范围里<b>只有一个节点</b>：本期履约窗结束后的 FINAL。
     *
     * <p>真源：总纲 §2.1.2 写的是「付款<b>可</b>按启动、成团锁资源、活动完成和 T+7 绩效四个节点
     * <b>拆分</b>」（可选），而承载这些节点的是四档阶梯表 —— 裁决表 §三「偏离 3」把那一整节
     * 删掉、改成「一次性签一份」。所以本轮不建节点模型，这是按真源的实现选择，不是遗漏。
     *
     * <p>那条硬要求（「平台在俱乐部已经履行对应工作后取消活动，已到达的付款节点不得追回」）
     * 由这条路承重：活动没办成时，四类工时按 <b>0</b> 自报并确认，
     * {@code fulfillment_end_at} 是开售就冻死的日历事实（不是「有没有真办」），
     * 所以窗一过 FINAL 照样结得出来，冻结的 F <b>一分不打折</b>，EXTRA 自然为 0。
     */
    @Test
    void aCancelledEditionStillSettlesTheFullFrozenFixedCompensation() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("0", "0", "0", "0"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertFalse(facts.isPending(), "履约窗已过、三维已落定、四类工时已确认 ⇒ 结得出来");
        assertEquals(new BigDecimal("1500.00"), facts.getFixedCompensation(),
                "取消不得让已冻结的 F 缩水：F 的付款节点在窗结束那一刻就到了");
        assertEquals(0, BigDecimal.ZERO.compareTo(facts.getExtraHours()), "没干活就没有超范围工时");
    }

    @Test
    void approvedExtraIsAnUpperBoundNotAPayout() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "10", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        // 批准 10 但实际只多做 1 ⇒ 只付 40 × 1
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("5", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        assertEquals(new BigDecimal("40.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getExtraByCategory().get("PREP"));
    }

    /** 上限方向：实际额外 > 已批准 ⇒ 只能付到批准额，多做的不自动买单。 */
    @Test
    void actualExtraBeyondTheApprovedBudgetIsCappedAtTheApprovedHours() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "1", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        // 计划 4、确认 9 ⇒ 实际额外 5；只批了 1 ⇒ 40 × 1 = 40，不是 40 × 5 = 200
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("9", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        assertEquals(new BigDecimal("40.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getExtraByCategory().get("PREP"));
    }

    @Test
    void noExtraIsPaidWhenActualDoesNotExceedThePlan() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "10", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("3", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        assertEquals(new BigDecimal("0.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getExtraByCategory().get("PREP"));
    }

    // ── §5.2-4 四类之间不得借额度 ──────────────────────────────────────────
    @Test
    void oneCategoryNeverEatsAnotherCategorysApprovedBudget() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "1", DUE.getTime() - 4000L, DUE.getTime() - 5000L),
                approval(2L, "CONTENT", "9", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        // PREP 超额 5 但只批 1；CONTENT 批 9 却一小时没超 ⇒ 只能付 PREP 的 40 × 1
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("9", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertEquals(new BigDecimal("40.00"), facts.getExtraByCategory().get("PREP"));
        assertEquals(new BigDecimal("0.00"), facts.getExtraByCategory().get("CONTENT"));
        assertEquals(new BigDecimal("40.00"), facts.getExtraHours());
    }

    /**
     * ★合法向下更正之后到底付多少 —— 契约里唯一合法的调整路径，此前两个方向都没有测试钉住。
     *
     * <p>更正行的 approved_at 由数据库在「更正当时」写，必然晚于继承来的 work_started_at；
     * 若把事前审批判据套在更正行自己身上，它恒为假 ⇒ 更正行被丢、原行又已被 supersede
     * ⇒ 该类应付整类归零。事前审批性属于<b>根审批行</b>，更正只改额度不改「当初是否事前」。
     */
    @Test
    void aLawfulDownwardCorrectionStillPaysTheCorrectedHours() {
        // 根审批 5h（事前：approvedAt 早于 workStartedAt）→ 合法下调为 3h（更正发生在开工之后）
        ClubExtraHoursApproval root = approval(1L, "PREP", "5", DUE.getTime() - 4000L, DUE.getTime() - 5000L);
        ClubExtraHoursApproval corrected = correction(2L, root, "3", DUE.getTime() - 3000L);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(root, corrected));
        // PREP 计划 4、确认 7 ⇒ 实际额外 3；更正后额度 3 ⇒ 40 × min(3,3) = 120
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("7", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertEquals(new BigDecimal("120.00"), facts.getExtraByCategory().get("PREP"),
                "走契约唯一合法的更正路径不得让该类应付归零");
        assertEquals(new BigDecimal("120.00"), facts.getExtraHours());
    }

    /** 更正链继承的仍是根行的事前审批性：根行是事后补批的，整条链一分钱不产生。 */
    @Test
    void aCorrectionOfALateApprovalStillPaysNothing() {
        ClubExtraHoursApproval lateRoot = approval(1L, "PREP", "5", DUE.getTime() - 5000L, DUE.getTime() - 4000L);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(
                Arrays.asList(lateRoot, correction(2L, lateRoot, "3", DUE.getTime() - 3000L)));
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("7", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        assertEquals(new BigDecimal("0.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getExtraByCategory().get("PREP"),
                "更正不得把一条事后补批洗成事前审批");
    }

    /**
     * ★兄弟 correction 不得按条累加额度。写侧已经拒绝第二条（见下一条测试）+ DB 唯一键兜底，
     * 但读侧也必须独立正确：历史数据里真出现两条兄弟时，只认最后一条，绝不 5+5=10。
     */
    @Test
    void siblingCorrectionsNeverStackIntoADoubledBudget() {
        ClubExtraHoursApproval root = approval(1L, "PREP", "5", DUE.getTime() - 4000L, DUE.getTime() - 5000L);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(root,
                correction(2L, root, "5", DUE.getTime() - 3000L),
                correction(3L, root, "5", DUE.getTime() - 2000L)));
        // PREP 计划 4、确认 14 ⇒ 实际额外 10；额度只能是 5 ⇒ 40 × 5 = 200，不是 40 × 10 = 400
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("14", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        assertEquals(new BigDecimal("200.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getExtraByCategory().get("PREP"),
                "两条兄弟更正各 5h 被累加成 10h ⇒ 多付一倍");
    }

    // ── §5.1-1 每维只能 0 或 200 ───────────────────────────────────────────
    @Test
    void qualityConfirmationWritesExactlyTwoHundredOrZeroNothingInBetween() {
        givenEvidenceOn("DELIVERY_SAFETY");
        when(mapper.confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"), any(), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any()))
                .thenReturn(1);

        service.confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", true, "依据现场交付清单逐项核对", OPERATOR);
        verify(mapper).confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"),
                eq(new BigDecimal("200")), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any());

        when(mapper.confirmQualityCas(eq(TOPIC), eq(CLUB), eq("CONTENT_REPORT"), any(), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any()))
                .thenReturn(1);
        service.confirmQualityOnce(TOPIC, CLUB, "CONTENT_REPORT", false, "依据现场交付清单逐项核对", OPERATOR);
        verify(mapper).confirmQualityCas(eq(TOPIC), eq(CLUB), eq("CONTENT_REPORT"),
                eq(new BigDecimal("0")), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any());
    }

    @Test
    void anUnknownQualityDimensionIsRefused() {
        assertEquals("EDITION_QUALITY_DIMENSION_INVALID", assertThrows(IllegalArgumentException.class,
                () -> service.confirmQualityOnce(TOPIC, CLUB, "VIBES", true, "依据现场交付清单逐项核对", OPERATOR)).getMessage());
    }

    // ── §5.1-6 一次确认：并发第二次拿不到 CAS ──────────────────────────────
    @Test
    void secondQualityConfirmationLosesTheCasInsteadOfPayingTwice() {
        givenEvidenceOn("DELIVERY_SAFETY");
        when(mapper.confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"), any(), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any()))
                .thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", true, "依据现场交付清单逐项核对", OPERATOR));

        assertEquals("EDITION_QUALITY_ALREADY_CONFIRMED", error.getMessage());
    }

    /**
     * ★两个 Q 写动作都必须带理由，空理由在动任何一行之前就被拒。
     *
     * <p>一次确认不可逆（CAS 旧状态写死 PENDING），过错排除更重 —— 它是全仓唯一能推翻一个
     * 已确认 Q 决定的动作，方向是<b>加钱</b>（每维 +200，一期最多 +600），触发条件只是操作者
     * 自行断言归责方。审计 2 §「权限、审计和失败语义」对审批类动作的最低集里「理由」是明写的；
     * 本仓 {@code LogAspect} 不写请求体，不落 {@code reason} 列它就只存在于操作者的记忆里。
     */
    @Test
    void bothQualityWritesRefuseABlankReasonBeforeTouchingAnyRow() {
        givenEvidenceOn("DELIVERY_SAFETY");

        for (String blank : new String[] {null, "", "   "}) {
            assertEquals("EDITION_QUALITY_REASON_REQUIRED", assertThrows(IllegalArgumentException.class,
                    () -> service.confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", true, blank, OPERATOR))
                    .getMessage());
            assertEquals("EDITION_QUALITY_REASON_REQUIRED", assertThrows(IllegalArgumentException.class,
                    () -> service.excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", "MERCHANT", blank,
                            OPERATOR)).getMessage());
        }

        verify(mapper, never()).confirmQualityCas(any(), any(), anyString(), any(), any(), any(), any());
        verify(mapper, never()).supersedeCurrentQuality(any(), any(), anyString());
        verify(mapper, never()).insertQualityDecision(any());
    }

    /** 理由必须原样传到写入口（两条路各一遍），首尾空白剪掉但内容不变。 */
    @Test
    void theReasonReachesBothWritePathsVerbatim() {
        givenEvidenceOn("DELIVERY_SAFETY");
        when(mapper.confirmQualityCas(any(), any(), anyString(), any(), any(), any(), any())).thenReturn(1);

        service.confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", true, "  现场三项交付逐条核对通过  ", OPERATOR);

        verify(mapper).confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"),
                eq(new BigDecimal("200")), eq("现场三项交付逐条核对通过"), eq(OPERATOR), any());

        ClubQualityDecision confirmed = confirmedDecision("PLAYER_EXPERIENCE", "0");
        confirmed.setId(41L);
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(Arrays.asList(confirmed));
        when(mapper.supersedeCurrentQuality(TOPIC, CLUB, "PLAYER_EXPERIENCE")).thenReturn(1);
        when(mapper.insertQualityDecision(any())).thenReturn(1);

        service.excludeQualityByFault(TOPIC, CLUB, "PLAYER_EXPERIENCE", "MERCHANT",
                "商家 8/12 临时闭店，工单 #3312", OPERATOR);

        org.mockito.ArgumentCaptor<ClubQualityDecision> written =
                org.mockito.ArgumentCaptor.forClass(ClubQualityDecision.class);
        verify(mapper).insertQualityDecision(written.capture());
        assertEquals("商家 8/12 临时闭店，工单 #3312", written.getValue().getReason());
        assertEquals("MERCHANT", written.getValue().getFaultParty(), "归责方与依据是两件事，都要留");
    }

    /**
     * ★选期读模型逐行回读 {@code executing_club_id}：SQL 已按它收窄，这里再核一遍它真的照做了。
     *
     * <p>哪天谁把那个谓词改丢，别的俱乐部的期次会出现在这个俱乐部的选期控件里，
     * 而选错期的工时会直接进另一期的结算基数（EXTRA 按 {@code H_actual − H_plan} 算）。
     */
    @Test
    void theEditionListIsRereadRowByRowAgainstTheExecutingClub() {
        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(Arrays.asList(
                editionRow(TOPIC, CLUB), editionRow(TOPIC + 1, CLUB)));

        assertEquals(2, service.editionsForExecutingClub(CLUB).size());

        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(Arrays.asList(
                editionRow(TOPIC, CLUB), editionRow(TOPIC + 1, CLUB + 99)));

        assertEquals("EDITION_CLUB_SCOPE_LEAK", assertThrows(IllegalStateException.class,
                () -> service.editionsForExecutingClub(CLUB)).getMessage());
    }

    /** 一个期次都没有是<b>合法结果</b>（还没开售），不能抛，也不能返回 null。 */
    @Test
    void aClubWithNoFrozenEditionsGetsAnEmptyListNotAnError() {
        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(null);
        assertTrue(service.editionsForExecutingClub(CLUB).isEmpty());

        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(new ArrayList<java.util.Map<String, Object>>());
        assertTrue(service.editionsForExecutingClub(CLUB).isEmpty());
    }

    /** W10a #26:空原因只从服务端真源推出 —— 有期次 / 已承接未开售 / 未承接,三种互斥。 */
    @Test
    void editionsEmptyReasonDistinguishesNotUndertakenFromNotOnSale() {
        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(Arrays.asList(editionRow(TOPIC, CLUB)));
        assertEquals(IClubEditionCompensationService.EDITIONS_REASON_HAS_EDITIONS, service.editionsEmptyReason(CLUB));
        verify(mapper, never()).countEffectiveOrganizerOrdersForClub(CLUB);

        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(new ArrayList<java.util.Map<String, Object>>());
        when(mapper.countEffectiveOrganizerOrdersForClub(CLUB)).thenReturn(2);
        assertEquals(IClubEditionCompensationService.EDITIONS_REASON_UNDERTAKEN_NOT_ON_SALE,
                service.editionsEmptyReason(CLUB));

        when(mapper.countEffectiveOrganizerOrdersForClub(CLUB)).thenReturn(0);
        assertEquals(IClubEditionCompensationService.EDITIONS_REASON_NOT_UNDERTAKEN, service.editionsEmptyReason(CLUB));
    }

    @Test
    void editionsEmptyReasonStillFailsClosedOnScopeLeak() {
        when(mapper.selectEditionsForExecutingClub(CLUB)).thenReturn(Arrays.asList(editionRow(TOPIC, CLUB + 99)));
        assertEquals("EDITION_CLUB_SCOPE_LEAK", assertThrows(IllegalStateException.class,
                () -> service.editionsEmptyReason(CLUB)).getMessage());
    }

    private static java.util.Map<String, Object> editionRow(long topicId, long executingClubId) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("topicId", topicId);
        row.put("executingClubId", executingClubId);
        row.put("topicName", "毛孩子探店日 · 第 " + topicId + " 期");
        return row;
    }

    /** §5.1-3 缺证据不得判过：「缺证据 ⇒ 0」不能只靠「没人去点确认」实现，误点一下就是 200。 */
        /** 判不过那一侧不需要证据：没证据本来就该落 0。 */
    @Test
    void aDimensionWithoutEvidenceMayStillBeConfirmedAsZero() {
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB))
                .thenReturn(Arrays.asList(pendingDecision("DELIVERY_SAFETY")));
        when(mapper.confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"), any(), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any()))
                .thenReturn(1);

        service.confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", false, "依据现场交付清单逐项核对", OPERATOR);

        verify(mapper).confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"),
                eq(BigDecimal.ZERO), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any());
    }

    // ── §5.1-8 过错排除：读侧早就写好了，此前<b>没有任何写入路径</b> ────────────
    @Test
    void faultExclusionWritesANewCurrentVersionPointingAtTheOneItOverrides() {
        ClubQualityDecision confirmed = confirmedDecision("DELIVERY_SAFETY", "200");
        confirmed.setId(41L);
        confirmed.setVersion(1L);
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(Arrays.asList(confirmed));
        when(mapper.supersedeCurrentQuality(TOPIC, CLUB, "DELIVERY_SAFETY")).thenReturn(1);

        service.excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", "MERCHANT", "依据现场交付清单逐项核对", OPERATOR);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).supersedeCurrentQuality(TOPIC, CLUB, "DELIVERY_SAFETY");
        org.mockito.ArgumentCaptor<ClubQualityDecision> captor =
                org.mockito.ArgumentCaptor.forClass(ClubQualityDecision.class);
        order.verify(mapper).insertQualityDecision(captor.capture());
        ClubQualityDecision written = captor.getValue();
        assertEquals(Integer.valueOf(1), written.getExcludedByFault());
        assertEquals(Integer.valueOf(1), written.getCurrent());
        assertEquals(2L, written.getVersion());
        assertEquals(41L, written.getCorrectionOf());
        assertEquals(ClubQualityDecision.STATUS_CONFIRMED, written.getDecisionStatus(),
                "排除后必须是已决，留 PENDING 会让 FINAL 卡在 T+7 之前");
        assertEquals(new BigDecimal("200"), written.getAmount(),
                "总纲字面：商家/平台故障从俱乐部绩效责任中剔除 ⇒ 该维不扣分、照贡献满额");
        assertEquals("MERCHANT", written.getFaultParty(),
                "归责方必须落库：本仓 @Log 不写请求体，sys_oper_log 里查不到是谁的过错");
        assertEquals(OPERATOR, written.getConfirmedBy());
    }

    /**
     * ★方向：过错排除是「不扣俱乐部的分」，不是「不给俱乐部钱」。
     *
     * <p>总纲 §2.1.2 原文「商家或平台故障导致的未完成，<b>从俱乐部绩效责任中剔除</b>」——
     * 剔除的宾语是失分事件，不是报酬。把它落成俱乐部少拿 200 意思正好相反，
     * 而且是每期最多三维 ¥600 的系统性少付。
     */
    @Test
    void aDimensionExcludedByMerchantOrPlatformFaultStillPaysItsTwoHundred() {
        ClubQualityDecision failed = confirmedDecision("PLAYER_EXPERIENCE", "0");
        failed.setId(41L);
        failed.setVersion(1L);
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(Arrays.asList(failed));
        when(mapper.supersedeCurrentQuality(TOPIC, CLUB, "PLAYER_EXPERIENCE")).thenReturn(1);

        service.excludeQualityByFault(TOPIC, CLUB, "PLAYER_EXPERIENCE", "PLATFORM", "依据现场交付清单逐项核对", OPERATOR);

        org.mockito.ArgumentCaptor<ClubQualityDecision> captor =
                org.mockito.ArgumentCaptor.forClass(ClubQualityDecision.class);
        verify(mapper).insertQualityDecision(captor.capture());
        assertEquals(new BigDecimal("200"), captor.getValue().getAmount(),
                "平台自己的故障不得让俱乐部丢掉这一维的 200");
        assertEquals("PLATFORM", captor.getValue().getFaultParty());
    }

    @Test
    void onlyMerchantOrPlatformFaultCanExcludeADimension() {
        assertEquals("EDITION_QUALITY_FAULT_PARTY_INVALID", assertThrows(IllegalArgumentException.class,
                () -> service.excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", "CLUB", "依据现场交付清单逐项核对", OPERATOR)).getMessage());
        assertEquals("EDITION_QUALITY_FAULT_PARTY_INVALID", assertThrows(IllegalArgumentException.class,
                () -> service.excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", null, "依据现场交付清单逐项核对", OPERATOR)).getMessage());
        verify(mapper, never()).supersedeCurrentQuality(any(), any(), anyString());
        verify(mapper, never()).insertQualityDecision(any());
    }

    @Test
    void anAlreadyExcludedDimensionIsNotExcludedTwice() {
        ClubQualityDecision excluded = confirmedDecision("DELIVERY_SAFETY", "200");
        excluded.setId(41L);
        excluded.setExcludedByFault(1);
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(Arrays.asList(excluded));

        assertEquals("EDITION_QUALITY_ALREADY_EXCLUDED", assertThrows(IllegalStateException.class,
                () -> service.excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", "PLATFORM",
                        "依据现场交付清单逐项核对", OPERATOR)).getMessage());
        verify(mapper, never()).insertQualityDecision(any());
    }

    /** 并发改了 current ⇒ supersede 命中 0 行 ⇒ 整笔回滚，绝不出现两个 current。 */
    @Test
    void aLostSupersedeCasStopsTheExclusionBeforeItWritesASecondCurrentVersion() {
        ClubQualityDecision confirmed = confirmedDecision("DELIVERY_SAFETY", "200");
        confirmed.setId(41L);
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(Arrays.asList(confirmed));
        when(mapper.supersedeCurrentQuality(TOPIC, CLUB, "DELIVERY_SAFETY")).thenReturn(0);

        assertEquals("EDITION_QUALITY_SUPERSEDE_CONFLICT", assertThrows(IllegalStateException.class,
                () -> service.excludeQualityByFault(TOPIC, CLUB, "DELIVERY_SAFETY", "PLATFORM",
                        "依据现场交付清单逐项核对", OPERATOR)).getMessage());
        verify(mapper, never()).insertQualityDecision(any());
    }

    @Test
    void excludingADimensionOfAClubThatIsNotTheExecutingOneIsRefused() {
        assertEquals("EDITION_CLUB_NOT_EXECUTING", assertThrows(IllegalStateException.class,
                () -> service.excludeQualityByFault(TOPIC, 999L, "DELIVERY_SAFETY", "MERCHANT",
                        "依据现场交付清单逐项核对", OPERATOR)).getMessage());
    }

    private void givenEvidenceOn(String dimension) {
        ClubQualityDecision pending = pendingDecision(dimension);
        pending.setId(41L);
        pending.setEvidenceHash("EVIDENCE_HASH");
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(Arrays.asList(pending));
    }

    // ── §5.1-3/4 T+7 前 PENDING，不是默认 600；到期缺证据显式落 0 ──────────
    @Test
    void qualityIsPendingBeforeTheEvidenceDeadlineNotSixHundred() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "4", "4"));
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(
                Arrays.asList(pendingDecision("DELIVERY_SAFETY"), pendingDecision("PLAYER_EXPERIENCE"),
                        pendingDecision("CONTENT_REPORT")));

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, BEFORE_DUE);

        assertTrue(facts.isPending());
        assertEquals("EDITION_QUALITY_PENDING", facts.getPendingReason());
        assertNull(facts.getQuality());
    }

    @Test
    void dimensionsStillMissingAtTheDeadlineAreExplicitlyZero() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "4", "4"));
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(
                Arrays.asList(confirmedDecision("DELIVERY_SAFETY", "200"), pendingDecision("PLAYER_EXPERIENCE"),
                        pendingDecision("CONTENT_REPORT")));

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertFalse(facts.isPending());
        assertEquals(new BigDecimal("200"), facts.getQuality());
    }

    @Test
    void theOnlyLegalQualityTotalsAreZeroTwoHundredFourHundredOrSixHundred() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "4", "4"));
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(
                Arrays.asList(confirmedDecision("DELIVERY_SAFETY", "200"),
                        confirmedDecision("PLAYER_EXPERIENCE", "199"),
                        confirmedDecision("CONTENT_REPORT", "200")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.computeFinalCompensationFacts(TOPIC, AT_DUE));

        assertEquals("EDITION_QUALITY_AMOUNT_INVALID", error.getMessage());
    }

    // ── §5.1-8 商家/平台过错排除：该维不扣分，照拿 200 ──────────────────────
    @Test
    void aDimensionExcludedByMerchantOrPlatformFaultKeepsItsTwoHundredInTheTotal() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "4", "4"));
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        // 写侧已经把排除行的金额落成满额（见 excludeQualityByFault），读侧不再另做方向判断。
        ClubQualityDecision excluded = confirmedDecision("PLAYER_EXPERIENCE", "200");
        excluded.setExcludedByFault(1);
        excluded.setFaultParty("MERCHANT");
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(
                Arrays.asList(confirmedDecision("DELIVERY_SAFETY", "200"), excluded,
                        confirmedDecision("CONTENT_REPORT", "200")));

        assertEquals(new BigDecimal("600"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getQuality(),
                "商家的过错不得让俱乐部这一期少拿 200");
    }

    // ── 证据截止在写侧真的是闸（迟交不得拿 200） ────────────────────────────
                /**
     * ★证据截止只约束<b>交付</b>，不约束平台的审批时刻。
     *
     * <p>总纲 §2.1.2 那一维的原文是「预热、现场素材、回顾和 T+7 报告<b>按清单交付</b>」——
     * 管的是交付方。上一版把同一个 due_at 也套在「平台点确认」上，于是俱乐部按时交齐、
     * 平台在 T+7 之后才点一下，这一维照样归 0 —— 正是锁定裁决禁止的那一侧
     * （商家/平台过错不得让俱乐部少拿）。
     *
     * <p>2026-09-10：证据链路已整条删除，这条用例要证的「平台晚判不扣俱乐部的钱」
     * 不依赖证据，原样成立。
     */
    @Test
    void afterTheDeadlineADimensionDeliveredInTimeCanStillBeConfirmedAsPassed() {
        ClubEditionCompensationTerms expired = terms();
        expired.setEvidenceDueAt(new Date(System.currentTimeMillis() - DAY_MILLIS));
        expired.setFulfillmentEndAt(new Date(System.currentTimeMillis() - 8L * DAY_MILLIS));
        expired.setConfigHash(ClubEditionCompensationServiceImpl.configHash(expired));
        when(mapper.selectTerms(TOPIC)).thenReturn(expired);
        givenEvidenceOn("DELIVERY_SAFETY");
        when(mapper.confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"), any(), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any()))
                .thenReturn(1);

        service.confirmQualityOnce(TOPIC, CLUB, "DELIVERY_SAFETY", true, "依据现场交付清单逐项核对", OPERATOR);

        verify(mapper).confirmQualityCas(eq(TOPIC), eq(CLUB), eq("DELIVERY_SAFETY"),
                eq(new BigDecimal("200")), eq("依据现场交付清单逐项核对"), eq(OPERATOR), any());
    }

    /** 但没交证据的维度，截止之后照样判不了 200；判 0 是收口路径，放行。 */
        // ── 工时自报退役契约（2026-08-25 产品裁决）────────────────────────────────
    //
    // 退役的是【实际】工时整条：自报 → 后台确认 → 更正 → EXTRA 预批／更正。
    // 理由是实际工时线下无法核实，而 EXTRA 单价 ¥40／人小时 —— 自报即计酬等于给了一条
    // 「凭空写小时数换钱」的通道，服务端没有任何事实能证伪它。
    //
    // 这条契约替代了原来那 15 个逐项守护退役路径不变量的测试：那些不变量属于一条
    // 不再存在的路径，留着是噪音；真正要守的是「它不能悄悄回来」。
    @Test
    void everyRetiredHoursWritePathIsRefusedWithTheRetirementCode() {
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> service.reportHours(TOPIC, CLUB, "PREP", new BigDecimal("1"), OPERATOR)).getMessage());
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> service.confirmActualHours(TOPIC, CLUB, "PREP", new BigDecimal("1"), OPERATOR)).getMessage());
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> service.correctConfirmedHours(TOPIC, CLUB, "PREP", "理由", OPERATOR)).getMessage());
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> service.preapproveExtraHours(TOPIC, CLUB, "PREP", new BigDecimal("1"),
                        DUE, "idem-1", OPERATOR)).getMessage());
        assertEquals("EDITION_ACTUAL_HOURS_RETIRED", assertThrows(IllegalStateException.class,
                () -> service.correctExtraHours(TOPIC, CLUB, 1L, new BigDecimal("1"),
                        "idem-2", OPERATOR)).getMessage());
    }

    // ★拒绝必须发生在【任何写入之前】：只要有一次落到 mapper 上，退役就是假的。
    @Test
    void retiredPathsNeverTouchTheMapperBeforeRefusing() {
        assertThrows(IllegalStateException.class,
                () -> service.reportHours(TOPIC, CLUB, "PREP", new BigDecimal("1"), OPERATOR));
        assertThrows(IllegalStateException.class,
                () -> service.preapproveExtraHours(TOPIC, CLUB, "PREP", new BigDecimal("1"),
                        DUE, "idem-1", OPERATOR));
        verify(mapper, never()).reportActualHoursCas(anyLong(), anyLong(), anyString(),
                any(BigDecimal.class), anyLong());
        verify(mapper, never()).insertExtraApproval(any(ClubExtraHoursApproval.class));
    }

    // ★没有被退役的那条必须仍然活着 —— 否则这次退役就变成了「顺手把 F 也砍了」。
    // 2026-09-10:质量证据那半条随用户裁决「探店日不交证据」整条删除,不再在此断言。
    @Test
    void planHoursIsNotRetired() {
        // 断言的是「不会被退役闸挡下」，不是「一定成功」—— planHours 还有开售后冻结这道自己的闸，
        // 那不属于本次退役的范围。
        assertNotEquals("EDITION_ACTUAL_HOURS_RETIRED",
                assertThrows(RuntimeException.class,
                        () -> service.planHours(TOPIC, CLUB, "PREP", new BigDecimal("6"), OPERATOR))
                        .getMessage(),
                "计划工时是 F 的金额来源，由平台登记，不在退役范围内");
    }

    // ── §5.5-2（2026-08-25 反转）工时自报退役后，未确认工时【不再】阻塞 FINAL ─────
    //
    // 反转的理由：F 的金额读的是开售冻结的 fixed_compensation（由平台登记的 H_plan 算出），
    // 与自报无关；那道「四类全部 CONFIRMED」的闸原本只服务 EXTRA，卡住 F/Q 是副作用。
    // 自报整条退役后再留着它，等于让承接方永远拿不到 ¥2,000 保底。
    @Test
    void unconfirmedHoursNoLongerBlockFinalSoTheFloorStillGetsPaid() {
        List<ClubEditionHours> hours = confirmedHours("6", "4", "4", "4");
        hours.get(1).setConfirmStatus("REPORTED");
        hours.get(1).setConfirmedActualHours(null);
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(hours);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertFalse(facts.isPending(), "工时没确认不该再把 F/Q 一起卡住");
        assertEquals(0, new BigDecimal("1500.00").compareTo(facts.getFixedCompensation()), "F 照常结算");
        assertEquals(0, BigDecimal.ZERO.compareTo(facts.getExtraHours()),
                "四类工时不完整 ⇒ 没有可计的 EXTRA，落 0 而不是 PENDING");
    }

    // ★★存量少付的那一支：确认了 3 类、但已经批过 EXTRA。
    //   confirmedHoursByKind 是「四类全齐才返回」，缺一即 null —— 如果这里直接落 0，
    //   FINAL 会从「PENDING(不付、看得见)」变成「settled 且金额缺一块、零告警」，
    //   那正是本类第 3 条纪律禁止的「把『没查到』当成 0 通过」。
    @Test
    void partiallyConfirmedHoursWithApprovedExtraStayPendingInsteadOfSilentlyUnderpaying() {
        List<ClubEditionHours> hours = confirmedHours("6", "4", "4", "4");
        hours.get(1).setConfirmStatus("REPORTED");     // 只差这一类没确认
        hours.get(1).setConfirmedActualHours(null);
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(hours);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "2", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertTrue(facts.isPending(), "有已批 EXTRA 却凑不齐工时事实，必须 PENDING 而不是少付结清");
        assertEquals("EDITION_HOURS_NOT_CONFIRMED", facts.getPendingReason());
    }

    // ★存量保护：退役前已经把四类工时确认完、并且批过 EXTRA 的期次，必须照原样结清。
    //   把 EXTRA 一刀切成 0 会让这些期次少付 —— 对平台是安全侧，对合同对手方是违约。
    @Test
    void editionsThatAlreadyConfirmedHoursStillGetTheirApprovedExtra() {
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("6", "4", "4", "4"));
        // 审批早于开工、开工落在期内 ⇒ 这笔 EXTRA 是可付的
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "2", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertFalse(facts.isPending());
        assertTrue(facts.getExtraHours().signum() > 0,
                "存量已确认工时 + 已批 EXTRA 的期次不得被退役抹成 0");
    }

    @Test
    void finalIsBlockedWhenSaleTermsWereNeverFrozen() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.computeFinalCompensationFacts(TOPIC, AT_DUE));

        assertEquals("EDITION_TERMS_MISSING", error.getMessage());
    }

    @Test
    void driftedTermsConfigHashBlocksFinal() {
        ClubEditionCompensationTerms drifted = terms();
        drifted.setConfigHash("0000");
        when(mapper.selectTerms(TOPIC)).thenReturn(drifted);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.computeFinalCompensationFacts(TOPIC, AT_DUE));

        assertEquals("EDITION_TERMS_TAMPERED", error.getMessage());
    }

    // ── 固定补偿 F 与 Q/EXTRA 是三个不同事实，分开带出 ─────────────────────
    @Test
    void fixedQualityAndExtraStayThreeSeparateFactsAndAreNeverNettedOff() {
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "ONSITE", "2", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "6", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertEquals(new BigDecimal("1500.00"), facts.getFixedCompensation());
        assertEquals(new BigDecimal("600"), facts.getQuality());
        assertEquals(new BigDecimal("80.00"), facts.getExtraHours());
        assertEquals(CLUB, facts.getClubId());
    }

    // ── §5.1-7 快照漂移：FINAL 必须按开售冻结的费率算，不按当前常量 ──────────
    @Test
    void finalPaysTheRateFrozenAtSaleTimeNotTodaysConstant() {
        ClubEditionCompensationTerms legacyRate = terms();
        legacyRate.setExtraHourRate(new BigDecimal("50"));
        legacyRate.setConfigHash(ClubEditionCompensationServiceImpl.configHash(legacyRate));
        when(mapper.selectTerms(TOPIC)).thenReturn(legacyRate);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(Arrays.asList(
                approval(1L, "PREP", "3", DUE.getTime() - 4000L, DUE.getTime() - 5000L)));
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("6", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertEquals(new BigDecimal("100.00"), facts.getExtraByCategory().get("PREP"),
                "50 元/小时 × 2 小时 —— 用当前常量 40 算就会得到 80");
    }

    private ClubExtraHoursApproval captureApproval() {
        org.mockito.ArgumentCaptor<ClubExtraHoursApproval> captor =
                org.mockito.ArgumentCaptor.forClass(ClubExtraHoursApproval.class);
        verify(mapper).insertExtraApproval(captor.capture());
        return captor.getValue();
    }

    // ── HEADCOUNT：按已赚票数计酬的承接费（2026-08-25 换轨） ──────────────────

    /** 开售时把当期成本行上的单价与封顶一起冻进条款：一次读回同一行，不允许 rate/cap 来自不同版本。 */
    @Test
    void saleFreezesTheHeadcountRateAndCapFromTheCurrentCostRow() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2000.00"));
        givenAllFourPlannedHoursRegistered();
        when(mapper.selectCurrentHeadcountConfig(TOPIC)).thenReturn(
                headcountConfig(new BigDecimal("30.00"), new BigDecimal("900.00")));

        ClubEditionCompensationTerms frozen =
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END);

        assertEquals(new BigDecimal("30.00"), frozen.getHeadcountRate());
        assertEquals(new BigDecimal("900.00"), frozen.getHeadcountCap());
    }

    /**
     * 缺成本行时 headcount 冻成 0／不封顶，<b>不</b>抛错 —— 与 F 的处置刻意不同。
     *
     * <p>F 缺行必须拦（那是合同义务，静默冻 0 等于违约）；人头计酬是<b>可选</b>的额外条款，
     * 「这一期只发保底」是最常见的正常配置，把它当事实缺失去拦会让绝大多数期次开不了售。
     */
    @Test
    void aMissingHeadcountConfigFreezesZeroInsteadOfBlockingTheSale() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2000.00"));
        givenAllFourPlannedHoursRegistered();
        when(mapper.selectCurrentHeadcountConfig(TOPIC)).thenReturn(null);

        ClubEditionCompensationTerms frozen =
                service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END);

        assertEquals(BigDecimal.ZERO, frozen.getHeadcountRate());
        assertNull(frozen.getHeadcountCap(), "不封顶要用 null 表达，别拿 0 冒充（0 会把这一期的承接费全吃掉）");
    }

    /** 负单价／负封顶不许冻进条款：那会让 FINAL 结出一笔负应付，把台账做成倒扣。 */
    @Test
    void negativeHeadcountConfigBlocksTheSale() {
        when(mapper.selectTerms(TOPIC)).thenReturn(null);
        when(mapper.selectCurrentFixedCompensation(TOPIC)).thenReturn(new BigDecimal("2000.00"));
        givenAllFourPlannedHoursRegistered();

        when(mapper.selectCurrentHeadcountConfig(TOPIC)).thenReturn(
                headcountConfig(new BigDecimal("-1.00"), null));
        assertEquals("EDITION_TERMS_HEADCOUNT_RATE_INVALID",
                assertThrows(IllegalStateException.class,
                        () -> service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END))
                        .getMessage());

        when(mapper.selectCurrentHeadcountConfig(TOPIC)).thenReturn(
                headcountConfig(new BigDecimal("30.00"), new BigDecimal("-1.00")));
        assertEquals("EDITION_TERMS_HEADCOUNT_CAP_INVALID",
                assertThrows(IllegalStateException.class,
                        () -> service.freezeTermsForSale(TOPIC, CLUB, AT_DUE, DUE, FULFILLMENT_END))
                        .getMessage());

        verify(mapper, never()).insertTerms(any());
    }

    /** FINAL：承接费 = 冻结单价 × Σ净票数，取全部归因行之和（与每张票的引流归属无关）。 */
    @Test
    void headcountPaysTheFrozenRateTimesTheFinalTicketCount() {
        givenSettledFinalWithHeadcount(new BigDecimal("30.00"), null);
        when(mapper.selectCurrentFinalAttributions(TOPIC)).thenReturn(attributionsOf(9L, 4L));

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertFalse(facts.isPending());
        assertEquals(new BigDecimal("390.00"), facts.getHeadcount(), "30 × (9+4) = 390");
    }

    /** 封顶生效：算出来超过封顶就付封顶，平台侧敞口有上界。 */
    @Test
    void headcountIsCappedAtTheFrozenCap() {
        givenSettledFinalWithHeadcount(new BigDecimal("30.00"), new BigDecimal("300.00"));
        when(mapper.selectCurrentFinalAttributions(TOPIC)).thenReturn(attributionsOf(20L));

        assertEquals(new BigDecimal("300.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getHeadcount(),
                "600 > 封顶 300 ⇒ 付 300");
    }

    /**
     * ★净票数为 NULL（本列落库前冻的老 FINAL 快照）⇒ 整体 PENDING。
     *
     * <p>「没有值」不等于「值是 0」。当 0 过 = 静默少付；退回读毛计数 = 静默多付。
     * 这是本类第 3 条纪律:缺事实一律 PENDING。
     *
     * <p>⚠️ 这条测的是<b>真实可达</b>的路径。原先写的是 {@code thenReturn(null)}（整个列表为
     * null），那是假绿:MyBatis 对 List 返回类型只会给空集合,永远不返 null。
     */
    @Test
    void aLegacySnapshotWithoutNetTicketCountKeepsTheWholeCompensationPending() {
        givenSettledFinalWithHeadcount(new BigDecimal("30.00"), null);
        List<TopicEditionFundsAttribution> legacy = attributionsOf(9L);
        legacy.get(0).setNetTicketCount(null);          // 老快照没有这个事实
        when(mapper.selectCurrentFinalAttributions(TOPIC)).thenReturn(legacy);

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertTrue(facts.isPending());
        assertEquals("EDITION_HEADCOUNT_FACTS_MISSING", facts.getPendingReason());
    }

    /**
     * ★计酬基数必须是<b>净</b>票数,不是毛计数 —— 全额退款的报名不许照付。
     *
     * <p>{@code ticket_count} 是 {@code count(*)} over {@code payment_status = 2},
     * 退款只在 {@code earned_amount} 里扣。拿它计酬 = 「整场退光了还照付承接费」。
     */
    @Test
    void headcountPaysOnTheNetTicketCountNotTheGrossPaidCount() {
        givenSettledFinalWithHeadcount(new BigDecimal("30.00"), null);
        TopicEditionFundsAttribution row = new TopicEditionFundsAttribution();
        row.setTicketCount(10L);        // 10 张付过款
        row.setNetTicketCount(6L);      // 其中 4 张全额退了
        when(mapper.selectCurrentFinalAttributions(TOPIC))
                .thenReturn(new ArrayList<TopicEditionFundsAttribution>(Arrays.asList(row)));

        assertEquals(new BigDecimal("180.00"),
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getHeadcount(),
                "30 × 6(净) = 180；若误读毛计数会得到 300");
    }

    /** 一张没卖:空归因是合法答案,结 0 而不是 PENDING —— 「卖了 0 张」是事实,不是缺事实。 */
    @Test
    void anEditionThatSoldNothingSettlesZeroInsteadOfPending() {
        givenSettledFinalWithHeadcount(new BigDecimal("30.00"), null);
        when(mapper.selectCurrentFinalAttributions(TOPIC))
                .thenReturn(new ArrayList<TopicEditionFundsAttribution>());

        FinalCompensationFacts facts = service.computeFinalCompensationFacts(TOPIC, AT_DUE);

        assertFalse(facts.isPending());
        assertEquals(new BigDecimal("0.00"), facts.getHeadcount());
    }

    /** 没配人头计酬的期次结 0，且<b>不去读</b>归因：那种期次本来就不看票数，缺快照不该把它卡住。 */
    @Test
    void anEditionWithoutHeadcountSettlesZeroWithoutReadingAttributions() {
        givenSettledFinalWithHeadcount(null, null);

        assertEquals(BigDecimal.ZERO,
                service.computeFinalCompensationFacts(TOPIC, AT_DUE).getHeadcount());
        verify(mapper, never()).selectCurrentFinalAttributions(anyLong());
    }

    /**
     * ★向后兼容负控：存量条款（DDL 加列后 rate=0.00／cap=NULL）的指纹必须<b>没变</b>。
     *
     * <p>无条件把这两列拼进 configHash 会让每一条历史条款重算指纹都对不上 ⇒
     * requireFrozenTerms 全部抛 EDITION_TERMS_TAMPERED ⇒ 所有历史期次的 FINAL 当场瘫掉，
     * 而且现场看起来像「库被人改过」。这条盯着它不会发生。
     */
    @Test
    void addingTheHeadcountColumnsDoesNotInvalidateExistingFrozenTerms() {
        ClubEditionCompensationTerms legacy = terms();          // 指纹是在没有这两列时算的
        legacy.setHeadcountRate(new BigDecimal("0.00"));        // DDL 加列后存量行的实际取值
        legacy.setHeadcountCap(null);
        when(mapper.selectTerms(TOPIC)).thenReturn(legacy);

        assertSame(legacy, service.requireFrozenTerms(TOPIC));
    }

    /** 但真配了之后就钉死：改单价 ⇒ 指纹对不上 ⇒ 判篡改。「冻了但改了没人知道」不许存在。 */
    @Test
    void tamperingWithAFrozenHeadcountRateIsDetected() {
        ClubEditionCompensationTerms frozen = terms();
        frozen.setHeadcountRate(new BigDecimal("30.00"));
        frozen.setConfigHash(ClubEditionCompensationServiceImpl.configHash(frozen));
        frozen.setHeadcountRate(new BigDecimal("50.00"));       // 事后被「最新配置」改写
        when(mapper.selectTerms(TOPIC)).thenReturn(frozen);

        assertEquals("EDITION_TERMS_TAMPERED", assertThrows(IllegalStateException.class,
                () -> service.requireFrozenTerms(TOPIC)).getMessage());
    }

    /** 把 FINAL 前置事实铺齐，只留 headcount 一个变量。 */
    private void givenSettledFinalWithHeadcount(BigDecimal rate, BigDecimal cap) {
        ClubEditionCompensationTerms frozen = terms();
        frozen.setHeadcountRate(rate);
        frozen.setHeadcountCap(cap);
        frozen.setConfigHash(ClubEditionCompensationServiceImpl.configHash(frozen));
        when(mapper.selectTerms(TOPIC)).thenReturn(frozen);
        when(mapper.selectExtraApprovals(TOPIC, CLUB)).thenReturn(new ArrayList<ClubExtraHoursApproval>());
        when(mapper.selectHoursForClub(TOPIC, CLUB)).thenReturn(confirmedHours("4", "4", "4", "4"));
        when(mapper.selectCurrentQualityDecisions(TOPIC, CLUB)).thenReturn(allDimensionsConfirmed());
    }

    private static Map<String, Object> headcountConfig(BigDecimal rate, BigDecimal cap) {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("headcountRate", rate);
        config.put("headcountCap", cap);
        return config;
    }

    /** 归因行：毛计数与净票数分开给，好让「拿错了那一列」直接暴露出来。 */
    private static List<TopicEditionFundsAttribution> attributionsOf(long... netTicketCounts) {
        List<TopicEditionFundsAttribution> rows = new ArrayList<TopicEditionFundsAttribution>();
        for (long net : netTicketCounts) {
            TopicEditionFundsAttribution row = new TopicEditionFundsAttribution();
            // 毛计数刻意比净票数大：拿错列会算出不同的钱，测试就能分辨。
            row.setTicketCount(net + 100L);
            row.setNetTicketCount(net);
            rows.add(row);
        }
        return rows;
    }

    private static ClubEditionCompensationTerms terms() {
        ClubEditionCompensationTerms t = new ClubEditionCompensationTerms();
        t.setId(1L);
        t.setTopicId(TOPIC);
        t.setExecutingClubId(CLUB);
        t.setFixedCompensation(new BigDecimal("1500.00"));
        t.setExtraHourRate(new BigDecimal("40"));
        t.setQualityDimensionAmount(new BigDecimal("200"));
        t.setQualityDimensions("DELIVERY_SAFETY,PLAYER_EXPERIENCE,CONTENT_REPORT");
        t.setEvidenceDueAt(DUE);
        t.setFulfillmentEndAt(FULFILLMENT_END);
        t.setExtraApproverUserId(OPERATOR);
        t.setFrozenAt(new Date(1_690_000_000_000L));
        t.setConfigHash(ClubEditionCompensationServiceImpl.configHash(t));
        return t;
    }

    /** 合法预审批要求 approvedAt 严格早于 workStartedAt；两个时刻分开传，避免"顺手写反"。 */
    private static ClubExtraHoursApproval approval(Long id, String category, String hours,
                                                   long workStartedAtMillis, long approvedAtMillis) {
        ClubExtraHoursApproval a = new ClubExtraHoursApproval();
        a.setId(id);
        a.setTopicId(TOPIC);
        a.setClubId(CLUB);
        a.setCategory(category);
        a.setApprovedHours(new BigDecimal(hours));
        a.setApprovedBy(OPERATOR);
        a.setApprovedAt(new Date(approvedAtMillis));
        a.setWorkStartedAt(new Date(workStartedAtMillis));
        return a;
    }

    /**
     * 更正事件的真实形状：继承根行的 category 与 workStartedAt，
     * 而 approvedAt 由数据库在<b>更正当时</b>写 ⇒ 必然晚于开工时刻。
     */
    private static ClubExtraHoursApproval correction(Long id, ClubExtraHoursApproval root, String hours,
                                                     long approvedAtMillis) {
        ClubExtraHoursApproval c = approval(id, root.getCategory(), hours,
                root.getWorkStartedAt().getTime(), approvedAtMillis);
        c.setCorrectionOf(root.getId());
        return c;
    }

    /** 只登记了计划、还没自报/确认的四类工时 —— 开售当刻的正常形状。 */
    private static List<ClubEditionHours> plannedOnlyHours() {
        return plannedHoursOf("4");
    }

    /** 四类各 {@code perKind} 小时的计划工时行；ΣH_plan = 4 × perKind。 */
    private static List<ClubEditionHours> plannedHoursOf(String perKind) {
        List<ClubEditionHours> list = new ArrayList<ClubEditionHours>();
        for (String kind : new String[]{"PREP", "CONTENT", "ONSITE", "REVIEW"}) {
            ClubEditionHours h = new ClubEditionHours();
            h.setTopicId(TOPIC);
            h.setClubId(CLUB);
            h.setHourKind(kind);
            h.setPlannedHours(new BigDecimal(perKind));
            h.setConfirmStatus("UNREPORTED");
            list.add(h);
        }
        return list;
    }

    private static List<ClubEditionHours> confirmedHours(String prep, String content, String onsite, String review) {
        String[] kinds = {"PREP", "CONTENT", "ONSITE", "REVIEW"};
        String[] actual = {prep, content, onsite, review};
        List<ClubEditionHours> list = new ArrayList<ClubEditionHours>();
        for (int i = 0; i < kinds.length; i++) {
            ClubEditionHours h = new ClubEditionHours();
            h.setTopicId(TOPIC);
            h.setClubId(CLUB);
            h.setHourKind(kinds[i]);
            h.setPlannedHours(new BigDecimal("4"));
            h.setReportedActualHours(new BigDecimal(actual[i]));
            h.setConfirmedActualHours(new BigDecimal(actual[i]));
            h.setConfirmStatus("CONFIRMED");
            list.add(h);
        }
        return list;
    }

    private static ClubQualityDecision pendingDecision(String dimension) {
        ClubQualityDecision d = new ClubQualityDecision();
        d.setTopicId(TOPIC);
        d.setClubId(CLUB);
        d.setDimension(dimension);
        d.setDecisionStatus(ClubQualityDecision.STATUS_PENDING);
        d.setDueAt(DUE);
        d.setCurrent(1);
        return d;
    }

    private static ClubQualityDecision confirmedDecision(String dimension, String amount) {
        ClubQualityDecision d = pendingDecision(dimension);
        d.setDecisionStatus(ClubQualityDecision.STATUS_CONFIRMED);
        d.setAmount(new BigDecimal(amount));
        d.setConfirmedBy(OPERATOR);
        d.setConfirmedAt(new Date(DUE.getTime() - 10L));
        return d;
    }

    private static List<ClubQualityDecision> allDimensionsConfirmed() {
        return Arrays.asList(confirmedDecision("DELIVERY_SAFETY", "200"),
                confirmedDecision("PLAYER_EXPERIENCE", "200"),
                confirmedDecision("CONTENT_REPORT", "200"));
    }
}
