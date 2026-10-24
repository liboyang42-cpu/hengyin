package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ai.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit effects/identity tests; E owns the durable claim and real storage verification. */
class AiCreatorPreparedThemeDraftTest {
    private static final Long MEMBER = 7L;
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String HASH = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-10-23T04:00:00Z");
    private static final ZoneId BJ = ZoneId.of("Asia/Shanghai");
    private static final String VALID = "{\"plans\":[{\"title\":\"night\",\"storyline\":\"walk\",\"nodes\":[{\"merchantName\":\"place\"}]}]}";
    private AiCreatorServiceImpl creator;
    private AiGatewayService gateway;
    private IUmsMemberService members;
    private IRoleService roles;
    private AiQuotaRecoveryService recovery;
    private RedisTemplate<Object,Object> redis;
    private UmsMember member;
    private final List<List<Object>> reservations = new ArrayList<>();

    @BeforeEach @SuppressWarnings("unchecked")
    void fixture() {
        creator = new AiCreatorServiceImpl(); gateway = mock(AiGatewayService.class);
        members = mock(IUmsMemberService.class); roles = mock(IRoleService.class);
        recovery = mock(AiQuotaRecoveryService.class); redis = mock(RedisTemplate.class);
        ReflectionTestUtils.setField(creator,"gateway",gateway);
        ReflectionTestUtils.setField(creator,"umsMemberService",members);
        ReflectionTestUtils.setField(creator,"roleService",roles);
        ReflectionTestUtils.setField(creator,"quotaRecovery",recovery);
        ReflectionTestUtils.setField(creator,"redisTemplate",redis);
        ReflectionTestUtils.setField(creator,"themeDraftClock",Clock.fixed(NOW,BJ));
        member = new UmsMember(); member.setId(MEMBER); member.setRole("player");
        member.setStatus(1L); member.setDelFlag(0);
        when(members.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(roles.resolveRole(any(UmsMember.class))).thenAnswer(inv -> ((UmsMember) inv.getArgument(0)).getRole());
        when(gateway.themeDraftConfigurationHash()).thenReturn(HASH);
        when(gateway.planQuotaReservation(MEMBER,"theme_draft",NONCE))
                .thenReturn("ai:gen:limit:gen:7:20261023|"+NONCE);
        when(redis.execute(any(RedisScript.class),anyList(),anyInt(),anyInt(),anyInt())).thenAnswer(inv -> {
            reservations.add(new ArrayList<>((List<Object>)inv.getArgument(1)));
            assertEquals(43_200, (Integer) inv.getArgument(2));
            assertEquals(3, (Integer) inv.getArgument(3));
            return 2L;
        });
    }

    private AiThemeDraftPlan plan() { return creator.planThemeDraft(MEMBER,NONCE); }
    private AiThemeDraftReq request() {
        AiThemeDraftReq req = new AiThemeDraftReq(); req.setIdea("IDEA_ONLY_SHORT_CONTEXT"); return req;
    }
    private void zeroEffects() { verifyNoInteractions(redis,recovery); assertTrue(reservations.isEmpty());
        verify(gateway,never()).generateThemeDraftPrepared(anyLong(),any(),anyString(),anyString()); }
    private void code(AiThemeDraftPlanRejectedException.Code expected, AiThemeDraftPlan p) {
        AiThemeDraftPlanRejectedException failure = assertThrows(AiThemeDraftPlanRejectedException.class,
                () -> creator.themeDraftPrepared(request(),MEMBER,p));
        assertEquals(expected,failure.getCode()); assertNull(failure.getCause()); zeroEffects();
    }

    @Test void playerPlanningIsPureAndFreezesSuppliedIdentityAndOriginalBjDay() {
        AiThemeDraftPlan p=plan();
        assertEquals("player-draft",p.pool()); assertEquals("20261023",p.originalDay());
        assertEquals("ai:theme:draft:player:7:20261023",p.counterKey());
        assertEquals(p.counterKey()+"|"+NONCE,p.reservation());
        assertEquals("ai:player:theme:draft:reservation:"+p.counterKey()+":"+NONCE,p.receiptKey());
        assertEquals(Instant.parse("2026-10-23T16:00:00Z"),p.executeBefore());
        assertTrue(p.creatorConfigHash().matches("[a-f0-9]{64}"));
        verify(gateway,never()).planQuotaReservation(anyLong(),anyString(),anyString()); zeroEffects();
    }

    @Test void clubPlanningReusesGatewaysExactOriginalTokenAndDoesNotReserve() {
        member.setRole("club"); AiThemeDraftPlan p=plan();
        assertEquals("gen",p.pool()); assertEquals("ai:gen:limit:gen:7:20261023|"+NONCE,p.reservation());
        verify(gateway).planQuotaReservation(MEMBER,"theme_draft",NONCE); zeroEffects();
    }

    @ParameterizedTest @ValueSource(strings={"member","role","pool","day","counter","nonce","receipt","reservation","creatorHash","gatewayHash","deadline"})
    void malformedOriginalPlanRejectsWithoutQuotaReviewOrModel(String field) {
        AiThemeDraftPlan p=plan();
        AiThemeDraftPlan bad=new AiThemeDraftPlan(field.equals("member")?8L:p.memberId(),
                field.equals("role")?"not-a-role":p.role(), field.equals("pool")?"gen":p.pool(),
                field.equals("day")?"20261399":p.originalDay(),field.equals("counter")?p.counterKey()+"0":p.counterKey(),
                field.equals("nonce")?"UPPER":p.nonce(),field.equals("receipt")?p.receiptKey()+"0":p.receiptKey(),
                field.equals("reservation")?p.reservation()+"0":p.reservation(),
                field.equals("creatorHash")?null:p.creatorConfigHash(),field.equals("gatewayHash")?null:p.gatewayConfigHash(),
                field.equals("deadline")?p.executeBefore().plusSeconds(1):p.executeBefore());
        code(AiThemeDraftPlanRejectedException.Code.PLAN_INVALID,bad);
    }

    @Test void committedRoleChangeRejectsRatherThanReplanningPool() {
        AiThemeDraftPlan p=plan(); member.setRole("club");
        code(AiThemeDraftPlanRejectedException.Code.AUTHORITY_CHANGED,p);
        verify(gateway,never()).planQuotaReservation(anyLong(),anyString(),anyString());
    }

    @Test void missingMemberCannotFallBackToPlayer() {
        AiThemeDraftPlan p=plan(); when(members.selectUmsMemberById(MEMBER)).thenReturn(null);
        code(AiThemeDraftPlanRejectedException.Code.AUTHORITY_CHANGED,p);
    }

    @ParameterizedTest @ValueSource(strings={"disabled","deleted"})
    void currentMemberEligibilityUsesExistingRoleServicePolicy(String kind) {
        AiThemeDraftPlan p=plan();
        if(kind.equals("disabled"))member.setStatus(0L); else member.setDelFlag(2);
        code(AiThemeDraftPlanRejectedException.Code.AUTHORITY_CHANGED,p);
    }

    @Test void memberReadFailureIsCheckUnavailableWithoutUnsentSideEffects() {
        AiThemeDraftPlan p=plan(); when(members.selectUmsMemberById(MEMBER)).thenThrow(new IllegalStateException("IDEA_ONLY_SHORT_CONTEXT"));
        code(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,p);
    }

    @Test void roleResolverFailureIsCheckUnavailableRatherThanAuthorityDecision() {
        AiThemeDraftPlan p=plan(); when(roles.resolveRole(member)).thenThrow(new IllegalStateException("resolver unavailable"));
        code(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,p);
    }

    @Test void changedGatewayConfigurationRejectsBeforeOriginalPlayerReservation() {
        AiThemeDraftPlan p=plan(); when(gateway.themeDraftConfigurationHash()).thenReturn("b".repeat(64));
        code(AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED,p);
    }

    @ParameterizedTest @ValueSource(longs={43200,86400})
    void deadlineOrChangedBjDayRejectsWithoutCreatingNewIdentity(long seconds) {
        AiThemeDraftPlan p=plan(); ReflectionTestUtils.setField(creator,"themeDraftClock",Clock.fixed(NOW.plusSeconds(seconds),BJ));
        code(AiThemeDraftPlanRejectedException.Code.PLAN_EXPIRED,p);
    }

    @Test void preparedPlayerConsumesOnlyOriginalReceiptAndKeepsValidDraftPolicy() {
        AiThemeDraftPlan p=plan();
        when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString()))
                .thenReturn(new AiGatewayResult(NONCE,VALID,"deepseek",0));
        AiThemeDraftResp result=creator.themeDraftPrepared(request(),MEMBER,p);
        assertEquals(NONCE,result.getTraceId()); assertEquals(1,result.getRemainingQuota());
        assertEquals(List.of(p.counterKey(),p.counterKey()+":generation",p.receiptKey()),reservations.get(0));
        assertEquals(1,reservations.size());
        verify(recovery).prepare(MEMBER,"theme_draft","player-draft",p.counterKey(),p.receiptKey());
        verify(recovery).reserved(p.receiptKey(),2L); verify(recovery).retain(p.receiptKey(),"VALID_PLAYER_DRAFT");
        verify(recovery,never()).requireRefund(anyString(),anyString());
        verify(gateway,never()).generate(anyLong(),anyString(),anyString(),anyString(),anyString());
        verify(gateway,never()).consumePlannedQuota(anyLong(),anyString(),anyString());
    }

    @ParameterizedTest @ValueSource(strings={"notSent","unknown","received","blocked","parse"})
    void preparedPlayerKeepsReviewedFailureEconomicsOnItsOriginalReceipt(String kind) {
        AiThemeDraftPlan p=plan(); RuntimeException failure;
        switch(kind) {
            case "unknown": failure=new AiProviderOutcomeUnknownException("unknown",null); break;
            case "received": failure=new AiProviderResultReceivedException(AiServiceException.Reason.OUTPUT_BLOCKED,"received"); break;
            case "blocked": failure=new AiServiceException(AiServiceException.Reason.INPUT_BLOCKED,"blocked"); break;
            default: failure=new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,"unsent",new AiProviderNotSentException());
        }
        if(kind.equals("parse")) when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString()))
                .thenReturn(new AiGatewayResult(NONCE,"not-json-OUTPUT_SENTINEL","deepseek",0));
        else when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString())).thenThrow(failure);
        assertThrows(AiServiceException.class,()->creator.themeDraftPrepared(request(),MEMBER,p));
        assertEquals(1,reservations.size()); assertEquals(p.receiptKey(),reservations.get(0).get(2));
        boolean refund=!(kind.equals("unknown")||kind.equals("blocked"));
        verify(recovery,times(refund?1:0)).requireRefund(p.receiptKey(),"PLAYER_DRAFT_FAILED");
        verify(recovery,times(kind.equals("blocked")?1:0)).retain(p.receiptKey(),"INPUT_BLOCKED");
        verify(gateway,times(1)).generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString());
    }

    @Test void latePreflightClassAfterPlayerReservationCannotEscapeAsZeroEffectProof() {
        AiThemeDraftPlan p=plan();
        when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString()))
                .thenThrow(new AiThemeDraftPlanRejectedException(AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED));
        AiServiceException failure=assertThrows(AiServiceException.class,()->creator.themeDraftPrepared(request(),MEMBER,p));
        assertFalse(failure instanceof AiThemeDraftPlanRejectedException); assertNull(failure.getCause());
        verify(recovery).requireRefund(p.receiptKey(),"PLAYER_DRAFT_FAILED");
    }

    @ParameterizedTest @ValueSource(strings={"notSent","planRejected","ordinary"})
    void validModelReceiptRetentionFailureCannotTurnReceivedResultIntoUnsent(String kind) {
        AiThemeDraftPlan p=plan();
        when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString()))
                .thenReturn(new AiGatewayResult(NONCE,VALID,"deepseek",0));
        RuntimeException failure=kind.equals("notSent")?new AiProviderNotSentException():kind.equals("planRejected")
                ?new AiThemeDraftPlanRejectedException(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE)
                :new IllegalStateException("retention unavailable");
        doThrow(failure).when(recovery).retain(p.receiptKey(),"VALID_PLAYER_DRAFT");
        assertThrows(AiProviderResultReceivedException.class,()->creator.themeDraftPrepared(request(),MEMBER,p));
        verify(recovery,never()).requireRefund(anyString(),anyString()); assertEquals(1,reservations.size());
    }

    @Test void playerMissingRecoveryWiringRejectsBeforeRedisAndRecoveryFallback() {
        AiThemeDraftPlan p=plan(); ReflectionTestUtils.setField(creator,"quotaRecovery",null);
        code(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,p);
    }

    @Test void clubDelegatesOriginalPlanWithoutCreatorPlayerPool() {
        member.setRole("club"); AiThemeDraftPlan p=plan();
        when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString()))
                .thenReturn(new AiGatewayResult(NONCE,VALID,"deepseek",0));
        assertNotNull(creator.themeDraftPrepared(request(),MEMBER,p).getDraft());
        verifyNoInteractions(redis,recovery);
    }

    @Test void shorterExecutionDeadlineCannotExpireAndResetOriginalDailyCounterEarly() {
        AiThemeDraftPlan p=plan();
        AiThemeDraftPlan shortDeadline=new AiThemeDraftPlan(p.memberId(),p.role(),p.pool(),p.originalDay(),
                p.counterKey(),p.nonce(),p.receiptKey(),p.reservation(),p.creatorConfigHash(),p.gatewayConfigHash(),NOW.plusSeconds(60));
        when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(shortDeadline),anyString(),anyString()))
                .thenReturn(new AiGatewayResult(NONCE,VALID,"deepseek",0));
        // The Redis fixture requires the original midnight TTL (43200), not the 60-second execution deadline.
        assertNotNull(creator.themeDraftPrepared(request(),MEMBER,shortDeadline).getDraft());
        assertEquals(p.counterKey(),reservations.get(0).get(0));
    }

    @Test void changedCreatorFingerprintRejectsBeforeQuotaAndGatewayCheck() {
        AiThemeDraftPlan p=plan(); clearInvocations(gateway);
        AiThemeDraftPlan changed=new AiThemeDraftPlan(p.memberId(),p.role(),p.pool(),p.originalDay(),
                p.counterKey(),p.nonce(),p.receiptKey(),p.reservation(),"b".repeat(64),p.gatewayConfigHash(),p.executeBefore());
        code(AiThemeDraftPlanRejectedException.Code.CONFIG_CHANGED,changed);
        verify(gateway,never()).themeDraftConfigurationHash();
    }

    @Test void unavailableOriginalRequestSnapshotIsRejectedBeforeAnyEffect() {
        AiThemeDraftPlan p=plan();
        AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,
                ()->creator.themeDraftPrepared(null,MEMBER,p));
        assertEquals(AiThemeDraftPlanRejectedException.Code.PLAN_INVALID,ex.getCode()); zeroEffects();
    }

    @Test void preparedExecutionCannotEnterQuotaOrProviderInsideAmbientTransaction() {
        AiThemeDraftPlan p=plan();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try { code(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,p); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    @Test void boundOldSqlSessionWithoutActiveTransactionCannotSupplyAuthoritySnapshot() {
        AiThemeDraftPlan p=plan(); Object key=new Object();
        org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(
                key,new org.mybatis.spring.SqlSessionHolder(mock(org.apache.ibatis.session.SqlSession.class),
                        org.apache.ibatis.session.ExecutorType.SIMPLE,null));
        try { code(AiThemeDraftPlanRejectedException.Code.CHECK_UNAVAILABLE,p); }
        finally { org.springframework.transaction.support.TransactionSynchronizationManager.unbindResourceIfPossible(key); }
    }

    @ParameterizedTest @ValueSource(strings={"", "UPPER", "012345"})
    void planningDoesNotRepairOrReplaceInvalidSuppliedNonce(String nonce) {
        AiThemeDraftPlanRejectedException ex=assertThrows(AiThemeDraftPlanRejectedException.class,
                ()->creator.planThemeDraft(MEMBER,nonce));
        assertEquals(AiThemeDraftPlanRejectedException.Code.PLAN_INVALID,ex.getCode());
        verifyNoInteractions(gateway,members,roles,redis,recovery);
    }

    @Test void preparedParseLogsNeverRetainOriginalRequestOrModelExceptionText() {
        AiThemeDraftPlan p=plan();
        when(gateway.generateThemeDraftPrepared(eq(MEMBER),eq(p),anyString(),anyString()))
                .thenReturn(new AiGatewayResult(NONCE,"invalid-OUTPUT_SENTINEL","deepseek",0));
        Logger logger=(Logger) LoggerFactory.getLogger(AiCreatorServiceImpl.class);
        ListAppender<ILoggingEvent> appender=new ListAppender<>(); appender.start(); logger.addAppender(appender);
        try {
            assertThrows(AiServiceException.class,()->creator.themeDraftPrepared(request(),MEMBER,p));
            assertFalse(appender.list.isEmpty());
            for(ILoggingEvent event:appender.list) {
                assertFalse(event.getFormattedMessage().contains("IDEA_ONLY_SHORT_CONTEXT"));
                assertFalse(event.getFormattedMessage().contains("OUTPUT_SENTINEL"));
                assertNull(event.getThrowableProxy());
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
