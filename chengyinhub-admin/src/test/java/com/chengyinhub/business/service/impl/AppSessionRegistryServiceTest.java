package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class AppSessionRegistryServiceTest {

    private RedisTemplate<Object, Object> redisTemplate;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> values;
    private SetOperations<Object, Object> sets;
    private AppSessionRegistryServiceImpl registry;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.opsForSet()).thenReturn(sets);
        registry = new AppSessionRegistryServiceImpl();
        ReflectionTestUtils.setField(registry, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(registry, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void revokeAllAdvancesEpochBeforeDeletingIndexedTokenEntries() {
        String epochKey = CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42";
        String indexKey = CacheConstants.LOGIN_APP_MEMBER_TOKENS_KEY + "42";
        when(values.increment(epochKey)).thenReturn(2L);
        when(sets.members(indexKey)).thenReturn(new HashSet<>(Arrays.asList("token-a", "token-b")));

        registry.revokeAll(42L);

        InOrder order = inOrder(values, sets, redisTemplate);
        order.verify(values).setIfAbsent(epochKey, "1");
        order.verify(values).increment(epochKey);
        order.verify(sets).members(indexKey);
        order.verify(redisTemplate).delete(anyCollection());
        order.verify(redisTemplate).delete(indexKey);
    }

    @Test
    void currentEpochIsCreatedOnceAndReadBackFromRedis() {
        when(stringRedisTemplate.execute(any(), eq(java.util.Arrays.asList(
                CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42",
                CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42")), eq("1"))).thenReturn(7L);

        assertEquals(7L, registry.currentOrCreateEpoch(42L));

        verify(stringRedisTemplate).execute(any(), eq(java.util.Arrays.asList(
                CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42",
                CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42")), eq("1"));
        verify(values, never()).get(CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42");
    }

    @Test
    void tokenRegistrationFailsClosedWhenIndexCannotReceiveAbsoluteTtl() {
        String indexKey = CacheConstants.LOGIN_APP_MEMBER_TOKENS_KEY + "42";
        when(redisTemplate.expire(indexKey, 60_000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertThrows(ServiceException.class,
                () -> registry.registerToken(42L, "token-a", 60_000L));

        verify(sets).remove(indexKey, "token-a");
    }

    @Test
    void revokeAllRedisFailureStopsBeforeTokenCleanup() {
        String epochKey = CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42";
        when(values.increment(epochKey)).thenThrow(new IllegalStateException("redis unavailable"));

        assertThrows(IllegalStateException.class, () -> registry.revokeAll(42L));

        verify(sets, never()).members(CacheConstants.LOGIN_APP_MEMBER_TOKENS_KEY + "42");
        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void firstRevocationAdvancesPastTheInitialLoginEpoch() {
        String epochKey = CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42";
        when(values.increment(epochKey)).thenReturn(2L);

        registry.revokeAll(42L);

        InOrder order = inOrder(values);
        order.verify(values).setIfAbsent(epochKey, "1");
        order.verify(values).increment(epochKey);
    }

    @Test
    void memberMutationBlocksNewEpochReadsUntilReleased() {
        when(stringRedisTemplate.execute(any(), any(), eq("1"))).thenReturn(-1L);

        assertThrows(ServiceException.class, () -> registry.currentOrCreateEpoch(42L));

        verify(values, never()).get(CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42");
    }

    @Test
    void epochVerificationAtomicallyChecksMutationGuardAndEpoch() {
        java.util.List<String> keys = java.util.Arrays.asList(
                CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42",
                CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42");
        when(stringRedisTemplate.execute(any(), eq(keys))).thenReturn(7L);

        assertEquals(true, registry.isCurrentEpoch(42L, 7L));

        verify(stringRedisTemplate).execute(any(), eq(keys));
        verify(stringRedisTemplate, never()).hasKey(CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42");
        verify(values, never()).get(CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42");
    }

    @Test
    void beginMutationAcquiresTimedGuardBeforeRevokingSessions() {
        String mutationKey = CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42";
        String epochKey = CacheConstants.LOGIN_APP_MEMBER_EPOCH_KEY + "42";
        when(values.setIfAbsent(eq(mutationKey), anyString(), eq(30L), eq(TimeUnit.MINUTES))).thenReturn(true);
        when(values.increment(epochKey)).thenReturn(2L);

        String mutationId = registry.beginMemberMutation(42L);

        verify(values).setIfAbsent(mutationKey, mutationId, 30L, TimeUnit.MINUTES);
        verify(values).increment(epochKey);
    }

    @Test
    void finishMutationUsesCompareAndDeleteScript() {
        when(stringRedisTemplate.execute(any(), eq(java.util.Collections.singletonList(
                CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42")), eq("mutation-id"))).thenReturn(1L);

        registry.finishMemberMutation(42L, "mutation-id");

        verify(stringRedisTemplate).execute(any(), eq(java.util.Collections.singletonList(
                CacheConstants.LOGIN_APP_MEMBER_MUTATION_KEY + "42")), eq("mutation-id"));
    }

    @Test
    void mutationGuardIsReleasedOnlyAfterDatabaseTransactionCompletes() {
        when(stringRedisTemplate.execute(any(), any(), eq("mutation-id"))).thenReturn(1L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            registry.finishMemberMutationAfterTransaction(42L, "mutation-id");

            verify(stringRedisTemplate, never()).execute(any(), any(), anyString());
            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }

            verify(stringRedisTemplate, times(1)).execute(any(), any(), eq("mutation-id"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
