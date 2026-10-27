package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.web.service.play.PlayBasicIntentApplication;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 命令边界逻辑测试；不证明 MySQL 锁、事务或 HTTP 鉴权。 */
class PlayCommandIntentTest {
    private ApiPlayProgressController controller;
    private GameSessionRuntimeMapper receipts;
    private TopicRouteSessionMapper routes;
    private TopicRouteSession session;
    private final Map<String, GameCommandReceipt> saved = new HashMap<>();
    private final AtomicInteger effects = new AtomicInteger();
    private ICmsRegistrationService registrations;

    @BeforeEach void setup() {
        saved.clear(); effects.set(0);
        controller = spy(new ApiPlayProgressController()); doReturn(7L).when(controller).getAppUserId();
        receipts = mock(GameSessionRuntimeMapper.class); routes = mock(TopicRouteSessionMapper.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        registrations = mock(ICmsRegistrationService.class);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCommandReceiptMapper", receipts);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "playCommandRouteMapper", routes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsTopicNodeService", nodes);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsActivityService", activities);
        com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.setField(controller, "cmsRegistrationService", registrations);
        CmsTopicNode node = new CmsTopicNode(); node.setId(11L); node.setTopicId(10L);
        when(nodes.selectCmsTopicNodeById(11L)).thenReturn(node);
        CmsActivity activity = new CmsActivity(); activity.setId(20L); activity.setTopicId(10L);
        when(activities.selectCmsActivityById(20L)).thenReturn(activity);
        CmsRegistration ticket = new CmsRegistration(); ticket.setMemberId(7L); ticket.setOwnerType(2L);
        ticket.setOwnerId(20L); ticket.setPaymentStatus(2); ticket.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(ticket));
        session = new TopicRouteSession(); session.setId(99L); session.setVersion(3); session.setMemberId(7L); session.setActivityId(20L); session.setTopicId(10L); session.setStatus(TopicRouteSession.STATUS_RUNNING); session.setPlayInstanceId("default");
        when(routes.selectCurrentByOwner(20L, 10L, 7L)).thenReturn(session);
        when(routes.selectAccessibleByIdForUpdate(99L, 7L)).thenReturn(session);
        when(receipts.selectReceiptForUpdate(eq(20L), eq(7L), anyString())).thenAnswer(i -> saved.get(i.getArgument(2)));
        when(receipts.selectReceipt(eq(20L), eq(7L), anyString())).thenAnswer(i -> saved.get(i.getArgument(2)));
        when(receipts.insertReceiptClaim(any())).thenAnswer(i -> {
            GameCommandReceipt row = i.getArgument(0);
            if (saved.containsKey(row.getRequestId())) return 0;
            row.setId((long) saved.size() + 1); saved.put(row.getRequestId(), row); return 1;
        });
        when(receipts.completeReceipt(any())).thenReturn(1);
    }

    private AjaxResult command(String id, Integer expected, String answer, Supplier<AjaxResult> execute) {
        return com.chengyinhub.web.controller.api.PlayNodeAccessTestFixtures.invokeMethod(controller, "executeBasicIntent", controller.getAppUserId(), "ANSWER", "20", null, "11",
                id, expected, null, 99L, null, null, Collections.singletonList(answer), (java.util.function.Function<com.chengyinhub.web.service.play.PlayOriginalRun, AjaxResult>) originalSession -> execute.get());
    }

    /** Real HTTP-method/application proxies with mocked JDBC/shared logic only, not a MySQL refresh proof. */
    @Test void actionContextAllowsSharedRefreshButActionReceiptKeepsItsReadOnlyTransaction() throws Exception {
        var sharedMethod = com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl.class
                .getMethod("sharedRouteSnapshot", Long.class, Long.class, Long.class, Long.class);
        var applicationMethod = PlayBasicIntentApplication.class
                .getMethod("basicActionContext", Long.class, String.class, String.class, String.class);
        Transactional sharedTx = sharedMethod.getAnnotation(Transactional.class);
        Transactional applicationTx = applicationMethod.getAnnotation(Transactional.class);
        assertNotNull(sharedTx); assertNotNull(applicationTx);
        assertFalse(applicationTx.readOnly());
        assertEquals(sharedTx.propagation(), applicationTx.propagation());
        assertEquals(sharedTx.isolation(), applicationTx.isolation());
        assertArrayEquals(sharedTx.rollbackFor(), applicationTx.rollbackFor());
        assertTrue(PlayBasicIntentApplication.class.getMethod("basicActionReceipt", Long.class,
                String.class, String.class, String.class, String.class).getAnnotation(Transactional.class).readOnly());

        java.sql.Connection connection = mock(java.sql.Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        javax.sql.DataSource dataSource = mock(javax.sql.DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var transactions = new DataSourceTransactionManager(dataSource);
        IGameSessionRuntimeService shared = mock(IGameSessionRuntimeService.class);
        when(shared.sharedRouteSnapshot(7L, 10L, 20L, 11L)).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                    "the real shared refresh joins this transaction and may UPDATE/CAS zero-HP state");
            assertNull(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), "retain A's DEFAULT isolation");
            return com.chengyinhub.common.utils.JsonUtils.getObjectMapper().createObjectNode()
                    .put("runtimeInstance", "shared:77:4").put("authorityEpoch", 4);
        });
        // Boundary double uses the actual A entry's transaction attributes: REQUIRED must join,
        // rather than opening a replacement transaction to hide the caller's read-only mistake.
        var sharedAttribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(sharedMethod,
                com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl.class);
        assertNotNull(sharedAttribute);
        ProxyFactory sharedProxy = new ProxyFactory(shared); sharedProxy.setInterfaces(IGameSessionRuntimeService.class);
        sharedProxy.addAdvice(new TransactionInterceptor(transactions, (method, type) -> sharedAttribute));
        PlayNodeAccessTestFixtures.setField(controller, "gameSessionRuntimeService", sharedProxy.getProxy());
        GameSession game = new GameSession(); game.setId(77L); game.setRevision(3);
        when(receipts.selectSessionByActivityForUpdate(20L)).thenReturn(game);
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return 7L; })
                .when(controller).getAppUserId();
        PlayNodeAccessTestFixtures.installTransactions(controller, transactions);
        AjaxResult context = controller.basicActionContext("20", null, "11");
        assertEquals(200, context.get("code"));
        // HTTP 出口把 Long 会话号写成字符串(防 JS 精度丢失);服务端内部比对仍用 Long。
        assertEquals(Map.of("routeSessionId", "77", "expectedRouteVersion", 3,
                "runtimeInstance", "shared:77:4", "authorityEpoch", 4), context.get("data"));
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return null;
        }).when(receipts).selectReceipt(eq(20L), eq(7L), anyString());
        assertEquals(520, controller.basicActionReceipt("20", null, "11", "original-unknown").get("code"));
        verify(shared, times(1)).sharedRouteSnapshot(7L, 10L, 20L, 11L);
        verify(dataSource, times(2)).getConnection(); // one context transaction + one receipt transaction; A joins
        verify(connection, times(2)).commit();
        verify(connection, never()).rollback();
        verifyNoInteractions(routes);
        verify(receipts, never()).insertReceiptClaim(any());
        verify(receipts, never()).completeReceipt(any());
        assertTrue(saved.isEmpty());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** Separate plain/locking objects model MyBatis statement-key identity; no MySQL execution claim. */
    @Test void sharedRefreshUsesPostLockVersionAndRejectsTheOldExpectedBeforeClaiming() {
        GameSession before = new GameSession(); before.setId(77L); before.setRevision(3);
        GameSession locked = new GameSession(); locked.setId(77L); locked.setRevision(3);
        List<String> reads = new ArrayList<>();
        when(receipts.selectSessionByActivity(20L)).thenAnswer(call -> { reads.add("plain:3"); return before; });
        when(receipts.selectSessionByActivityForUpdate(20L)).thenAnswer(call -> {
            reads.add("locked:" + locked.getRevision()); return locked;
        });
        IGameSessionRuntimeService shared = mock(IGameSessionRuntimeService.class);
        when(shared.sharedRouteSnapshot(7L, 10L, 20L, 11L)).thenAnswer(call -> {
            // A refresh returns its locked object after bump; detached ordinary-read references stay old.
            locked.setRevision(4);
            reads.add("refresh:4");
            return com.chengyinhub.common.utils.JsonUtils.getObjectMapper().createObjectNode()
                    .put("gameSessionId", 77L).put("runtimeInstance", "game:77").put("authorityEpoch", 2);
        });
        PlayNodeAccessTestFixtures.setField(controller, "gameSessionRuntimeService", shared);
        AjaxResult response = controller.basicActionContext("20", null, "11");
        assertEquals(200, response.get("code"));
        assertEquals(Map.of("routeSessionId", "77", "expectedRouteVersion", 4,
                "runtimeInstance", "game:77", "authorityEpoch", 2), response.get("data"));
        assertEquals(List.of("refresh:4", "locked:4"), reads);
        assertNotSame(before, locked);
        assertEquals(3, before.getRevision(), "cache invalidation never mutates the held old Java reference");
        java.util.function.Function<com.chengyinhub.web.service.play.PlayOriginalRun, AjaxResult> effect = originalSession -> { effects.incrementAndGet(); return AjaxResult.success("done"); };
        AjaxResult stale = PlayNodeAccessTestFixtures.invokeMethod(controller, "executeBasicIntent", 7L,
                "ANSWER", "20", null, "11", "old-expected", 3, null, 77L, "game:77", 2,
                Collections.singletonList("A"), effect);
        assertEquals(409, stale.get("code"));
        assertEquals(true, stale.get("intentNotExecuted"));
        assertEquals(0, effects.get()); assertTrue(saved.isEmpty());
        verify(receipts, never()).insertReceiptClaim(any());
        verify(receipts, never()).completeReceipt(any());
        AjaxResult applied = PlayNodeAccessTestFixtures.invokeMethod(controller, "executeBasicIntent", 7L,
                "ANSWER", "20", null, "11", "post-refresh-expected", 4, null, 77L, "game:77", 2,
                Collections.singletonList("A"), effect);
        assertEquals(200, applied.get("code")); assertEquals(1, effects.get()); assertEquals(1, saved.size());
    }

    @Test void replayPrecedesCurrentVersionAndChangedInputOrExpectedConflicts() {
        AjaxResult first = command("same", 3, "A", () -> { effects.incrementAndGet(); return AjaxResult.success("done"); });
        session.setVersion(8);
        assertEquals(first, command("same", 3, "A", () -> fail("must replay")));
        assertEquals(409, command("same", 8, "A", () -> fail("changed expected" )).get("code"));
        assertEquals(409, command("same", 3, "B", () -> fail("changed answer")).get("code"));
        assertEquals(1, effects.get());
    }

    @Test void wrongAnswerIsTerminalOnceAndSameAnswerNewAttemptRunsAgain() {
        Supplier<AjaxResult> wrong = () -> { effects.incrementAndGet(); return AjaxResult.error("答案不正确", Collections.singletonMap("answerWrong", true)); };
        AjaxResult first = command("attempt-1", 3, "wrong", wrong);
        assertEquals(first, command("attempt-1", 3, "wrong", wrong));
        command("attempt-2", 3, "wrong", wrong);
        assertEquals(2, effects.get());
        assertEquals(2, saved.size());
    }

    @Test void staleOrMissingIdentityHasNoEffectsAndNoClaim() {
        assertEquals(409, command("stale", 2, "A", () -> fail("stale write")).get("code"));
        assertNotEquals(200, command(null, 3, "A", () -> fail("missing id")).get("code"));
        assertNotEquals(200, command("missing-version", null, "A", () -> fail("missing version")).get("code"));
        assertTrue(saved.isEmpty()); verify(receipts, never()).insertReceiptClaim(any());
    }

    @Test void receiptReadRequiresActualOwnerAndAbsentReceiptRemainsUnknown() {
        assertEquals(520, controller.basicActionReceipt("20", null, "11", "unknown").get("code"));
        command("done", 3, "A", () -> AjaxResult.success("done"));
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertNotEquals(200, controller.basicActionReceipt("20", null, "11", "done").get("code"));
    }

    @Test void ordinaryFreeRegistrationKeepsOriginalScopeButUnpaidExploreAndForeignRowsCannotReadReceipts() {
        CmsRegistration registration = new CmsRegistration(); registration.setMemberId(7L); registration.setOwnerType(2L);
        registration.setOwnerId(20L); registration.setRegistrationStatus(2); registration.setPurchaseKind(1); registration.setPaymentStatus(0);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
        assertEquals(200, command("free", 3, "A", () -> AjaxResult.success("done")).get("code"));
        assertEquals(200, controller.basicActionReceipt("20", null, "11", "free").get("code"));
        registration.setPurchaseKind(3);
        assertNotEquals(200, controller.basicActionReceipt("20", null, "11", "free").get("code"));
        registration.setPaymentStatus(2); registration.setMemberId(8L);
        assertNotEquals(200, controller.basicActionReceipt("20", null, "11", "free").get("code"));
    }

    @Test void concurrentWinnerIsRecheckedAfterBusinessLockBeforeVersionConflict() {
        command("winner", 3, "A", () -> AjaxResult.success("done"));
        GameCommandReceipt committed = saved.values().iterator().next();
        session.setVersion(4);
        when(receipts.selectReceiptForUpdate(eq(20L), eq(7L), anyString())).thenReturn(null, committed);
        assertEquals(200, command("winner", 3, "A", () -> fail("waiter must replay")).get("code"));
    }
}
