package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.AiQuotaReservationRecord;
import com.chengyinhub.business.mapper.AiQuotaReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiQuotaRecoveryServiceTest {
    private AiQuotaReservationMapper mapper;
    private RedisTemplate<Object,Object> redis;
    private PlatformTransactionManager transactions;
    private AiQuotaRecoveryService service;
    private com.chengyinhub.business.mapper.NpcAsrIntentMapper asrIntents;
    private AiQuotaReservationRecord row;
    private static final String RECEIPT="ai:gen:reservation:ai:gen:limit:gen:7:20261013:01234567890123456789012345678901";

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        mapper=mock(AiQuotaReservationMapper.class); redis=mock(RedisTemplate.class);
        transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        asrIntents=mock(com.chengyinhub.business.mapper.NpcAsrIntentMapper.class);
        service=new AiQuotaRecoveryService(mapper,redis,transactions,asrIntents);
        row=new AiQuotaReservationRecord(); row.setId(1L);row.setFenceVersion(0L);
        row.setCounterKey("ai:gen:limit:gen:7:20261013");row.setReceiptKey(RECEIPT);
        row.setState("REFUND_DUE");row.setStage("RESULT_CLASSIFIED");row.setGeneration("original-generation");
        when(mapper.selectDue(100)).thenReturn(Collections.singletonList(row));
        when(mapper.claim(eq(1L),eq(0L),anyString())).thenReturn(1);
        when(mapper.selectById(1L)).thenAnswer(invocation -> { row.setFenceVersion(1L);return row; });
        when(mapper.complete(anyLong(),anyString(),anyLong(),anyString(),nullable(String.class))).thenReturn(1);
    }

    @Test void databasePrepareFailureNeverTouchesRedis() {
        when(mapper.insertPrepared(any())).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class,()->service.prepare(7L,"theme_draft","gen",row.getCounterKey(),RECEIPT));
        verifyNoInteractions(redis);
    }

    @Test void prepareUsesIndependentCommitBeforeAnyCharge() {
        when(mapper.insertPrepared(any())).thenReturn(1);
        service.prepare(7L,"theme_draft","gen",row.getCounterKey(),RECEIPT);
        ArgumentCaptor<TransactionDefinition> definition=ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getValue().getPropagationBehavior());
        verify(transactions).commit(any()); verifyNoInteractions(redis);
    }

    @Test void refundIntentDatabaseFailureDoesNotBlindDebitRedis() {
        when(mapper.recordOutcome(RECEIPT,"REFUND_DUE","SERVICE_FAILURE")).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class,()->service.requireRefund(RECEIPT,"SERVICE_FAILURE"));
        verifyNoInteractions(redis);
    }

    @Test void staleModelOutcomeIsUncertainAndNeverRefunded() {
        row.setState("RESERVED");row.setStage("MODEL_STARTED");
        service.runDue();
        verifyNoInteractions(redis);
        verify(mapper).complete(eq(1L),anyString(),eq(1L),eq("UNCERTAIN"),eq("PROVIDER_OUTCOME_UNKNOWN"));
    }

    @Test void expiredReceiptWithActiveCounterNeverClaimsRefundOrNoActive() {
        when(redis.execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),anyList(),any(),any()))
                .thenReturn(-3L);
        service.runDue();
        verify(mapper).complete(eq(1L),anyString(),eq(1L),eq("UNCERTAIN"),eq("RECEIPT_EVIDENCE_EXPIRED"));
        verify(mapper,never()).complete(anyLong(),anyString(),anyLong(),eq("REFUNDED"),any());
    }

    @Test void evenPreReserveMissingEvidenceCannotPretendNoActiveCounter() {
        row.setState("PREPARED");row.setStage("PRE_RESERVE");row.setGeneration(null);
        when(redis.execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),anyList(),any(),any()))
                .thenReturn(-3L);
        service.runDue();
        verify(mapper).complete(eq(1L),anyString(),eq(1L),eq("UNCERTAIN"),eq("RECEIPT_EVIDENCE_EXPIRED"));
    }

    @Test void originalDayGoneResolvesWithoutCurrentDayAllowance() {
        when(redis.execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),anyList(),any(),any()))
                .thenReturn(3L);
        service.runDue();
        verify(mapper).complete(eq(1L),anyString(),eq(1L),eq("RESOLVED_NO_ACTIVE_RESERVATION"),isNull());
    }

    @Test void lostClaimCannotRunRedisOrFinalize() {
        when(mapper.claim(anyLong(),anyLong(),anyString())).thenReturn(0);
        service.runDue();
        verifyNoInteractions(redis);
        verify(mapper,never()).complete(anyLong(),anyString(),anyLong(),anyString(),any());
    }

    @Test void outageRemainsDueAndDoesNotStarveNextRow() {
        AiQuotaReservationRecord second=new AiQuotaReservationRecord();second.setId(2L);second.setFenceVersion(0L);
        second.setState("RESERVED");second.setStage("INPUT_REVIEW_STARTED");
        when(mapper.selectDue(100)).thenReturn(Arrays.asList(row,second));
        when(mapper.claim(eq(2L),eq(0L),anyString())).thenReturn(1);
        when(mapper.selectById(2L)).thenReturn(second);
        when(redis.execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),anyList(),any(),any()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        service.runDue();
        verify(mapper).retry(eq(1L),anyString(),eq(1L),eq("RECOVERY_UNAVAILABLE"));
        verify(mapper).complete(eq(2L),anyString(),eq(0L),eq("UNCERTAIN"),eq("PROVIDER_OUTCOME_UNKNOWN"));
    }

    @Test void retainedDecisionHasNoRedisEffect() {
        when(mapper.recordOutcome(RECEIPT,"RETAINED","INPUT_BLOCKED")).thenReturn(1);
        service.retain(RECEIPT,"INPUT_BLOCKED");
        verifyNoInteractions(redis);
    }

    @Test void completedNonceReplyFinalizesUnderCurrentFence() {
        when(redis.execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),anyList(),any(),any()))
                .thenReturn(2L);
        service.runDue();
        verify(mapper).complete(eq(1L),anyString(),eq(1L),eq("REFUNDED"),isNull());
    }

    private com.chengyinhub.business.domain.NpcAsrIntent originalAsrProof(String state) {
        row.setMemberId(7L);row.setBizType("npc_shop_chat");row.setPool("gen");row.setOriginalDay("20261013");
        row.setNonce("01234567890123456789012345678901");row.setUsedAfterReserve(1L);
        row.setState("RESERVED");row.setStage("ASR_STARTED");
        when(mapper.selectByReceipt(RECEIPT)).thenReturn(row);when(mapper.selectByReceiptForUpdate(RECEIPT)).thenReturn(row);
        var original=new com.chengyinhub.business.domain.NpcAsrIntent();original.setId(2L);original.setUserId(7L);
        original.setRequestId("e2596106-82d9-4a4f-9ff2-058414599f90");original.setNodeId(21L);original.setMerchantMemberId(41L);
        original.setClaimToken("01234567-8901-2345-6789-012345678901");original.setState(state);
        original.setQuotaReservation(row.getCounterKey()+"|"+row.getNonce());
        when(asrIntents.selectForUpdate(7L,original.getRequestId())).thenReturn(original);
        when(asrIntents.selectQuotaRefundProof(7L,original.getRequestId())).thenReturn(original);
        return original;
    }
    @Test void asrHandoffAcceptsOnlyTheOriginalMemberNodeShopAndHeldReceiptEvenAfterLeaseExpiry() {
        var original=originalAsrProof("RECEIVED");original.setHandoffStarted(true);original.setTextAdmitted(true);original.setTranscript("原转写");
        row.setState("UNCERTAIN");
        var handoff=new NpcAsrQuotaHandoff(7L,original.getRequestId(),21L,41L,original.getQuotaReservation());
        assertDoesNotThrow(()->service.requireNpcAsrHandoff(handoff));verifyNoInteractions(redis);
        assertThrows(IllegalStateException.class,()->service.requireNpcAsrHandoff(
                new NpcAsrQuotaHandoff(7L,original.getRequestId(),21L,42L,original.getQuotaReservation())));
        original.setQuotaReservation(null);assertThrows(IllegalStateException.class,()->service.requireNpcAsrHandoff(handoff));
    }
    @Test void asrStartedRecoveryNeverUsesLeaseExpiryAsNotSentProof() {
        originalAsrProof("UNKNOWN");service.runDue();
        verifyNoInteractions(redis);verify(mapper).complete(eq(1L),anyString(),eq(1L),eq("UNCERTAIN"),eq("PROVIDER_OUTCOME_UNKNOWN"));
        verify(mapper,never()).requestAsrNotSentRefund(anyString(),anyLong());
    }
    @Test void originalNotSentProofCompensatesExactReceiptAndClearsOnlyItsPendingFlag() {
        var original=originalAsrProof("NOT_SENT");original.setQuotaRefundPending(true);
        when(mapper.requestAsrNotSentRefund(RECEIPT,0L)).thenAnswer(call->{row.setState("REFUND_DUE");row.setStage("ASR_NOT_SENT");return 1;});
        when(redis.execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),anyList(),any(),any())).thenReturn(2L);
        when(mapper.complete(anyLong(),anyString(),anyLong(),anyString(),nullable(String.class)))
                .thenAnswer(call->{row.setState(call.getArgument(3));return 1;});
        when(asrIntents.completeQuotaRefund(7L,original.getRequestId(),original.getQuotaReservation()))
                .thenAnswer(call->{original.setQuotaRefundPending(false);return 1;});
        service.refundNpcAsrNotSent(7L,original.getRequestId());assertFalse(original.isQuotaRefundPending());assertEquals("REFUNDED",row.getState());
        service.refundNpcAsrNotSent(7L,original.getRequestId());
        verify(redis,times(1)).execute(any(RedisScript.class),any(RedisSerializer.class),any(RedisSerializer.class),
                eq(Arrays.<Object>asList(row.getCounterKey(),row.getCounterKey()+":generation",RECEIPT)),eq("ASR_NOT_SENT"),eq("original-generation"));
    }
    @Test void refundSqlFailureKeepsOriginalAsrPendingAndDoesNotTouchRedis() {
        var original=originalAsrProof("NOT_SENT");original.setQuotaRefundPending(true);
        doThrow(new IllegalStateException("controlled refund intent SQL failure")).when(mapper).requestAsrNotSentRefund(RECEIPT,0L);
        assertThrows(IllegalStateException.class,()->service.refundNpcAsrNotSent(7L,original.getRequestId()));
        assertTrue(original.isQuotaRefundPending());verifyNoInteractions(redis);verify(asrIntents,never()).completeQuotaRefund(anyLong(),anyString(),anyString());
    }
    @Test void unknownOrHandoffCannotBeReclassifiedAsNotSentToRefund() {
        var original=originalAsrProof("UNKNOWN");original.setQuotaRefundPending(true);
        assertThrows(IllegalStateException.class,()->service.refundNpcAsrNotSent(7L,original.getRequestId()));
        original.setState("NOT_SENT");original.setHandoffStarted(true);
        assertThrows(IllegalStateException.class,()->service.refundNpcAsrNotSent(7L,original.getRequestId()));
        verifyNoInteractions(redis);verify(mapper,never()).requestAsrNotSentRefund(anyString(),anyLong());
    }

}
