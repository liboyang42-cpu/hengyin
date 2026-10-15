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
    private AiQuotaReservationRecord row;
    private static final String RECEIPT="ai:gen:reservation:ai:gen:limit:gen:7:20261013:01234567890123456789012345678901";

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        mapper=mock(AiQuotaReservationMapper.class); redis=mock(RedisTemplate.class);
        transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new AiQuotaRecoveryService(mapper,redis,transactions);
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
}
