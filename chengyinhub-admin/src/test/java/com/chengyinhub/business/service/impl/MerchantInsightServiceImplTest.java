package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.dto.MerchantInsightCrowdFact;
import com.chengyinhub.business.domain.dto.MerchantInsightSupplyFact;
import com.chengyinhub.business.domain.dto.MerchantInsightVisitFact;
import com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp;
import com.chengyinhub.business.domain.vo.ai.MerchantInsightResp;
import com.chengyinhub.business.mapper.MerchantInsightMapper;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ai.AiServiceException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家店铺参谋编排层:聚合口径(工作例)、lowSample 闸、AI 降级不连坐、缓存读写、PII 白名单。
 */
class MerchantInsightServiceImplTest {

    private MerchantInsightServiceImpl service;
    private MerchantInsightMapper mapper;
    private IAiCreatorService aiCreatorService;
    private RedisTemplate<Object, Object> redisTemplate;
    private ValueOperations<Object, Object> valueOperations;
    private com.chengyinhub.business.mapper.MmsMerchantMapper mmsMerchantMapper;
    private com.chengyinhub.business.service.ICmsTopicService cmsTopicService;
    private com.chengyinhub.business.service.IMerchantLeadService merchantLeadService;
    private com.chengyinhub.business.service.ITopicContentPublisherProjectionService
            topicContentPublisherProjectionService;

    private static final Long MERCHANT_ID = 9001L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(MerchantInsightMapper.class);
        aiCreatorService = mock(IAiCreatorService.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        service = new MerchantInsightServiceImpl();
        mmsMerchantMapper = mock(com.chengyinhub.business.mapper.MmsMerchantMapper.class);
        ReflectionTestUtils.setField(service, "merchantInsightMapper", mapper);
        ReflectionTestUtils.setField(service, "aiCreatorService", aiCreatorService);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", mmsMerchantMapper);
        when(mmsMerchantMapper.selectMmsMerchantByMemberId(MERCHANT_ID)).thenReturn(null);
        cmsTopicService = mock(com.chengyinhub.business.service.ICmsTopicService.class);
        merchantLeadService = mock(com.chengyinhub.business.service.IMerchantLeadService.class);
        ReflectionTestUtils.setField(service, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(service, "merchantLeadService", merchantLeadService);
        // CU-M-85:推荐卡补场次/主办方 —— 主办方名走 app 侧「来源」投影(内容槽不在 cms_topic 上)。
        // 默认桩:投影原样返回(不写主办方名),个别用例自己接管。
        topicContentPublisherProjectionService =
                mock(com.chengyinhub.business.service.ITopicContentPublisherProjectionService.class);
        ReflectionTestUtils.setField(service, "topicContentPublisherProjectionService",
                topicContentPublisherProjectionService);
        when(topicContentPublisherProjectionService.project(any())).thenAnswer(call -> call.getArgument(0));
        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(Collections.emptyList());

        when(mapper.selectVisitFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(Collections.emptyList());
        when(mapper.selectCrowdFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(Collections.emptyList());
        when(mapper.selectSupplyFacts(MERCHANT_ID)).thenReturn(Collections.emptyList());
        when(mapper.selectVisitorEvents(MERCHANT_ID)).thenReturn(Collections.emptyList());
        when(aiCreatorService.merchantInsight(anyString(), anyLong())).thenReturn(aiResp("AI总结"));
    }

    private static AiMerchantInsightResp aiResp(String summary) {
        AiMerchantInsightResp ai = new AiMerchantInsightResp();
        ai.setTraceId("trace-mi");
        ai.setSummary(summary);
        return ai;
    }

    private static Date at(int dayOffset, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTime();
    }

    private static MerchantInsightVisitFact visit(long regId, long memberId, Date checkin, Date redeemed) {
        MerchantInsightVisitFact f = new MerchantInsightVisitFact();
        f.setRegistrationId(regId);
        f.setMemberId(memberId);
        f.setCheckinAt(checkin);
        f.setRedeemedAt(redeemed);
        return f;
    }

    private static com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent event(
            long memberId, String nickname, String topic, Date checkin, Date redeemed) {
        com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent e =
                new com.chengyinhub.business.domain.dto.MerchantInsightVisitorEvent();
        e.setMemberId(memberId);
        e.setNickname(nickname);
        e.setAvatar("a" + memberId + ".png");
        e.setTopicName(topic);
        e.setCheckinAt(checkin);
        e.setRedeemedAt(redeemed);
        return e;
    }

    private static Date birthdayYearsAgo(int years) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.YEAR, -years);
        c.add(Calendar.DAY_OF_MONTH, -1); // 已过生日,年龄恰为 years
        return c.getTime();
    }

    private static MerchantInsightCrowdFact crowd(long memberId, Integer sex, Date birthday,
                                                  Integer level, String weightsJson) {
        MerchantInsightCrowdFact f = new MerchantInsightCrowdFact();
        f.setMemberId(memberId);
        f.setSex(sex);
        f.setBirthday(birthday);
        f.setLevel(level);
        f.setCategoryWeightsJson(weightsJson);
        return f;
    }

    // ---- 聚合口径:工作例,期望值全部手算独立于实现 ----

    @Test
    void factsWorkedExample() {
        // 4 行 entitlement:member1 两次核销(复购)、member2 只打卡、member3 没来
        List<MerchantInsightVisitFact> visits = Arrays.asList(
                visit(1, 1L, at(-1, 14, 5), at(-1, 14, 25)),   // 等待 20 分钟
                visit(2, 1L, at(-2, 14, 40), at(-2, 15, 0)),   // 等待 20 分钟
                visit(3, 2L, at(-3, 10, 10), null),
                visit(4, 3L, null, null));
        when(mapper.selectVisitFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(visits);
        when(mapper.selectCrowdFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(Arrays.asList(
                crowd(1L, 1, birthdayYearsAgo(28), 5, "{\"咖啡\":2,\"citywalk\":1}"),
                crowd(2L, 2, null, null, "{\"咖啡\":1}")));
        MerchantInsightSupplyFact s1 = new MerchantInsightSupplyFact();
        s1.setTopicName("秋日探店"); s1.setQuotaTotal(20); s1.setQuotaUsed(5);
        MerchantInsightSupplyFact s2 = new MerchantInsightSupplyFact();
        s2.setTopicName("咖啡地图"); s2.setQuotaTotal(10); s2.setQuotaUsed(10);
        when(mapper.selectSupplyFacts(MERCHANT_ID)).thenReturn(Arrays.asList(s1, s2));
        // 事件行(全历史):member1 本期两次(秋日探店)+ 45 天前一次(回头客证据);member2 本期打卡一次(咖啡地图)
        when(mapper.selectVisitorEvents(MERCHANT_ID)).thenReturn(Arrays.asList(
                event(1L, "小王", "秋日探店", at(-1, 14, 5), at(-1, 14, 25)),
                event(1L, "小王", "秋日探店", at(-2, 14, 40), at(-2, 15, 0)),
                event(2L, "小李", "咖啡地图", at(-3, 10, 10), null),
                event(1L, "小王", "旧主题", at(-45, 12, 0), at(-45, 12, 30))));

        MerchantInsightResp resp = service.insight(MERCHANT_ID);
        JSONObject facts = resp.getFacts();
        assertNotNull(facts);

        JSONObject checkin = facts.getJSONObject("checkin");
        assertEquals(3, checkin.getIntValue("total"));                    // 3 行有打卡
        assertEquals(0.5, checkin.getDouble("redeemRate"), 1e-9);         // 2 核销 / 4 应得
        assertEquals(1.0, checkin.getDouble("repeatRate"), 1e-9);         // 唯一核销客 member1 核销 2 次
        assertEquals(20L, checkin.getLong("avgWaitMinutes").longValue()); // 两次都是 20 分钟
        // 时段 4 段固定:早(10点)=1、午(14点×2)=2、晚=0、夜=0
        assertEquals(4, checkin.getJSONArray("slotBuckets").size());
        assertEquals(1, checkin.getJSONArray("slotBuckets").getJSONObject(0).getIntValue("count"));
        assertEquals(2, checkin.getJSONArray("slotBuckets").getJSONObject(1).getIntValue("count"));
        assertEquals(0, checkin.getJSONArray("slotBuckets").getJSONObject(2).getIntValue("count"));

        // 归因:本期 2 位玩家 / 3 次打卡 / 2 单核销;上期(30-60天)1 位 / 1 次 / 1 单
        JSONObject attribution = facts.getJSONObject("attribution");
        assertEquals(2, attribution.getIntValue("visitors"));
        assertEquals(3, attribution.getIntValue("checkins"));
        assertEquals(2, attribution.getIntValue("redeems"));
        assertEquals(1, attribution.getIntValue("prevVisitors"));
        assertEquals(1, attribution.getIntValue("prevCheckins"));
        assertEquals(1, attribution.getIntValue("prevRedeems"));

        // 新老客:member1 窗口前来过=回头客,member2=新客
        JSONObject split = facts.getJSONObject("split");
        assertEquals(1, split.getIntValue("newVisitors"));
        assertEquals(1, split.getIntValue("returningVisitors"));

        // 来源主题:窗口内 秋日探店 2 行 > 咖啡地图 1 行(旧主题在窗口外不计)
        assertEquals(2, facts.getJSONArray("sources").size());
        assertEquals("秋日探店", facts.getJSONArray("sources").getJSONObject(0).getString("name"));
        assertEquals(2, facts.getJSONArray("sources").getJSONObject(0).getIntValue("count"));

        // 单客行:2 位去重,最近的 member1 在前,「第 3 次来」= 全历史 3 行
        assertEquals(2, resp.getRecentVisitors().size());
        assertEquals("小王", resp.getRecentVisitors().get(0).getNickname());
        assertEquals(3, resp.getRecentVisitors().get(0).getVisitCount().intValue());
        assertEquals("秋日探店", resp.getRecentVisitors().get(0).getTopicName());

        JSONObject crowd = facts.getJSONObject("crowd");
        assertEquals(2, crowd.getIntValue("members"));
        assertEquals(0.5, crowd.getJSONObject("sexRatio").getDouble("male"), 1e-9);
        assertEquals(1, crowd.getJSONObject("ageBuckets").getIntValue("25-34"));
        assertEquals(1, crowd.getJSONObject("ageBuckets").getIntValue("未知"));
        assertEquals(1, crowd.getJSONObject("levelBuckets").getIntValue("L5"));
        assertEquals("咖啡", crowd.getJSONArray("interestTop").getString(0)); // 权重 3 > 1

        JSONObject supply = facts.getJSONObject("supply");
        assertEquals(2, supply.getIntValue("activeOffers"));
        assertEquals(0.5, supply.getDouble("quotaUsedRate"), 1e-9);       // (5+10)/(20+10)

        assertEquals(1, facts.getIntValue("sampleMembers"));              // 只有 member1 核销过
        assertTrue(facts.getBooleanValue("lowSample"));
    }

    @Test
    void supplyTopicsAreKeyedByTopicIdNotName() {
        // 两个不同主题同名「城市漫步」各算一个;同一主题(id=7)承接两章只算一次
        MerchantInsightSupplyFact a = supplyFact(5L, "城市漫步");
        MerchantInsightSupplyFact b = supplyFact(6L, "城市漫步");
        MerchantInsightSupplyFact c1 = supplyFact(7L, "咖啡地图");
        MerchantInsightSupplyFact c2 = supplyFact(7L, "咖啡地图");
        when(mapper.selectSupplyFacts(MERCHANT_ID)).thenReturn(Arrays.asList(a, b, c1, c2));

        JSONObject supply = service.insight(MERCHANT_ID).getFacts().getJSONObject("supply");

        assertEquals(4, supply.getIntValue("activeOffers"));
        assertEquals(Arrays.asList("城市漫步", "城市漫步", "咖啡地图"),
                supply.getJSONArray("topics").toJavaList(String.class));
    }

    private static MerchantInsightSupplyFact supplyFact(Long topicId, String name) {
        MerchantInsightSupplyFact f = new MerchantInsightSupplyFact();
        f.setTopicId(topicId);
        f.setTopicName(name);
        f.setQuotaTotal(1);
        f.setQuotaUsed(0);
        return f;
    }

    // ---- lowSample 闸在服务端:summary 被固定模板覆盖,audiences/suggestions 保留 ----

    @Test
    void lowSampleReplacesSummaryServerSide() {
        AiMerchantInsightResp ai = aiResp("模型自信满满的统计结论");
        ai.setAudiences(new ArrayList<>());
        when(aiCreatorService.merchantInsight(anyString(), anyLong())).thenReturn(ai);

        MerchantInsightResp resp = service.insight(MERCHANT_ID); // 空数据 ⇒ lowSample

        assertNotNull(resp.getAi());
        assertFalse(resp.getAi().getSummary().contains("自信满满"));
        assertTrue(resp.getAi().getSummary().contains("合作中心"));
        assertNotNull(resp.getAi().getAudiences());
    }

    // ---- AI 失败:facts 不连坐,ai=null + aiError,且不写缓存(负控) ----

    @Test
    void aiFailureDegradesWithoutCaching() {
        when(aiCreatorService.merchantInsight(anyString(), anyLong()))
                .thenThrow(new AiServiceException("今日 AI 次数已用完"));

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        assertNotNull(resp.getFacts());
        assertNull(resp.getAi());
        assertEquals("今日 AI 次数已用完", resp.getAiError());
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any(TimeUnit.class));
    }

    // ---- 成功写缓存;当日第二次命中缓存不再查库不再调 AI ----

    @Test
    void successWritesCacheAndSecondCallHitsIt() {
        MerchantInsightResp first = service.insight(MERCHANT_ID);
        assertNotNull(first.getAi());

        ArgumentCaptor<Object> valueCap = ArgumentCaptor.forClass(Object.class);
        verify(valueOperations).set(anyString(), valueCap.capture(), anyLong(), eq(TimeUnit.SECONDS));

        // 第二次:redis 返回刚才写入的值
        when(valueOperations.get(anyString())).thenReturn(valueCap.getValue());
        MerchantInsightResp second = service.insight(MERCHANT_ID);

        // 空数据 ⇒ lowSample ⇒ 首次返回的 summary 已是固定模板;缓存往返后必须原样一致
        assertNotNull(second.getAi());
        assertEquals(first.getAi().getSummary(), second.getAi().getSummary());
        // mapper 只在第一次被调过(各一次)
        verify(mapper, org.mockito.Mockito.times(1)).selectVisitFacts(eq(MERCHANT_ID), any(Date.class));
        verify(aiCreatorService, org.mockito.Mockito.times(1)).merchantInsight(anyString(), anyLong());
    }

    // ---- 店铺承接档案:未配置引导 + 缓存命中仍实时读 ----

    @Test
    void profileUnconfiguredWhenMerchantMissingOrEmpty() {
        MerchantInsightResp resp = service.insight(MERCHANT_ID); // setUp 默认查无商家
        assertNotNull(resp.getProfile());
        assertFalse(resp.getProfile().getBooleanValue("configured"));
    }

    @Test
    void profileIsFreshEvenOnCacheHit() {
        // 第一次:未配置,结果被写缓存
        MerchantInsightResp first = service.insight(MERCHANT_ID);
        assertFalse(first.getProfile().getBooleanValue("configured"));
        ArgumentCaptor<Object> valueCap = ArgumentCaptor.forClass(Object.class);
        verify(valueOperations).set(anyString(), valueCap.capture(), anyLong(), eq(TimeUnit.SECONDS));

        // 商家随后在装修-合作设置页填了档案;当日缓存还在
        com.chengyinhub.business.domain.MmsMerchant m = new com.chengyinhub.business.domain.MmsMerchant();
        m.setCapacity(30);
        m.setSuitActivityTypes("citywalk,探店");
        m.setDemand("想接下午茶主题");
        when(mmsMerchantMapper.selectMmsMerchantByMemberId(MERCHANT_ID)).thenReturn(m);
        when(valueOperations.get(anyString())).thenReturn(valueCap.getValue());

        MerchantInsightResp second = service.insight(MERCHANT_ID);
        // facts/ai 走缓存,但档案必须是实时的 —— 否则商家配置完回来仍看到「去配置」死循环
        assertTrue(second.getProfile().getBooleanValue("configured"));
        assertEquals(30, second.getProfile().getIntValue("capacity"));
    }

    // ---- 实体推荐:反幻觉校验 + AI 挂时兜底 ----

    @Test
    void ownTopicsAreExcludedBeforeAiAndFallbackRecommendation() {
        com.chengyinhub.business.domain.CmsTopic own = topic(101L, "自己的主题");
        own.setMemberId(MERCHANT_ID);
        com.chengyinhub.business.domain.CmsTopic other = topic(102L, "可承接主题");
        other.setMemberId(9002L);
        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(own, other));
        MerchantInsightResp resp = service.insight(MERCHANT_ID);
        assertEquals(1, resp.getRecommendedTopics().size());
        assertEquals(102L, resp.getRecommendedTopics().get(0).getLongValue("topicId"));
        assertFalse(resp.getFacts().getJSONObject("candidates").toJSONString().contains("自己的主题"));
    }

    @Test
    void expiredMerchantSignupTopicsAreExcludedByCooperationDeadlineOnly() {
        long now = System.currentTimeMillis();
        com.chengyinhub.business.domain.CmsTopic expired = topic(103L, "已截止但活动未开始");
        expired.setMerchantSignUpEndDate(new Date(now - 60_000L));
        expired.setStartDate(new Date(now + 86_400_000L));
        expired.setEndDate(new Date(now + 172_800_000L));

        com.chengyinhub.business.domain.CmsTopic open = topic(104L, "商家报名仍开放");
        open.setMerchantSignUpEndDate(new Date(now + 86_400_000L));
        open.setStartDate(new Date(now - 172_800_000L));
        open.setEndDate(new Date(now - 86_400_000L));
        open.setRecruitDeadline(new Date(now - 60_000L));

        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(expired, open));

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        assertEquals(1, resp.getRecommendedTopics().size());
        assertEquals(104L, resp.getRecommendedTopics().get(0).getLongValue("topicId"));
        String candidates = resp.getFacts().getJSONObject("candidates").toJSONString();
        assertFalse(candidates.contains("已截止但活动未开始"));
        assertTrue(candidates.contains("商家报名仍开放"));
    }

    @Test
    void cachedRecommendationsAreRevalidatedWhenSignupDeadlinePasses() {
        com.chengyinhub.business.domain.CmsTopic candidate = topic(105L, "当天截止主题");
        candidate.setMerchantSignUpEndDate(new Date(System.currentTimeMillis() + 86_400_000L));
        when(cmsTopicService.selectCmsTopicList(any()))
                .thenReturn(new ArrayList<>(Arrays.asList(candidate)));

        MerchantInsightResp first = service.insight(MERCHANT_ID);
        assertEquals(1, first.getRecommendedTopics().size());

        ArgumentCaptor<Object> valueCap = ArgumentCaptor.forClass(Object.class);
        verify(valueOperations).set(anyString(), valueCap.capture(), anyLong(), eq(TimeUnit.SECONDS));
        candidate.setMerchantSignUpEndDate(new Date(System.currentTimeMillis() - 60_000L));
        when(valueOperations.get(anyString())).thenReturn(valueCap.getValue());

        MerchantInsightResp second = service.insight(MERCHANT_ID);

        assertTrue(second.getRecommendedTopics().isEmpty());
        verify(aiCreatorService, org.mockito.Mockito.times(1)).merchantInsight(anyString(), anyLong());
        verify(cmsTopicService, org.mockito.Mockito.times(2)).selectCmsTopicList(any());
    }

    private static com.chengyinhub.business.domain.CmsTopic topic(long id, String name) {
        com.chengyinhub.business.domain.CmsTopic t = new com.chengyinhub.business.domain.CmsTopic();
        t.setId(id);
        t.setName(name);
        return t;
    }

    @Test
    void hallucinatedPickIsDroppedAndFallsBackToPool() {
        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(
                topic(101L, "秋日探店季"), topic(102L, "咖啡地图")));
        AiMerchantInsightResp ai = aiResp("AI总结");
        AiMerchantInsightResp.InsightPick bogus = new AiMerchantInsightResp.InsightPick();
        bogus.setId(999L); // 池里没有 —— 模型编的
        bogus.setReason("编造的理由");
        ai.setRecommendedTopics(Arrays.asList(bogus));
        when(aiCreatorService.merchantInsight(anyString(), anyLong())).thenReturn(ai);

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        // 越界 id 全丢 ⇒ 回退候选前 3(这里是 2 条),名字来自真实池,reason 为空
        assertEquals(2, resp.getRecommendedTopics().size());
        assertEquals("秋日探店季", resp.getRecommendedTopics().get(0).getString("name"));
        assertNull(resp.getRecommendedTopics().get(0).getString("reason"));
    }

    @Test
    void validPickIsFilledWithRealEntityAndReason() {
        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(
                topic(101L, "秋日探店季"), topic(102L, "咖啡地图")));
        AiMerchantInsightResp ai = aiResp("AI总结");
        AiMerchantInsightResp.InsightPick pick = new AiMerchantInsightResp.InsightPick();
        pick.setId(102L);
        pick.setReason("客群兴趣第一位是咖啡");
        ai.setRecommendedTopics(Arrays.asList(pick));
        when(aiCreatorService.merchantInsight(anyString(), anyLong())).thenReturn(ai);

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        assertEquals(1, resp.getRecommendedTopics().size());
        assertEquals(102L, resp.getRecommendedTopics().get(0).getLongValue("topicId"));
        assertEquals("咖啡地图", resp.getRecommendedTopics().get(0).getString("name"));
        assertEquals("客群兴趣第一位是咖啡", resp.getRecommendedTopics().get(0).getString("reason"));
    }

    @Test
    void recommendedTopicRowsCarrySessionInfo() {
        // CU-M-85:同名主题靠名字分不出是哪一场 —— 行里必须带起止日期与主办方,卡面才写得出来。
        com.chengyinhub.business.domain.CmsTopic first = topic(101L, "E2E 探店日一期");
        first.setStartDate(at(-10, 10, 0));
        first.setEndDate(at(-1, 18, 0));
        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(new ArrayList<>(Arrays.asList(first)));
        when(topicContentPublisherProjectionService.project(any())).thenAnswer(call -> {
            List<com.chengyinhub.business.domain.CmsTopic> pool = call.getArgument(0);
            for (com.chengyinhub.business.domain.CmsTopic t : pool) t.setContentPublisherName("夜航俱乐部");
            return pool;
        });

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        JSONObject row = resp.getRecommendedTopics().get(0);
        assertEquals(101L, row.getLongValue("topicId"));
        assertNotNull(row.get("startDate"), "缺起止日期同名主题就分不出场次");
        assertNotNull(row.get("endDate"));
        assertEquals("夜航俱乐部", row.getString("hostName"));
    }

    @Test
    void recommendationsSurviveAiFailure() {
        when(cmsTopicService.selectCmsTopicList(any())).thenReturn(Arrays.asList(
                topic(101L, "秋日探店季")));
        when(aiCreatorService.merchantInsight(anyString(), anyLong()))
                .thenThrow(new AiServiceException("今日次数已用完"));

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        // 推荐区是必出层:AI 挂了仍给真实候选
        assertNull(resp.getAi());
        assertEquals(1, resp.getRecommendedTopics().size());
        assertEquals("秋日探店季", resp.getRecommendedTopics().get(0).getString("name"));
    }

    // ---- PII 白名单:喂给模型的事实包里不许出现明细字段 ----

    @Test
    void promptPayloadContainsNoPii() {
        when(mapper.selectVisitFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(
                Collections.singletonList(visit(1, 1L, at(-1, 14, 0), at(-1, 14, 10))));
        when(mapper.selectCrowdFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(
                Collections.singletonList(crowd(1L, 1, birthdayYearsAgo(30), 3, null)));
        // 真负控:事件行确实带着昵称/头像进了服务层 —— 它们只许出现在 recentVisitors,不许漏进 prompt
        when(mapper.selectVisitorEvents(MERCHANT_ID)).thenReturn(Collections.singletonList(
                event(1L, "泄漏哨兵昵称", "秋日探店", at(-1, 14, 0), at(-1, 14, 10))));

        service.insight(MERCHANT_ID);

        ArgumentCaptor<String> factsCap = ArgumentCaptor.forClass(String.class);
        verify(aiCreatorService).merchantInsight(factsCap.capture(), eq(MERCHANT_ID));
        String payload = factsCap.getValue();
        assertFalse(payload.contains("nickname"), "事实包禁止携带昵称字段");
        assertFalse(payload.contains("泄漏哨兵昵称"), "事件行的昵称值绝不许漏进 prompt(recentVisitors 只在 resp)");
        assertFalse(payload.contains("phone"), "事实包禁止携带手机号字段");
        assertFalse(payload.contains("memberId"), "事实包禁止携带会员ID明细");
        // 正控:确实是结构化事实包,不是空串,且新口径三块都在
        assertTrue(JSON.parseObject(payload).containsKey("checkin"));
        assertTrue(JSON.parseObject(payload).containsKey("attribution"));
        assertTrue(JSON.parseObject(payload).containsKey("sources"));
    }

    // ---- 主卡走势:7/30/90 自然日窗口 + 逐日三数,期望值手算 ----

    private static JSONObject trendWindow(MerchantInsightResp resp, int days) {
        for (Object o : resp.getTrends().getJSONArray("windows")) {
            JSONObject w = (JSONObject) o;
            if (w.getIntValue("days") == days) return w;
        }
        throw new AssertionError("缺少 " + days + " 天窗口");
    }

    @Test
    void trendsWorkedExample() {
        when(mapper.selectVisitorEvents(MERCHANT_ID)).thenReturn(Arrays.asList(
                event(1L, "n1", "T", at(0, 10, 0), at(0, 10, 20)),    // 今天:到店+打卡+核销
                event(1L, "n1", "T", at(-2, 15, 0), null),            // 同一人前天再打卡
                event(2L, "n2", "T", at(-6, 9, 0), at(-5, 9, 0)),     // 7 天窗首日打卡,次日核销
                event(3L, "n3", "T", at(-7, 12, 0), null),            // 7 天窗的上期 / 30 天窗本期
                event(4L, "n4", "T", null, at(-40, 12, 0)),           // 只核销:30 天窗上期 / 90 天窗本期
                event(2L, "n2", "T", at(-100, 12, 0), null)));        // 90 天窗上期

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        JSONObject w7 = trendWindow(resp, 7);
        assertEquals(2, w7.getIntValue("visitors"));
        assertEquals(3, w7.getIntValue("checkins"));
        assertEquals(2, w7.getIntValue("redeems"));
        assertEquals(1, w7.getIntValue("prevVisitors"));
        assertEquals(1, w7.getIntValue("prevCheckins"));
        assertEquals(0, w7.getIntValue("prevRedeems"));
        com.alibaba.fastjson2.JSONArray d7 = w7.getJSONArray("daily");
        assertEquals(7, d7.size());
        assertEquals(1, d7.getJSONObject(0).getIntValue("visitors"));   // -6 天:member2
        assertEquals(1, d7.getJSONObject(0).getIntValue("checkins"));
        assertEquals(0, d7.getJSONObject(1).getIntValue("visitors"));   // -5 天只有核销,到店算在打卡那天
        assertEquals(1, d7.getJSONObject(1).getIntValue("redeems"));
        assertEquals(1, d7.getJSONObject(4).getIntValue("checkins"));   // -2 天
        assertEquals(1, d7.getJSONObject(6).getIntValue("redeems"));    // 今天
        assertEquals(new java.text.SimpleDateFormat("MM-dd").format(new Date()),
                d7.getJSONObject(6).getString("date"));

        JSONObject w30 = trendWindow(resp, 30);
        assertEquals(3, w30.getIntValue("visitors"));
        assertEquals(4, w30.getIntValue("checkins"));
        assertEquals(2, w30.getIntValue("redeems"));
        assertEquals(1, w30.getIntValue("prevVisitors"));
        assertEquals(0, w30.getIntValue("prevCheckins"));
        assertEquals(1, w30.getIntValue("prevRedeems"));
        assertEquals(30, w30.getJSONArray("daily").size());

        JSONObject w90 = trendWindow(resp, 90);
        assertEquals(4, w90.getIntValue("visitors"));
        assertEquals(4, w90.getIntValue("checkins"));
        assertEquals(3, w90.getIntValue("redeems"));
        assertEquals(1, w90.getIntValue("prevVisitors"));
        assertEquals(1, w90.getIntValue("prevCheckins"));
        assertEquals(0, w90.getIntValue("prevRedeems"));
        assertEquals(90, w90.getJSONArray("daily").size());

        // 走势只在 resp:喂 AI 的事实包不带 trends/daily
        ArgumentCaptor<String> factsCap = ArgumentCaptor.forClass(String.class);
        verify(aiCreatorService).merchantInsight(factsCap.capture(), eq(MERCHANT_ID));
        assertFalse(factsCap.getValue().contains("daily"));
        assertFalse(JSON.parseObject(factsCap.getValue()).containsKey("trends"));
    }

    @Test
    void trendsSurviveCacheRoundTrip() {
        when(mapper.selectVisitorEvents(MERCHANT_ID)).thenReturn(Collections.singletonList(
                event(1L, "n1", "T", at(-1, 14, 0), null)));
        MerchantInsightResp first = service.insight(MERCHANT_ID);
        ArgumentCaptor<Object> valueCap = ArgumentCaptor.forClass(Object.class);
        verify(valueOperations).set(anyString(), valueCap.capture(), anyLong(), eq(TimeUnit.SECONDS));
        when(valueOperations.get(anyString())).thenReturn(valueCap.getValue());

        MerchantInsightResp second = service.insight(MERCHANT_ID);

        assertNotNull(second.getTrends());
        assertEquals(first.getTrends().toJSONString(), second.getTrends().toJSONString());
        assertEquals(1, trendWindow(second, 30).getIntValue("checkins"));
    }

    @Test
    void profileCarriesStoreNameAndLogoButPromptDoesNot() {
        com.chengyinhub.business.domain.MmsMerchant m = new com.chengyinhub.business.domain.MmsMerchant();
        m.setName("哨兵小馆");
        m.setLogo("https://img.example/logo-sentinel.png");
        when(mmsMerchantMapper.selectMmsMerchantByMemberId(MERCHANT_ID)).thenReturn(m);

        MerchantInsightResp resp = service.insight(MERCHANT_ID);

        assertEquals("哨兵小馆", resp.getProfile().getString("storeName"));
        assertEquals("https://img.example/logo-sentinel.png", resp.getProfile().getString("logo"));
        ArgumentCaptor<String> factsCap = ArgumentCaptor.forClass(String.class);
        verify(aiCreatorService).merchantInsight(factsCap.capture(), eq(MERCHANT_ID));
        assertFalse(factsCap.getValue().contains("哨兵小馆"));
        assertFalse(factsCap.getValue().contains("logo-sentinel"));
    }

    // ---- dashboard(升级5:数字直出,不走 AI) ----

    @Test
    void dashboardComposesExposureCheckinAndSupply_withoutAi() {
        when(mapper.selectVisitFacts(eq(MERCHANT_ID), any(Date.class))).thenReturn(Arrays.asList(
                visit(1, 1L, at(-1, 14, 0), at(-1, 14, 10)),
                visit(2, 2L, at(-2, 15, 0), null)));
        when(mapper.selectSupplyFacts(eq(MERCHANT_ID))).thenReturn(
                Collections.singletonList(new MerchantInsightSupplyFact()));
        when(mapper.countTopicExposure(eq(MERCHANT_ID), any(Date.class))).thenReturn(123);

        JSONObject data = service.dashboard(MERCHANT_ID);

        assertEquals(Integer.valueOf(123), data.getInteger("exposure"));
        assertEquals(Integer.valueOf(1), data.getInteger("supplyCount"));
        JSONObject checkin = data.getJSONObject("checkin");
        assertNotNull(checkin);
        assertEquals(Integer.valueOf(2), checkin.getInteger("total"), "两行都有 checkinAt");
        assertEquals(0.5, checkin.getDoubleValue("redeemRate"), 1e-9, "2 单 1 核销");
        verify(aiCreatorService, never()).merchantInsight(anyString(), anyLong());
    }
}
