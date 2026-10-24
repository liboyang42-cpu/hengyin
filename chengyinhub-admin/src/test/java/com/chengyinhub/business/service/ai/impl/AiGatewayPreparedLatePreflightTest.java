package com.chengyinhub.business.service.ai.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.impl.AiCreatorServiceImpl;
import com.chengyinhub.common.config.AiModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Creator/Gateway orchestration with existing network-free fixture; no E/DB/provider proof. */
class AiGatewayPreparedLatePreflightTest {
    private static final long MEMBER=7L;
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    private static final String VALID="{\"plans\":[{\"title\":\"night\",\"storyline\":\"walk\",\"nodes\":[{\"merchantName\":\"place\"}]}]}";
    private AiCreatorServiceImpl creator;
    private AiGatewayServiceImpl gateway;
    private AiQuotaRecoveryService recovery;
    private RedisTemplate<Object,Object> redis;
    private IWxContentSecurityService review;
    private IUmsMemberService members;
    private AiGenerationLogMapper logs;
    private UmsMember member;
    private AiModelConfig config;
    private AiGatewayPreparedThemeDraftTest.StubClient client;
    private final List<List<Object>> consumedKeys=new ArrayList<>();

    @BeforeEach @SuppressWarnings("unchecked")
    void fixture() {
        // Reuse the original fixture unchanged; do not inherit/execute any of its test methods.
        AiGatewayPreparedThemeDraftTest source=new AiGatewayPreparedThemeDraftTest(); source.fixture();
        gateway=spy((AiGatewayServiceImpl)ReflectionTestUtils.getField(source,"gateway"));
        recovery=(AiQuotaRecoveryService)ReflectionTestUtils.getField(source,"recovery");
        redis=(RedisTemplate<Object,Object>)ReflectionTestUtils.getField(source,"redis");
        review=(IWxContentSecurityService)ReflectionTestUtils.getField(source,"review");
        members=(IUmsMemberService)ReflectionTestUtils.getField(source,"members");
        logs=(AiGenerationLogMapper)ReflectionTestUtils.getField(source,"logs");
        member=(UmsMember)ReflectionTestUtils.getField(source,"member");
        config=(AiModelConfig)ReflectionTestUtils.getField(source,"config");
        client=(AiGatewayPreparedThemeDraftTest.StubClient)ReflectionTestUtils.getField(source,"a");
        client.text=VALID;
        // Freeze only the pure planner's day; all consumption/stage/review/Creator code remains real.
        doReturn("ai:gen:limit:gen:7:20261023|"+NONCE).when(gateway)
                .planQuotaReservation(MEMBER,"theme_draft",NONCE);
        doAnswer(inv->{
            List<Object> keys=new ArrayList<>((List<Object>)inv.getArgument(1)); consumedKeys.add(keys);
            boolean player=String.valueOf(keys.get(0)).startsWith("ai:theme:draft:player:");
            assertEquals(player?3:20,(Integer)inv.getArgument(3));
            return player?2L:5L;
        }).when(redis).execute(any(RedisScript.class),anyList(),anyInt(),anyInt(),anyInt());
        creator=new AiCreatorServiceImpl(); IRoleService roles=mock(IRoleService.class);
        when(roles.resolveRole(any(UmsMember.class))).thenAnswer(inv->((UmsMember)inv.getArgument(0)).getRole());
        ReflectionTestUtils.setField(creator,"gateway",gateway);
        ReflectionTestUtils.setField(creator,"umsMemberService",members);
        ReflectionTestUtils.setField(creator,"roleService",roles);
        ReflectionTestUtils.setField(creator,"quotaRecovery",recovery);
        ReflectionTestUtils.setField(creator,"redisTemplate",redis);
        ReflectionTestUtils.setField(creator,"themeDraftClock",Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"),ZoneId.of("Asia/Shanghai")));
    }

    private AiThemeDraftPlan plan(String role) { member.setRole(role); return creator.planThemeDraft(MEMBER,NONCE); }
    private AiThemeDraftResp execute(AiThemeDraftPlan p) {
        AiThemeDraftReq request=new AiThemeDraftReq(); request.setIdea("short original idea");
        return creator.themeDraftPrepared(request,MEMBER,p);
    }
    private void originalConsumption(AiThemeDraftPlan p,long used) {
        assertEquals(List.of(List.of(p.counterKey(),p.counterKey()+":generation",p.receiptKey())),consumedKeys);
        verify(recovery,times(1)).prepare(MEMBER,"theme_draft",p.pool(),p.counterKey(),p.receiptKey());
        verify(recovery,times(1)).reserved(p.receiptKey(),used);
    }

    @ParameterizedTest @ValueSource(strings={"stage","review","inputRejectedRetain"})
    void lateLocalTypeCannotCrossRealGatewayAndCreatorAsZeroEffectGenProof(String where) {
        AiThemeDraftPlan p=plan("club");
        AiThemeDraftPlanRejectedException late=new AiThemeDraftPlanRejectedException(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE);
        if(where.equals("stage"))doThrow(late).when(recovery).stage(p.receiptKey(),"INPUT_REVIEW_STARTED");
        else if(where.equals("review"))when(review.checkText(anyString(),anyString())).thenThrow(late);
        else {
            late.initCause(new IllegalStateException("UNTRUSTED_PROMPT_SENTINEL"));
            when(review.checkText(anyString(),anyString())).thenReturn("内容违规");
            doThrow(late).when(recovery).retain(p.receiptKey(),"INPUT_REJECTED");
        }
        AiServiceException outward=assertThrows(AiServiceException.class,()->execute(p));
        assertFalse(outward instanceof AiThemeDraftPlanRejectedException);
        assertFalse(outward instanceof AiProviderOutcomeUnknownException);
        assertFalse(outward instanceof AiProviderResultReceivedException);
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,outward.getReason());
        assertFalse(outward.getMessage().contains("UNTRUSTED_PROMPT_SENTINEL"));
        if(where.equals("inputRejectedRetain")) {
            assertEquals("AI 草稿原计划执行失败",outward.getMessage()); assertNull(outward.getCause());
        }
        originalConsumption(p,5L); assertEquals(0,client.calls); verifyNoInteractions(logs);
        InOrder order=inOrder(recovery,review);
        order.verify(recovery).prepare(MEMBER,"theme_draft","gen",p.counterKey(),p.receiptKey());
        order.verify(recovery).reserved(p.receiptKey(),5L);
        order.verify(recovery).stage(p.receiptKey(),"INPUT_REVIEW_STARTED");
        if(!where.equals("stage"))order.verify(review).checkText(anyString(),anyString());
        if(where.equals("inputRejectedRetain")) {
            order.verify(recovery).retain(p.receiptKey(),"INPUT_REJECTED");
            verify(recovery,never()).requireRefund(anyString(),anyString());
        } else order.verify(recovery).requireRefund(p.receiptKey(),"SERVICE_FAILURE");
        order.verifyNoMoreInteractions();
        verify(recovery,never()).stage(p.receiptKey(),"MODEL_STARTED");
    }

    @ParameterizedTest @ValueSource(strings={"stage","review"})
    void playerLateInputFailureKeepsOnlyItsOriginalPoolAndRefundPolicy(String where) {
        AiThemeDraftPlan p=plan("player");
        AiThemeDraftPlanRejectedException late=new AiThemeDraftPlanRejectedException(AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED);
        if(where.equals("stage"))doThrow(late).when(recovery).stage(p.receiptKey(),"INPUT_REVIEW_STARTED");
        else when(review.checkText(anyString(),anyString())).thenThrow(late);
        AiServiceException outward=assertThrows(AiServiceException.class,()->execute(p));
        assertFalse(outward instanceof AiThemeDraftPlanRejectedException);
        originalConsumption(p,2L); assertEquals(0,client.calls);
        verify(recovery,times(1)).requireRefund(p.receiptKey(),"PLAYER_DRAFT_FAILED");
        verify(recovery,never()).requireRefund(p.receiptKey(),"SERVICE_FAILURE");
        verify(recovery,never()).prepare(eq(MEMBER),eq("theme_draft"),eq("gen"),anyString(),anyString());
    }

    @ParameterizedTest @ValueSource(strings={"unknown","received"})
    void realModelOutcomeClassIsUnchangedAcrossBoundaryAndCreator(String outcome) {
        AiThemeDraftPlan p=plan("club"); AtomicReference<RuntimeException> gatewayFailure=new AtomicReference<>();
        if(outcome.equals("unknown"))client.failure=new IllegalStateException("model completion unknown");
        else when(review.checkText(anyString(),anyString())).thenAnswer(inv->{
            if(VALID.equals(inv.getArgument(0)))throw new IllegalStateException("output review unavailable");
            return null;
        });
        doAnswer(inv->{
            try { return inv.callRealMethod(); }
            catch(RuntimeException failure) { gatewayFailure.set(failure); throw failure; }
        }).when(gateway).generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString());
        AiServiceException outward=assertThrows(AiServiceException.class,()->execute(p));
        assertSame(gatewayFailure.get(),outward);
        assertTrue(outcome.equals("unknown")?outward instanceof AiProviderOutcomeUnknownException:
                outward instanceof AiProviderResultReceivedException);
        originalConsumption(p,5L); assertEquals(1,client.calls);
        verify(recovery,never()).requireRefund(anyString(),anyString());
        verify(recovery).stage(p.receiptKey(),"MODEL_STARTED");
    }

    @ParameterizedTest @ValueSource(strings={"unknown","received"})
    void nonLocalTypedOutcomeAtUnprotectedRetainEdgeKeepsExactObject(String outcome) {
        AiThemeDraftPlan p=plan("club");
        AiServiceException injected=outcome.equals("unknown")?new AiProviderOutcomeUnknownException("unknown",null):
                new AiProviderResultReceivedException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,"received");
        when(review.checkText(anyString(),anyString())).thenReturn("内容违规");
        doThrow(injected).when(recovery).retain(p.receiptKey(),"INPUT_REJECTED");
        assertSame(injected,assertThrows(AiServiceException.class,()->execute(p)));
        originalConsumption(p,5L); assertEquals(0,client.calls);
        verify(recovery,never()).requireRefund(anyString(),anyString());
    }

    @ParameterizedTest @ValueSource(strings={"invalid","config","member"})
    void genuineGenPreflightRetainsTypedCodeAndZeroEffects(String what) {
        AiThemeDraftPlan p=plan("club");
        if(what.equals("invalid"))p=new AiThemeDraftPlan(p.memberId(),p.role(),p.pool(),p.originalDay(),p.counterKey(),
                p.nonce(),p.receiptKey()+"bad",p.reservation(),p.creatorConfigHash(),p.gatewayConfigHash(),p.executeBefore());
        if(what.equals("config"))ReflectionTestUtils.setField(config,"dailyLimit",21);
        if(what.equals("member"))when(members.selectUmsMemberById(MEMBER)).thenReturn(null);
        AiThemeDraftPlan original=p;
        AiThemeDraftPlanRejectedException failure=assertThrows(AiThemeDraftPlanRejectedException.class,
                ()->gateway.generateThemeDraftPrepared(MEMBER,original,"system","user"));
        assertEquals(what.equals("invalid")?AiThemeDraftPlanRejectedException.Code.PLAN_INVALID:
                what.equals("config")?AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED:
                AiThemeDraftPlanRejectedException.Code.AUTHORITY_CHANGED,failure.getCode());
        assertTrue(consumedKeys.isEmpty()); verifyNoInteractions(recovery,redis,review,logs); assertEquals(0,client.calls);
    }

    @Test void legitimateFirstGenStillProducesDraftUsingOriginalIdentityExactlyOnce() {
        AiThemeDraftPlan p=plan("club"); AiThemeDraftResp result=execute(p);
        assertNotNull(result.getDraft()); assertEquals(NONCE,result.getTraceId()); assertNull(result.getRemainingQuota());
        originalConsumption(p,5L); assertEquals(1,client.calls);
        verify(recovery,times(1)).retain(p.receiptKey(),"SUCCESS");
        verify(recovery,never()).requireRefund(anyString(),anyString());
        verify(gateway,times(1)).generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString());
    }
}
