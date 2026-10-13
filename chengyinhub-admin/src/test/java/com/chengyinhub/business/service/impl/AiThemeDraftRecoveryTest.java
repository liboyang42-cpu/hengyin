package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.AiThemeDraftRequest;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.mapper.AiThemeDraftRequestMapper;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiThemeDraftRecoveryTest {
    private final Map<String, AiThemeDraftRequest> records = new ConcurrentHashMap<>();
    private AiThemeDraftRequestMapper mapper;
    private AiGatewayService gateway;
    private AiCreatorServiceImpl service;

    @BeforeEach
    void setup() {
        mapper = mock(AiThemeDraftRequestMapper.class);
        gateway = mock(AiGatewayService.class);
        IRoleService roles = mock(IRoleService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberById(anyLong())).thenReturn(new UmsMember());
        when(roles.resolveRole(any())).thenReturn("club");
        service = new AiCreatorServiceImpl();
        ReflectionTestUtils.setField(service, "draftRequestMapper", mapper);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        ReflectionTestUtils.setField(service, "roleService", roles);
        ReflectionTestUtils.setField(service, "umsMemberService", members);
        when(mapper.selectByUserAndRequestId(anyLong(), anyString())).thenAnswer(c -> records.get(c.getArgument(0) + ":" + c.getArgument(1)));
        when(mapper.insertRequest(any())).thenAnswer(c -> {
            AiThemeDraftRequest record = copy(c.getArgument(0));
            if (records.putIfAbsent(key(record), record) != null) throw new DuplicateKeyException("duplicate");
            return 1;
        });
        when(mapper.finishRequest(any())).thenAnswer(c -> {
            AiThemeDraftRequest record = copy(c.getArgument(0));
            records.put(key(record), record);
            return 1;
        });
        when(gateway.generate(anyLong(), anyString(), eq("theme_draft"), anyString(), anyString())).thenReturn(result());
    }

    @Test
    void lostResponseReplaysOriginalDraftAndRejectsChangedContent() {
        AiThemeDraftResp first = service.themeDraft(request("same", "原想法"), 1L);
        AiThemeDraftResp replay = service.themeDraft(request("same", "原想法"), 1L);
        assertEquals(first.getTraceId(), replay.getTraceId());
        assertEquals(first.getDraft().getTitle(), replay.getDraft().getTitle());
        assertEquals("REQUEST_CONFLICT", service.themeDraft(request("same", "不同想法"), 1L).getErrorCode());
        verify(gateway).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        assertTrue(records.get("1:same").getRequestHash().matches("[a-f0-9]{64}"));
        assertFalse(records.get("1:same").getResultJson().contains("原想法"));
    }

    @Test
    void currentMemberCannotReadAnotherMembersDraftAndSameIdRemainsIndependent() {
        service.themeDraft(request("same", "A"), 1L);
        assertNull(service.themeDraftStatus(2L, "same").getDraft());
        assertEquals("REQUEST_NOT_FOUND", service.themeDraftStatus(2L, "same").getErrorCode());
        assertEquals("SUCCEEDED", service.themeDraft(request("same", "B"), 2L).getOutcomeStatus());
        verify(gateway, times(2)).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedGenerationReplaysFailureInsteadOfConsumingAgain() {
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString())).thenThrow(new AiServiceException("暂不可用"));
        assertEquals("FAILED", service.themeDraft(request("failed", "A"), 1L).getOutcomeStatus());
        assertEquals("FAILED", service.themeDraft(request("failed", "A"), 1L).getOutcomeStatus());
        verify(gateway).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void lostResultPersistenceStaysUnknownAcrossNewServiceInstance() {
        doThrow(new IllegalStateException("database unavailable")).when(mapper).finishRequest(any());
        assertEquals("PROCESSING", service.themeDraft(request("lost", "A"), 1L).getOutcomeStatus());
        AiCreatorServiceImpl restarted = new AiCreatorServiceImpl();
        ReflectionTestUtils.setField(restarted, "draftRequestMapper", mapper);
        assertEquals("PROCESSING", restarted.themeDraftStatus(1L, "lost").getOutcomeStatus());
        assertEquals("PROCESSING", service.themeDraft(request("lost", "A"), 1L).getOutcomeStatus());
        verify(gateway).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void concurrentSameRequestOnlyOneModelCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(gateway.generate(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(c -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return result();
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AiThemeDraftResp> first = executor.submit(() -> service.themeDraft(request("concurrent", "A"), 1L));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals("PROCESSING", service.themeDraft(request("concurrent", "A"), 1L).getOutcomeStatus());
            release.countDown();
            assertEquals("SUCCEEDED", first.get(3, TimeUnit.SECONDS).getOutcomeStatus());
            verify(gateway).generate(anyLong(), anyString(), anyString(), anyString(), anyString());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private static AiThemeDraftReq request(String id, String idea) {
        AiThemeDraftReq req = new AiThemeDraftReq(); req.setRequestId(id); req.setIdea(idea); req.setProductType(1); return req;
    }
    private static AiGatewayResult result() {
        return new AiGatewayResult("original-trace", "{\"plans\":[{\"title\":\"路线\",\"storyline\":\"沿街探索\",\"nodes\":[{\"merchantName\":\"待核实地点\"}]}]}", "qwen", 0);
    }
    private static String key(AiThemeDraftRequest r) { return r.getUserId() + ":" + r.getRequestId(); }
    private static AiThemeDraftRequest copy(AiThemeDraftRequest r) { return JSON.parseObject(JSON.toJSONString(r), AiThemeDraftRequest.class); }
}
