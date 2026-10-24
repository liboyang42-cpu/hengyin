package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayBingoCard;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IPlayBingoService;
import com.chengyinhub.common.constant.PlayMode;
import com.chengyinhub.common.constant.RewardPolicy;
import com.chengyinhub.framework.config.DeadlockRetryConfig;
import com.chengyinhub.web.controller.api.ApiPlayProgressController;
import org.junit.jupiter.api.Test;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual application + Spring advice; JDBC/leaf effects are mocks, not MySQL/award evidence. */
class PlayCompletionLockFailureTest {
    @Test void attemptTransientFailuresEscapeAsTheOriginalException() {
        PlayNodeCompletionApplication completion = new PlayNodeCompletionApplication();
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        ReflectionTestUtils.setField(completion, "playAttemptLogMapper", attempts);
        for (TransientDataAccessException failure : lockFailures()) {
            doThrow(failure).when(attempts).insertPlayAttemptLog(any());
            assertSame(failure, assertThrows(failure.getClass(),
                    () -> completion.logAttempt(7L, node(), 500L, 1, 0, "wrong")));
        }
        verify(attempts, times(3)).insertPlayAttemptLog(any());
    }

    @Test void bingoTransientFailuresEscapeWithoutPublishingSuccessData() {
        PlayNodeCompletionApplication completion = new PlayNodeCompletionApplication();
        IPlayBingoService bingo = mock(IPlayBingoService.class);
        ReflectionTestUtils.setField(completion, "playBingoService", bingo);
        for (TransientDataAccessException failure : lockFailures()) {
            doThrow(failure).when(bingo).onNodeDone(7L, 300L);
            Map<String, Object> response = new LinkedHashMap<>(Map.of("nodeId", 401L));
            assertSame(failure, assertThrows(failure.getClass(), () -> completion.refreshBingo(7L, 300L, response)));
            assertEquals(Map.of("nodeId", 401L), response);
        }
    }

    @Test void nonTransientOptionalFailuresKeepTheirOriginalBestEffortBehavior() {
        PlayNodeCompletionApplication completion = new PlayNodeCompletionApplication();
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        IPlayBingoService bingo = mock(IPlayBingoService.class);
        ReflectionTestUtils.setField(completion, "playAttemptLogMapper", attempts);
        ReflectionTestUtils.setField(completion, "playBingoService", bingo);
        doThrow(new IllegalArgumentException("optional audit unavailable")).when(attempts).insertPlayAttemptLog(any());
        doThrow(new IllegalStateException("optional card unavailable")).when(bingo).onNodeDone(7L, 300L);
        assertDoesNotThrow(() -> completion.logAttempt(7L, node(), 500L, 1, 0, "wrong"));
        Map<String, Object> response = new LinkedHashMap<>(Map.of("nodeId", 401L));
        assertDoesNotThrow(() -> completion.refreshBingo(7L, 300L, response));
        assertEquals(Map.of("nodeId", 401L), response);
    }

    @Test void normalBingoProjectionKeepsBothOriginalMasks() {
        PlayNodeCompletionApplication completion = new PlayNodeCompletionApplication();
        IPlayBingoService bingo = mock(IPlayBingoService.class);
        ReflectionTestUtils.setField(completion, "playBingoService", bingo);
        PlayBingoCard card = new PlayBingoCard(); card.setFilledMask(7); card.setRewardMask(1);
        when(bingo.onNodeDone(7L, 300L)).thenReturn(card);
        Map<String, Object> response = new LinkedHashMap<>(Map.of("nodeId", 401L));
        completion.refreshBingo(7L, 300L, response);
        assertEquals(Map.of("nodeId", 401L, "bingo", Map.of("filledMask", 7, "rewardMask", 1)), response);
    }

    @Test void photoAuditDeadlockRollsBackBeforeRetryingTheOriginalCommand() throws Exception {
        Boundary boundary = photoBoundary(false);
        var result = boundary.controller().photo("500", "401", "media-asset:71", null, "photo-original", 4);
        assertEquals(200, result.get("code"));
        assertEquals("已提交,打卡成功", result.get("msg"));
        assertEquals(Map.of("firstTime", true, "nodeId", 401L), result.get("data"));
        assertEquals(1, boundary.downstreamEffects().get(), "no progress/reward leaf after the failed audit attempt");
        assertEquals(List.of("identity", "begin1", "audit1", "rollback1", "close1", "identity", "begin2",
                "audit2", "effects", "commit2", "close2"), boundary.events());
        List<Object> original = Arrays.asList(7L, "500", "401", "media-asset:71", null, "photo-original", 4);
        assertEquals(List.of(original, original), boundary.commands());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void repeatedAuditDeadlockNeverReturnsSuccessOrRunsDownstreamEffects() throws Exception {
        Boundary boundary = photoBoundary(true);
        assertThrowsExactly(DeadlockLoserDataAccessException.class,
                () -> boundary.controller().photo("500", "401", "media-asset:71", null, "photo-original", 4));
        assertEquals(0, boundary.downstreamEffects().get());
        assertEquals(List.of("identity", "begin1", "audit1", "rollback1", "close1", "identity", "begin2",
                "audit2", "rollback2", "close2"), boundary.events());
        List<Object> original = Arrays.asList(7L, "500", "401", "media-asset:71", null, "photo-original", 4);
        assertEquals(List.of(original, original), boundary.commands());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private static List<TransientDataAccessException> lockFailures() {
        return List.of(new DeadlockLoserDataAccessException("1213", null),
                new CannotAcquireLockException("1205"), new QueryTimeoutException("query timeout"));
    }

    private static CmsTopicNode node() {
        CmsTopicNode node = new CmsTopicNode(); node.setId(401L); node.setTopicId(300L); node.setTemplateId(39L); node.setStatus(1L);
        return node;
    }

    private record Boundary(ApiPlayProgressController controller, List<String> events,
                            List<List<Object>> commands, AtomicInteger downstreamEffects) { }

    private static Boundary photoBoundary(boolean alwaysFail) throws Exception {
        List<String> events = new ArrayList<>(); List<List<Object>> commands = new ArrayList<>();
        AtomicInteger auditCalls = new AtomicInteger(); AtomicInteger effects = new AtomicInteger();
        DataSource source = mock(DataSource.class);
        Connection first = connection(events, 1); Connection second = connection(events, 2);
        when(source.getConnection()).thenReturn(first, second);
        var transactions = new DataSourceTransactionManager(source);
        CmsTopicNode node = node();
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        when(nodes.selectCmsTopicNodeById(401L)).thenReturn(node);
        PlayNodeContext context = mock(PlayNodeContext.class);
        PlayNodeContext.PlayCtx scope = new PlayNodeContext.PlayCtx(); scope.actId = 500L; scope.topicId = 300L;
        when(context.resolveCtx(7L, "500", null, node)).thenReturn(scope);
        when(context.resolveMode(500L, 300L)).thenReturn(PlayMode.LINEAR_ORIENTEERING);
        CmsMemberTemplate template = new CmsMemberTemplate(); template.setValidationMethod(2);
        when(context.effectiveTemplate(7L, 300L, 500L, node)).thenReturn(template);
        PlayAttemptLogMapper attempts = mock(PlayAttemptLogMapper.class);
        doAnswer(call -> {
            assertWritableTransaction();
            var row = (com.chengyinhub.business.domain.PlayAttemptLog) call.getArgument(0);
            assertEquals(7L, row.getMemberId()); assertEquals(500L, row.getActivityId());
            assertEquals(300L, row.getTopicId()); assertEquals(401L, row.getNodeId());
            int attempt = auditCalls.incrementAndGet(); events.add("audit" + attempt);
            if (alwaysFail || attempt == 1) throw new DeadlockLoserDataAccessException("1213", null);
            return 1;
        }).when(attempts).insertPlayAttemptLog(any());
        PlayNodeCompletionApplication completion = spy(new PlayNodeCompletionApplication());
        ReflectionTestUtils.setField(completion, "playAttemptLogMapper", attempts);
        doAnswer(call -> {
            assertWritableTransaction(); effects.incrementAndGet(); events.add("effects");
            return new LinkedHashMap<>(Map.of("firstTime", true, "nodeId", 401L));
        }).when(completion).advanceProgress(eq(7L), same(node), eq(500L), eq("media-asset:71"), isNull(), isNull(),
                eq(RewardPolicy.FULL), eq("photo-original"), eq(4), isNull(), isNull());
        PlayPhotoCommandApplication application = new PlayPhotoCommandApplication();
        ReflectionTestUtils.setField(application, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(application, "playNodeContext", context);
        ReflectionTestUtils.setField(application, "playNodeCompletionApplication", completion);
        ReflectionTestUtils.setField(application, "photoUploadFactVerifier",
                (PlayPhotoCommandApplication.PlayPhotoUploadFactVerifier) (url, actor) -> {
                    assertWritableTransaction(); assertEquals("media-asset:71", url); assertEquals(7L, actor);
                });
        ProxyFactory inner = new ProxyFactory(application); inner.setProxyTargetClass(true);
        inner.addAdvice((MethodBeforeAdvice) (method, arguments, target) -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            commands.add(java.util.Collections.unmodifiableList(Arrays.asList(arguments.clone())));
        });
        inner.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        ApiPlayProgressController controller = new ApiPlayProgressController() {
            @Override public Long getAppUserId() {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); events.add("identity"); return 7L;
            }
        };
        ReflectionTestUtils.setField(controller, "playPhotoCommandApplication", inner.getProxy());
        ProxyFactory outer = new ProxyFactory(controller); outer.setProxyTargetClass(true);
        outer.addAdvisor(DeadlockRetryConfig.deadlockRetryAdvisor());
        return new Boundary((ApiPlayProgressController) outer.getProxy(), events, commands, effects);
    }

    private static void assertWritableTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED),
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }

    private static Connection connection(List<String> events, int id) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        doAnswer(call -> { if (Boolean.FALSE.equals(call.getArgument(0))) events.add("begin" + id); return null; })
                .when(connection).setAutoCommit(anyBoolean());
        doAnswer(call -> { events.add("rollback" + id); return null; }).when(connection).rollback();
        doAnswer(call -> { events.add("commit" + id); return null; }).when(connection).commit();
        doAnswer(call -> { events.add("close" + id); return null; }).when(connection).close();
        return connection;
    }
}
