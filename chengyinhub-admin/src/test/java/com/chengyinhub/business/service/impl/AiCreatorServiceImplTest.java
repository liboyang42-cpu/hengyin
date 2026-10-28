package com.chengyinhub.business.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiClubDesignReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.dto.ai.AiMerchantTaskSuggestReq;
import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.ai.AiSafetyPrecheckReq;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.dto.ai.AiChapterOverviewReq;
import com.chengyinhub.business.domain.vo.ai.AiChapterOverviewResp;
import com.chengyinhub.business.domain.vo.MerchantCandidateVO;
import com.chengyinhub.business.domain.vo.ai.AiClubDesignResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;
import com.chengyinhub.business.domain.vo.ai.AiMerchantTaskSuggestResp;
import com.chengyinhub.business.domain.vo.ai.AiNodeGenResp;
import com.chengyinhub.business.domain.vo.ai.AiSafetyPrecheckResp;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IAiTopicPlanService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.MockLlmClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 创作服务实现测试:6 方法全覆盖。
 */
class AiCreatorServiceImplTest {

    private AiCreatorServiceImpl service;
    private IAiTopicPlanService aiTopicPlanService;
    private AiGatewayService gateway;
    private IRoleService roleService;
    private IUmsMemberService umsMemberService;
    private IWxContentSecurityService wxContentSecurityService;
    private RedisTemplate<Object, Object> redisTemplate;
    private ValueOperations<Object, Object> valueOperations;

    private Long reserveResult = 1L;
    private boolean countByKey;
    private final java.util.Map<String, Long> counters = new java.util.HashMap<>();
    private final java.util.List<java.util.List<Object>> reserved = new java.util.ArrayList<>();
    private final java.util.List<java.util.List<Object>> refunded = new java.util.ArrayList<>();
    private final java.util.List<Integer> reserveTtls = new java.util.ArrayList<>();

    @SuppressWarnings("unchecked")
    private void installReservationStub() {
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            java.util.List<Object> keys = inv.getArgument(1);
            reserved.add(new java.util.ArrayList<>(keys));
            Object ttl = inv.getArgument(2);
            assertTrue(ttl instanceof Integer, "production serializer requires Integer TTL");
            reserveTtls.add((Integer) ttl);
            String key = String.valueOf(keys.get(0));
            if (countByKey) {
                long value = counters.getOrDefault(key, 0L) + 1;
                counters.put(key, value);
                return value;
            }
            return reserveResult;
        });
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv -> {
            refunded.add(new java.util.ArrayList<>((java.util.List<Object>) inv.getArgument(1)));
            return 1L;
        });
    }

    private static final Long MEMBER_ID = 1001L;

    @BeforeEach
    void setUp() {
        aiTopicPlanService = mock(IAiTopicPlanService.class);
        gateway = mock(AiGatewayService.class);
        roleService = mock(IRoleService.class);
        umsMemberService = mock(IUmsMemberService.class);
        wxContentSecurityService = mock(IWxContentSecurityService.class);

        service = new AiCreatorServiceImpl();
        ReflectionTestUtils.setField(service, "aiTopicPlanService", aiTopicPlanService);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        ReflectionTestUtils.setField(service, "roleService", roleService);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        ReflectionTestUtils.setField(service, "wxContentSecurityService", wxContentSecurityService);

        // 默认角色解析
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        when(umsMemberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("club");
    }

    // ---- merchantPlan ----

    @Test
    void merchantPlanDelegatesToTopicPlanModule() {
        AiMerchantPlanReq req = new AiMerchantPlanReq();
        req.setIdea("夜游静安");
        req.setLat(31.23);
        req.setLng(121.47);
        req.setDurationMin(90);
        AiMerchantPlanResp expected = new AiMerchantPlanResp();
        expected.setTraceId("trace-1");
        when(aiTopicPlanService.generate(req, MEMBER_ID)).thenReturn(expected);

        AiMerchantPlanResp resp = service.merchantPlan(req, MEMBER_ID);

        assertEquals(expected, resp);
        verify(aiTopicPlanService).generate(req, MEMBER_ID);
    }

    // ---- themeDraft ----

    @Test
    void themeDraftNormalFlow() {
        AiGatewayResult gwResult = new AiGatewayResult("trace-td",
                new MockLlmClient().chat("[BIZ:theme_draft]\n", "{\"idea\":\"city walk\"}"),
                "qwen", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("theme_draft"),
                anyString(), anyString())).thenReturn(gwResult);

        AiThemeDraftReq req = new AiThemeDraftReq();
        req.setIdea("city walk");

        AiThemeDraftResp resp = service.themeDraft(req, MEMBER_ID);

        assertNotNull(resp);
        assertEquals("trace-td", resp.getTraceId());
        assertNotNull(resp.getDraft());
        assertNotNull(resp.getDraft().getTitle());
    }

    @Test
    void themeDraftRejectsMockModelForClub() {
        assertMockThemeDraftRejected("club");
    }

    @Test
    void themeDraftRejectsMockModelForMerchant() {
        assertMockThemeDraftRejected("merchant");
    }

    /** mock 输出本身是结构完整的草稿,所以抛错只可能来自 mock 闸,不是解析失败。 */
    private void assertMockThemeDraftRejected(String role) {
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn(role);
        AiGatewayResult gwResult = new AiGatewayResult("trace-mock-" + role,
                new MockLlmClient().chat("[BIZ:theme_draft]\n", "{\"idea\":\"city walk\"}"),
                "mock", 0);
        when(gateway.generate(any(Long.class), eq(role), eq("theme_draft"),
                anyString(), anyString())).thenReturn(gwResult);

        AiThemeDraftReq req = new AiThemeDraftReq();
        req.setIdea("city walk");

        com.chengyinhub.business.service.ai.AiServiceException ex = assertThrows(
                com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(req, MEMBER_ID));
        assertEquals("AI 服务暂未就绪,请稍后重试", ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftReleasesReservationWhenMockModel() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        reserveResult = 1L;
        installReservationStub();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.generate(any(Long.class), eq("player"), eq("theme_draft"),
                anyString(), anyString())).thenReturn(new AiGatewayResult("trace-player-mock",
                new MockLlmClient().chat("[BIZ:theme_draft]\n", "{\"idea\":\"city walk\"}"),
                "mock", 0));

        com.chengyinhub.business.service.ai.AiServiceException ex = assertThrows(
                com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(new AiThemeDraftReq(), MEMBER_ID));
        assertEquals("AI 服务暂未就绪,请稍后重试", ex.getMessage());

        assertEquals(1, reserved.size());
        assertEquals(1, refunded.size());
    }

    /** P-43:模型真实回包的外形(plan 对象 / 根即 plan / 前后带说明 / 节点用 name)都能解析成草稿。 */
    @Test
    void themeDraftAcceptsRealModelOutputShapes() {
        String[] outputs = {
            "{\"plan\":{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"order\":1,\"name\":\"老码头\",\"roleText\":\"起点\"}]}}",
            "{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"name\":\"老码头\"}]}",
            "好的,下面是草稿:\n```json\n{\"plans\":[{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"name\":\"老码头\"}]}]}\n```",
            "这是方案 {\"plans\":[{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"name\":\"老码头\"}]}]} 祝玩得开心"
        };
        for (String text : outputs) {
            when(gateway.generate(any(Long.class), anyString(), eq("theme_draft"), anyString(), anyString()))
                    .thenReturn(new AiGatewayResult("trace-shape", text, "deepseek-chat", 0));
            AiThemeDraftResp resp = service.themeDraft(new AiThemeDraftReq(), MEMBER_ID);
            assertEquals("夜行", resp.getDraft().getTitle(), text);
            assertEquals("老码头", resp.getDraft().getNodes().get(0).getMerchantName(), text);
        }
    }

    /** P-43:外形放宽不等于放掉必填:缺故事线、节点没名字、根本没有节点,仍然报格式异常。 */
    @Test
    void themeDraftStillRequiresTitleStorylineAndNamedNodes() {
        String[] outputs = {
            "{\"plan\":{\"title\":\"夜行\",\"nodes\":[{\"name\":\"老码头\"}]}}",
            "{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"roleText\":\"没有名字\"}]}",
            "{\"title\":\"夜行\",\"storyline\":\"沿街探索\"}",
            "{\"plans\":[]}",
            "完全不是 JSON"
        };
        for (String text : outputs) {
            when(gateway.generate(any(Long.class), anyString(), eq("theme_draft"), anyString(), anyString()))
                    .thenReturn(new AiGatewayResult("trace-bad", text, "deepseek-chat", 0));
            com.chengyinhub.business.service.ai.AiServiceException error = assertThrows(
                    com.chengyinhub.business.service.ai.AiServiceException.class,
                    () -> service.themeDraft(new AiThemeDraftReq(), MEMBER_ID), text);
            assertEquals("AI 响应格式异常,请重试", error.getMessage(), text);
        }
    }

    /** P-43 根因:提示词曾只说「同 merchant_plan 的 plans 结构」,模型看不到那份结构;现在必须自带结构与节点名称字段。 */
    @Test
    void themeDraftPromptCarriesItsOwnSchema() {
        when(gateway.generate(any(Long.class), anyString(), eq("theme_draft"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-prompt",
                        "{\"plans\":[{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"name\":\"老码头\"}]}]}",
                        "deepseek-chat", 0));
        service.themeDraft(new AiThemeDraftReq(), MEMBER_ID);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(gateway).generate(any(Long.class), anyString(), eq("theme_draft"), system.capture(), anyString());
        assertTrue(system.getValue().contains("\"plans\""));
        assertTrue(system.getValue().contains("\"name\""));
        assertFalse(system.getValue().contains("同 merchant_plan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftCountsOnlyCompleteRealDraft() {
        // 额度按北京时间换日:TTL 期望值也按北京时间算,不随运行测试的 JVM 默认时区变。
        java.time.ZoneId beijing = java.time.ZoneId.of("Asia/Shanghai");
        LocalDateTime before = LocalDateTime.now(beijing);
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        reserveResult = 1L;
        installReservationStub();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.generate(any(Long.class), eq("player"), eq("theme_draft"),
                anyString(), anyString())).thenReturn(new AiGatewayResult("trace-player",
                "{\"plans\":[{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"merchantName\":\"待选点位\"}]}]}",
                "qwen", 0));

        AiThemeDraftResp resp = service.themeDraft(new AiThemeDraftReq(), MEMBER_ID);

        assertNotNull(resp.getDraft());
        assertEquals(1, reserved.size());
        assertEquals(0, refunded.size());
        LocalDateTime after = LocalDateTime.now(beijing);
        long ttl = reserveTtls.get(0).longValue();
        if (before.toLocalDate().equals(after.toLocalDate())) {
            long minExpected = Duration.between(after, after.toLocalDate().plusDays(1).atStartOfDay()).getSeconds();
            long maxExpected = Duration.between(before, before.toLocalDate().plusDays(1).atStartOfDay()).getSeconds();
            assertTrue(ttl >= minExpected && ttl <= maxExpected);
        } else {
            assertTrue(ttl > 0 && ttl <= TimeUnit.DAYS.toSeconds(1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftDoesNotCountIncompleteOutput() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        reserveResult = 1L;
        installReservationStub();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.generate(any(Long.class), eq("player"), eq("theme_draft"),
                anyString(), anyString())).thenReturn(new AiGatewayResult("trace-incomplete",
                "{\"plans\":[{\"title\":\"缺点位\",\"storyline\":\"还不完整\",\"nodes\":[]}]}",
                "qwen", 0));

        assertThrows(com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(new AiThemeDraftReq(), MEMBER_ID));
        assertEquals(1, reserved.size());
        assertEquals(1, refunded.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftFourthRequestIsRejectedBeforeCallingModel() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        reserveResult = -2L;
        installReservationStub();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.generate(any(Long.class), eq("player"), eq("theme_draft"),
                anyString(), anyString())).thenReturn(new AiGatewayResult("trace-fourth",
                "{\"plans\":[{\"title\":\"夜行\",\"storyline\":\"沿街探索\",\"nodes\":[{\"merchantName\":\"待选点位\"}]}]}",
                "qwen", 0));

        assertThrows(com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(new AiThemeDraftReq(), MEMBER_ID));

        verify(gateway, never()).generate(any(Long.class), anyString(), eq("theme_draft"),
                anyString(), anyString());
        assertEquals(0, refunded.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftReleasesReservationWhenModelFails() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        reserveResult = 1L;
        installReservationStub();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.generate(any(Long.class), eq("player"), eq("theme_draft"),
                anyString(), anyString())).thenThrow(new com.chengyinhub.business.service.ai.AiServiceException("模型繁忙"));

        assertThrows(com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(new AiThemeDraftReq(), MEMBER_ID));

        assertEquals(1, reserved.size());
        assertEquals(1, refunded.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftQuotaReturnsRemainingCount() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(2L);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.remainingQuota(MEMBER_ID, "theme_draft")).thenReturn(20);

        com.chengyinhub.business.domain.vo.ai.AiThemeDraftQuotaResp resp = service.themeDraftQuota(MEMBER_ID);

        assertTrue(resp.isLimited());
        assertEquals(Integer.valueOf(3), resp.getLimit());
        assertEquals(Integer.valueOf(1), resp.getRemaining());
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerThemeDraftQuotaReportsZeroWhenSharedGatewayPoolIsExhausted() {
        redisTemplate = mock(RedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(0L);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.remainingQuota(MEMBER_ID, "theme_draft")).thenReturn(0);

        com.chengyinhub.business.domain.vo.ai.AiThemeDraftQuotaResp resp = service.themeDraftQuota(MEMBER_ID);

        assertTrue(resp.isLimited());
        assertEquals(Integer.valueOf(3), resp.getLimit());
        assertEquals(Integer.valueOf(0), resp.getRemaining());
    }

    // ---- nodeGenerate ----

    @Test
    void nodeGenerateNormalFlow() {
        AiGatewayResult gwResult = new AiGatewayResult("trace-ng",
                new MockLlmClient().chat("[BIZ:node_generate]\n",
                        "{\"nodeName\":\"test\",\"address\":\"addr\"}"),
                "mock", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("node_generate"),
                anyString(), anyString())).thenReturn(gwResult);

        AiNodeGenReq req = new AiNodeGenReq();
        req.setNodeName("静安寺");
        req.setAddress("静安区南京西路");
        req.setThemeContext("夜间主题");

        AiNodeGenResp resp = service.nodeGenerate(req, MEMBER_ID);

        assertNotNull(resp);
        assertEquals("trace-ng", resp.getTraceId());
        assertNotNull(resp.getNode());
        assertNotNull(resp.getNode().getQuestionName());
        assertNotNull(resp.getNode().getStoryText());
    }

    // ---- chapterOverview ----

    @Test
    void chapterOverviewNormalFlow() {
        AiGatewayResult gwResult = new AiGatewayResult("trace-co",
                new MockLlmClient().chat("[BIZ:chapter_overview]\n",
                        "{\"chapterName\":\"第一章\"}"),
                "mock", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("chapter_overview"),
                anyString(), anyString())).thenReturn(gwResult);

        AiChapterOverviewReq req = new AiChapterOverviewReq();
        req.setChapterName("第一章");
        req.setIdea("埋一个悬念开场");
        req.setThemeContext("静安夜行");

        AiChapterOverviewResp resp = service.chapterOverview(req, MEMBER_ID);

        assertNotNull(resp);
        assertEquals("trace-co", resp.getTraceId());
        assertNotNull(resp.getOverview());
        assertTrue(resp.getOverview().contains("第一章"));
    }

    // ---- safetyPrecheck ----

    @Test
    void safetyPrecheckPassCase() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null);

        AiGatewayResult gwResult = new AiGatewayResult("trace-sp",
                "{\"pass\":true,\"issues\":[]}", "mock", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("safety_precheck"),
                anyString(), anyString())).thenReturn(gwResult);

        AiSafetyPrecheckReq req = new AiSafetyPrecheckReq();
        req.setTitle("城市夜游");
        req.setSubtitle("探索静安");
        req.setNodes(Collections.emptyList());

        AiSafetyPrecheckResp resp = service.safetyPrecheck(req, MEMBER_ID);

        assertNotNull(resp);
        assertTrue(resp.isPass());
    }

    @Test
    void safetyPrecheckContentSecurityBlock() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn("包含违规内容");

        AiGatewayResult gwResult = new AiGatewayResult("trace-sp2",
                "{\"pass\":true,\"issues\":[]}", "mock", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("safety_precheck"),
                anyString(), anyString())).thenReturn(gwResult);

        AiSafetyPrecheckReq req = new AiSafetyPrecheckReq();
        req.setTitle("敏感词");

        AiSafetyPrecheckResp resp = service.safetyPrecheck(req, MEMBER_ID);

        assertNotNull(resp);
        assertFalse(resp.isPass());
        assertFalse(resp.getIssues().isEmpty());
        assertEquals("content_security", resp.getIssues().get(0).getType());
    }

    // ---- merchantTaskSuggest ----

    @Test
    void merchantTaskSuggestNormalFlow() {
        AiGatewayResult gwResult = new AiGatewayResult("trace-mts",
                new MockLlmClient().chat("[BIZ:merchant_task_suggest]\n",
                        "{\"merchantContext\":\"咖啡店\",\"goal\":\"适合参与哪些主题\"}"),
                "mock", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("merchant_task_suggest"),
                anyString(), anyString())).thenReturn(gwResult);

        AiMerchantTaskSuggestReq req = new AiMerchantTaskSuggestReq();
        req.setMerchantContext("咖啡店在静安");
        req.setGoal("适合参与哪些主题");

        AiMerchantTaskSuggestResp resp = service.merchantTaskSuggest(req, MEMBER_ID);

        assertNotNull(resp);
        assertEquals("trace-mts", resp.getTraceId());
        assertNotNull(resp.getSuggestions());
        assertFalse(resp.getSuggestions().isEmpty());
        // 验证响应字段
        assertNotNull(resp.getSuggestions().get(0).getTitle());
        assertNotNull(resp.getSuggestions().get(0).getThemeFit());
        assertNotNull(resp.getSuggestions().get(0).getTaskRole());
    }

    // ---- clubDesign ----

    @Test
    void clubDesignNormalFlow() {
        AiGatewayResult gwResult = new AiGatewayResult("trace-cd",
                new MockLlmClient().chat("[BIZ:club_design]\n",
                        "{\"idea\":\"夜晚咖啡\",\"clubStyle\":\"文艺\"}"),
                "mock", 0);
        when(gateway.generate(any(Long.class), anyString(), eq("club_design"),
                anyString(), anyString())).thenReturn(gwResult);

        AiClubDesignReq req = new AiClubDesignReq();
        req.setIdea("夜晚咖啡与诗");
        req.setClubStyle("文艺慢生活");
        req.setTargetDurationMin(90);

        AiClubDesignResp resp = service.clubDesign(req, MEMBER_ID);

        assertNotNull(resp);
        assertEquals("trace-cd", resp.getTraceId());
        // 验证 plan
        assertNotNull(resp.getPlan());
        assertNotNull(resp.getPlan().getTitle());
        // 验证 merchantSuggestions
        assertNotNull(resp.getMerchantSuggestions());
        assertFalse(resp.getMerchantSuggestions().isEmpty());
        // 验证 promoCopy
        assertNotNull(resp.getPromoCopy());
        assertFalse(resp.getPromoCopy().isEmpty());
    }

    // ---- resolveRoleFor ----

    @Test
    void resolveRoleForReturnsRole() {
        String role = service.resolveRoleFor(MEMBER_ID);
        assertEquals("club", role);
    }

    @Test
    void resolveRoleForNullMemberReturnsPlayer() {
        when(umsMemberService.selectUmsMemberById(9999L)).thenReturn(null);
        String role = service.resolveRoleFor(9999L);
        assertEquals("player", role);
    }

    // ---- 加固1: markdown fence 剥离 ----

    @Test
    void safetyPrecheckParsesFencedResponse() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null);
        stubGateway("safety_precheck", "trace-f1",
                fence("json", "{\"pass\":true,\"issues\":[]}"));

        AiSafetyPrecheckReq req = new AiSafetyPrecheckReq();
        req.setTitle("城市夜游");
        req.setNodes(Collections.emptyList());

        AiSafetyPrecheckResp resp = service.safetyPrecheck(req, MEMBER_ID);

        assertTrue(resp.isPass(), "fence 包裹的响应应被正常解析为 pass=true");
        assertTrue(resp.getIssues().isEmpty());
    }

    @Test
    void clubDesignParsesFencedResponse() {
        String raw = new MockLlmClient().chat("[BIZ:club_design]\n",
                "{\"idea\":\"夜晚咖啡\",\"clubStyle\":\"文艺\"}");
        stubGateway("club_design", "trace-f2", fence("json", raw));

        AiClubDesignResp resp = service.clubDesign(sampleClubReq(), MEMBER_ID);

        assertNull(resp.getParseError(), "fence 剥离后应解析成功,不该置 parseError");
        assertNotNull(resp.getPlan());
        assertNotNull(resp.getPlan().getTitle());
        assertNotNull(resp.getPromoCopy());
    }

    @Test
    void merchantTaskSuggestParsesFencedResponse() {
        String raw = new MockLlmClient().chat("[BIZ:merchant_task_suggest]\n",
                "{\"merchantContext\":\"咖啡店\",\"goal\":\"适合参与哪些主题\"}");
        // 无语言标注的裸围栏 + 前后多余空白
        stubGateway("merchant_task_suggest", "trace-f3", "\n\n" + fence("", raw) + "\n");

        AiMerchantTaskSuggestResp resp = service.merchantTaskSuggest(sampleSuggestReq(), MEMBER_ID);

        assertNull(resp.getParseError(), "裸围栏 + 空白也应被剥离");
        assertNotNull(resp.getSuggestions());
        assertFalse(resp.getSuggestions().isEmpty());
    }

    // ---- 加固2: text 为 null 时不抛 NPE,走降级 ----

    @Test
    void safetyPrecheckNullTextDegradesWithoutNpe() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null);
        stubGateway("safety_precheck", "trace-n1", null);

        AiSafetyPrecheckReq req = new AiSafetyPrecheckReq();
        req.setTitle("城市夜游");

        AiSafetyPrecheckResp resp = service.safetyPrecheck(req, MEMBER_ID);

        assertFalse(resp.isPass(), "解析失败必须 pass=false,不能默认放行");
        assertEquals(1, resp.getIssues().size());
        assertEquals("parse_error", resp.getIssues().get(0).getType());
        assertEquals("error", resp.getIssues().get(0).getLevel());
    }

    @Test
    void clubDesignNullTextSetsParseError() {
        stubGateway("club_design", "trace-n2", null);

        AiClubDesignResp resp = service.clubDesign(sampleClubReq(), MEMBER_ID);

        assertEquals("trace-n2", resp.getTraceId());
        assertNotNull(resp.getParseError(), "text=null 必须置 parseError,不能静默空成功");
        assertNull(resp.getPlan());
    }

    @Test
    void merchantTaskSuggestNullTextSetsParseError() {
        stubGateway("merchant_task_suggest", "trace-n3", null);

        AiMerchantTaskSuggestResp resp = service.merchantTaskSuggest(sampleSuggestReq(), MEMBER_ID);

        assertNotNull(resp.getParseError(), "text=null 必须置 parseError,不能静默空成功");
        assertNull(resp.getSuggestions());
    }

    @Test
    void themeDraftAndNodeGenAndChapterOverviewNullTextDegradeWithoutNpe() {
        stubGateway("theme_draft", "trace-n5", null);
        stubGateway("node_generate", "trace-n6", null);
        stubGateway("chapter_overview", "trace-n7", null);

        AiThemeDraftReq td = new AiThemeDraftReq();
        td.setIdea("x");
        assertThrows(com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(td, MEMBER_ID));

        AiNodeGenReq ng = new AiNodeGenReq();
        ng.setNodeName("x");
        assertNull(service.nodeGenerate(ng, MEMBER_ID).getNode());

        AiChapterOverviewReq co = new AiChapterOverviewReq();
        co.setChapterName("x");
        assertNull(service.chapterOverview(co, MEMBER_ID).getOverview());
    }

    // ---- 加固3: 垃圾字符串走降级并置错误标记 ----

    @Test
    void safetyPrecheckGarbageTextDegrades() {
        when(wxContentSecurityService.checkText(anyString(), anyString())).thenReturn(null);
        stubGateway("safety_precheck", "trace-g1", "抱歉,我无法回答这个问题。");

        AiSafetyPrecheckReq req = new AiSafetyPrecheckReq();
        req.setTitle("城市夜游");

        AiSafetyPrecheckResp resp = service.safetyPrecheck(req, MEMBER_ID);

        assertFalse(resp.isPass());
        assertEquals("parse_error", resp.getIssues().get(0).getType());
    }

    @Test
    void clubDesignGarbageTextSetsParseError() {
        stubGateway("club_design", "trace-g2", "抱歉,我无法回答这个问题。");

        AiClubDesignResp resp = service.clubDesign(sampleClubReq(), MEMBER_ID);

        assertNotNull(resp.getParseError(), "垃圾串必须置 parseError");
        assertNull(resp.getPlan());
        assertNull(resp.getPromoCopy());
    }

    @Test
    void merchantTaskSuggestGarbageTextSetsParseError() {
        stubGateway("merchant_task_suggest", "trace-g3", "抱歉,我无法回答这个问题。");

        AiMerchantTaskSuggestResp resp = service.merchantTaskSuggest(sampleSuggestReq(), MEMBER_ID);

        assertNotNull(resp.getParseError(), "垃圾串必须置 parseError");
        assertNull(resp.getSuggestions());
    }

    @Test
    void merchantTaskSuggestValidJsonWithoutSuggestionsKeepsParseErrorNull() {
        // 区分「AI 没给建议」与「解析炸了」: 合法 JSON 但没 suggestions 字段 → parseError 必须为 null
        stubGateway("merchant_task_suggest", "trace-g4", "{\"suggestions\":[]}");

        AiMerchantTaskSuggestResp resp = service.merchantTaskSuggest(sampleSuggestReq(), MEMBER_ID);

        assertNull(resp.getParseError(), "合法 JSON 不该被标记为解析失败");
        assertNotNull(resp.getSuggestions());
        assertTrue(resp.getSuggestions().isEmpty());
    }

    // ---- helpers ----

    private void stubGateway(String bizType, String traceId, String text) {
        when(gateway.generate(any(Long.class), anyString(), eq(bizType), anyString(), anyString()))
                .thenReturn(new AiGatewayResult(traceId, text, "mock", 0));
    }

    /** 用 markdown 代码块围栏包住 body;lang 为空串则是裸围栏。 */
    private String fence(String lang, String body) {
        return "```" + lang + "\n" + body + "\n```";
    }

    private AiClubDesignReq sampleClubReq() {
        AiClubDesignReq req = new AiClubDesignReq();
        req.setIdea("夜晚咖啡与诗");
        req.setClubStyle("文艺慢生活");
        req.setTargetDurationMin(90);
        return req;
    }

    // ---- merchantInsight ----

    @Test
    void merchantInsightParsesAndSanitizesSuggestions() {
        // 5 条建议:1 条非法 type(self_event 是被裁掉的枚举)、4 条合法 ⇒ 丢非法 + 截断到 3
        String text = "{\"summary\":\"工作日下午客流最旺\"," +
                "\"audiences\":[{\"label\":\"25-34岁女性\",\"reason\":\"占比最高\"}]," +
                "\"suggestions\":[" +
                "{\"title\":\"A\",\"type\":\"topic_coop\",\"timeSlot\":\"周三下午\",\"audience\":\"白领\",\"reason\":\"r\"}," +
                "{\"title\":\"B\",\"type\":\"self_event\",\"reason\":\"非法枚举必须整条丢弃\"}," +
                "{\"title\":\"C\",\"type\":\"decor\",\"reason\":\"r\"}," +
                "{\"title\":\"D\",\"type\":\"content\",\"reason\":\"r\"}," +
                "{\"title\":\"E\",\"type\":\"topic_coop\",\"reason\":\"第4条合法但超3条上限\"}]}";
        when(gateway.generate(any(Long.class), anyString(), eq("merchant_insight"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-mi", text, "deepseek-chat", 0));

        com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp resp =
                service.merchantInsight("{\"checkin\":{}}", MEMBER_ID);

        assertEquals("trace-mi", resp.getTraceId());
        assertEquals("工作日下午客流最旺", resp.getSummary());
        assertEquals(1, resp.getAudiences().size());
        assertEquals(3, resp.getSuggestions().size());
        for (com.chengyinhub.business.domain.vo.ai.AiMerchantInsightResp.InsightSuggestion s : resp.getSuggestions()) {
            assertTrue(Arrays.asList("topic_coop", "decor", "content").contains(s.getType()),
                    "泄漏了非法 type: " + s.getType());
        }
    }

    @Test
    void merchantInsightParseFailureThrows() {
        when(gateway.generate(any(Long.class), anyString(), eq("merchant_insight"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-bad", "这不是JSON", "deepseek-chat", 0));

        com.chengyinhub.business.service.ai.AiServiceException e = assertThrows(
                com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.merchantInsight("{}", MEMBER_ID));
        assertTrue(e.getMessage().contains("格式异常"), "必须是解析失败文案,不是别的闸: " + e.getMessage());
    }

    @Test
    void merchantInsightRejectsMockModel() {
        when(gateway.generate(any(Long.class), anyString(), eq("merchant_insight"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-mock", "{\"summary\":\"假的\"}", "mock", 0));

        com.chengyinhub.business.service.ai.AiServiceException e = assertThrows(
                com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.merchantInsight("{}", MEMBER_ID));
        assertTrue(e.getMessage().contains("未就绪"), "必须是 mock 闸文案,不是解析失败: " + e.getMessage());
    }

    private AiMerchantTaskSuggestReq sampleSuggestReq() {
        AiMerchantTaskSuggestReq req = new AiMerchantTaskSuggestReq();
        req.setMerchantContext("咖啡店在静安");
        req.setGoal("适合参与哪些主题");
        return req;
    }

    private List<MerchantCandidateVO> sampleCandidates(int count) {
        List<MerchantCandidateVO> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MerchantCandidateVO c = new MerchantCandidateVO();
            c.setCandidateId((long) (100 + i));
            c.setMerchantId((long) (200 + i));
            c.setMerchantName("商家" + (i + 1));
            c.setAddress("地址" + (i + 1));
            c.setLongitude("121.47");
            c.setLatitude("31.23");
            c.setBusinessTime("09:00-22:00");
            c.setCouponName("优惠券" + (i + 1));
            c.setCouponStock(100L);
            c.setDistance(500.0 + i * 200);
            c.setSuitActivityTypes("citywalk,探店");
            list.add(c);
        }
        return list;
    }

    private String buildCandidatesJson(List<MerchantCandidateVO> candidates) {
        StringBuilder sb = new StringBuilder("{\"idea\":\"test\",\"lat\":31.23,\"lng\":121.47,\"durationMin\":90,\"needCoupon\":false,\"candidates\":[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(",");
            MerchantCandidateVO c = candidates.get(i);
            sb.append("{\"candidateId\":").append(c.getCandidateId());
            sb.append(",\"merchantId\":").append(c.getMerchantId());
            sb.append(",\"merchantName\":\"").append(c.getMerchantName()).append("\"");
            sb.append(",\"address\":\"").append(c.getAddress()).append("\"");
            sb.append(",\"longitude\":\"").append(c.getLongitude()).append("\"");
            sb.append(",\"latitude\":\"").append(c.getLatitude()).append("\"");
            sb.append(",\"businessTime\":\"").append(c.getBusinessTime()).append("\"");
            sb.append(",\"couponName\":\"").append(c.getCouponName()).append("\"");
            sb.append(",\"couponStock\":").append(c.getCouponStock());
            sb.append(",\"distance\":").append(c.getDistance());
            sb.append(",\"suitActivityTypes\":\"").append(c.getSuitActivityTypes()).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Test
    void playerExplicitInputBlockedKeepsReservation() {
        playerInputFailure(com.chengyinhub.business.service.ai.AiServiceException.Reason.INPUT_BLOCKED, 0);
    }

    @Test
    void playerReviewRequiredUnknownRetainsOldRefundPolicy() {
        playerInputFailure(com.chengyinhub.business.service.ai.AiServiceException.Reason.UNKNOWN, 1);
    }

    private void playerInputFailure(com.chengyinhub.business.service.ai.AiServiceException.Reason reason, int refunds) {
        redisTemplate = mock(RedisTemplate.class);
        installReservationStub();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");
        when(gateway.generate(any(Long.class), eq("player"), eq("theme_draft"), anyString(), anyString()))
                .thenThrow(new com.chengyinhub.business.service.ai.AiServiceException(reason, "input decision"));
        assertThrows(com.chengyinhub.business.service.ai.AiServiceException.class,
                () -> service.themeDraft(new AiThemeDraftReq(), MEMBER_ID));
        assertEquals(1, reserved.size());
        assertEquals(refunds, refunded.size());
        if (refunds == 1) assertEquals(reserved, refunded);
    }
}
