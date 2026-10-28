package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.ClubPayable;
import com.chengyinhub.business.domain.ClubPayableBatch;
import com.chengyinhub.business.domain.TopicEditionFinalization;
import com.chengyinhub.business.domain.TopicSalePayeeSnapshot;
import com.chengyinhub.business.mapper.ClubPayableMapper;
import com.chengyinhub.business.mapper.CoopComplaintMapper;
import com.chengyinhub.business.mapper.RefundApplicationMapper;
import com.chengyinhub.business.service.impl.TopicEditionFinalizationServiceImpl;
import com.chengyinhub.business.service.support.EditionFinalFactGate;
import com.chengyinhub.business.service.support.FinalCompensationFacts;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

/** §5.5 跨域 FINAL/payable：缺一个事实就整体回滚，重放不重复应付，确认人 ≠ 付款登记人。 */
class TopicEditionFinalizationServiceTest {
    private static final long TOPIC = 23L;
    private static final long CLUB = 7L;
    private static final long OPERATOR = 71L;
    private static final long PAYER = 72L;
    private static final Date NOW = new Date(1_700_000_000_000L);

    private ClubPayableMapper mapper;
    private IClubEditionCompensationService compensationService;
    private IClubCorporatePayeeService payeeService;
    private IClubService clubService;
    private ITopicEditionFundsService fundsService;
    private CoopComplaintMapper complaintMapper;
    private RefundApplicationMapper refundMapper;
    private TopicEditionFinalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubPayableMapper.class);
        compensationService = mock(IClubEditionCompensationService.class);
        payeeService = mock(IClubCorporatePayeeService.class);
        clubService = mock(IClubService.class);
        fundsService = mock(ITopicEditionFundsService.class);
        complaintMapper = mock(CoopComplaintMapper.class);
        refundMapper = mock(RefundApplicationMapper.class);
        when(clubService.selectClubById(CLUB)).thenReturn(new Club());
        when(mapper.insertFinalization(any())).thenReturn(1);
        when(mapper.insertPayable(any())).thenReturn(1);
        when(mapper.insertBatch(any())).thenReturn(1);
        when(compensationService.computeFinalCompensationFacts(TOPIC, NOW)).thenReturn(settledFacts());
        when(compensationService.requireFrozenTerms(TOPIC)).thenReturn(frozenTerms());
        when(payeeService.requireSnapshotForFinal(TOPIC, CLUB)).thenReturn(snapshot());
        // 事实门默认放行：期已结束、退款落定、无未结工单。逐条打开是下面各用例自己的事。
        when(complaintMapper.countOpenByTopicId(TOPIC)).thenReturn(0);
        when(refundMapper.countUnsettledRefundsByTopic(TOPIC)).thenReturn(0);
        service = new TopicEditionFinalizationServiceImpl(mapper, compensationService, payeeService, clubService,
                fundsService, new EditionFinalFactGate(complaintMapper, refundMapper));
        com.chengyinhub.business.mapper.CmsTopicMapper topics = mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic(); topic.setId(TOPIC);
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"topicMapper",topics);
    }

    /** 开售冻结的条款：履约窗结束落在 NOW 之前 ⇒ 「期已结束」这一条默认成立。 */
    private static ClubEditionCompensationTerms frozenTerms() {
        ClubEditionCompensationTerms terms = new ClubEditionCompensationTerms();
        terms.setTopicId(TOPIC);
        terms.setExecutingClubId(CLUB);
        terms.setFulfillmentEndAt(new Date(NOW.getTime() - 86_400_000L));
        terms.setEvidenceDueAt(new Date(NOW.getTime() + 86_400_000L));
        return terms;
    }

    // ── §5.5-3 重放 / 并发只产生一次应付 ────────────────────────────────────
    @Test
    void replayingFinalReturnsTheSameResultWithoutWritingPayableAgain() {
        TopicEditionFinalization already = new TopicEditionFinalization();
        already.setTopicId(TOPIC);
        already.setClubId(CLUB);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(already);

        TopicEditionFinalization again = service.finalizeEdition(TOPIC, NOW, OPERATOR);

        assertSame(already, again);
        verify(mapper, never()).insertFinalization(any());
        verify(mapper, never()).insertPayable(any());
        verifyNoInteractions(compensationService);
    }

    @Test
    void eachComponentGetsItsOwnAccrualWithItsOwnAmount() {
        service.finalizeEdition(TOPIC, NOW, OPERATOR);

        Map<String, BigDecimal> byComponent = capturedAccrualsByComponent();
        assertEquals(new BigDecimal("1500.00"), byComponent.get("FIXED"));
        assertEquals(new BigDecimal("400"), byComponent.get("QUALITY"));
        assertEquals(new BigDecimal("80.00"), byComponent.get("EXTRA"));
        assertEquals(new BigDecimal("300.00"), byComponent.get("HEADCOUNT"),
                "按已赚票数计酬的承接费也必须独立成行：它跟票源奖励是两件事，合并了就查不清哪笔在赔");
        assertEquals(4, byComponent.size(), "F/Q/EXTRA/HEADCOUNT 是四个经济事实，不得合并");
    }

    @Test
    void finalizationProducesTheFundsFinalBeforeReadingCompensationFacts() {
        service.finalizeEdition(TOPIC, NOW, OPERATOR);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(fundsService, compensationService);
        order.verify(fundsService).recompute(TOPIC, NOW, "FINAL:23:FUNDS", "edition-final:71",
                TopicEditionFundsPhase.FINAL);
        order.verify(compensationService).computeFinalCompensationFacts(TOPIC, NOW);
    }

    /** 只发保底的期次（人头单价为 0）照样要落 0 元的 HEADCOUNT 行：0 是一个金额，不能用缺行表达。 */
    @Test
    void aBaseOnlyEditionStillWritesTheZeroHeadcountRow() {
        when(compensationService.computeFinalCompensationFacts(TOPIC, NOW))
                .thenReturn(settledFacts(BigDecimal.ZERO));

        service.finalizeEdition(TOPIC, NOW, OPERATOR);

        Map<String, BigDecimal> byComponent = capturedAccrualsByComponent();
        assertTrue(byComponent.containsKey("HEADCOUNT"), "只发保底也要落 0 元行，不能用缺行表达");
        assertEquals(BigDecimal.ZERO, byComponent.get("HEADCOUNT"));
        assertEquals(4, byComponent.size());
    }

    @Test
    void finalHeadCarriesTheSalePayeeVersionNotTodaysProfile() {
        service.finalizeEdition(TOPIC, NOW, OPERATOR);

        ArgumentCaptor<TopicEditionFinalization> captor =
                ArgumentCaptor.forClass(TopicEditionFinalization.class);
        verify(mapper).insertFinalization(captor.capture());
        assertEquals(9L, captor.getValue().getPayeeProfileId());
        assertEquals(1L, captor.getValue().getPayeeProfileVersion());
        assertEquals("FACTS_HASH", captor.getValue().getPayeeFactsHash());
    }

    @Test
    void finalHeadConservesAllFourPayableComponents() {
        service.finalizeEdition(TOPIC, NOW, OPERATOR);

        ArgumentCaptor<TopicEditionFinalization> captor =
                ArgumentCaptor.forClass(TopicEditionFinalization.class);
        verify(mapper).insertFinalization(captor.capture());
        TopicEditionFinalization head = captor.getValue();
        assertEquals(new BigDecimal("2280.00"), head.getFixedCompensation()
                .add(head.getQualityAmount()).add(head.getExtraAmount()).add(head.getHeadcountAmount()),
                "1500 + 400 + 80 + 300(HEADCOUNT)；TICKET_SOURCE 已退役，不再进这个和");
    }

    // ── §5.5-1 payee 无效 ⇒ 整个 FINAL 回滚，无 ledger 无 payable ────────────
    @Test
    void anInvalidPayeeRollsBackTheWholeFinalWithNoLedgerAndNoPayable() {
        when(payeeService.requireSnapshotForFinal(TOPIC, CLUB))
                .thenThrow(new IllegalStateException("FINAL_PAYEE_SNAPSHOT_MISSING"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeEdition(TOPIC, NOW, OPERATOR));

        assertEquals("FINAL_PAYEE_SNAPSHOT_MISSING", error.getMessage());
        verify(mapper, never()).insertFinalization(any());
        verify(mapper, never()).insertPayable(any());
    }

    // ── §5.5-2 某类工时未确认 ⇒ 拒绝，不得部分应付 ──────────────────────────
    @Test
    void pendingCompensationFactsBlockFinalWithNoPartialPayable() {
        when(compensationService.computeFinalCompensationFacts(TOPIC, NOW))
                .thenReturn(FinalCompensationFacts.pending(CLUB, "EDITION_HOURS_NOT_CONFIRMED"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.finalizeEdition(TOPIC, NOW, OPERATOR));

        assertEquals("EDITION_FINAL_PENDING:EDITION_HOURS_NOT_CONFIRMED", error.getMessage());
        verify(mapper, never()).insertFinalization(any());
        verify(mapper, never()).insertPayable(any());
        verifyNoInteractions(payeeService);
    }

    // ── §5.5-4 撤销写反向新行，绝不改原行 ──────────────────────────────────
    @Test
    void reversalAppendsANegativeRowAndNeverUpdatesTheOriginal() {
        when(mapper.selectPayableByIdForUpdate(5L)).thenReturn(accrual(5L, "QUALITY", "400"));

        ClubPayable reversal = service.reversePayable(TOPIC, 5L, OPERATOR, "质量证据复核后撤销");

        assertEquals(new BigDecimal("-400"), reversal.getAmount());
        assertEquals(ClubPayable.ENTRY_REVERSAL, reversal.getEntryType());
        assertEquals(5L, reversal.getReversalOf());
        assertEquals("QUALITY", reversal.getComponent());
        verify(mapper).insertPayable(any());
    }

    @Test
    void aPayableAlreadyInABatchCannotBeReversedInPlace() {
        ClubPayable batched = accrual(5L, "QUALITY", "400");
        batched.setBatchId(3L);
        when(mapper.selectPayableByIdForUpdate(5L)).thenReturn(batched);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reversePayable(TOPIC, 5L, OPERATOR, "撤销"));

        assertEquals("CLUB_PAYABLE_ALREADY_BATCHED", error.getMessage());
        verify(mapper, never()).insertPayable(any());
    }

    @Test
    void aReversalItselfCannotBeReversedAgain() {
        ClubPayable reversal = accrual(6L, "QUALITY", "-400");
        reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
        when(mapper.selectPayableByIdForUpdate(6L)).thenReturn(reversal);

        assertEquals("CLUB_PAYABLE_ACCRUAL_NOT_FOUND", assertThrows(IllegalStateException.class,
                () -> service.reversePayable(TOPIC, 6L, OPERATOR, "撤销")).getMessage());
    }

    /**
     * ★撤销必须绑作用域：Controller 用路径上的 topicId 过 canEditTopic，
     * service 再拿它和台账行自报的 topic_id 对一遍。少了这一句，
     * 拿一个别的主题的 payableId 就能在「自己有权限的那个主题」名义下写出负向资金行。
     */
    @Test
    void aPayableFromAnotherTopicCannotBeReversedUnderThisTopicsScope() {
        ClubPayable elsewhere = accrual(5L, "QUALITY", "400");
        elsewhere.setTopicId(999L);
        when(mapper.selectPayableByIdForUpdate(5L)).thenReturn(elsewhere);

        assertEquals("CLUB_PAYABLE_TOPIC_MISMATCH", assertThrows(IllegalStateException.class,
                () -> service.reversePayable(TOPIC, 5L, OPERATOR, "撤销")).getMessage());
        verify(mapper, never()).insertPayable(any());
    }

    @Test
    void aBatchCannotBeCreatedForAClubThatDoesNotExist() {
        when(clubService.selectClubById(CLUB)).thenReturn(null);

        assertEquals("CLUB_PAYABLE_CLUB_UNKNOWN", assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, OPERATOR)).getMessage());
        verify(mapper, never()).selectUnbatchedPayables(anyLong(), anyLong());
        verify(mapper, never()).insertBatch(any());
    }

    /** SQL 里已经带了 club_id 谓词；这条是「万一那个 where 被改丢」的服务端回读负控。 */
    @Test
    void aRowBelongingToAnotherClubCanNeverBeSweptIntoThisBatch() {
        ClubPayable foreign = accrual(2L, "QUALITY", "400");
        foreign.setClubId(999L);
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(
                Arrays.asList(accrual(1L, "FIXED", "1500.00"), foreign));

        assertEquals("CLUB_PAYABLE_SCOPE_LEAK", assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, OPERATOR)).getMessage());
        verify(mapper, never()).insertBatch(any());
    }

    /** 红冲把正向行抵干净以后合计是 0：那种批次不该被开出来。 */
    @Test
    void aBatchWhoseRowsNetToZeroOrLessIsNotCreated() {
        ClubPayable reversal = accrual(2L, "QUALITY", "-400");
        reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(
                Arrays.asList(accrual(1L, "QUALITY", "400"), reversal));

        assertEquals("CLUB_PAYABLE_BATCH_TOTAL_NOT_POSITIVE", assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, OPERATOR)).getMessage());
        verify(mapper, never()).insertBatch(any());
    }

    // ── 单超管环境的显式降级：闸一步不松，但「按不动」必须说得出为什么 ────────────
    //
    // 审计 2 P0-9 要的双人复核是对的，DB CHECK / SQL CAS / Java 三层都不许动。
    // 问题在既有裁决「后台只用超管单账号、四角色不建号」：confirm 与 paid 只能同一个
    // userId=1 去点 ⇒ 第一笔真实对公打款登记必然被拒，而系统里没有任何一处说明
    // 「本环境降级为单人确认」。审计 2 明写「若环境尚无法提供第二账号，必须以配置化降级
    // 状态显式显示，不能偷偷放弃复核」——所以补的是**可观测性**，不是放宽。

    @Test
    void ledgerReportsSeparationReadyWhenTwoDistinctNonSuperUsersHoldTheTwoPerms() {
        when(mapper.selectPermHolderUserIds("business:clubpayable:confirm")).thenReturn(Arrays.asList(11L));
        when(mapper.selectPermHolderUserIds("business:clubpayable:paid")).thenReturn(Arrays.asList(12L));

        Map<String, Object> separation = separationOf(service.editionLedgerView(TOPIC));

        assertEquals(Boolean.FALSE, separation.get("degraded"));
        assertEquals(1, separation.get("confirmHolders"));
        assertEquals(1, separation.get("paidHolders"));
    }

    @Test
    void ledgerReportsDegradedWithTheMissingPermNamedWhenNobodyButTheSuperUserExists() {
        when(mapper.selectPermHolderUserIds(anyString())).thenReturn(Collections.<Long>emptyList());

        Map<String, Object> separation = separationOf(service.editionLedgerView(TOPIC));

        assertEquals(Boolean.TRUE, separation.get("degraded"));
        String reason = String.valueOf(separation.get("reason"));
        assertTrue(reason.contains("business:clubpayable:confirm"), reason);
        assertTrue(reason.contains("business:clubpayable:paid"), reason);
        // 「怎么修」必须和「坏在哪」在同一个回包里，否则运营只知道按钮点不动
        assertTrue(String.valueOf(separation.get("deploymentAction")).contains("非超管"), "缺部署动作");
    }

    @Test
    void ledgerReportsDegradedWhenTheSameSingleAccountHoldsBothPerms() {
        when(mapper.selectPermHolderUserIds(anyString())).thenReturn(Arrays.asList(11L));

        Map<String, Object> separation = separationOf(service.editionLedgerView(TOPIC));

        assertEquals(Boolean.TRUE, separation.get("degraded"));
        assertTrue(String.valueOf(separation.get("reason")).contains("同一个账号"),
                String.valueOf(separation.get("reason")));
    }

    /** 两个权限都有人、且能凑出不同的两个人 ⇒ 不降级（即使有人两头都持）。 */
    @Test
    void anOverlappingHolderIsStillSeparableAsLongAsSomeDistinctPairExists() {
        when(mapper.selectPermHolderUserIds("business:clubpayable:confirm")).thenReturn(Arrays.asList(11L, 12L));
        when(mapper.selectPermHolderUserIds("business:clubpayable:paid")).thenReturn(Arrays.asList(11L));

        assertEquals(Boolean.FALSE, separationOf(service.editionLedgerView(TOPIC)).get("degraded"));
    }

    /** 机器码走写回，人话与部署动作走已有 separation 读面，不复制长字符串。 */
    @Test
    void markBatchPaidKeepsAStableClientCodeWhileTheReadModelExplainsTheDegradation() {
        when(mapper.selectBatch(3L)).thenReturn(confirmedBatch(OPERATOR));
        when(mapper.selectPermHolderUserIds(anyString())).thenReturn(Collections.<Long>emptyList());

        Map<String, Object> separation = separationOf(service.editionLedgerView(TOPIC));
        assertTrue(String.valueOf(separation.get("reason")).contains("非超管"));
        assertTrue(String.valueOf(separation.get("deploymentAction")).contains("非超管"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.markBatchPaid(TOPIC, 3L, OPERATOR, "TX-1"));

        AjaxResult client = new GlobalExceptionHandler().handleRuntimeException(
                error, mock(HttpServletRequest.class));
        assertEquals("CLUB_PAYABLE_BATCH_SEPARATION_REQUIRED", client.get("msg"),
                "全局 120 字符闸后客户端仍必须拿到稳定机器码");
        // ★闸一步没松：CAS 仍然没被调用
        verify(mapper, never()).markBatchPaidCas(anyLong(), anyLong(), any(), anyString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> separationOf(Map<String, Object> view) {
        Object separation = view.get("separation");
        assertNotNull(separation, "期级资金明细必须自报双人分离是否可用");
        return (Map<String, Object>) separation;
    }

    // ── §5.5-5 批次：确认人 ≠ 付款登记人 ───────────────────────────────────
    @Test
    void theConfirmerCannotAlsoRecordThePayment() {
        when(mapper.selectBatch(3L)).thenReturn(confirmedBatch(OPERATOR));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.markBatchPaid(TOPIC, 3L, OPERATOR, "TX-1"));

        assertTrue(error.getMessage().startsWith("CLUB_PAYABLE_BATCH_SEPARATION_REQUIRED"),
                error.getMessage());
        verify(mapper, never()).markBatchPaidCas(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void aDifferentPersonMayRecordThePaymentOnAConfirmedBatch() {
        when(mapper.selectBatch(3L)).thenReturn(confirmedBatch(OPERATOR));
        when(mapper.markBatchPaidCas(eq(3L), eq(PAYER), any(), eq("TX-1"))).thenReturn(1);

        service.markBatchPaid(TOPIC, 3L, PAYER, "TX-1");

        verify(mapper).markBatchPaidCas(eq(3L), eq(PAYER), any(), eq("TX-1"));
    }

    @Test
    void anUnconfirmedBatchCannotJumpStraightToPaid() {
        ClubPayableBatch draft = confirmedBatch(OPERATOR);
        draft.setBatchStatus(ClubPayableBatch.STATUS_PENDING);
        when(mapper.selectBatch(3L)).thenReturn(draft);

        assertEquals("CLUB_PAYABLE_BATCH_NOT_CONFIRMED", assertThrows(IllegalStateException.class,
                () -> service.markBatchPaid(TOPIC, 3L, PAYER, "TX-1")).getMessage());
        verify(mapper, never()).markBatchPaidCas(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void aSecondConfirmationLosesTheCas() {
        when(mapper.selectBatch(3L)).thenReturn(confirmedBatch(OPERATOR));
        when(mapper.confirmBatchCas(eq(3L), eq(OPERATOR), any())).thenReturn(0);

        assertEquals("CLUB_PAYABLE_BATCH_CONFIRM_CONFLICT", assertThrows(IllegalStateException.class,
                () -> service.confirmBatch(TOPIC, 3L, OPERATOR)).getMessage());
    }

    @Test
    void batchCreationRollsBackWhenItsContentChangedUnderneath() {
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(
                Arrays.asList(accrual(1L, "FIXED", "1500.00"), accrual(2L, "QUALITY", "400")));
        when(mapper.attachUnbatchedPayables(any(), eq(TOPIC), eq(CLUB))).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, OPERATOR));

        assertEquals("CLUB_PAYABLE_BATCH_CONTENT_CHANGED", error.getMessage());
    }

    @Test
    void anEmptyLedgerProducesNoBatchAtAll() {
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(new ArrayList<ClubPayable>());

        assertEquals("CLUB_PAYABLE_NOTHING_TO_BATCH", assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, OPERATOR)).getMessage());
        verify(mapper, never()).insertBatch(any());
    }

    @Test
    void batchTotalIsTheSumOfTheRowsItActuallyCaptured() {
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(
                Arrays.asList(accrual(1L, "FIXED", "1500.00"), accrual(2L, "QUALITY", "400")));
        when(mapper.attachUnbatchedPayables(any(), eq(TOPIC), eq(CLUB))).thenReturn(2);

        ClubPayableBatch batch = service.createBatch(TOPIC, CLUB, OPERATOR);

        assertEquals(new BigDecimal("1900.00"), batch.getTotalAmount());
        assertEquals(ClubPayableBatch.STATUS_PENDING, batch.getBatchStatus());
    }

    // ── FINAL 之前的事实门（审计 2「暂结与终结动作合同」）────────────────────
    /**
     * 期还没结束就不许终结。FINAL 一期只能一次、红冲后只能靠重计提补救 ——
     * 期中把三条应付落进台账，之后每一次改动都要在资金流水上留一串修正。
     */
    @Test
    void anEditionStillInsideItsFulfillmentWindowCannotBeFinalized() {
        ClubEditionCompensationTerms running = frozenTerms();
        running.setFulfillmentEndAt(new Date(NOW.getTime() + 86_400_000L));
        when(compensationService.requireFrozenTerms(TOPIC)).thenReturn(running);

        assertEquals("EDITION_FINALIZATION_BLOCKED_EDITION_RUNNING", assertThrows(IllegalStateException.class,
                () -> service.finalizeEdition(TOPIC, NOW, OPERATOR)).getMessage());
        verify(mapper, never()).insertFinalization(any());
        verify(mapper, never()).insertPayable(any());
    }

    /** 退款还没落定就终结 ⇒ 退款一到账，g_earned 与已落的应付对不上，而 FINAL 改不回去。 */
    @Test
    void anEditionWithUnsettledRefundsCannotBeFinalized() {
        when(refundMapper.countUnsettledRefundsByTopic(TOPIC)).thenReturn(1);

        assertEquals("EDITION_FINALIZATION_BLOCKED_REFUND_WINDOW", assertThrows(IllegalStateException.class,
                () -> service.finalizeEdition(TOPIC, NOW, OPERATOR)).getMessage());
        verify(mapper, never()).insertFinalization(any());
    }

    /**
     * 未结投诉工单就终结 ⇒ 定责结果晚到时钱已经落了。
     * 旧结算腿本来就有这道闸（「有未结工单 → 暂缓放款」），新腿此前对它零调用。
     */
    @Test
    void anEditionWithOpenComplaintCasesCannotBeFinalized() {
        when(complaintMapper.countOpenByTopicId(TOPIC)).thenReturn(2);

        assertEquals("EDITION_FINALIZATION_BLOCKED_CASE", assertThrows(IllegalStateException.class,
                () -> service.finalizeEdition(TOPIC, NOW, OPERATOR)).getMessage());
        verify(mapper, never()).insertFinalization(any());
    }

    /** 事实门必须先于任何应付生成：三条事实都要在写台账之前问过。 */
    @Test
    void theFactGateRunsBeforeAnyPayableIsWritten() {
        when(complaintMapper.countOpenByTopicId(TOPIC)).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> service.finalizeEdition(TOPIC, NOW, OPERATOR));

        verify(compensationService, never()).computeFinalCompensationFacts(any(), any());
    }

    // ── 红冲之后的 append-only 重计提 ──────────────────────────────────────
    /**
     * ★没有这条路，红冲就是死胡同：FINAL 头一期一行、幂等键恒 FINAL:topicId，
     * 重跑 finalizeEdition 只会读回旧头，正确金额永远进不了台账，只剩手工改库。
     */
    @Test
    void afterAReversalTheCorrectedAmountCanBeAccruedAgainAsANewRow() {
        TopicEditionFinalization head = new TopicEditionFinalization();
        head.setTopicId(TOPIC);
        head.setClubId(CLUB);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(head);
        // QUALITY 原来计提 400、已被红冲 -400 ⇒ 净额 0；重算事实说该付 400 ⇒ 补一条 +400
        ClubPayable original = accrual(1L, "QUALITY", "400");
        ClubPayable reversal = accrual(2L, "QUALITY", "-400");
        reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "QUALITY"))
                .thenReturn(Arrays.asList(original, reversal));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED"))
                .thenReturn(Arrays.asList(accrual(3L, "FIXED", "1500.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "EXTRA"))
                .thenReturn(Arrays.asList(accrual(4L, "EXTRA", "80.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "HEADCOUNT"))
                .thenReturn(Arrays.asList(accrual(6L, "HEADCOUNT", "300.00")));

        List<ClubPayable> written = service.reaccrueAfterReversal(TOPIC, NOW, OPERATOR, "过错排除定责晚到");

        assertEquals(1, written.size(), "只有净额与重算不符的那个 component 才追加新行");
        assertEquals("QUALITY", written.get(0).getComponent());
        assertEquals(new BigDecimal("400"), written.get(0).getAmount());
        assertEquals(ClubPayable.ENTRY_ACCRUAL, written.get(0).getEntryType());
        assertEquals("FINAL:" + TOPIC + "#R2", written.get(0).getIdempotencyKey(),
                "幂等键带修订序号，重放同一次重计提照样撞唯一键");
        assertEquals("过错排除定责晚到", written.get(0).getReason(), "理由必须落库");
    }

    @Test
    void afterAHeadcountReversalItsCorrectedAmountUsesTheRevisionIdempotencyKey() {
        TopicEditionFinalization head = new TopicEditionFinalization();
        head.setTopicId(TOPIC);
        head.setClubId(CLUB);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(head);
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED"))
                .thenReturn(Arrays.asList(accrual(1L, "FIXED", "1500.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "QUALITY"))
                .thenReturn(Arrays.asList(accrual(2L, "QUALITY", "400")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "EXTRA"))
                .thenReturn(Arrays.asList(accrual(3L, "EXTRA", "80.00")));
        ClubPayable headcount = accrual(4L, "HEADCOUNT", "300.00");
        ClubPayable reversal = accrual(5L, "HEADCOUNT", "-300.00");
        reversal.setEntryType(ClubPayable.ENTRY_REVERSAL);
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "HEADCOUNT"))
                .thenReturn(Arrays.asList(headcount, reversal));

        List<ClubPayable> written = service.reaccrueAfterReversal(
                TOPIC, NOW, OPERATOR, "承接费复核后重计提");

        assertEquals(1, written.size());
        assertEquals("HEADCOUNT", written.get(0).getComponent());
        assertEquals(new BigDecimal("300.00"), written.get(0).getAmount());
        assertEquals("FINAL:" + TOPIC + "#R2", written.get(0).getIdempotencyKey());
        assertEquals("承接费复核后重计提", written.get(0).getReason());
    }

    /** 重计提也是 append-only：净额比重算的多，就写一条负向行，绝不 UPDATE 旧行。 */
    @Test
    void anOvershotLedgerIsCorrectedByAppendingANegativeRowNotByUpdating() {
        TopicEditionFinalization head = new TopicEditionFinalization();
        head.setTopicId(TOPIC);
        head.setClubId(CLUB);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(head);
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "QUALITY"))
                .thenReturn(Arrays.asList(accrual(1L, "QUALITY", "600")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED"))
                .thenReturn(Arrays.asList(accrual(3L, "FIXED", "1500.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "EXTRA"))
                .thenReturn(Arrays.asList(accrual(4L, "EXTRA", "80.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "HEADCOUNT"))
                .thenReturn(Arrays.asList(accrual(6L, "HEADCOUNT", "300.00")));

        List<ClubPayable> written = service.reaccrueAfterReversal(TOPIC, NOW, OPERATOR, "Q 三维复核后下调");

        assertEquals(new BigDecimal("-200"), written.get(0).getAmount());
        assertEquals(ClubPayable.ENTRY_REVERSAL, written.get(0).getEntryType());
    }

    /** 已进批次的行不许再被重计提改动：那笔钱在付或已付，走退款/追偿不是改台账。 */
    @Test
    void aBatchedComponentCannotBeReaccrued() {
        TopicEditionFinalization head = new TopicEditionFinalization();
        head.setTopicId(TOPIC);
        head.setClubId(CLUB);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(head);
        ClubPayable batched = accrual(1L, "FIXED", "1500.00");
        batched.setBatchId(5L);
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED")).thenReturn(Arrays.asList(batched));

        assertEquals("CLUB_PAYABLE_ALREADY_BATCHED", assertThrows(IllegalStateException.class,
                () -> service.reaccrueAfterReversal(TOPIC, NOW, OPERATOR, "r")).getMessage());
    }

    @Test
    void reaccrualRequiresAnExistingFinalHeadAndAReason() {
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(null);
        assertEquals("EDITION_FINAL_NOT_FOUND", assertThrows(IllegalStateException.class,
                () -> service.reaccrueAfterReversal(TOPIC, NOW, OPERATOR, "r")).getMessage());

        assertEquals("CLUB_PAYABLE_ARGUMENT_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> service.reaccrueAfterReversal(TOPIC, NOW, OPERATOR, "  ")).getMessage());
    }

    // ── E4 一期一批次一张发票 ──────────────────────────────────────────────
    @Test
    void theBatchCarriesTheEditionItPaysForSoTwoEditionsNeverShareOneInvoice() {
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(
                Arrays.asList(accrual(1L, "FIXED", "1500.00")));
        when(mapper.attachUnbatchedPayables(any(), eq(TOPIC), eq(CLUB))).thenReturn(1);

        ClubPayableBatch batch = service.createBatch(TOPIC, CLUB, OPERATOR);

        assertEquals(Long.valueOf(TOPIC), batch.getTopicId(),
                "批次必须自报期次，否则两期两份合同的钱会并进同一张发票");
        assertEquals(ClubPayableBatch.INVOICE_NONE, batch.getInvoiceStatus(),
                "发票轴独立起步：新批次一张票都还没开");
        verify(mapper).selectUnbatchedPayables(TOPIC, CLUB);
        verify(mapper).attachUnbatchedPayables(any(), eq(TOPIC), eq(CLUB));
    }

    /** SQL 里已经带了 topic 谓词；这条是「万一那个 where 被改丢」的服务端回读负控。 */
    @Test
    void aRowBelongingToAnotherEditionCanNeverBeSweptIntoThisBatch() {
        ClubPayable otherEdition = accrual(2L, "QUALITY", "400");
        otherEdition.setTopicId(TOPIC + 1);
        when(mapper.selectUnbatchedPayables(TOPIC, CLUB)).thenReturn(
                Arrays.asList(accrual(1L, "FIXED", "1500.00"), otherEdition));

        assertEquals("CLUB_PAYABLE_EDITION_LEAK", assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, OPERATOR)).getMessage());
        verify(mapper, never()).insertBatch(any());
    }

    // ── 发票轴 ────────────────────────────────────────────────────────────
    @Test
    void issuingAnInvoiceIsACasThatCannotBeReplayed() {
        when(mapper.markBatchInvoiceIssued(3L, "INV-001")).thenReturn(1);
        when(mapper.selectBatch(3L)).thenReturn(confirmedBatch(OPERATOR));

        service.markBatchInvoiceIssued(TOPIC, 3L, "INV-001", OPERATOR);
        verify(mapper).markBatchInvoiceIssued(3L, "INV-001");

        when(mapper.markBatchInvoiceIssued(3L, "INV-002")).thenReturn(0);
        assertEquals("CLUB_PAYABLE_BATCH_INVOICE_CONFLICT", assertThrows(IllegalStateException.class,
                () -> service.markBatchInvoiceIssued(TOPIC, 3L, "INV-002", OPERATOR)).getMessage());
    }

    @Test
    void aRedInvoiceWithoutAnIssuedOneIsRefused() {
        when(mapper.markBatchInvoiceRed(3L, "RED-001")).thenReturn(0);
        when(mapper.selectBatch(3L)).thenReturn(confirmedBatch(OPERATOR));

        assertEquals("CLUB_PAYABLE_BATCH_RED_INVOICE_CONFLICT", assertThrows(IllegalStateException.class,
                () -> service.markBatchInvoiceRed(TOPIC, 3L, "RED-001", OPERATOR)).getMessage());
    }

    /**
     * ★红冲必须用<b>锁定读</b>取原行，不能用快照读。
     *
     * <p>失败交错（非锁定读时真实成立）：T1 读到 {@code P.batch_id = null}（快照）→ T2 建批次，
     * {@code attachUnbatchedPayables} 命中 1 行 == pending.size() 并提交 → T1 插入反向行 R
     * （纯 INSERT，不与 T2 的行锁冲突）并提交。结果：批次 B 的 total 含 P 的正额，抵消它的 R 落在
     * 批次<b>之外</b> ⇒ B 照常 CONFIRM → PAID ⇒ <b>多付 |R|</b>，零告警。
     *
     * <p>本改动自己在 {@code ClubEditionCompensationMapper.xml} 和 {@code CoopComplaintServiceImpl}
     * 两处都判过这种「先读后写」形状不合格，红冲这条路必须按同一把尺子。
     * 真正的行锁行为由 {@code ClubPayableMybatisIntegrationTest#aLockedPayableBlocksAConcurrentBatchAttach}
     * 在真 MySQL 上证；这里钉住「红冲走的是那条锁定读」。
     */
    @Test
    void reversalReadsTheOriginalWithARowLockNotASnapshot() {
        ClubPayable original = accrual(11L, "QUALITY", "400");
        when(mapper.selectPayableByIdForUpdate(11L)).thenReturn(original);

        service.reversePayable(TOPIC, 11L, OPERATOR, "复核后撤销");

        verify(mapper).selectPayableByIdForUpdate(11L);
        verify(mapper, never()).selectPayableById(anyLong());
    }

    /**
     * ★重计提也必须锁定读，理由与红冲<b>逐字相同</b>——{@code appendDelta} 的形状就是
     * 「快照读 → {@code batchId != null} 守卫 → 纯 INSERT」。
     *
     * <p>失败交错（非锁定读时真实成立）：台账现有 FIXED 一条 P = +2000 未进批次，当下重算应为 1500。
     * T1 {@code reaccrueAfterReversal} 锁住 FINAL 头、<b>快照读</b>得 [P]、{@code batch_id} 为空
     * ⇒ 过守卫，算出 delta = −500；T2 {@code createBatch} 读到 [P]（pending.size()=1）→ 插批次 →
     * {@code attachUnbatchedPayables} 命中 1 行 == 1 ⇒ {@code CLUB_PAYABLE_BATCH_CONTENT_CHANGED}
     * <b>不触发</b> ⇒ 提交，批次 total = 2000；T1 再插入 −500（纯 INSERT，不与 T2 的行锁冲突）⇒ 提交。
     * 结果：批次付 2000，台账真实净额 1500，抵消它的 −500 落在批次<b>之外</b> ⇒ <b>多付 500</b>，零告警。
     *
     * <p>⚠️ FINAL 头的行锁 serialize 不了它：{@code createBatch} 全程不碰
     * {@code topic_edition_finalization}。
     *
     * <p>行锁真的拦住并发挂靠由 {@code ClubPayableMybatisIntegrationTest#
     * aReaccrualHoldsItsComponentRowsAgainstAConcurrentBatchAttach} 在真 MySQL 上证；
     * 这里钉住「重计提走的是那条锁定读」。
     */
    @Test
    void reaccrualReadsTheComponentLedgerWithARowLockNotASnapshot() {
        TopicEditionFinalization head = new TopicEditionFinalization();
        head.setTopicId(TOPIC);
        head.setClubId(CLUB);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(head);
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED"))
                .thenReturn(Arrays.asList(accrual(1L, "FIXED", "2000.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "QUALITY"))
                .thenReturn(Arrays.asList(accrual(2L, "QUALITY", "400")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "EXTRA"))
                .thenReturn(Arrays.asList(accrual(3L, "EXTRA", "80.00")));
        when(mapper.selectPayablesByComponentForUpdate(TOPIC, CLUB, "HEADCOUNT"))
                .thenReturn(Arrays.asList(accrual(6L, "HEADCOUNT", "300.00")));

        List<ClubPayable> written = service.reaccrueAfterReversal(TOPIC, NOW, OPERATOR, "F 下调复核");

        assertEquals(1, written.size());
        assertEquals(new BigDecimal("-500.00"), written.get(0).getAmount());
        verify(mapper).selectPayablesByComponentForUpdate(TOPIC, CLUB, "FIXED");
        verify(mapper, never()).selectPayablesByComponent(anyLong(), anyLong(), anyString());
    }

    /**
     * ★批次自报的期次必须等于路径上的那一期，四个批次动作各自独立承重。
     *
     * <p>与 {@code reversePayable} 的 {@code CLUB_PAYABLE_TOPIC_MISMATCH} 同形：Controller 那一层
     * 判的是「路径上那个 topic」，CAS 只按 {@code id} 命中 —— 少了这一句，
     * {@code POST /{我有权限的topicId}/batch/{别的期的batchId}/paid} 登记的是<b>另一期</b>的付款。
     */
    @Test
    void aBatchBelongingToAnotherEditionIsRefusedByAllFourBatchActions() {
        ClubPayableBatch foreign = confirmedBatch(OPERATOR);
        foreign.setTopicId(TOPIC + 1);
        when(mapper.selectBatch(3L)).thenReturn(foreign);

        assertEquals("CLUB_PAYABLE_BATCH_TOPIC_MISMATCH", assertThrows(IllegalStateException.class,
                () -> service.confirmBatch(TOPIC, 3L, OPERATOR)).getMessage());
        assertEquals("CLUB_PAYABLE_BATCH_TOPIC_MISMATCH", assertThrows(IllegalStateException.class,
                () -> service.markBatchPaid(TOPIC, 3L, PAYER, "TX-1")).getMessage());
        assertEquals("CLUB_PAYABLE_BATCH_TOPIC_MISMATCH", assertThrows(IllegalStateException.class,
                () -> service.markBatchInvoiceIssued(TOPIC, 3L, "INV-001", OPERATOR)).getMessage());
        assertEquals("CLUB_PAYABLE_BATCH_TOPIC_MISMATCH", assertThrows(IllegalStateException.class,
                () -> service.markBatchInvoiceRed(TOPIC, 3L, "RED-001", OPERATOR)).getMessage());

        verify(mapper, never()).confirmBatchCas(anyLong(), anyLong(), any());
        verify(mapper, never()).markBatchPaidCas(anyLong(), anyLong(), any(), anyString());
        verify(mapper, never()).markBatchInvoiceIssued(anyLong(), anyString());
        verify(mapper, never()).markBatchInvoiceRed(anyLong(), anyString());
    }

    /** 批次根本不存在时也不能落进 CAS：报「批次不存在」而不是「CAS 冲突」。 */
    @Test
    void anUnknownBatchIdIsNamedAsSuchInsteadOfLookingLikeACasConflict() {
        when(mapper.selectBatch(404L)).thenReturn(null);

        assertEquals("CLUB_PAYABLE_BATCH_NOT_FOUND", assertThrows(IllegalStateException.class,
                () -> service.confirmBatch(TOPIC, 404L, OPERATOR)).getMessage());
        verify(mapper, never()).confirmBatchCas(anyLong(), anyLong(), any());
    }

    /**
     * ★只读操作面：按期读回 FINAL 头 + 应付原始行 + 批次（含付款/发票双轴）。
     *
     * <p>没有它，红冲 / 更正 / 批次四个动作都要求操作者先连生产库把 id 捞出来 —— 与「后台是唯一
     * 操作面」直接冲突，叠加「批次不绑期」就是「输错一个数字把钱记到别的期」。
     *
     * <p>对象 scope 由两层承重：SQL 按 {@code topic_id} 收窄，<b>再</b>对读回的每一行核一遍它自报的
     * topic —— 哪天谁把那个谓词改丢，这一句就是那道负控（与 {@code createBatch} 的
     * {@code CLUB_PAYABLE_EDITION_LEAK} 同形）。
     */
    @Test
    void theLedgerViewReadsBackOnlyThisEditionsRows() {
        TopicEditionFinalization head = new TopicEditionFinalization();
        head.setTopicId(TOPIC);
        head.setClubId(CLUB);
        when(mapper.selectFinalization(TOPIC)).thenReturn(head);
        when(mapper.selectPayablesByTopic(TOPIC)).thenReturn(Arrays.asList(accrual(11L, "FIXED", "1500.00")));
        ClubPayableBatch batch = confirmedBatch(OPERATOR);
        when(mapper.selectBatchesByTopic(TOPIC)).thenReturn(Collections.singletonList(batch));

        Map<String, Object> view = service.editionLedgerView(TOPIC);

        assertSame(head, view.get("finalization"));
        assertEquals(1, ((List<?>) view.get("payables")).size());
        assertEquals(1, ((List<?>) view.get("batches")).size());
    }

    @Test
    void aRowFromAnotherEditionNeverLeaksIntoTheLedgerView() {
        ClubPayable foreign = accrual(11L, "FIXED", "1500.00");
        foreign.setTopicId(TOPIC + 1);
        when(mapper.selectPayablesByTopic(TOPIC)).thenReturn(Arrays.asList(foreign));

        assertEquals("CLUB_PAYABLE_EDITION_LEAK", assertThrows(IllegalStateException.class,
                () -> service.editionLedgerView(TOPIC)).getMessage());
    }

    /** 红冲理由必须落进台账行：本仓 @Log 不写请求体，日志里查不到红冲原因。 */
    @Test
    void theReversalReasonIsPersistedOnTheLedgerRowNotOnlyValidated() {
        ClubPayable original = accrual(11L, "QUALITY", "400");
        when(mapper.selectPayableByIdForUpdate(11L)).thenReturn(original);

        service.reversePayable(TOPIC, 11L, OPERATOR, "商家过错定责晚到");

        ArgumentCaptor<ClubPayable> captor = ArgumentCaptor.forClass(ClubPayable.class);
        verify(mapper).insertPayable(captor.capture());
        assertEquals("商家过错定责晚到", captor.getValue().getReason());
    }

    private Map<String, BigDecimal> capturedAccrualsByComponent() {
        ArgumentCaptor<ClubPayable> captor = ArgumentCaptor.forClass(ClubPayable.class);
        verify(mapper, times(4)).insertPayable(captor.capture());
        Map<String, BigDecimal> byComponent = new LinkedHashMap<String, BigDecimal>();
        for (ClubPayable payable : captor.getAllValues()) {
            assertEquals(ClubPayable.ENTRY_ACCRUAL, payable.getEntryType());
            byComponent.put(payable.getComponent(), payable.getAmount());
        }
        return byComponent;
    }

    private static FinalCompensationFacts settledFacts() {
        return settledFacts(new BigDecimal("300.00"));
    }

    private static FinalCompensationFacts settledFacts(BigDecimal headcount) {
        Map<String, BigDecimal> extra = new LinkedHashMap<String, BigDecimal>();
        extra.put("PREP", new BigDecimal("80.00"));
        return FinalCompensationFacts.settled(CLUB, new BigDecimal("1500.00"), new BigDecimal("400"),
                new BigDecimal("80.00"), headcount, extra);
    }

    private static TopicSalePayeeSnapshot snapshot() {
        TopicSalePayeeSnapshot s = new TopicSalePayeeSnapshot();
        s.setTopicId(TOPIC);
        s.setClubId(CLUB);
        s.setProfileId(9L);
        s.setProfileVersion(1L);
        s.setFactsHash("FACTS_HASH");
        s.setLicenseHash("LICENSE_HASH");
        s.setFrozenAt(new Date(1_690_000_000_000L));
        return s;
    }

    private static ClubPayable accrual(Long id, String component, String amount) {
        ClubPayable payable = new ClubPayable();
        payable.setId(id);
        payable.setTopicId(TOPIC);
        payable.setClubId(CLUB);
        payable.setComponent(component);
        payable.setEntryType(ClubPayable.ENTRY_ACCRUAL);
        payable.setAmount(new BigDecimal(amount));
        return payable;
    }

    private static ClubPayableBatch confirmedBatch(Long confirmedBy) {
        ClubPayableBatch batch = new ClubPayableBatch();
        batch.setId(3L);
        batch.setClubId(CLUB);
        batch.setTopicId(TOPIC);
        batch.setBatchStatus(ClubPayableBatch.STATUS_CONFIRMED);
        batch.setConfirmedBy(confirmedBy);
        batch.setConfirmedAt(NOW);
        batch.setTotalAmount(new BigDecimal("1980.00"));
        return batch;
    }
    @Test
    void finalReplayStillAcquiresTopicBeforeFinalizationHead() {
        com.chengyinhub.business.mapper.CmsTopicMapper topics = (com.chengyinhub.business.mapper.CmsTopicMapper)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "topicMapper");
        TopicEditionFinalization original = new TopicEditionFinalization(); original.setTopicId(TOPIC);
        when(mapper.selectFinalizationForUpdate(TOPIC)).thenReturn(original);
        org.junit.jupiter.api.Assertions.assertSame(original, service.finalizeEdition(TOPIC, NOW, 9L));
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(topics, mapper);
        order.verify(topics).selectCmsTopicByIdForUpdate(TOPIC);
        order.verify(mapper).selectFinalizationForUpdate(TOPIC);
        org.mockito.Mockito.verifyNoInteractions(fundsService);
    }

    @Test
    void missingTopicCannotGenerateFinalizationOrBatchSideEffects() {
        com.chengyinhub.business.mapper.CmsTopicMapper topics = (com.chengyinhub.business.mapper.CmsTopicMapper)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "topicMapper");
        when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.finalizeEdition(TOPIC, NOW, 9L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.createBatch(TOPIC, CLUB, 9L));
        org.mockito.Mockito.verifyNoInteractions(mapper, fundsService);
    }

}
