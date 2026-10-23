package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.CmsRegistrationMerchantServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

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
        P0AcceptanceSchema.baseTables(db, "cms_registration_merchant");
        // Apply the original audit column ALTER; the remainder rebuilds an unrelated topic view.
        String audit = Files.readString(P0AcceptanceSchema.repoRoot()
                .resolve("chengyinhub-admin/sql/migration_registration_merchant_audit_time_20260910.sql"));
        int start = audit.indexOf("ALTER TABLE `cms_registration_merchant`");
        assertTrue(start >= 0);
        db.execute(ItSchema.splitStatements(audit.substring(start)).get(0));
        mapper = P0AcceptanceSchema.sql(source, "CmsRegistrationMerchant").getMapper(CmsRegistrationMerchantMapper.class);
        service = withMapper(mapper);
    }

    private CmsRegistrationMerchantServiceImpl withMapper(CmsRegistrationMerchantMapper actual) {
        CmsRegistrationMerchantServiceImpl target = new CmsRegistrationMerchantServiceImpl();
        ReflectionTestUtils.setField(target, "cmsRegistrationMerchantMapper", actual);
        return target;
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
