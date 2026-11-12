package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.vo.ai.MerchantInsightResp;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.MerchantInsightMapper;
import com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IMerchantLeadService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.ITopicContentPublisherProjectionService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.support.MerchantMetricPeriod;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-93(2026-11-11 owner:营销首页和店铺参谋指标合并成一套)。
 *
 * <p>两页的服务入口({@code marketingHome}/{@code funnel} 与 {@code insight})接同一个
 * {@link MerchantMetricsServiceImpl}、同一份核销事件,断言同一商家同一窗口吐出的核销本期/上期/环比完全一致;
 * 覆盖零数据、上海 0 点窗口边界、他店隔离。
 */
class MerchantMetricsParityTest {

    private static final long MERCHANT_A = 501L;
    private static final long MERCHANT_B = 502L;
    private static final long DAY = 24L * 60L * 60L * 1000L;
    /** 上海 2026-11-11 15:00 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T07:00:00Z"), MerchantMetricPeriod.ZONE);
    /** 上海 2026-11-12 00:00 = 今天窗口的 endExclusive */
    private static final long END = Instant.parse("2026-11-11T16:00:00Z").toEpochMilli();

    private MerchantMarketingAnalyticsMapper analyticsMapper;
    private MerchantAggregateReadServiceImpl marketing;
    private MerchantInsightServiceImpl advisor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        analyticsMapper = mock(MerchantMarketingAnalyticsMapper.class);
        MerchantMetricsServiceImpl metrics = new MerchantMetricsServiceImpl(analyticsMapper, CLOCK);

        marketing = new MerchantAggregateReadServiceImpl(
                mock(IMmsMerchantService.class), mock(IClubService.class), mock(IClubMemberService.class),
                mock(CoopInviteMapper.class), mock(ICmsTopicService.class), mock(ISmsCouponService.class),
                mock(ICmsActivityService.class), analyticsMapper, mock(CmsTopicChapterMapper.class),
                mock(ISysCategoryService.class), metrics);

        advisor = new MerchantInsightServiceImpl();
        RedisTemplate<Object, Object> redis = mock(RedisTemplate.class);
        ValueOperations<Object, Object> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        IAiCreatorService ai = mock(IAiCreatorService.class);
        when(ai.merchantInsight(anyString(), anyLong())).thenThrow(new AiServiceException("off"));
        ICmsTopicService topics = mock(ICmsTopicService.class);
        when(topics.selectCmsTopicList(any())).thenReturn(Collections.emptyList());
        ITopicContentPublisherProjectionService projection = mock(ITopicContentPublisherProjectionService.class);
        when(projection.project(any())).thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(advisor, "merchantInsightMapper", mock(MerchantInsightMapper.class));
        ReflectionTestUtils.setField(advisor, "aiCreatorService", ai);
        ReflectionTestUtils.setField(advisor, "redisTemplate", redis);
        ReflectionTestUtils.setField(advisor, "mmsMerchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(advisor, "cmsTopicService", topics);
        ReflectionTestUtils.setField(advisor, "merchantLeadService", mock(IMerchantLeadService.class));
        ReflectionTestUtils.setField(advisor, "topicContentPublisherProjectionService", projection);
        ReflectionTestUtils.setField(advisor, "merchantMetricsService", metrics);
    }

    private void verifyEvents(long owner, Date... times) {
        when(analyticsMapper.selectVerifyEventTimes(eq(owner), any(Date.class), any(Date.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(times)));
    }

    private static Date at(long epochMs) {
        return new Date(epochMs);
    }

    @Test
    void bothPagesReturnTheSameVerifyNumbersFor7And30Days() {
        verifyEvents(MERCHANT_A,
                at(END - 1),                  // 今天 23:59:59.999
                at(END - 3 * DAY),
                at(END - 10 * DAY),           // 7 天窗上期 / 30 天窗本期
                at(END - 12 * DAY),
                at(END - 45 * DAY));          // 30 天窗上期

        for (int days : new int[] { 7, 30 }) {
            Map<String, Object> marketingVerify = verifyStage(marketing.marketingHome(MERCHANT_A, 9L, days));
            Map<String, Object> funnelVerify = verifyStage(marketing.funnel(MERCHANT_A, days));
            JSONObject advisorWindow = trendWindow(advisor.insight(MERCHANT_A), days);

            assertEquals(marketingVerify.get("currentCount"), advisorWindow.getLong("redeems"), days + " 天本期核销");
            assertEquals(marketingVerify.get("previousCount"), advisorWindow.getLong("prevRedeems"), days + " 天上期核销");
            assertEquals(marketingVerify.get("changeRate"),
                    advisorWindow.getJSONObject("changeRates").getString("redeems"), days + " 天核销环比");
            assertEquals(marketingVerify, funnelVerify, "兼容路由 /funnel 与营销首页同一份数");
            assertEquals(advisorWindow.getLong("redeems"), sumDaily(advisorWindow), "逐日格合计 = 窗口核销");
        }

        Map<String, Object> v7 = verifyStage(marketing.marketingHome(MERCHANT_A, 9L, 7));
        assertEquals(2L, v7.get("currentCount"));
        assertEquals(2L, v7.get("previousCount"));
        assertEquals("0.0000", v7.get("changeRate"));
        Map<String, Object> v30 = verifyStage(marketing.marketingHome(MERCHANT_A, 9L, 30));
        assertEquals(4L, v30.get("currentCount"));
        assertEquals(1L, v30.get("previousCount"));
        assertEquals("3.0000", v30.get("changeRate"));

        JSONObject attribution = advisor.insight(MERCHANT_A).getFacts().getJSONObject("attribution");
        assertEquals(4L, attribution.getLongValue("redeems"), "归因卡与 30 天主卡/营销首页同一份核销");
        assertEquals(1L, attribution.getLongValue("prevRedeems"));
    }

    @Test
    void zeroDataIsZeroOnBothPagesAndChangeRateStaysUnknown() {
        Map<String, Object> marketingVerify = verifyStage(marketing.marketingHome(MERCHANT_A, 9L, 30));
        JSONObject advisorWindow = trendWindow(advisor.insight(MERCHANT_A), 30);

        assertEquals(0L, marketingVerify.get("currentCount"));
        assertEquals(0L, marketingVerify.get("previousCount"));
        assertNull(marketingVerify.get("changeRate"), "上期为 0 不造环比");
        assertEquals(0L, advisorWindow.getLongValue("redeems"));
        assertEquals(0L, advisorWindow.getLongValue("prevRedeems"));
        assertNull(advisorWindow.getJSONObject("changeRates").get("redeems"));
    }

    @Test
    void shanghaiMidnightBoundaryIsHalfOpenOnBothPages() {
        long currentStart = END - 30 * DAY;
        long previousStart = currentStart - 30 * DAY;
        verifyEvents(MERCHANT_A,
                at(currentStart),             // 本期首刻:算本期
                at(currentStart - 1),         // 上期最后一刻:算上期
                at(previousStart),            // 上期首刻:算上期
                at(previousStart - 1),        // 更早:都不算
                at(END));                     // 明天 0 点:不算(半开)

        Map<String, Object> marketingVerify = verifyStage(marketing.marketingHome(MERCHANT_A, 9L, 30));
        JSONObject advisorWindow = trendWindow(advisor.insight(MERCHANT_A), 30);

        assertEquals(1L, marketingVerify.get("currentCount"));
        assertEquals(2L, marketingVerify.get("previousCount"));
        assertEquals(marketingVerify.get("currentCount"), advisorWindow.getLong("redeems"));
        assertEquals(marketingVerify.get("previousCount"), advisorWindow.getLong("prevRedeems"));
        JSONArray daily = advisorWindow.getJSONArray("daily");
        assertEquals(1L, daily.getJSONObject(0).getLongValue("redeems"), "本期首刻落在窗口首日");
        assertEquals("10-13", daily.getJSONObject(0).getString("date"), "上海 11-11 往前 30 个自然日含今天");
        assertEquals("11-11", daily.getJSONObject(29).getString("date"));
    }

    @Test
    void otherMerchantsVerifyEventsNeverLeakIntoEitherPage() {
        verifyEvents(MERCHANT_A, at(END - DAY), at(END - 2 * DAY), at(END - 3 * DAY));
        verifyEvents(MERCHANT_B, at(END - DAY));

        Map<String, Object> marketingB = verifyStage(marketing.marketingHome(MERCHANT_B, 9L, 30));
        JSONObject advisorB = trendWindow(advisor.insight(MERCHANT_B), 30);

        assertEquals(1L, marketingB.get("currentCount"));
        assertEquals(1L, advisorB.getLongValue("redeems"));
        verify(analyticsMapper, never()).selectVerifyEventTimes(eq(MERCHANT_A), any(Date.class), any(Date.class));

        assertEquals(3L, verifyStage(marketing.marketingHome(MERCHANT_A, 9L, 30)).get("currentCount"));
        assertEquals(3L, trendWindow(advisor.insight(MERCHANT_A), 30).getLongValue("redeems"));
    }

    @Test
    void sharedServiceRejectsMissingOwnerAndUnsupportedWindows() {
        MerchantMetricsServiceImpl metrics = new MerchantMetricsServiceImpl(analyticsMapper, CLOCK);
        assertThrows(IllegalArgumentException.class,
                () -> metrics.verify(null, metrics.windows(30)), "owner 只来自服务端,缺失不得查全表");
        assertThrows(IllegalArgumentException.class, () -> metrics.window(14));
        assertThrows(IllegalArgumentException.class, () -> marketing.marketingHome(MERCHANT_A, 9L, 90),
                "营销首页仍只开放 7/30");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verifyStage(Map<String, Object> homeOrAnalytics) {
        Map<String, Object> analytics = homeOrAnalytics.containsKey("analytics")
                ? (Map<String, Object>) homeOrAnalytics.get("analytics") : homeOrAnalytics;
        for (Map<String, Object> stage : (List<Map<String, Object>>) analytics.get("stages")) {
            if ("VERIFY".equals(stage.get("code"))) return stage;
        }
        throw new AssertionError("缺少 VERIFY 阶段");
    }

    private static JSONObject trendWindow(MerchantInsightResp resp, int days) {
        for (Object o : resp.getTrends().getJSONArray("windows")) {
            JSONObject w = (JSONObject) o;
            if (w.getIntValue("days") == days) return w;
        }
        throw new AssertionError("缺少 " + days + " 天窗口");
    }

    private static long sumDaily(JSONObject window) {
        long total = 0;
        for (Object o : window.getJSONArray("daily")) total += ((JSONObject) o).getLongValue("redeems");
        return total;
    }
}
