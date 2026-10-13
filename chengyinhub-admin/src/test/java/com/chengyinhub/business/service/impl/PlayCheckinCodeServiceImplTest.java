package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.IChapterMerchantNodeService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayCheckinCodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayCheckinCodeServiceImplTest {

    private static final String SECRET = "play-checkin-test-secret";
    private static final Long MERCHANT = 8801L;
    private static final Long NODE_ID = 77L;

    private IChapterMerchantNodeService chapterNodes;
    private ICmsTopicNodeService nodes;
    private GameSessionRuntimeMapper gameSessions;
    private RedisCache redis;
    private IQrcodeService qrcodeService;
    private PlayCheckinCodeServiceImpl service;
    private static final String KEY = "play:checkin:live:77";
    private AtomicReference<String> live;

    @BeforeEach
    void setUp() {
        chapterNodes = mock(IChapterMerchantNodeService.class);
        nodes = mock(ICmsTopicNodeService.class);
        gameSessions = mock(GameSessionRuntimeMapper.class);
        redis = mock(RedisCache.class);
        qrcodeService = mock(IQrcodeService.class);
        service = new PlayCheckinCodeServiceImpl(chapterNodes, nodes, gameSessions, redis, qrcodeService, SECRET);
        when(qrcodeService.generateQrcode(anyString(), eq(300), eq(300))).thenReturn("https://oss/live-qr.png");
        when(chapterNodes.posterCodeNode(MERCHANT, NODE_ID)).thenReturn(node());
        live = new AtomicReference<>();
        doAnswer(call -> { live.set(call.getArgument(1)); return null; })
                .when(redis).setCacheObject(eq(KEY), anyString(), anyInt(), eq(TimeUnit.SECONDS));
        when(redis.getCacheObject(KEY)).thenAnswer(call -> live.get());
        when(redis.compareAndDelete(eq(KEY), anyString()))
                .thenAnswer(call -> live.compareAndSet(call.getArgument(1), null));
        when(redis.compareAndSet(eq(KEY), anyString(), anyString(), anyInt(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(call -> live.compareAndSet(call.getArgument(1), call.getArgument(2)));
    }

    @Test
    void issueStoresLiveCodeAndReturnsQr() {
        IPlayCheckinCodeService.Issued issued = service.issue(MERCHANT, NODE_ID);

        assertEquals(NODE_ID, issued.nodeId);
        assertEquals("毛孩子咖啡", issued.nodeName);
        assertEquals(60_000L, issued.ttlMs);
        assertEquals("https://oss/live-qr.png", issued.qrcodeUrl);
        VerifyDynCodeUtil.Result parsed = VerifyDynCodeUtil.verify(issued.code, System.currentTimeMillis(), SECRET);
        assertTrue(parsed.valid);
        assertEquals(NODE_ID, parsed.regId);
        assertEquals(PlayCheckinCodeServiceImpl.TYPE, parsed.type);
        verify(redis).setCacheObject(eq("play:checkin:live:77"), eq(issued.code), eq(60), eq(TimeUnit.SECONDS));
    }

    @Test
    void consumeSucceedsOnceThenReportsUsed() {
        IPlayCheckinCodeService.Issued issued = service.issue(MERCHANT, NODE_ID);

        IPlayCheckinCodeService.ConsumeResult first = service.consume(issued.code);
        IPlayCheckinCodeService.ConsumeResult second = service.consume(issued.code);

        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, first.kind);
        assertEquals(NODE_ID, first.nodeId);
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.USED, second.kind);
        assertEquals("这张码已用过", second.message);
        assertNull(second.nodeId);
    }

    @Test
    void expiredSignedCodeDoesNotFallBackToStaticLookup() {
        String expired = VerifyDynCodeUtil.issue(NODE_ID, PlayCheckinCodeServiceImpl.TYPE,
                System.currentTimeMillis() - 120_000L, 60_000L, 9L, SECRET);

        IPlayCheckinCodeService.ConsumeResult result = service.consume(expired);

        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.EXPIRED, result.kind);
        assertEquals("这张码已过期", result.message);
        verify(redis, never()).compareAndDelete(anyString(), anyString());
    }

    @Test
    void unsignedStaticCodeIsInvalidSoCallerCanFallBack() {
        IPlayCheckinCodeService.ConsumeResult result = service.consume("0f1e2d3c4b5a69788796a5b4c3d2e1f0");

        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.INVALID, result.kind);
        assertEquals("无效的打卡码", result.message);
    }

    @Test
    void rotatingIssueInvalidatesPreviousLiveCode() {
        IPlayCheckinCodeService.Issued first = service.issue(MERCHANT, NODE_ID);
        IPlayCheckinCodeService.Issued second = service.issue(MERCHANT, NODE_ID);
        assertNotEquals(first.code, second.code);

        IPlayCheckinCodeService.ConsumeResult stale = service.consume(first.code);

        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.USED, stale.kind);
    }

    @Test
    void gameStationOperatorCanIssueWithoutPosterOwnership() {
        when(chapterNodes.posterCodeNode(MERCHANT, NODE_ID))
                .thenThrow(new IllegalStateException("点位不存在或无权操作"));
        when(gameSessions.countMerchantOperableStation(MERCHANT, NODE_ID)).thenReturn(1);
        when(nodes.selectCmsTopicNodeById(NODE_ID)).thenReturn(node());

        IPlayCheckinCodeService.Issued issued = service.issue(MERCHANT, NODE_ID);

        assertEquals(NODE_ID, issued.nodeId);
        assertNotNull(issued.code);
    }

    @Test
    void inspectionDoesNotConsumeTheCode() {
        String code = service.issue(MERCHANT, NODE_ID).code;
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.inspect(code).kind);
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.inspect(code).kind);
        verify(redis, never()).compareAndDelete(anyString(), anyString());
        verify(redis, never()).compareAndSet(anyString(), anyString(), anyString(), anyInt(), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void databaseRollbackRestoresCodeAndCommitConsumesIt() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:scan_code_rollback;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("create table if not exists scan_progress(id int primary key)");
        jdbc.update("delete from scan_progress");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        String code = service.issue(MERCHANT, NODE_ID).code;
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.consume(code).kind);
            assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.USED, service.inspect(code).kind);
            jdbc.update("insert into scan_progress values (1)");
            throw new IllegalStateException("模拟奖励落库失败");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from scan_progress", Integer.class));
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.inspect(code).kind);
        tx.execute(status -> {
            assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.consume(code).kind);
            jdbc.update("insert into scan_progress values (1)");
            return null;
        });
        assertEquals(1, jdbc.queryForObject("select count(*) from scan_progress", Integer.class));
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.USED, service.consume(code).kind);
    }

    @Test
    void rollbackCannotReplaceARefreshedCode() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:scan_code_rotation", "sa", "")));
        String old = service.issue(MERCHANT, NODE_ID).code;
        String fresh = tx.execute(status -> {
            assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.consume(old).kind);
            String code = service.issue(MERCHANT, NODE_ID).code;
            status.setRollbackOnly();
            return code;
        });
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.USED, service.inspect(old).kind);
        assertEquals(IPlayCheckinCodeService.ConsumeResult.Kind.OK, service.inspect(fresh).kind);
    }

    @Test
    void concurrentScansHaveOnlyOneWinner() throws Exception {
        String code = service.issue(MERCHANT, NODE_ID).code;
        java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> scan = () -> {
                TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(
                        new DriverManagerDataSource("jdbc:h2:mem:scan_code_concurrent", "sa", "")));
                return tx.execute(status -> service.consume(code).kind == IPlayCheckinCodeService.ConsumeResult.Kind.OK);
            };
            java.util.concurrent.Future<Boolean> first = pool.submit(scan);
            java.util.concurrent.Future<Boolean> second = pool.submit(scan);
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setName("毛孩子咖啡");
        node.setMerchantMemberId(MERCHANT);
        return node;
    }
}
