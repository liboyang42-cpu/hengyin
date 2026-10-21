package com.chengyinhub.business.service.merchantauthoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapper;
import com.chengyinhub.business.mapper.merchantauthoring.MerchantAuthoringMapperTest;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.impl.MerchantNpcFactsBuilder;
import com.chengyinhub.business.service.merchantauthoring.EnvironmentObservationSource.Observation;
import com.chengyinhub.business.service.merchantauthoring.MerchantAuthoringService.Request;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.api.ApiMerchantAuthoringController;
import com.fasterxml.jackson.databind.ObjectMapper;

class MerchantAuthoringFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MERCHANT_USER = 10L;
    private static final long THEME_OWNER = 11L;
    private static final long MERCHANT = 20L;
    private static final long THEME = 7L;
    private static final String MODEL = "{\"canonical_plot\":\"店里找一只猫\",\"allowed_variables\":[\"猫\"],"
            + "\"approved_branches\":[\"AFTERNOON\"],\"server_action_rules\":{\"fail_exit\":\"回柜台\",\"offline_alternative\":\"纸质线索\"},"
            + "\"duration_minutes\":20,\"headcount\":4,\"business_segment\":\"AFTERNOON\",\"staff_need\":\"一名店员\","
            + "\"noise_limit\":\"低声\",\"photo_limit\":\"不拍客人\",\"accessibility_alternative\":\"坐着完成\","
            + "\"offline_alternative\":\"纸质线索\",\"fail_exit\":\"回柜台\",\"reward_rule_ref\":\"rule-existing\"}";

    @Test
    void controllerReachesMapperAndReplayDoesNotCallGatewayAgain() throws Exception {
        Harness harness = harness(policy(), null);
        when(harness.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-1", MODEL, "fixture-model", 0));
        ApiMerchantAuthoringController controller = org.mockito.Mockito.spy(new ApiMerchantAuthoringController());
        org.mockito.Mockito.doReturn(MERCHANT_USER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "authoring", harness.service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        String created = mvc.perform(post("/api/merchant/authoring/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("job-1", "fact-1")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.costRef").value("trace-1"))
                .andReturn().getResponse().getContentAsString();
        String draftId = JSON.readTree(created).path("data").path("draftId").asText();
        mvc.perform(post("/api/merchant/authoring/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("job-1", "fact-1")))
                .andExpect(jsonPath("$.data.outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.draftId").value(draftId));
        verify(harness.gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());

        mvc.perform(post("/api/merchant/authoring/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("CLIENT_CONCLUSION"));
        verify(harness.gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> harness.service.generate(MERCHANT_USER, request("job-1", "fact-2")));
        assertEquals("CONFLICT", conflict.getMessage());

        mvc.perform(post("/api/merchant/authoring/drafts/" + draftId + "/facts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(jsonPath("$.data.kind").value("MERCHANT_FACT"))
                .andExpect(jsonPath("$.data.actorId").value((int) MERCHANT_USER));
        org.mockito.Mockito.doReturn(THEME_OWNER).when(controller).getAppUserId();
        mvc.perform(post("/api/merchant/authoring/drafts/" + draftId + "/content").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(jsonPath("$.data.kind").value("THEME_CONTENT"))
                .andExpect(jsonPath("$.data.actorId").value((int) THEME_OWNER));
        org.mockito.Mockito.doReturn(MERCHANT_USER).when(controller).getAppUserId();
        String published = mvc.perform(post("/api/merchant/authoring/releases").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"pub-1\",\"draftId\":\"" + draftId + "\",\"version\":1}"))
                .andExpect(jsonPath("$.data.outcome").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString();
        long releaseId = JSON.readTree(published).path("data").path("releaseId").asLong();
        mvc.perform(get("/api/merchant/authoring/public").param("merchantId", "20").param("themeId", "7")
                        .param("releaseId", Long.toString(releaseId)).param("binding", "THEME"))
                .andExpect(jsonPath("$.data.outcome").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.revisionVersion").value(1))
                .andExpect(jsonPath("$.data.facts.canonical_plot").value("店里找一只猫"))
                .andExpect(jsonPath("$.data.facts.reward_rule_ref").doesNotExist())
                .andExpect(jsonPath("$.data.facts.order").doesNotExist())
                .andExpect(jsonPath("$.data.facts.customer").doesNotExist())
                .andExpect(jsonPath("$.data.facts.cost").doesNotExist())
                .andExpect(jsonPath("$.data.facts.price").doesNotExist())
                .andExpect(jsonPath("$.data.facts.stock").doesNotExist());
        mvc.perform(get("/api/merchant/authoring/public").param("merchantId", "20").param("themeId", "8")
                        .param("releaseId", Long.toString(releaseId)).param("binding", "THEME"))
                .andExpect(jsonPath("$.data.outcome").value("NONE"));
        mvc.perform(get("/api/merchant/authoring/public").param("merchantId", "20").param("themeId", "7")
                        .param("releaseId", Long.toString(releaseId)).param("runId", "other-run").param("binding", "THEME"))
                .andExpect(jsonPath("$.data.outcome").value("NONE"));
        assertNull(harness.service.publicPrompt(MERCHANT, 8L, releaseId, null, "THEME"));
        assertTrue(harness.service.publicPrompt(MERCHANT, THEME, releaseId, null, "THEME").contains("店里找一只猫"));
        assertFalse(harness.service.publicPrompt(MERCHANT, THEME, releaseId, null, "THEME").contains("订单"));
    }

    @Test
    void lostResponseAndLocalFailureStayUnknownWithoutASecondCharge() throws Exception {
        Harness lost = harness(policy(), null);
        when(lost.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new AiProviderOutcomeUnknownException("lost", new RuntimeException("timeout")));
        Map<String, Object> first = lost.service.generate(MERCHANT_USER, request("lost-1", "fact-1"));
        Map<String, Object> again = lost.service.generate(MERCHANT_USER, request("lost-1", "fact-1"));
        assertEquals("UNKNOWN", first.get("outcome"));
        assertEquals("UNKNOWN", again.get("outcome"));
        assertEquals(first.get("jobId"), again.get("jobId"));
        verify(lost.gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());

        Harness local = harness(policy(), null);
        AuthoringTx failing = new AuthoringTx() {
            int calls;
            public <T> T call(Function<MerchantAuthoringMapper, T> work) {
                if (++calls == 2) throw new IllegalStateException("disk");
                return local.tx.call(work);
            }
        };
        MerchantAuthoringService service = local.serviceWith(failing);
        when(local.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-local", MODEL, "fixture-model", 0));
        assertEquals("UNKNOWN", service.generate(MERCHANT_USER, request("local-1", "fact-1")).get("outcome"));
        assertEquals("UNKNOWN", service.generate(MERCHANT_USER, request("local-1", "fact-1")).get("outcome"));
        verify(local.gateway, times(1)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void concurrentGenerationUsesOneGatewayCall() throws Exception {
        Harness harness = harness(policy(), null);
        AtomicInteger calls = new AtomicInteger();
        when(harness.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(150);
            return new AiGatewayResult("trace-race", MODEL, "fixture-model", 0);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Map<String, Object>> left = pool.submit(() -> { start.await(); return harness.service.generate(MERCHANT_USER, request("race", "fact-1")); });
            Future<Map<String, Object>> right = pool.submit(() -> { start.await(); return harness.service.generate(MERCHANT_USER, request("race", "fact-1")); });
            start.countDown();
            left.get();
            right.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", harness.service.findJob(MERCHANT_USER, "race").get("outcome"));
    }

    @Test
    void wrongIdentityRevisionAndRevocationDoNotPublish() throws Exception {
        Harness harness = harness(policy(), null);
        when(harness.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-id", MODEL, "fixture-model", 0));
        String draftId = (String) harness.service.generate(MERCHANT_USER, request("id-1", "fact-1")).get("draftId");
        assertThrows(ServiceException.class, () -> harness.service.confirmFacts(THEME_OWNER, draftId, 1));
        verify(harness.audit, never()).submit(any(), any(), eq(THEME_OWNER), any(), any());
        assertThrows(ServiceException.class, () -> harness.service.approveContent(MERCHANT_USER, draftId, 1));
        harness.service.confirmFacts(MERCHANT_USER, draftId, 1);
        assertThrows(ServiceException.class, () -> harness.service.publish(MERCHANT_USER, "missing-content", draftId, 1));
        assertNull(harness.tx.call(mapper -> mapper.findRelease(MERCHANT_USER, "missing-content")));
        harness.service.approveContent(THEME_OWNER, draftId, 1);
        when(harness.access.resolve(MERCHANT_USER)).thenReturn(null);
        ServiceException revoked = assertThrows(ServiceException.class,
                () -> harness.service.publish(MERCHANT_USER, "revoked", draftId, 1));
        assertEquals("STAFF_REVOKED", revoked.getMessage());
        when(harness.access.resolve(MERCHANT_USER)).thenReturn(
                new MerchantAccessContext(MERCHANT_USER, MERCHANT, MERCHANT_USER, MerchantRoleCode.MERCHANT_OWNER));

        String original = harness.tx.call(mapper -> mapper.findRevision(draftId, 1).bodyJson);
        harness.service.save(MERCHANT_USER, draftId, 1, JSON.readTree(MODEL.replace("店里找一只猫", "改过的剧情")));
        ServiceException clash = assertThrows(ServiceException.class, () -> harness.service.save(MERCHANT_USER, draftId, 1,
                JSON.readTree(MODEL.replace("店里找一只猫", "再改一次"))));
        assertEquals("CONFLICT", clash.getMessage());
        assertEquals(original, harness.tx.call(mapper -> mapper.findRevision(draftId, 1).bodyJson));
        assertEquals("INVALIDATED", harness.tx.call(mapper -> mapper.findApproval(draftId, 1, "MERCHANT_FACT").status));
        assertThrows(ServiceException.class, () -> harness.service.publish(MERCHANT_USER, "stale", draftId, 2));
        assertNull(harness.tx.call(mapper -> mapper.findRelease(MERCHANT_USER, "stale")));
    }

    @Test
    void environmentUsesStoreFactsAndDoesNotInventWeather() throws Exception {
        Harness harness = harness(policy(), null);
        harness.service.clock = clock(9, 30);
        when(harness.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-env", MODEL, "fixture-model", 0));
        String draftId = (String) harness.service.generate(MERCHANT_USER, request("env-1", "fact-1")).get("draftId");
        harness.service.confirmFacts(MERCHANT_USER, draftId, 1);
        harness.service.approveContent(THEME_OWNER, draftId, 1);
        long releaseId = ((Number) harness.service.publish(MERCHANT_USER, "env-pub", draftId, 1).get("releaseId")).longValue();
        Map<String, Object> morning = harness.service.environment(MERCHANT_USER, releaseId);
        assertEquals("MORNING", morning.get("recordedBranch"));
        assertNull(morning.get("weather"));
        harness.service.clock = clock(15, 0);
        Map<String, Object> afternoon = harness.service.environment(MERCHANT_USER, releaseId);
        assertEquals("MORNING", afternoon.get("recordedBranch"));
        assertEquals("AFTERNOON", afternoon.get("branch"));
        assertEquals("EXIT", afternoon.get("action"));
        assertNull(afternoon.get("weather"));

        harness.merchant.setBusinessStatus(0);
        harness.merchant.setBusinessTime(null);
        Map<String, Object> closed = harness.service.environment(MERCHANT_USER, releaseId);
        assertEquals("CLOSED", closed.get("branch"));
        assertEquals("EXIT", closed.get("action"));
        assertNull(closed.get("storeHours"));
        assertNull(closed.get("weather"));

        Harness dangerous = harness(new MerchantAuthoringPolicy("fixture-model", "prompt-1", 1,
                Set.of("in-store"), Set.of("in-store"), true, true, "PUBLIC", "Asia/Shanghai",
                Set.of("MORNING", "AFTERNOON", "EVENING"), "station", null), new EnvironmentObservationSource() {
                    public String providerId() { return "station"; }
                    public Observation current(Long merchantId) {
                        return new Observation("station-feed", 1L, clock(15, 0).millis() + 60_000L,
                                "Asia/Shanghai", "OBSERVED", "DANGEROUS");
                    }
                });
        dangerous.service.clock = clock(15, 0);
        when(dangerous.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-weather", MODEL, "fixture-model", 0));
        String weatherDraft = (String) dangerous.service.generate(MERCHANT_USER, request("weather", "fact-1")).get("draftId");
        dangerous.service.confirmFacts(MERCHANT_USER, weatherDraft, 1);
        dangerous.service.approveContent(THEME_OWNER, weatherDraft, 1);
        long weatherRelease = ((Number) dangerous.service.publish(MERCHANT_USER, "weather-pub", weatherDraft, 1).get("releaseId")).longValue();
        Map<String, Object> storm = dangerous.service.environment(MERCHANT_USER, weatherRelease);
        assertEquals("DANGEROUS", storm.get("weather"));
        assertEquals("EXIT", storm.get("action"));
        assertEquals("station-feed", storm.get("source"));
        assertFalse(String.valueOf(storm.get("weather")).toLowerCase().contains("sun"));
    }

    @Test
    void failedGenerationDoesNotRemoveThePublishedMainLine() throws Exception {
        Harness harness = harness(policy(), null);
        when(harness.gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-main", MODEL, "fixture-model", 0))
                .thenThrow(new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, "down"));
        String draftId = (String) harness.service.generate(MERCHANT_USER, request("main-1", "fact-1")).get("draftId");
        harness.service.confirmFacts(MERCHANT_USER, draftId, 1);
        harness.service.approveContent(THEME_OWNER, draftId, 1);
        long releaseId = ((Number) harness.service.publish(MERCHANT_USER, "main-pub", draftId, 1).get("releaseId")).longValue();
        assertEquals("FAILED", harness.service.generate(MERCHANT_USER, request("main-2", "fact-1")).get("outcome"));
        assertEquals(releaseId, ((Number) harness.service.mainLine(MERCHANT, THEME).get("releaseId")).longValue());
        Map<String, Object> cancelled = harness.service.cancel(MERCHANT_USER, "main-2");
        assertEquals("NONE", cancelled.get("publishOutcome"));
        assertEquals("FAILED", cancelled.get("outcome"));
    }

    @Test
    void claimedReleaseIsNotListSuccessAndUnsetConfigRefuses() throws Exception {
        Harness harness = harness(policy(), null);
        Request draft = request("draft-only", "fact-1");
        draft.requestId = null;
        String draftId = (String) harness.service.createDraft(MERCHANT_USER, draft).get("draftId");
        var release = new com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Release();
        release.ownerId = MERCHANT_USER;
        release.requestId = "claimed-publish";
        release.payloadFingerprint = "b".repeat(64);
        release.draftId = draftId;
        release.revisionId = 1L;
        release.revisionVersion = 0;
        release.merchantId = MERCHANT;
        release.bindingKey = "STORE";
        release.snapshotJson = "{}";
        release.snapshotHash = "b".repeat(64);
        release.factHash = "b".repeat(64);
        release.contentHash = "b".repeat(64);
        release.status = "CLAIMED";
        harness.tx.call(mapper -> mapper.insertRelease(release));
        Map<String, Object> listed = harness.service.listDrafts(MERCHANT_USER).get(0);
        assertEquals("UNKNOWN", listed.get("publishOutcome"));
        assertEquals(false, listed.get("published"));

        MerchantAuthoringService bare = harness.serviceWithPolicy(null);
        ServiceException model = assertThrows(ServiceException.class, () -> bare.generate(MERCHANT_USER, request("cfg", "fact-1")));
        assertEquals("CONFIG_MODEL", model.getMessage());
        verify(harness.gateway, never()).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        MerchantAuthoringService noCost = harness.serviceWithPolicy(new MerchantAuthoringPolicy("fixture-model", "prompt-1",
                null, Set.of("in-store"), Set.of("in-store"), true, true, "PUBLIC", "Asia/Shanghai",
                Set.of("AFTERNOON"), null, null));
        assertEquals("CONFIG_COST", assertThrows(ServiceException.class,
                () -> noCost.generate(MERCHANT_USER, request("cfg-cost", "fact-1"))).getMessage());
        Request foreignTemplate = request("cfg-template", "fact-1");
        foreignTemplate.templateId = "not-approved";
        assertEquals("CONFIG_TEMPLATE", assertThrows(ServiceException.class,
                () -> harness.service.generate(MERCHANT_USER, foreignTemplate)).getMessage());
    }

    @Test
    void storeNpcPromptUsesOnlyTheStoreRelease() throws Exception {
        Harness harness = harness(policy(), null);
        Request store = request("store-1", "fact-1");
        store.themeId = null;
        store.templateId = null;
        store.scene = null;
        store.productId = 5L;
        String draftId = (String) harness.service.createDraft(MERCHANT_USER, store).get("draftId");
        harness.service.save(MERCHANT_USER, draftId, 0, JSON.readTree(
                "{\"noise_limit\":\"低声\",\"photo_limit\":\"不拍客人\",\"accessibility_alternative\":\"坐着完成\",\"business_segment\":\"AFTERNOON\"}"));
        harness.service.confirmFacts(MERCHANT_USER, draftId, 1);
        long releaseId = ((Number) harness.service.publish(MERCHANT_USER, "store-pub", draftId, 1).get("releaseId")).longValue();
        assertEquals("PUBLISHED", harness.service.readPublic(MERCHANT, null, releaseId, null, "STORE").get("outcome"));
        assertEquals("NONE", harness.service.readPublic(MERCHANT, THEME, releaseId, null, "THEME").get("outcome"));
        MerchantNpcFactsBuilder builder = new MerchantNpcFactsBuilder();
        ReflectionTestUtils.setField(builder, "mmsMerchantService", harness.merchants);
        ReflectionTestUtils.setField(builder, "publicReleaseFacts", harness.service);
        NpcProfile profile = new NpcProfile();
        profile.setKnowledge("本店有猫");
        String prompt = builder.build(profile, MERCHANT);
        assertTrue(prompt.contains("已发布公开事实"));
        assertFalse(prompt.contains("订单"));
        assertFalse(prompt.contains("客户"));
        assertFalse(prompt.contains("成本"));
        assertEquals("NONE", harness.service.mainLine(MERCHANT, THEME).get("outcome"));
    }

    private static Clock clock(int hour, int minute) {
        return Clock.fixed(ZonedDateTime.of(2026, 10, 21, hour, minute, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneOffset.UTC);
    }

    private static MerchantAuthoringPolicy policy() {
        return new MerchantAuthoringPolicy("fixture-model", "prompt-1", 1, Set.of("in-store"), Set.of("in-store"),
                true, true, "PUBLIC", "Asia/Shanghai", Set.of("MORNING", "AFTERNOON", "EVENING"), null, null);
    }

    private static Request request(String requestId, String factVersion) {
        Request request = new Request();
        request.requestId = requestId;
        request.factVersion = factVersion;
        request.templateId = "in-store";
        request.productId = 5L;
        request.themeId = THEME;
        request.scene = "IN_STORE_GAME";
        return request;
    }

    private static String requestJson(String requestId, String factVersion) {
        return "{\"requestId\":\"" + requestId + "\",\"factVersion\":\"" + factVersion
                + "\",\"templateId\":\"in-store\",\"productId\":5,\"themeId\":7,\"scene\":\"IN_STORE_GAME\"}";
    }

    private static Harness harness(MerchantAuthoringPolicy policy, EnvironmentObservationSource observations) throws Exception {
        SqlSessionFactory factory = MerchantAuthoringMapperTest.factory();
        AuthoringTx tx = new AuthoringTx() {
            public <T> T call(Function<MerchantAuthoringMapper, T> work) {
                try (SqlSession session = factory.openSession(false)) {
                    try {
                        T value = work.apply(session.getMapper(MerchantAuthoringMapper.class));
                        session.commit();
                        return value;
                    } catch (RuntimeException ex) {
                        session.rollback();
                        throw ex;
                    }
                }
            }
        };
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext context = new MerchantAccessContext(MERCHANT_USER, MERCHANT, MERCHANT_USER, MerchantRoleCode.MERCHANT_OWNER);
        when(access.require(eq(MERCHANT_USER), eq(MerchantPermission.PROJECT_MANAGE))).thenReturn(context);
        when(access.require(eq(THEME_OWNER), anyString())).thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        when(access.resolve(MERCHANT_USER)).thenReturn(context);
        IAuditTaskService audit = mock(IAuditTaskService.class);
        Map<Long, AuditTask> tasks = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        when(audit.submit(anyString(), any(), any(), anyString(), anyString())).thenAnswer(inv -> {
            long id = ids.incrementAndGet();
            AuditTask task = new AuditTask();
            task.setId(id);
            task.setBizType(inv.getArgument(0));
            task.setSubmitterId(inv.getArgument(2));
            task.setSnapshotJson(inv.getArgument(4));
            task.setStatus(AuditTask.STATUS_PENDING);
            tasks.put(id, task);
            return id;
        });
        when(audit.review(anyLong(), anyLong(), any())).thenAnswer(inv -> {
            AuditTask task = tasks.get(inv.getArgument(0));
            if (task == null || !task.getSubmitterId().equals(inv.getArgument(1))) return 0;
            task.setStatus(AuditTask.STATUS_APPROVED);
            return 1;
        });
        when(audit.selectAuditTaskById(anyLong())).thenAnswer(inv -> tasks.get(inv.getArgument(0)));
        IPmsProductService products = mock(IPmsProductService.class);
        PmsProduct product = new PmsProduct();
        product.setId(5L);
        product.setMerchantId(MERCHANT);
        product.setDelFlag(0);
        when(products.selectPmsProductById(5L)).thenReturn(product);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsTopic topic = new CmsTopic();
        topic.setId(THEME);
        topic.setMemberId(THEME_OWNER);
        when(topics.selectCmsTopicById(THEME)).thenReturn(topic);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setName("猫店");
        merchant.setBusinessStatus(1);
        merchant.setBusinessTime("09:00-21:00");
        when(merchants.selectMmsMerchantById(MERCHANT)).thenReturn(merchant);
        when(merchants.selectMmsMerchantByMemberId(30L)).thenReturn(merchant);
        AiGatewayService gateway = mock(AiGatewayService.class);
        MerchantAuthoringService service = new MerchantAuthoringService(tx, gateway, audit, products, mock(IPmsSkuService.class),
                access, topics, merchants, policy, observations);
        service.clock = clock(15, 0);
        Harness harness = new Harness();
        harness.tx = tx;
        harness.service = service;
        harness.gateway = gateway;
        harness.audit = audit;
        harness.access = access;
        harness.merchants = merchants;
        harness.merchant = merchant;
        harness.policy = policy;
        harness.observations = observations;
        harness.products = products;
        harness.topics = topics;
        return harness;
    }

    private static final class Harness {
        AuthoringTx tx;
        MerchantAuthoringService service;
        AiGatewayService gateway;
        IAuditTaskService audit;
        IMerchantAccessService access;
        IMmsMerchantService merchants;
        IPmsProductService products;
        ICmsTopicService topics;
        MmsMerchant merchant;
        MerchantAuthoringPolicy policy;
        EnvironmentObservationSource observations;

        MerchantAuthoringService serviceWith(AuthoringTx replacement) {
            MerchantAuthoringService created = new MerchantAuthoringService(replacement, gateway, audit, products,
                    mock(IPmsSkuService.class), access, topics, merchants, policy, observations);
            created.clock = service.clock;
            return created;
        }

        MerchantAuthoringService serviceWithPolicy(MerchantAuthoringPolicy replacement) {
            MerchantAuthoringService created = new MerchantAuthoringService(tx, gateway, audit, products,
                    mock(IPmsSkuService.class), access, topics, merchants, replacement, observations);
            created.clock = service.clock;
            return created;
        }
    }
}
