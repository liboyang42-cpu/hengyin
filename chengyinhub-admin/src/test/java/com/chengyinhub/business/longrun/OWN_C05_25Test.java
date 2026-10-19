package com.chengyinhub.business.longrun;

import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
import com.chengyinhub.business.service.IPlayerWithdrawalCooldownService;
import com.chengyinhub.business.service.impl.PlayerWithdrawalCooldownServiceImpl;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.mapper.SysConfigMapper;
import com.chengyinhub.system.service.impl.SysConfigServiceImpl;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Actual services and Spring synchronization; mocked storage is not DB rollback/concurrency evidence. */
class OWN_C05_25Test {
    private static final String KEY = IPlayerWithdrawalCooldownService.CONFIG_KEY;
    private static final String CACHE_KEY = CacheConstants.SYS_CONFIG_KEY + KEY;

    private static class SynchronizationOnlyTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    private static class Harness {
        final SysConfigMapper configs = mock(SysConfigMapper.class);
        final RedisCache redis = mock(RedisCache.class);
        final PlayerWithdrawalCooldownMapper audits = mock(PlayerWithdrawalCooldownMapper.class);
        final AtomicReference<String> value = new AtomicReference<>("48");
        final SysConfigServiceImpl system = new SysConfigServiceImpl();
        final PlayerWithdrawalCooldownServiceImpl cooldown;
        final TransactionTemplate transaction = new TransactionTemplate(new SynchronizationOnlyTransactionManager());

        Harness() {
            when(configs.selectFundsPolicyConfigForUpdate(KEY)).thenAnswer(call -> {
                if (value.get() == null) return null;
                SysConfig row = new SysConfig();
                row.setConfigId(101L);
                row.setConfigKey(KEY);
                row.setConfigValue(value.get());
                return row;
            });
            when(configs.updateConfig(any(SysConfig.class))).thenAnswer(call -> {
                value.set(((SysConfig) call.getArgument(0)).getConfigValue());
                return 1;
            });
            when(audits.insertConfigChangeAudit(anyInt(), anyInt(), anyString(), anyLong())).thenReturn(1);
            ReflectionTestUtils.setField(system, "configMapper", configs);
            ReflectionTestUtils.setField(system, "redisCache", redis);
            cooldown = new PlayerWithdrawalCooldownServiceImpl(audits, system);
        }
    }

    @Test
    void readsLockedDatabaseBeforeAuditAndInvalidatesOnlyAfterCommit() {
        Harness h = new Harness();
        h.transaction.execute(status -> {
            assertEquals(48, h.cooldown.rewriteCooldownHours(24, "reason", 7L));
            verifyNoInteractions(h.redis);
            return null;
        });
        verify(h.redis).deleteObject(CACHE_KEY);
        InOrder ordered = inOrder(h.configs, h.audits);
        ordered.verify(h.configs).selectFundsPolicyConfigForUpdate(KEY);
        ordered.verify(h.audits).insertConfigChangeAudit(48, 24, "reason", 7L);
        ordered.verify(h.configs).selectFundsPolicyConfigForUpdate(KEY);
        ordered.verify(h.configs).updateConfig(any(SysConfig.class));
        ordered.verify(h.configs).selectFundsPolicyConfigForUpdate(KEY);
        verify(h.configs, never()).selectConfig(any(SysConfig.class));
        // A later committed rewrite uses the database's new value, never the old cache value.
        h.transaction.execute(status -> h.cooldown.rewriteCooldownHours(72, "next", 8L));
        verify(h.audits).insertConfigChangeAudit(24, 72, "next", 8L);
        verify(h.redis, times(2)).deleteObject(CACHE_KEY);
        verifyNoMoreInteractions(h.redis);
    }

    @Test
    void rollbackDoesNotPublishCacheAndAuditFailureStillStopsUpdate() {
        Harness h = new Harness();
        assertThrows(IllegalStateException.class, () -> h.transaction.execute(status -> {
            h.cooldown.rewriteCooldownHours(24, "reason", 7L);
            throw new IllegalStateException("force transaction rollback");
        }));
        verifyNoInteractions(h.redis);
        // The transaction manager has no database: no assertion pretends the mock table rolled back.
        Harness auditFailure = new Harness();
        when(auditFailure.audits.insertConfigChangeAudit(anyInt(), anyInt(), anyString(), anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> auditFailure.transaction.execute(status ->
                auditFailure.cooldown.rewriteCooldownHours(24, "reason", 7L)));
        verify(auditFailure.configs, never()).updateConfig(any(SysConfig.class));
        verifyNoInteractions(auditFailure.redis);
    }

    @Test
    void authorityIgnoresCacheEvenIfCommitInvalidationFailsAndKeepsDefaultAndValidation() {
        Harness h = new Harness();
        doThrow(new IllegalStateException("redis unavailable")).when(h.redis).deleteObject(CACHE_KEY);
        assertDoesNotThrow(() -> h.transaction.execute(status -> h.cooldown.rewriteCooldownHours(24, "reason", 7L)));
        assertEquals(24, h.cooldown.currentCooldownHours());
        h.value.set(null);
        assertEquals(48, h.cooldown.currentCooldownHours());
        for (String invalid : new String[] {"0", "-1", "bad"}) {
            h.value.set(invalid);
            assertThrows(IllegalStateException.class, h.cooldown::currentCooldownHours);
        }
        verify(h.redis).deleteObject(CACHE_KEY);
        verifyNoMoreInteractions(h.redis);
    }

    @Test
    void dedicatedMapperUsesUncachedCurrentLockRead() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("SysConfig", SysConfig.class);
        String resource = "mapper/system/SysConfigMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        MappedStatement statement = configuration.getMappedStatement(
                SysConfigMapper.class.getName() + ".selectFundsPolicyConfigForUpdate");
        String sql = statement.getBoundSql(KEY).getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        assertTrue(sql.contains("where config_key = ?"));
        assertTrue(sql.endsWith("for update"));
        assertFalse(statement.isUseCache());
        assertTrue(statement.isFlushCacheRequired());
    }
}
