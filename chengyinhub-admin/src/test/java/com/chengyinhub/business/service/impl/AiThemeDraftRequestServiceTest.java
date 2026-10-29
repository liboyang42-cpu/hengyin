package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.*;
import com.chengyinhub.business.service.IAiCreatorService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy is read-only. Retired execution assertions are mapped to the v2 suite in CASE-MAPPING.md. */
class AiThemeDraftRequestServiceTest {
    private static final long MEMBER = 501L;
    private static final String RID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private AiThemeDraftRequestService service;
    private IAiCreatorService creator;
    private RedisTemplate<Object,Object> redis;
    private ValueOperations<Object,Object> values;
    private final Map<Object,Object> store = new HashMap<>();

    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        service=new AiThemeDraftRequestService(); creator=mock(IAiCreatorService.class);
        redis=mock(RedisTemplate.class); values=mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(c -> store.get(c.getArgument(0)));
        ReflectionTestUtils.setField(service,"aiCreatorService",creator);
        ReflectionTestUtils.setField(service,"redisTemplate",redis);
    }
    private void seed(String state, Object result, String message, long started) {
        JSONObject value=new JSONObject(); value.put("state",state); value.put("result",result);
        value.put("message",message); value.put("startedAt",started);
        store.put(AiThemeDraftRequestService.key(MEMBER,RID),value.toJSONString());
    }
    @Test void legacySubmitRefusesOldOrMissingIdWithoutDependencyAccess() {
        clearInvocations(redis,values);
        for(String id:new String[]{RID,null}) {
            AiThemeDraftReq req=new AiThemeDraftReq(); req.setRequestId(id);
            var e=assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.submit(req,MEMBER));
            assertEquals(Integer.valueOf(400),e.getCode()); assertEquals("AI_DRAFT_PREPARE_REQUIRED",e.getErrorCode());
        }
        assertThrows(AiThemeDraftRequestService.IntentRejectedException.class,()->service.submit(null,MEMBER));
        verifyNoInteractions(redis,values,creator);
    }
    @Test void legacyKnownSuccessIsReadWithoutGenerating() {
        AiThemePlan plan=new AiThemePlan(); plan.setTitle("原草稿");
        AiThemeDraftResp result=new AiThemeDraftResp(); result.setDraft(plan);
        seed("SUCCEEDED",result,null,0);
        assertEquals("原草稿",service.read(MEMBER,RID).getResult().getDraft().getTitle());
        assertEquals("原草稿",service.read(MEMBER,RID).getResult().getDraft().getTitle()); verifyNoInteractions(creator);
        seed("SUCCEEDED","corrupt result body",null,0);
        assertEquals("UNKNOWN",service.read(MEMBER,RID).getState());verifyNoInteractions(creator);
    }
    @Test void legacyOwnerIsolationAndMissingRecordsAreUnknown() {
        seed("FAILED",null,"原失败",0);
        var other=service.read(MEMBER+1,RID);
        assertEquals("UNKNOWN",other.getState()); assertEquals("LEGACY_UNBOUND",other.getMessage());
        assertNull(other.getResult()); verifyNoInteractions(creator);
    }
    @Test void legacyKnownFailureReadPreservesOriginalReason() {
        seed("FAILED",null,"原失败",0);
        assertEquals("FAILED",service.read(MEMBER,RID).getState());
        assertEquals("原失败",service.read(MEMBER,RID).getMessage()); verifyNoInteractions(creator);
    }
    @Test void legacyProcessingExpiresToUnknownNeverResent() {
        seed("PROCESSING",null,null,System.currentTimeMillis());
        assertEquals("PROCESSING",service.read(MEMBER,RID).getState());
        seed("PROCESSING",null,null,System.currentTimeMillis()-AiThemeDraftRequestService.LEASE_MILLIS-1000);
        assertEquals("UNKNOWN",service.read(MEMBER,RID).getState());
        verifyNoInteractions(creator);
    }
    @Test void legacyRedisFailureIsNotMissingState() {
        when(values.get(any())).thenThrow(new IllegalStateException("controlled outage"));
        assertThrows(AiThemeDraftRequestService.StateUnavailableException.class,()->service.read(MEMBER,RID));
        verifyNoInteractions(creator);
    }
    @Test void requestIdFormatIsStrict() {
        assertTrue(AiThemeDraftRequestService.isValidRequestId(RID));
        assertFalse(AiThemeDraftRequestService.isValidRequestId("abc"));
        assertFalse(AiThemeDraftRequestService.isValidRequestId(null));
        assertFalse(AiThemeDraftRequestService.isValidRequestId("td2_"+"a".repeat(32)));
    }
}
