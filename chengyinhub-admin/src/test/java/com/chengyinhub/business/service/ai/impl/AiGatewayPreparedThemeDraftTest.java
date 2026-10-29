package com.chengyinhub.business.service.ai.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.AiGenerationLog;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.AiGenerationLogMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.common.config.AiModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prepared orchestration only; provider is an explicit local, network-free test double. */
class AiGatewayPreparedThemeDraftTest {
    private static final long MEMBER=7L;
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    private static final Instant NOW=Instant.parse("2026-10-23T04:00:00Z");
    private static final ZoneId BJ=ZoneId.of("Asia/Shanghai");
    private AiGatewayServiceImpl gateway;
    private RedisTemplate<Object,Object> redis;
    private AiQuotaRecoveryService recovery;
    private IWxContentSecurityService review;
    private IUmsMemberService members;
    private AiGenerationLogMapper logs;
    private AiModelConfig config;
    private Map<String,LlmClient> clients;
    private StubClient a,b;
    private UmsMember member;
    private final List<List<Object>> reservations=new ArrayList<>();

    /** Final production config/readiness, overridden chat performs no HTTP or model work. */
    static final class StubClient extends OpenAiCompatibleLlmClient {
        int calls; RuntimeException failure; String text="OUTPUT_ONLY_SHORT_BODY";
        StubClient(String model,String credential) {
            super("deepseek","https://model.invalid",credential,model,true,1000);
        }
        @Override public String chat(String system,String user) {
            calls++; if(failure!=null)throw failure; return text;
        }
    }

    @BeforeEach @SuppressWarnings("unchecked")
    void fixture() {
        gateway=new AiGatewayServiceImpl(); redis=mock(RedisTemplate.class);
        recovery=mock(AiQuotaRecoveryService.class); review=mock(IWxContentSecurityService.class);
        members=mock(IUmsMemberService.class); logs=mock(AiGenerationLogMapper.class);
        config=new AiModelConfig();
        ReflectionTestUtils.setField(config,"provider","deepseek");
        ReflectionTestUtils.setField(config,"baseUrl","https://model.invalid");
        ReflectionTestUtils.setField(config,"enabled",true);
        ReflectionTestUtils.setField(config,"timeoutMs",1000);
        ReflectionTestUtils.setField(config,"dailyLimit",20);
        a=new StubClient("model-a","unit-credential-one"); b=new StubClient("model-b","unit-credential-two");
        clients=new HashMap<>(); clients.put("deepseekLlmClient",a);
        ReflectionTestUtils.setField(gateway,"redisTemplate",redis);
        ReflectionTestUtils.setField(gateway,"quotaRecovery",recovery);
        ReflectionTestUtils.setField(gateway,"wxContentSecurityService",review);
        ReflectionTestUtils.setField(gateway,"umsMemberService",members);
        ReflectionTestUtils.setField(gateway,"aiGenerationLogMapper",logs);
        ReflectionTestUtils.setField(gateway,"aiModelConfig",config);
        ReflectionTestUtils.setField(gateway,"sceneRouter",new AiSceneRouter());
        ReflectionTestUtils.setField(gateway,"llmClientsByBeanName",clients);
        ReflectionTestUtils.setField(gateway,"themeDraftClock",Clock.fixed(NOW,BJ));
        member=new UmsMember(); member.setId(MEMBER); member.setRole("club");
        member.setStatus(1L); member.setDelFlag(0);
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(redis.execute(any(RedisScript.class),anyList(),anyInt(),anyInt(),anyInt())).thenAnswer(inv->{
            reservations.add(new ArrayList<>((List<Object>)inv.getArgument(1)));
            assertEquals(20,(Integer)inv.getArgument(3),"consumption must use the validated configuration snapshot");
            return 5L;
        });
    }

    private AiThemeDraftPlan plan(String role) {
        member.setRole(role);
        boolean player=role.equals("player");
        String counter=(player?"ai:theme:draft:player:":"ai:gen:limit:gen:")+MEMBER+":20261023";
        String receipt=(player?"ai:player:theme:draft:reservation:":"ai:gen:reservation:")+counter+":"+NONCE;
        String hash=gateway.themeDraftConfigurationHash();
        return new AiThemeDraftPlan(MEMBER,role,player?"player-draft":"gen","20261023",counter,NONCE,
                receipt,counter+"|"+NONCE,"c".repeat(64),hash,Instant.parse("2026-10-23T16:00:00Z"));
    }
    private AiGatewayResult execute(AiThemeDraftPlan p) {
        return gateway.generateThemeDraftPrepared(MEMBER,p,"SYSTEM_ONLY_SHORT_CONTEXT","IDEA_ONLY_SHORT_CONTEXT");
    }
    private void zeroEffects() {
        verifyNoInteractions(redis,recovery,review,logs); assertEquals(0,a.calls); assertEquals(0,b.calls);
    }

    @Test void configurationHashIsLocalAndChangesActualModelButNeverCredentials() {
        String first=gateway.themeDraftConfigurationHash();
        assertTrue(first.matches("[a-f0-9]{64}"));
        clients.put("deepseekLlmClient",new StubClient("model-a","rotated-unit-credential"));
        ReflectionTestUtils.setField(config,"apiKey","different-unit-credential");
        assertEquals(first,gateway.themeDraftConfigurationHash());
        clients.put("deepseekLlmClient",b);
        assertNotEquals(first,gateway.themeDraftConfigurationHash()); zeroEffects();
    }

    @Test void genConsumesOnlyOriginalPlanAndUsesNonceForTraceAndAudit() {
        AiThemeDraftPlan p=plan("club"); AiGatewayResult result=execute(p);
        assertEquals(NONCE,result.getTraceId()); assertEquals(1,a.calls);
        assertEquals(List.of(p.counterKey(),p.counterKey()+":generation",p.receiptKey()),reservations.get(0));
        assertEquals(1,reservations.size());
        verify(recovery).prepare(MEMBER,"theme_draft","gen",p.counterKey(),p.receiptKey());
        verify(recovery).reserved(p.receiptKey(),5L); verify(recovery).stage(p.receiptKey(),"MODEL_STARTED");
        verify(recovery).retain(p.receiptKey(),"SUCCESS"); verify(recovery,never()).requireRefund(anyString(),anyString());
        ArgumentCaptor<AiGenerationLog> audit=ArgumentCaptor.forClass(AiGenerationLog.class);
        verify(logs).insertAiGenerationLog(audit.capture());
        assertEquals(NONCE,audit.getValue().getTraceId());
        assertTrue(audit.getValue().getInputBrief().matches("sha256:[a-f0-9]{64}"));
        assertTrue(audit.getValue().getOutputBrief().matches("sha256:[a-f0-9]{64}"));
        assertFalse(audit.getValue().getInputBrief().contains("IDEA_ONLY_SHORT_CONTEXT"));
    }

    @Test void validatedClientAndLimitAreNotReselectedAfterQuotaOrInputReview() {
        AiThemeDraftPlan p=plan("club");
        doAnswer(inv->{ ReflectionTestUtils.setField(config,"dailyLimit",1); return null; })
                .when(recovery).prepare(MEMBER,"theme_draft","gen",p.counterKey(),p.receiptKey());
        when(review.checkText(anyString(),anyString())).thenAnswer(inv->{
            clients.put("deepseekLlmClient",b);
            ReflectionTestUtils.setField(config,"baseUrl","https://changed.invalid"); return null;
        });
        assertEquals(NONCE,execute(p).getTraceId()); assertEquals(1,a.calls); assertEquals(0,b.calls);
        verify(recovery).reserved(p.receiptKey(),5L);
    }

    @Test void changedActualModelRejectsGenBeforeAnyQuotaOrReview() {
        AiThemeDraftPlan p=plan("club"); clients.put("deepseekLlmClient",b);
        AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,()->execute(p));
        assertEquals(AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED,ex.getCode()); zeroEffects();
    }

    @ParameterizedTest @ValueSource(strings={"expired","member","receipt","pool","reservation","configHash"})
    void originalPlanRejectionIsZeroEffectForGen(String kind) {
        AiThemeDraftPlan p=plan("club");
        AiThemeDraftPlan bad=new AiThemeDraftPlan(kind.equals("member")?8L:p.memberId(),p.role(),
                kind.equals("pool")?"photo":p.pool(),p.originalDay(),p.counterKey(),p.nonce(),
                kind.equals("receipt")?p.receiptKey()+"bad":p.receiptKey(),
                kind.equals("reservation")?p.reservation()+"bad":p.reservation(),p.creatorConfigHash(),
                kind.equals("configHash")?"b".repeat(64):p.gatewayConfigHash(),
                kind.equals("expired")?NOW:p.executeBefore());
        AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,()->execute(bad));
        assertEquals(kind.equals("expired")?AiThemeDraftPlanRejectedException.Code.PLAN_EXPIRED:
                kind.equals("configHash")?AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED:
                AiThemeDraftPlanRejectedException.Code.PLAN_INVALID,ex.getCode()); zeroEffects();
    }

    @Test void failedMemberReadIsCheckUnavailableBeforeGenEffects() {
        AiThemeDraftPlan p=plan("club"); when(members.selectUmsMemberById(MEMBER)).thenThrow(new IllegalStateException("db unavailable"));
        AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,()->execute(p));
        assertEquals(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,ex.getCode()); zeroEffects();
    }

    @Test void changedCanonicalRoleCannotConsumeOriginalGenPlanForAnotherRole() {
        AiThemeDraftPlan p=plan("club"); member.setRole("merchant");
        AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,()->execute(p));
        assertEquals(AiThemeDraftPlanRejectedException.Code.AUTHORITY_CHANGED,ex.getCode()); zeroEffects();
    }

    @Test void ambientTransactionRejectsBeforeGenQuotaAndProvider() {
        AiThemeDraftPlan p=plan("club");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,()->execute(p));
            assertEquals(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,ex.getCode()); zeroEffects();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void boundOldSqlSessionWithoutActiveTransactionRejectsBeforeGenEffects() {
        AiThemeDraftPlan p=plan("club"); Object key=new Object();
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(
                key,new org.mybatis.spring.SqlSessionHolder(mock(org.apache.ibatis.session.SqlSession.class),
                        org.apache.ibatis.session.ExecutorType.SIMPLE,null));
        try {
            AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,()->execute(p));
            assertEquals(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,ex.getCode()); zeroEffects();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(key); }
    }

    @ParameterizedTest @ValueSource(strings={"notSent","ordinary","wrappedNotSent","empty"})
    void modelDispositionPreservesOriginalGenEconomics(String kind) {
        AiThemeDraftPlan p=plan("club");
        if(kind.equals("notSent"))a.failure=new AiProviderNotSentException();
        if(kind.equals("ordinary"))a.failure=new IllegalStateException("IDEA_ONLY_SHORT_CONTEXT");
        if(kind.equals("wrappedNotSent"))a.failure=new IllegalStateException(new AiProviderNotSentException());
        if(kind.equals("empty"))a.text="";
        AiServiceException ex=assertThrows(AiServiceException.class,()->execute(p));
        assertEquals(!kind.equals("notSent"),ex instanceof AiProviderOutcomeUnknownException);
        verify(recovery,times(kind.equals("notSent")?1:0)).requireRefund(p.receiptKey(),"SERVICE_FAILURE");
        assertEquals(1,a.calls); assertEquals(1,reservations.size());
        assertFalse(ex instanceof AiThemeDraftPlanRejectedException);
    }

    @Test void receivedOutputReviewFailureCannotBecomeNotSentOrRefund() {
        AiThemeDraftPlan p=plan("club");
        when(review.checkText(anyString(),anyString())).thenAnswer(inv->{
            if("OUTPUT_ONLY_SHORT_BODY".equals(inv.getArgument(0)))throw new IllegalStateException("review down");
            return null;
        });
        assertThrows(AiProviderResultReceivedException.class,()->execute(p));
        verify(recovery).retain(p.receiptKey(),"OUTPUT_REVIEW_UNKNOWN");
        verify(recovery,never()).requireRefund(anyString(),anyString()); assertEquals(1,a.calls);
    }

    @Test void receivedRetentionFailureEvenTypedNotSentKeepsReceivedDisposition() {
        AiThemeDraftPlan p=plan("club"); doThrow(new AiProviderNotSentException()).when(recovery).retain(p.receiptKey(),"SUCCESS");
        assertThrows(AiProviderResultReceivedException.class,()->execute(p));
        verify(recovery,never()).requireRefund(anyString(),anyString()); assertEquals(1,a.calls);
    }

    @Test void playerStagesOnlyOriginalReceiptAndNeverConsumesOrRefundsGen() {
        AiThemeDraftPlan p=plan("player"); assertEquals(NONCE,execute(p).getTraceId());
        verifyNoInteractions(redis); verify(recovery,never()).prepare(anyLong(),anyString(),anyString(),anyString(),anyString());
        verify(recovery).stage(p.receiptKey(),"INPUT_REVIEW_STARTED"); verify(recovery).stage(p.receiptKey(),"MODEL_STARTED");
        verify(recovery,never()).retain(anyString(),anyString()); verify(recovery,never()).requireRefund(anyString(),anyString());
    }

    @Test void latePlayerConfigurationCheckDoesNotClaimZeroEffectsAfterCreatorReservation() {
        AiThemeDraftPlan p=plan("player"); clients.put("deepseekLlmClient",b);
        AiServiceException ex=assertThrows(AiServiceException.class,()->execute(p));
        assertFalse(ex instanceof AiThemeDraftPlanRejectedException); assertNull(ex.getCause()); zeroEffects();
    }

    @Test void preparedErrorsAndPermanentAuditNeverCopyPromptOutputOrRawThrowable() {
        AiThemeDraftPlan p=plan("club"); a.failure=new IllegalStateException("IDEA_ONLY_SHORT_CONTEXT OUTPUT_ONLY_SHORT_BODY");
        Logger logger=(Logger)LoggerFactory.getLogger(AiGatewayServiceImpl.class);
        ListAppender<ILoggingEvent> appender=new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            assertThrows(AiProviderOutcomeUnknownException.class,()->execute(p));
            assertFalse(appender.list.isEmpty());
            for(ILoggingEvent event:appender.list) {
                assertFalse(event.getFormattedMessage().contains("IDEA_ONLY_SHORT_CONTEXT"));
                assertFalse(event.getFormattedMessage().contains("OUTPUT_ONLY_SHORT_BODY"));
                assertNull(event.getThrowableProxy());
            }
            ArgumentCaptor<AiGenerationLog> audit=ArgumentCaptor.forClass(AiGenerationLog.class);
            verify(logs).insertAiGenerationLog(audit.capture());
            assertTrue(audit.getValue().getInputBrief().matches("sha256:[a-f0-9]{64}"));
            assertTrue(audit.getValue().getOutputBrief().matches("sha256:[a-f0-9]{64}"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
