package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.service.impl.VerificationRiskService;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 5 · T3 上半:核销率风控必须认出探店日,否则**每场必炸**。
 *
 * <p>算术原因:探店日的核销率 ≈ A / N_service(到店的人几乎都会核销一次),
 * 而 G2 硬门要求 A / N_service ≥ 80% ⇒ 场子越成功、rate 越高 ⇒ **越成功越误报**。
 * 而 {@code idempotencyKey} 里含 {@code LocalDate},四店四天会累积 16 条谁都处置不了的工单。
 *
 * <p>处置方向按审计 3 · T3 的二选一取「③ 跳 high-rate、保留 burst」:
 * 高核销率对探店日是**设计目标**不是异常,而「同一 verifier 一分钟内连扫 N 次」
 * 仍然是刷单信号,与产品形态无关 —— 把整条风控关掉才是真的把闸拆了(N13 第二条)。
 *
 * <p>「活动壳」由 {@code selectVerifierStats} 经 {@code cms_activity.topic_id} 回到主题再取
 * {@code product_type} ⇒ ③ 的活动单(owner_type=2)与自玩票走同一判据,不会有一半漏判。
 */
class VerificationRiskFreeExploreTest {

    private static final long VERIFIER = 7L;
    private static final long TOPIC = 99L;

    private VerificationRiskService service(VerificationReviewMapper mapper) {
        VerificationRiskService s = new VerificationRiskService(new BigDecimal("0.90"), 8, 5);
        ReflectionTestUtils.setField(s, "reviewMapper", mapper);
        ReflectionTestUtils.setField(s, "committedBusinessNotice", mock(com.chengyinhub.business.service.support.CommittedBusinessNotice.class));
        ReflectionTestUtils.setField(s, "alarmService", mock(com.chengyinhub.business.service.impl.AlarmService.class));
        return s;
    }

    /** N13 第一条:探店日单店 paidCount=120 / verifiedCount=115(rate=0.958)⇒ 不许入队。 */
    @Test
    void freeExploreHighRateDoesNotEnterQueue() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);

        assertFalse(service(mapper).evaluateAndQueue(VERIFIER, TOPIC, 120, 115, 2,
                        ProductType.FREE_EXPLORE),
                "探店日高核销率是设计目标,不是异常");
        verify(mapper, never()).insertIgnore(any());
    }

    /** N13 第二条:同一 verifier 一分钟内 9 次核销 ⇒ 探店日也必须入队(burst 仍有效)。 */
    @Test
    void freeExploreBurstStillEntersQueue() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(1);

        assertTrue(service(mapper).evaluateAndQueue(VERIFIER, TOPIC, 120, 115, 9,
                ProductType.FREE_EXPLORE));

        ArgumentCaptor<VerificationReview> captor = ArgumentCaptor.forClass(VerificationReview.class);
        verify(mapper).insertIgnore(captor.capture());
        assertEquals("BURST", captor.getValue().getRiskType(),
                "探店日的高 rate 不该并进 riskType —— 并进去会让运营以为核销率也有问题");
    }

    /** ①② 经典定向不受影响:同样的 rate 仍然入队。回归防线 —— 别顺手把整条风控关掉。 */
    @Test
    void orienteeringHighRateStillEntersQueue() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(1);

        assertTrue(service(mapper).evaluateAndQueue(VERIFIER, TOPIC, 120, 115, 2,
                ProductType.ORIENTEERING));
        ArgumentCaptor<VerificationReview> captor = ArgumentCaptor.forClass(VerificationReview.class);
        verify(mapper).insertIgnore(captor.capture());
        assertEquals("HIGH_RATE", captor.getValue().getRiskType());
    }

    /** productType 为 NULL(存量未回填)按非探店日处理 —— fail-closed:宁可多报一条人审。 */
    @Test
    void unknownProductTypeKeepsHighRateGate() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        when(mapper.insertIgnore(any())).thenReturn(1);

        assertTrue(service(mapper).evaluateAndQueue(VERIFIER, TOPIC, 120, 115, 2, null));
        verify(mapper).insertIgnore(any(VerificationReview.class));
    }

    /** 活动壳:productType 由 selectVerifierStats 一并取回,inspectAndQueue 必须真的用它。 */
    @Test
    void activityShellProductTypeIsReadFromStats() {
        VerificationReviewMapper mapper = mock(VerificationReviewMapper.class);
        Map<String, Object> stats = new HashMap<>();
        stats.put("topicId", TOPIC);
        stats.put("paidCount", 120);
        stats.put("verifiedCount", 115);
        stats.put("oneMinuteCount", 2);
        stats.put("productType", ProductType.FREE_EXPLORE);
        when(mapper.selectVerifierStats(anyLong(), anyLong())).thenReturn(stats);

        assertFalse(service(mapper).inspectAndQueue(VERIFIER, 555L),
                "统计里已经带回 product_type=2,inspectAndQueue 不读它 = 活动壳这一半仍然每场必炸");
        verify(mapper, never()).insertIgnore(any());
    }
}
