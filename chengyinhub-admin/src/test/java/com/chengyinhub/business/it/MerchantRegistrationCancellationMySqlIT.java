package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.CmsRegistrationMerchantServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.controller.business.CmsRegistrationMerchantController;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real production mapper/DDL; no HTTP/RBAC/settlement claim. Run only in approved MySQL CI/VPS. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantRegistrationCancellationMySqlIT {
    private JdbcTemplate admin, db;
    private String schema;
    private boolean created;
    private CmsRegistrationMerchantMapper mapper;
    private CmsRegistrationMerchantServiceImpl service;
    private DataSourceTransactionManager transactions;
    private long nextId = 100;
    private static final Long OWNER = 100L;

    @BeforeAll void database() throws Exception {
        for (String key : new String[]{"HOST", "PORT", "DATABASE_BASE", "DATABASE_SUFFIX", "USER", "PASSWORD"})
            assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_" + key), "Explicit controlled test binding required: " + key);
        MysqlIntegrationTestConfig config = MysqlIntegrationTestConfig.fromEnvironment("merchant_cancel");
        admin = new JdbcTemplate(new DriverManagerDataSource(config.adminUrl(), config.user(), config.password()));
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.queryForObject("SELECT VERSION()", String.class)));
        schema = "architecture_a_mcancel_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + schema + "`"); created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(config.databaseUrl()
                .replace("/" + config.databaseName() + "?", "/" + schema + "?"), config.user(), config.password());
        db = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        P0AcceptanceSchema.baseTables(db, "cms_registration_merchant");
        // Apply the original audit column ALTER; the remainder rebuilds an unrelated topic view.
        String audit = Files.readString(P0AcceptanceSchema.repoRoot()
                .resolve("chengyinhub-admin/sql/migration_registration_merchant_audit_time_20260910.sql"));
        int start = audit.indexOf("ALTER TABLE `cms_registration_merchant`");
        assertTrue(start >= 0);
        db.execute(ItSchema.splitStatements(audit.substring(start)).get(0));
        mapper = P0AcceptanceSchema.sql(source, "CmsRegistrationMerchantMapper").getMapper(CmsRegistrationMerchantMapper.class);
        service = withMapper(mapper);
    }

    private CmsRegistrationMerchantServiceImpl withMapper(CmsRegistrationMerchantMapper actual) {
        CmsRegistrationMerchantServiceImpl target = new CmsRegistrationMerchantServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMerchantMapper", actual);
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        when(scope.canViewTopic(77L)).thenReturn(true);
        ReflectionTestUtils.setField(target, "topicOperationsScopeAuthorizer", scope);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (CmsRegistrationMerchantServiceImpl) proxy.getProxy();
    }

    private CmsRegistrationMerchantController adminController(CmsRegistrationMerchantServiceImpl actual) {
        CmsRegistrationMerchantController controller = new CmsRegistrationMerchantController();
        ITopicOperationsScopeAuthorizer scope = mock(ITopicOperationsScopeAuthorizer.class);
        when(scope.canViewTopic(77L)).thenReturn(true);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", actual);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        return controller;
    }

    @AfterAll void cleanup() {
        if (created) {
            assertTrue(schema.matches("architecture_a_mcancel_[a-f0-9]{32}"));
            admin.execute("DROP DATABASE `" + schema + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
        }
    }

    private Long original(Long audit, Integer deleted) {
        Long id = ++nextId;
        db.update("INSERT INTO cms_registration_merchant(id,member_id,topic_id,mode,status,audit_status,del_flag,address_name) "
                + "VALUES(?,?,77,1,0,?,?,?)", id, OWNER, audit, deleted, "original address");
        return id;
    }
    private Map<String, Object> row(Long id) {
        return db.queryForMap("SELECT * FROM cms_registration_merchant WHERE id=?", id);
    }
    private void await(CountDownLatch start) {
        try { assertTrue(start.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test void adminDeleteCannotEraseWinnerCommittedAfterItsPrecheck() {
        Long id = original(0L, 0);
        CmsRegistrationMerchantMapper delayed = spy(mapper);
        doAnswer(i -> {
            CmsRegistrationMerchant stale = mapper.selectCmsRegistrationMerchantById(id);
            assertEquals(1, mapper.confirmWinnerIfPending(id));
            return stale;
        }).when(delayed).selectCmsRegistrationMerchantById(id);
        com.chengyinhub.web.controller.business.CmsRegistrationMerchantController controller =
                new com.chengyinhub.web.controller.business.CmsRegistrationMerchantController();
        com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer scope =
                mock(com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer.class);
        when(scope.canViewTopic(77L)).thenReturn(true);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", withMapper(delayed));
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scope);
        com.chengyinhub.common.core.domain.AjaxResult result = controller.remove(new Long[]{id});
        assertEquals(1, db.queryForObject(
                "SELECT COUNT(*) FROM cms_registration_merchant WHERE id=? AND audit_status=1 AND del_flag=0",
                Integer.class, id), "a stale admin precheck must not delete the newly selected winner");
        assertNotEquals(200, result.get("code"));
    }

    @Test void winnerCommittedBetweenBatchPrecheckAndLocksPreservesEveryOriginalRow() {
        Long first = original(0L, 0), second = original(0L, 0);
        Map<String, Object> firstBefore = row(first);
        AtomicReference<Map<String, Object>> winner = new AtomicReference<>();
        CmsRegistrationMerchantMapper delayed = spy(mapper);
        doAnswer(i -> {
            CmsRegistrationMerchant stale = mapper.selectCmsRegistrationMerchantById(second);
            assertEquals(1, mapper.confirmWinnerIfPending(second));
            winner.set(row(second));
            return stale;
        }).when(delayed).selectCmsRegistrationMerchantById(second);
        assertNotEquals(200, adminController(withMapper(delayed)).remove(new Long[]{second, first}).get("code"));
        assertEquals(firstBefore, row(first), "a winner in the batch must prevent deleting an earlier valid target");
        assertEquals(winner.get(), row(second));
    }

    @Test void currentTopicScopeIsRecheckedAfterControllerSnapshot() {
        Long first = original(0L, 0), second = original(0L, 0);
        Map<String, Object> firstBefore = row(first);
        AtomicReference<Map<String, Object>> moved = new AtomicReference<>();
        CmsRegistrationMerchantMapper delayed = spy(mapper);
        doAnswer(i -> {
            CmsRegistrationMerchant stale = mapper.selectCmsRegistrationMerchantById(second);
            assertEquals(1, db.update("UPDATE cms_registration_merchant SET topic_id=88 WHERE id=?", second));
            moved.set(row(second));
            return stale;
        }).when(delayed).selectCmsRegistrationMerchantById(second);
        assertNotEquals(200, adminController(withMapper(delayed)).remove(new Long[]{first, second}).get("code"));
        assertEquals(firstBefore, row(first));
        assertEquals(moved.get(), row(second), "a stale authorized topic must not authorize the current row");
    }

    @Test void legitimateBatchLocksUniqueIdsInAscendingOrderAndDeletesOnlyThoseRows() {
        Long first = original(0L, 0), second = original(2L, 0), unrelated = original(0L, 0);
        Map<String, Object> unrelatedBefore = row(unrelated);
        CmsRegistrationMerchantMapper observed = spy(mapper);
        assertEquals(200, adminController(withMapper(observed)).remove(new Long[]{second, first, second}).get("code"));
        org.mockito.InOrder ordered = inOrder(observed);
        ordered.verify(observed).selectForDeletionByIdForUpdate(first);
        ordered.verify(observed).selectForDeletionByIdForUpdate(second);
        ordered.verify(observed).deleteCmsRegistrationMerchantByIds(new Long[]{first, second});
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM cms_registration_merchant WHERE id IN (?,?)",
                Integer.class, first, second));
        assertEquals(0, mapper.confirmWinnerIfPending(first), "selection after a committed deletion cannot win");
        assertEquals(0, mapper.confirmWinnerIfPending(second));
        assertEquals(unrelatedBefore, row(unrelated));
    }

    @Test void partialSqlDeleteThenCountMismatchRollsBackTheWholeBatch() {
        Long first = original(0L, 0), second = original(0L, 0);
        Map<String, Object> firstBefore = row(first), secondBefore = row(second);
        CmsRegistrationMerchantMapper broken = spy(mapper);
        // Perform a real partial DELETE in the service transaction, then report only that affected row.
        doAnswer(i -> {
            assertEquals(1, db.update("DELETE FROM cms_registration_merchant WHERE id=?", first));
            return 1;
        }).when(broken).deleteCmsRegistrationMerchantByIds(any(Long[].class));
        assertNotEquals(200, adminController(withMapper(broken)).remove(new Long[]{first, second}).get("code"));
        assertEquals(firstBefore, row(first));
        assertEquals(secondBefore, row(second));
    }

    @Test void missingTargetAndDirectSingleWinnerDeletionBothRejectWithoutChanges() {
        Long first = original(0L, 0), winner = original(0L, 0);
        assertEquals(1, mapper.confirmWinnerIfPending(winner));
        Map<String, Object> firstBefore = row(first), winnerBefore = row(winner);
        assertThrows(ServiceException.class,
                () -> service.deleteCmsRegistrationMerchantByIds(new Long[]{first, Long.MAX_VALUE}));
        assertThrows(ServiceException.class, () -> service.deleteCmsRegistrationMerchantById(winner));
        assertEquals(firstBefore, row(first));
        assertEquals(winnerBefore, row(winner));
    }

    @Test void currentLockReadSeesWinnerCommittedAfterAnOlderRepeatableReadSnapshot() throws Exception {
        Long id = original(0L, 0);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            TransactionTemplate oldView = new TransactionTemplate(transactions);
            oldView.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            oldView.execute(status -> {
                assertEquals(0L, mapper.selectCmsRegistrationMerchantById(id).getAuditStatus());
                Future<Integer> winner = worker.submit(() -> mapper.confirmWinnerIfPending(id));
                try { assertEquals(1, winner.get(10, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThrows(ServiceException.class, () -> service.deleteCmsRegistrationMerchantByIds(new Long[]{id}));
                // The joined service exception must escape through the transaction proxy and mark rollback-only.
                status.setRollbackOnly();
                return null;
            });
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(1, row(id).get("audit_status"));
        assertEquals(0, row(id).get("del_flag"));
    }

    @Test void cancelledCandidateCannotBeSelectedEvenWhenDispatcherReadItEarlier() {
        Long id = original(0L, 0);
        assertTrue(service.cancelByMerchant(id, OWNER, "merchant"));
        Map<String, Object> cancelled = row(id);
        assertEquals(0, mapper.confirmWinnerIfPending(id));
        assertEquals(cancelled, row(id));
    }

    @Test void selectedWinnerCannotBeCancelledAndRejectionChangesNoPersistedField() {
        Long id = original(0L, 0);
        assertEquals(1, mapper.confirmWinnerIfPending(id));
        Map<String, Object> selected = row(id);
        RuntimeException denial = assertThrows(RuntimeException.class, () -> service.cancelByMerchant(id, OWNER, "merchant"));
        assertTrue(denial.getMessage().contains("已中标"));
        assertEquals(selected, row(id));
    }

    @Test void winnerCommittedAfterCancelReadIsNotOverwrittenByStaleSnapshot() {
        Long id = original(0L, 0);
        CmsRegistrationMerchantMapper delayed = spy(mapper);
        AtomicReference<Map<String, Object>> committed = new AtomicReference<>();
        doAnswer(i -> {
            CmsRegistrationMerchant stale = mapper.selectCmsRegistrationMerchantById(id);
            assertEquals(1, mapper.confirmWinnerIfPending(id));
            committed.set(row(id)); return stale;
        }).when(delayed).selectCmsRegistrationMerchantById(id);

        assertFalse(withMapper(delayed).cancelByMerchant(id, OWNER, "merchant"));
        assertEquals(committed.get(), row(id));
    }

    @Test void ownerChangedAfterReadCannotBeCancelledOrReassignedBackByOldOwner() {
        Long id = original(0L, 0);
        CmsRegistrationMerchantMapper delayed = spy(mapper);
        AtomicReference<Map<String, Object>> committed = new AtomicReference<>();
        doAnswer(i -> {
            CmsRegistrationMerchant stale = mapper.selectCmsRegistrationMerchantById(id);
            assertEquals(1, db.update("UPDATE cms_registration_merchant SET member_id=200 WHERE id=?", id));
            committed.set(row(id)); return stale;
        }).when(delayed).selectCmsRegistrationMerchantById(id);

        assertFalse(withMapper(delayed).cancelByMerchant(id, OWNER, "merchant"));
        assertEquals(committed.get(), row(id));
    }

    @Test void allowedCancellationPreservesConcurrentRejectionAndEditedFields() {
        Long id = original(0L, 0);
        CmsRegistrationMerchantMapper delayed = spy(mapper);
        doAnswer(i -> {
            CmsRegistrationMerchant stale = mapper.selectCmsRegistrationMerchantById(id);
            assertEquals(1, mapper.rejectIfPending(id, "not selected"));
            assertEquals(1, db.update("UPDATE cms_registration_merchant SET address_name='new address' WHERE id=?", id));
            return stale;
        }).when(delayed).selectCmsRegistrationMerchantById(id);

        assertTrue(withMapper(delayed).cancelByMerchant(id, OWNER, "merchant"));
        CmsRegistrationMerchant cancelled = mapper.selectCmsRegistrationMerchantById(id);
        assertEquals(2L, cancelled.getAuditStatus()); assertEquals(3L, cancelled.getStatus());
        assertEquals(2, cancelled.getDelFlag()); assertEquals("not selected", cancelled.getReason());
        assertEquals("new address", cancelled.getAddressName()); assertNotNull(cancelled.getAuditTime());
    }

    @Test void sameOriginalConcurrentCancelAndSelectionHaveExactlyOneWinner() throws Exception {
        Long id = original(0L, 0); CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        int selected; boolean cancelled;
        try {
            Future<Boolean> cancel = workers.submit(() -> {
                await(start);
                try { return service.cancelByMerchant(id, OWNER, "merchant"); }
                catch (RuntimeException rejected) {
                    assertTrue(rejected.getMessage().contains("已中标")); return false;
                }
            });
            Future<Integer> select = workers.submit(() -> { await(start); return mapper.confirmWinnerIfPending(id); });
            start.countDown(); cancelled = cancel.get(10, TimeUnit.SECONDS); selected = select.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(1, (cancelled ? 1 : 0) + selected);
        CmsRegistrationMerchant persisted = mapper.selectCmsRegistrationMerchantById(id);
        assertEquals(OWNER, persisted.getMemberId());
        assertEquals(cancelled ? 2 : 0, persisted.getDelFlag());
        assertEquals(cancelled ? 0L : 1L, persisted.getAuditStatus());
    }

    @Test void knownPendingRejectedAndNullAuditStillAllowFirstCancellationOnly() {
        for (Long audit : new Long[]{0L, 2L, null}) {
            Long id = original(audit, 0);
            assertTrue(service.cancelByMerchant(id, OWNER, "merchant"));
            Map<String, Object> cancelled = row(id);
            assertFalse(service.cancelByMerchant(id, OWNER, "merchant"));
            assertEquals(cancelled, row(id));
        }
    }

    @Test void deletedOrUnknownDeletionStateCannotBeSelectedOrCancelledAgain() {
        for (Integer deleted : new Integer[]{2, null}) {
            Long id = original(0L, deleted); Map<String, Object> before = row(id);
            assertEquals(0, mapper.confirmWinnerIfPending(id));
            assertFalse(service.cancelByMerchant(id, OWNER, "merchant"));
            assertEquals(before, row(id));
        }
    }
}
