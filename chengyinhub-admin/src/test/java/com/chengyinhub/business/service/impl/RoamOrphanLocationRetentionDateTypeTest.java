package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿漫游位置清理:row.collectedAt 来自 resultType=map 的 FOR UPDATE 查询,MySQL Connector/J 8 回 LocalDateTime。
 * 旧代码 (Date) 强转在真库下抛 ClassCastException,清理任务整批失败。
 */
class RoamOrphanLocationRetentionDateTypeTest {
    private static final long ROW_ID = 5L;
    private static final long MEMBER = 9L;
    private static final LocalDateTime CUTOFF_LOCAL = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final Date CUTOFF = Date.from(CUTOFF_LOCAL.atZone(ZoneId.systemDefault()).toInstant());

    private RoamMapper roam;
    private RoamOrphanLocationRetentionService service;
    private PiiCryptoService previousCrypto;

    @BeforeEach
    void setUp() {
        previousCrypto = PiiCryptoService.getInstance();
        new PiiCryptoService(Base64.getEncoder().encodeToString(new byte[32]), "orphan-retention-test").register();
        roam = mock(RoamMapper.class);
        RoamOrphanLocationRetentionMapper orphan = mock(RoamOrphanLocationRetentionMapper.class);
        Map<String, Object> candidate = new HashMap<>();
        candidate.put("id", ROW_ID);
        candidate.put("memberId", MEMBER);
        candidate.put("sessionId", null);
        when(orphan.tiles(any(), eq(10))).thenReturn(Collections.singletonList(candidate));
        when(roam.clearSessionTileLocation(eq(ROW_ID), eq(MEMBER), anyString())).thenReturn(1);
        service = new RoamOrphanLocationRetentionService(roam, orphan);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", tx);
    }

    @AfterEach
    void restoreCrypto() {
        ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previousCrypto);
    }

    private void lockedRow(Object collectedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("collectedAt", collectedAt);
        row.put("location", "wtw3sjq");
        when(roam.selectCleanupTileForUpdate(ROW_ID, MEMBER, null)).thenReturn(row);
    }

    @Test
    void clearsExpiredOrphanWithDateCollectedAt() {
        lockedRow(new Date(CUTOFF.getTime() - 86_400_000L));

        assertEquals(1, service.clearTiles(CUTOFF, 10));
        verify(roam).clearSessionTileLocation(eq(ROW_ID), eq(MEMBER), anyString());
    }

    @Test
    void clearsExpiredOrphanWithLocalDateTimeCollectedAtFromMysqlDriver() {
        lockedRow(CUTOFF_LOCAL.minusDays(1));

        assertEquals(1, service.clearTiles(CUTOFF, 10));
        verify(roam).clearSessionTileLocation(eq(ROW_ID), eq(MEMBER), anyString());
    }

    @Test
    void keepsFreshOrphanWithLocalDateTimeCollectedAt() {
        lockedRow(CUTOFF_LOCAL.plusDays(1));

        assertEquals(0, service.clearTiles(CUTOFF, 10));
        verify(roam, never()).clearSessionTileLocation(anyLong(), anyLong(), anyString());
    }
}
