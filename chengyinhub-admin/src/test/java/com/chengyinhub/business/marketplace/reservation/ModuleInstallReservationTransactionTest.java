package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MyBatis XML + Spring transactions on isolated H2 MySQL mode, NOT real-MySQL evidence. */
class ModuleInstallReservationTransactionTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(90);
    private static final BuyerPrincipal BUYER = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 42);
    private static final BuyerPrincipal SELLER = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 8);
    private static final byte[] CONTENT = "reviewed canonical module fixture".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMS = "reviewed frozen license fixture".getBytes(StandardCharsets.UTF_8);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private ModuleReservationStore store;
    private ModuleInstallReservationService service;
    private FixtureAuthority authority;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = createDataSource();
        jdbc = new JdbcTemplate(ds); tx = new DataSourceTransactionManager(ds);
        applySchema(jdbc);
        jdbc.execute("create table fixture_grant (id varchar(100) primary key, state varchar(16) not null)");
        jdbc.update("insert into fixture_grant values ('license-1', 'ACTIVE'), ('license-2', 'ACTIVE')");
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("w18", new SpringManagedTransactionFactory(), ds));
        String resource = "mapper/marketplace/ModuleReservationStore.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        store = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ModuleReservationStore.class);
        clock = new MutableClock(); authority = new FixtureAuthority(); service = service(store);
    }

    // Test-only hooks: MySQL suites reuse every assertion but replace the H2 datasource and SQL adaptation.
    protected DriverManagerDataSource createDataSource() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:w18_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        source.setDriverClassName("org.h2.Driver"); return source;
    }
    protected void applySchema(JdbcTemplate jdbc) throws Exception {
        // Execute the actual additive migration with only unsupported engine/charset syntax removed.
        for (String statement : migration().replaceAll("(?m)^--.*$", "")
                .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "").split(";")) {
            if (!statement.trim().isEmpty()) jdbc.execute(statement);
        }
    }

    @Test void paidReservationPersistsExactProvenanceBindingsAndOnlyNonExecutableReceipt() {
        ModuleReservationReceipt r = reserve("request-1", 101);
        assertEquals("RESERVED", r.getState()); assertEquals(DEADLINE, r.getExpiresAt());
        assertFalse(r.isExecutable()); assertFalse(r.isPublished()); assertFalse(r.grantsPlayerAdmission());
        assertEquals(1, count("reservation")); assertEquals(2, count("scope")); assertEquals(1, count("binding"));
        Map<String, Object> row = jdbc.queryForMap("select * from w18_module_install_reservation");
        assertEquals("version-1", row.get("version_id")); assertEquals(sha(CONTENT), row.get("content_hash"));
        assertEquals("order-line:1", row.get("grant_evidence_reference")); assertEquals("source-event:1", row.get("source_event_id"));
        assertEquals("review:1", row.get("approval_reference")); assertEquals(terms().getTermsHash(), row.get("terms_hash"));
        assertEquals(8L, ((Number) row.get("seller_id")).longValue());
        assertEquals(1, authority.lockCalls);
    }

    @Test void freeAcquisitionStillRequiresVerifiedClaimAndRetainsFrozenOffer() {
        authority.free = true; reserve("free", 101);
        assertEquals("free-claim:1", jdbc.queryForObject("select grant_evidence_reference from w18_module_install_reservation", String.class));
        assertEquals("free-v1", jdbc.queryForObject("select offer_version from w18_module_install_reservation", String.class));
        authority.noGrant = true;
        assertDenied(ModuleInstallDeniedException.Reason.LICENSE_NOT_VERIFIED, () -> reserve("other", 101));
    }

    @Test void lostResponseRetryReturnsOriginalHoldWithoutExtendingExpiry() {
        ModuleReservationReceipt first = reserve("same", 101); clock.now.set(NOW.plusSeconds(30));
        ModuleReservationReceipt retry = reserve("same", 101);
        assertEquals(first.getReservationKey(), retry.getReservationKey()); assertEquals(first.getExpiresAt(), retry.getExpiresAt());
        assertEquals(1, count("reservation")); assertEquals(2, authority.lockCalls);
    }

    @Test void sameBusinessKeyDifferentPayloadOrDeadlineIsRejected() {
        reserve("same", 101);
        assertReservation(ModuleReservationException.Reason.IDEMPOTENCY_CONFLICT, () -> reserve("same", 102));
        assertReservation(ModuleReservationException.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.reserve(42, request(BUYER, "same", "license-1", 101), DEADLINE.plusSeconds(1)));
        assertEquals(1, count("reservation"));
    }

    @Test void buyerKindAndNumericIdIsolateIdenticalRequestIds() {
        reserve("same", 101);
        BuyerPrincipal other = new BuyerPrincipal(BuyerPrincipal.Kind.MERCHANT, 42);
        authority.buyer = other;
        ModuleReservationReceipt second = service.reserve(42, request(other, "same", "license-2", 102), DEADLINE);
        assertNotEquals(ReservationIdentity.key(BUYER, "same"), second.getReservationKey()); assertEquals(2, count("reservation"));
        assertReservation(ModuleReservationException.Reason.RESERVATION_NOT_FOUND,
                () -> service.release(42, other, "missing", ModuleInstallReservationService.Release.CANCELLED));
        assertDenied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED,
                () -> service.release(42, BUYER, "same", ModuleInstallReservationService.Release.CANCELLED));
        assertEquals(2, active());
    }

    @Test void unauthorizedActorCannotReadGrantOrCreateLocks() {
        assertDenied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED,
                () -> service.reserve(999, request(BUYER, "bad", "license-1", 101), DEADLINE));
        assertEquals(0, authority.lockCalls); assertEquals(0, count("mutex"));
    }

    @Test void crossBuyerLicenseIsRejectedBeforeAnyHold() {
        authority.grantBuyer = new BuyerPrincipal(BuyerPrincipal.Kind.ORGANIZATION, 42);
        assertDenied(ModuleInstallDeniedException.Reason.LICENSE_SCOPE_MISMATCH, () -> reserve("bad", 101));
        assertEquals(0, count("reservation"));
    }

    @Test void revokedSuspendedExpiredOrFutureGrantCannotReserve() {
        for (String state : Arrays.asList("REVOKED", "SUSPENDED", "EXPIRED")) {
            jdbc.update("update fixture_grant set state=?", state);
            assertDenied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> reserve("bad", 101));
        }
        jdbc.update("update fixture_grant set state='ACTIVE'"); authority.validUntil = NOW;
        assertDenied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> reserve("bad", 101));
        authority.validUntil = NOW.plusSeconds(100); authority.validFrom = NOW.plusSeconds(1);
        assertDenied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> reserve("bad", 101));
        assertEquals(0, count("reservation"));
    }

    @Test void retryRechecksRevocationAndReleaseDoesNotRequireActiveGrant() {
        reserve("same", 101); jdbc.update("update fixture_grant set state='REVOKED'");
        assertDenied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> reserve("same", 101));
        assertEquals("CANCELLED", service.release(42, BUYER, "same", ModuleInstallReservationService.Release.CANCELLED).getState());
        assertEquals(0, active());
    }

    @Test void retryRejectsChangedImmutableAuthorityEvidence() {
        reserve("same", 101); authority.evidence = "different-order-line";
        assertReservation(ModuleReservationException.Reason.AUTHORITY_CHANGED, () -> reserve("same", 101));
        assertEquals("order-line:1", jdbc.queryForObject("select grant_evidence_reference from w18_module_install_reservation", String.class));
    }

    @Test void releaseVersionHashAndDraftRevisionAreServerValidated() {
        authority.noSource = true;
        assertDenied(ModuleInstallDeniedException.Reason.VERSION_NOT_INSTALLABLE, () -> reserve("bad", 101));
        authority.noSource = false; authority.wrongHash = true;
        assertDenied(ModuleInstallDeniedException.Reason.VERSION_NOT_GRANTED, () -> reserve("bad", 101));
        authority.wrongHash = false; authority.revision = 2;
        assertDenied(ModuleInstallDeniedException.Reason.DRAFT_REVISION_MISMATCH, () -> reserve("bad", 101));
        authority.revision = 1; authority.draftState = ModuleInstallAuthority.OwnedDraft.State.PUBLISHED;
        assertDenied(ModuleInstallDeniedException.Reason.DRAFT_NOT_EDITABLE, () -> reserve("bad", 101));
        assertEquals(0, count("reservation"));
    }

    @Test void unavailableAuthorityAndBindingFailuresDoNotFallBackToClientHints() {
        authority.noUsage = true;
        assertDenied(ModuleInstallDeniedException.Reason.USAGE_UNAVAILABLE, () -> reserve("bad", 101));
        authority.noUsage = false; authority.bindingUntil = NOW;
        assertDenied(ModuleInstallDeniedException.Reason.BINDING_NOT_APPROVED, () -> reserve("bad", 101));
        authority.bindingUntil = DEADLINE; authority.failLock = true;
        assertThrows(IllegalStateException.class, () -> reserve("bad", 101)); assertEquals(0, count("reservation"));
    }

    @Test void authoritativeExistingInstallAndRunsAreIncludedWithoutCreatingRunQuota() {
        authority.usedThemes = Collections.singleton(900L);
        assertDenied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> reserve("bad", 101));
        authority.usedThemes = Collections.singleton(101L); authority.usedRuns = 10;
        reserve("same-theme", 101); assertEquals(10, authority.usedRuns);
        authority.usedRuns = 11;
        assertDenied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> reserve("bad", 101));
    }

    @Test void distinctMerchantQuotaIncludesOtherReservationBindings() {
        authority.themeLimit = 2; authority.merchantLimit = 1;
        reserve("first", 101); authority.productId = 602;
        assertDenied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> reserve("second", 102));
        assertEquals(1, active());
    }

    @Test void repeatedInstallToSameThemeCountsDistinctScopeAndOneCancellationDoesNotReleaseAnother() {
        reserve("one", 101); reserve("two", 101); assertEquals(2, active());
        service.release(42, BUYER, "one", ModuleInstallReservationService.Release.CANCELLED);
        assertDenied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> reserve("three", 102));
        service.release(42, BUYER, "two", ModuleInstallReservationService.Release.FAILED);
        reserve("three", 102); assertEquals(1, active()); assertEquals(6, count("scope"));
    }

    @Test void cancellationAndKnownFailureAreIdempotentTerminalAndPreserveEvidence() {
        reserve("one", 101);
        assertEquals("FAILED", service.release(42, BUYER, "one", ModuleInstallReservationService.Release.FAILED).getState());
        assertEquals("FAILED", service.release(42, BUYER, "one", ModuleInstallReservationService.Release.CANCELLED).getState());
        assertReservation(ModuleReservationException.Reason.RESERVATION_TERMINAL, () -> reserve("one", 101));
        reserve("new-key", 102); assertEquals(1, active()); assertEquals(2, count("binding"));
    }

    @Test void expiredReservationsAreReclaimedWithoutResurrectionOrDeletingAudit() {
        service.reserve(42, request(BUYER, "one", "license-1", 101), NOW.plusSeconds(5));
        clock.now.set(NOW.plusSeconds(5));
        assertReservation(ModuleReservationException.Reason.RESERVATION_EXPIRED,
                () -> service.reserve(42, request(BUYER, "one", "license-1", 101), NOW.plusSeconds(5)));
        reserve("new-key", 102);
        assertEquals("EXPIRED", jdbc.queryForObject("select state from w18_module_install_reservation where request_id='one'", String.class));
        assertEquals(2, count("reservation")); assertEquals(1, active());
    }

    @Test void releaseAtExclusiveExpiryRecordsExpired() {
        service.reserve(42, request(BUYER, "one", "license-1", 101), NOW.plusSeconds(1));
        clock.now.set(NOW.plusSeconds(1));
        assertEquals("EXPIRED", service.release(42, BUYER, "one", ModuleInstallReservationService.Release.CANCELLED).getState());
    }

    @Test void leaseNeverOutlivesGrantOrBindingAndRoundsDownToMicroseconds() {
        authority.validUntil = NOW.plusSeconds(20); authority.bindingUntil = NOW.plusSeconds(10).plusNanos(123456789);
        assertEquals(NOW.plusSeconds(10).plusNanos(123456000), reserve("one", 101).getExpiresAt());
    }

    @Test void grantExpiringDuringAuthorityLookupRollsBackReservation() {
        authority.validUntil = NOW.plusSeconds(2); authority.advanceOnUsage = NOW.plusSeconds(2);
        assertReservation(ModuleReservationException.Reason.RESERVATION_EXPIRED, () -> reserve("bad", 101));
        assertEquals(0, count("reservation")); assertEquals(0, count("mutex"));
    }

    @Test void deadlineCrossedDuringWritesRollsBackAllTablesAndRetryCanSucceed() {
        ModuleReservationStore slow = mock(ModuleReservationStore.class, delegatesTo(store));
        doAnswer(call -> { int result = store.insertScope(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            clock.now.set(DEADLINE); return result;
        }).when(slow).insertScope(anyString(), anyString(), anyLong());
        assertReservation(ModuleReservationException.Reason.RESERVATION_EXPIRED,
                () -> service(slow).reserve(42, request(BUYER, "retry", "license-1", 101), DEADLINE));
        assertEquals(0, count("reservation")); assertEquals(0, count("scope")); assertEquals(0, count("binding"));
        clock.now.set(NOW); reserve("retry", 101); assertEquals(1, active());
    }

    @Test void storageFailureAfterPartialWritesCompensatesByTransactionRollback() {
        ModuleReservationStore failing = mock(ModuleReservationStore.class, delegatesTo(store));
        doThrow(new IllegalStateException("fixture failed storage")).when(failing).insertScope(anyString(), eq("MERCHANT"), anyLong());
        assertThrows(IllegalStateException.class,
                () -> service(failing).reserve(42, request(BUYER, "retry", "license-1", 101), DEADLINE));
        assertEquals(0, count("reservation")); assertEquals(0, count("scope")); assertEquals(0, count("binding"));
        assertEquals(0, count("mutex")); reserve("retry", 101); assertEquals(1, active());
    }

    @Test @Timeout(20) void concurrentSameKeyRetriesCreateExactlyOneGrantUse() throws Exception {
        List<Object> outcomes = race(8, index -> reserve("same", 101));
        assertEquals(8, outcomes.stream().filter(x -> x instanceof ModuleReservationReceipt).count());
        assertEquals(1, count("reservation")); assertEquals(2, count("scope")); assertEquals(1, count("binding"));
    }

    @Test @Timeout(20) void concurrentDifferentKeysCannotOversubscribeRemainingScope() throws Exception {
        List<Object> outcomes = race(8, index -> reserve("request-" + index, 100 + index));
        assertEquals(1, outcomes.stream().filter(x -> x instanceof ModuleReservationReceipt).count());
        for (Object outcome : outcomes) if (outcome instanceof Throwable) {
            assertTrue(outcome instanceof ModuleInstallDeniedException, outcome.toString());
            assertEquals(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, ((ModuleInstallDeniedException) outcome).getReason());
        }
        assertEquals(1, active());
    }

    @Test @Timeout(20) void concurrentCancelAndReserveDoNotDeadlockOrOverrelease() throws Exception {
        reserve("old", 101);
        List<Object> outcomes = race(2, index -> index == 0 ? service.release(42, BUYER, "old", ModuleInstallReservationService.Release.CANCELLED)
                : reserve("new", 102));
        for (Object outcome : outcomes) if (outcome instanceof Throwable) {
            assertTrue(outcome instanceof ModuleInstallDeniedException, outcome.toString());
            assertEquals(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, ((ModuleInstallDeniedException) outcome).getReason());
        }
        reserve("new", 102); assertEquals(1, active());
        assertEquals("CANCELLED", jdbc.queryForObject("select state from w18_module_install_reservation where request_id='old'", String.class));
    }

    @Test void caseAndSupplementaryCharactersHaveDistinctBusinessKeysAndMalformedUtf16IsRejected() {
        assertNotEquals(ReservationIdentity.key(BUYER, "A"), ReservationIdentity.key(BUYER, "a"));
        assertNotEquals(ReservationIdentity.key(BUYER, "\uD83D\uDE00"), ReservationIdentity.key(BUYER, "?"));
        assertThrows(IllegalArgumentException.class, () -> ReservationIdentity.key(BUYER, "\uD800"));
        assertThrows(IllegalArgumentException.class, () -> ReservationIdentity.key(BUYER, "\uDC00"));
        reserve("A", 101); reserve("a", 101); assertEquals(2, count("reservation"));
    }

    @Test void returnedHoldSurvivesUnrelatedOuterTransactionRollback() {
        new TransactionTemplate(tx).execute(status -> {
            reserve("durable", 101); status.setRollbackOnly(); return null;
        });
        assertEquals(1, active());
        assertEquals(ReservationIdentity.key(BUYER, "durable"), reserve("durable", 101).getReservationKey());
    }

    @Test void cancellationRechecksBuyerAuthorityAfterWaitingForLocks() {
        reserve("one", 101); authority.revokeBuyerOnReleaseLock = true;
        assertDenied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED,
                () -> service.release(42, BUYER, "one", ModuleInstallReservationService.Release.CANCELLED));
        assertEquals(1, active());
    }

    @Test @Timeout(20) void grantRevocationWriterWaitsForReservationAuthorityLockThenRetrySeesRevocation() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), proceed = new CountDownLatch(1), revokeStarted = new CountDownLatch(1);
        authority.afterLock = () -> {
            locked.countDown();
            try { assertTrue(proceed.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ModuleReservationReceipt> reservation = pool.submit(() -> reserve("one", 101));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            Future<?> revoke = pool.submit(() -> new TransactionTemplate(tx).execute(status -> {
                revokeStarted.countDown(); jdbc.update("update fixture_grant set state='REVOKED' where id='license-1'"); return null;
            }));
            assertTrue(revokeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revoke.get(100, TimeUnit.MILLISECONDS));
            proceed.countDown(); assertEquals("RESERVED", reservation.get(5, TimeUnit.SECONDS).getState());
            revoke.get(5, TimeUnit.SECONDS); authority.afterLock = null;
            assertDenied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> reserve("one", 101));
        } finally { proceed.countDown(); pool.shutdownNow(); }
    }

    @Test void requestDigestIncludesEveryScopeAndIntentFieldAndSortsUnorderedBindings() {
        ModuleInstallRequest first = request(BUYER, "one", "license-1", 101);
        String digest = ReservationIdentity.payload(first, DEADLINE);
        List<ModuleInstallRequest> changed = Arrays.asList(
                request(BUYER, "one", "license-2", 101), request(BUYER, "two", "license-1", 101),
                request(BUYER, "one", "license-1", 102),
                new ModuleInstallRequest(BUYER, "one", "license-1", "module-1", "version-1", sha(CONTENT), "paid-v1",
                        terms().getTermsHash(), 101, 2, true, first.getChanges(), first.getBindings()),
                new ModuleInstallRequest(BUYER, "one", "license-1", "module-1", "version-1", sha(CONTENT), "paid-v1",
                        terms().getTermsHash(), 101, 1, false, first.getChanges(), first.getBindings()),
                new ModuleInstallRequest(BUYER, "one", "license-1", "module-1", "version-1", sha(CONTENT), "paid-v1",
                        terms().getTermsHash(), 101, 1, true, EnumSet.of(ModuleInstallRequest.Change.ADAPT_CONTENT), first.getBindings()));
        for (ModuleInstallRequest r : changed) assertNotEquals(digest, ReservationIdentity.payload(r, DEADLINE));
        Map<String, ModuleInstallRequest.ResourceRef> a = new LinkedHashMap<String, ModuleInstallRequest.ResourceRef>();
        a.put("z", new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.PRODUCT, 601));
        a.put("a", new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.LOCATION, 701));
        Map<String, ModuleInstallRequest.ResourceRef> b = new LinkedHashMap<String, ModuleInstallRequest.ResourceRef>();
        b.put("a", a.get("a")); b.put("z", a.get("z"));
        ModuleInstallRequest r1 = new ModuleInstallRequest(BUYER, "one", "license-1", "module-1", "version-1", sha(CONTENT),
                "paid-v1", terms().getTermsHash(), 101, 1, true, first.getChanges(), a);
        ModuleInstallRequest r2 = new ModuleInstallRequest(BUYER, "one", "license-1", "module-1", "version-1", sha(CONTENT),
                "paid-v1", terms().getTermsHash(), 101, 1, true, first.getChanges(), b);
        assertEquals(ReservationIdentity.payload(r1, DEADLINE), ReservationIdentity.payload(r2, DEADLINE));
        assertNotEquals(ReservationIdentity.payload(r1, DEADLINE), digest);
    }

    @Test void migrationAndMapperStayAdditiveUnregisteredAndUseCurrentLockingReads() throws Exception {
        String sql = migration(); assertFalse(sql.contains("ALTER TABLE")); assertFalse(sql.contains("DROP TABLE"));
        assertTrue(sql.contains("PRIMARY KEY (reservation_key)")); assertTrue(sql.contains("COLLATE ascii_bin"));
        assertEquals(0, ModuleInstallReservationService.class.getAnnotations().length);
        assertEquals(0, ModuleReservationStore.class.getAnnotations().length);
        assertFalse(ModuleReservationStore.class.getPackage().getName().endsWith(".mapper"));
        String xml;
        try (InputStream in = Resources.getResourceAsStream("mapper/marketplace/ModuleReservationStore.xml")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read); xml = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("ORDER BY s.scope_id FOR UPDATE"));
    }


    @Test void programmaticTransactionsRequireNewReadCommittedAndRestoreOuterIsolation() {
        setDefaultIsolation("REPEATABLE READ");
        List<DefaultTransactionDefinition> definitions = new ArrayList<DefaultTransactionDefinition>();
        PlatformTransactionManager observed = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                definitions.add(new DefaultTransactionDefinition(definition));
                return tx.getTransaction(definition);
            }
            public void commit(TransactionStatus status) { tx.commit(status); }
            public void rollback(TransactionStatus status) { tx.rollback(status); }
        };
        ReservationAuthority checked = mock(ReservationAuthority.class, delegatesTo(authority));
        doAnswer(call -> {
            assertEquals(Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED),
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            assertEquals(Connection.TRANSACTION_READ_COMMITTED, jdbcIsolation());
            return authority.resolveBuyer(call.getArgument(0), call.getArgument(1));
        }).when(checked).resolveBuyer(anyLong(), any(BuyerPrincipal.class));
        ModuleInstallReservationService explicit = new ModuleInstallReservationService(store, checked, observed, clock);
        TransactionTemplate outer = new TransactionTemplate(tx);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        outer.execute(status -> {
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, jdbcIsolation());
            explicit.reserve(42, request(BUYER, "isolated", "license-1", 101), DEADLINE);
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, jdbcIsolation());
            explicit.release(42, BUYER, "isolated", ModuleInstallReservationService.Release.CANCELLED);
            assertEquals(Connection.TRANSACTION_SERIALIZABLE, jdbcIsolation());
            status.setRollbackOnly(); return null;
        });
        assertEquals(2, definitions.size());
        for (DefaultTransactionDefinition definition : definitions) {
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED, definition.getIsolationLevel());
            assertFalse(definition.isReadOnly());
        }
        assertEquals("CANCELLED", jdbc.queryForObject("select state from w18_module_install_reservation", String.class));
        assertEquals(0, ModuleInstallReservationService.class.getAnnotations().length);
        for (java.lang.reflect.Method method : ModuleInstallReservationService.class.getDeclaredMethods())
            assertFalse(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test @Timeout(20) void readCommittedReleaseSeesFirstReservationAfterEarlierAuthorityReadForBothMutexSchedules() throws Exception {
        setDefaultIsolation(snapshotIsolation()); // H2 SNAPSHOT here; the MySQL subclass explicitly supplies REPEATABLE READ.
        for (boolean preseed : new boolean[] { false, true }) {
            String requestId = "visibility-" + preseed;
            if (preseed) {
                store.ensureMutex(ReservationIdentity.key(BUYER, requestId));
                store.ensureMutex(ReservationIdentity.license("license-1"));
            }
            CountDownLatch read = new CountDownLatch(1), committed = new CountDownLatch(1);
            ReservationAuthority gated = authorityAfterFirstRead(read, committed);
            ModuleInstallReservationService releasing = new ModuleInstallReservationService(store, gated, tx, clock);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<ModuleReservationReceipt> result = pool.submit(() -> releasing.release(42, BUYER, requestId,
                        ModuleInstallReservationService.Release.CANCELLED));
                assertTrue(read.await(5, TimeUnit.SECONDS));
                reserve(requestId, 101); committed.countDown();
                assertEquals("CANCELLED", result.get(5, TimeUnit.SECONDS).getState());
                assertEquals(0, active());
            } finally { committed.countDown(); pool.shutdownNow(); }
        }
    }

    @Test @Timeout(20) void releaseActuallyWaitingForFirstReservationMutexSeesCommittedHold() throws Exception {
        setDefaultIsolation(snapshotIsolation());
        CountDownLatch holding = new CountDownLatch(1), commit = new CountDownLatch(1), aboutToWait = new CountDownLatch(1);
        authority.afterLock = () -> { holding.countDown(); await(commit); };
        ReservationAuthority reading = mock(ReservationAuthority.class, delegatesTo(authority));
        doAnswer(call -> {
            jdbc.queryForObject("select count(*) from fixture_grant", Integer.class); // Earlier nonlocking authority read.
            return authority.resolveBuyer(call.getArgument(0), call.getArgument(1));
        }).when(reading).resolveBuyer(anyLong(), any(BuyerPrincipal.class));
        ModuleReservationStore observing = mock(ModuleReservationStore.class, delegatesTo(store));
        String key = ReservationIdentity.key(BUYER, "waiting");
        doAnswer(call -> { aboutToWait.countDown(); return store.ensureMutex(call.getArgument(0)); })
                .when(observing).ensureMutex(eq(key));
        ModuleInstallReservationService releasing = new ModuleInstallReservationService(observing, reading, tx, clock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ModuleReservationReceipt> reservation = pool.submit(() -> reserve("waiting", 101));
            assertTrue(holding.await(5, TimeUnit.SECONDS));
            Future<ModuleReservationReceipt> release = pool.submit(() -> releasing.release(42, BUYER, "waiting",
                    ModuleInstallReservationService.Release.FAILED));
            assertTrue(aboutToWait.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> release.get(100, TimeUnit.MILLISECONDS));
            commit.countDown(); assertEquals("RESERVED", reservation.get(5, TimeUnit.SECONDS).getState());
            assertEquals("FAILED", release.get(5, TimeUnit.SECONDS).getState());
            assertEquals(0, active());
            org.mockito.InOrder order = inOrder(observing);
            order.verify(observing).ensureMutex(key);
            order.verify(observing).lockMutex(key);
            order.verify(observing).find(key); // Nonlocking locator remains BEFORE license lock.
            order.verify(observing).ensureMutex(ReservationIdentity.license("license-1"));
            order.verify(observing).lockMutex(ReservationIdentity.license("license-1"));
            order.verify(observing).findForUpdate(key); // Row lock remains AFTER license lock.
        } finally { commit.countDown(); pool.shutdownNow(); }
    }

    @Test @Timeout(20) void inheritedSnapshotNegativeControlMissesNewReservationDespiteExistingMutexes() throws Exception {
        setDefaultIsolation(snapshotIsolation());
        store.ensureMutex(ReservationIdentity.key(BUYER, "negative-control"));
        store.ensureMutex(ReservationIdentity.license("license-1"));
        CountDownLatch read = new CountDownLatch(1), committed = new CountDownLatch(1);
        // Remove READ_COMMITTED for this control only. H2 uses SNAPSHOT; the real-MySQL
        // subclass uses InnoDB REPEATABLE READ. The assertion must fail if the old locator sees current data.
        PlatformTransactionManager inherited = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                DefaultTransactionDefinition original = new DefaultTransactionDefinition(definition);
                original.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
                return tx.getTransaction(original);
            }
            public void commit(TransactionStatus status) { tx.commit(status); }
            public void rollback(TransactionStatus status) { tx.rollback(status); }
        };
        ModuleInstallReservationService baseline = new ModuleInstallReservationService(store,
                authorityAfterFirstRead(read, committed), inherited, clock);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> result = pool.submit(() -> {
                try { return baseline.release(42, BUYER, "negative-control", ModuleInstallReservationService.Release.CANCELLED); }
                catch (ModuleReservationException e) { return e.getReason(); }
            });
            assertTrue(read.await(5, TimeUnit.SECONDS));
            reserve("negative-control", 101); committed.countDown();
            assertEquals(ModuleReservationException.Reason.RESERVATION_NOT_FOUND, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, active());
        } finally { committed.countDown(); pool.shutdownNow(); }
    }

    private ReservationAuthority authorityAfterFirstRead(CountDownLatch read, CountDownLatch committed) {
        ReservationAuthority gated = mock(ReservationAuthority.class, delegatesTo(authority));
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.compareAndSet(true, false)) {
                jdbc.queryForObject("select count(*) from fixture_grant", Integer.class);
                read.countDown(); await(committed);
            }
            return authority.resolveBuyer(call.getArgument(0), call.getArgument(1));
        }).when(gated).resolveBuyer(anyLong(), any(BuyerPrincipal.class));
        return gated;
    }
    protected String snapshotIsolation() { return "SNAPSHOT"; }
    protected void setDefaultIsolation(String isolation) {
        DriverManagerDataSource source = (DriverManagerDataSource) tx.getDataSource();
        source.setUrl(source.getUrl() + ";INIT=SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + isolation);
    }
    private int jdbcIsolation() {
        Connection connection = DataSourceUtils.getConnection(tx.getDataSource());
        try { return connection.getTransactionIsolation(); }
        catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
        finally { DataSourceUtils.releaseConnection(connection, tx.getDataSource()); }
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private ModuleInstallReservationService service(ModuleReservationStore s) { return new ModuleInstallReservationService(s, authority, tx, clock); }
    private ModuleReservationReceipt reserve(String key, long draft) { return service.reserve(42, request(BUYER, key, "license-1", draft), DEADLINE); }
    private ModuleInstallRequest request(BuyerPrincipal buyer, String key, String license, long draft) {
        return new ModuleInstallRequest(buyer, key, license, "module-1", "version-1", sha(CONTENT),
                authority.free ? "free-v1" : "paid-v1", terms().getTermsHash(), draft, 1, true,
                EnumSet.of(ModuleInstallRequest.Change.BIND_RESOURCES), Collections.singletonMap("reward",
                new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.PRODUCT, authority.productId)));
    }
    private LicenseTermsSnapshot terms() {
        return new LicenseTermsSnapshot("terms-v1", TERMS, sha(TERMS), "terms-document:1", LicenseTermsSnapshot.Permission.ALLOWED,
                LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY, LicenseTermsSnapshot.Permission.PROHIBITED,
                LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION, LicenseTermsSnapshot.Redistribution.PROHIBITED,
                Collections.singleton("fixture-region"), LicenseTermsSnapshot.Limit.atMost(authority.themeLimit),
                LicenseTermsSnapshot.Limit.atMost(authority.merchantLimit), LicenseTermsSnapshot.Limit.atMost(10));
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from w18_module_install_" + table, Integer.class); }
    private int active() { return jdbc.queryForObject("select count(*) from w18_module_install_reservation where state='RESERVED'", Integer.class); }
    private static void assertDenied(ModuleInstallDeniedException.Reason reason, Runnable action) { assertEquals(reason, assertThrows(ModuleInstallDeniedException.class, action::run).getReason()); }
    private static void assertReservation(ModuleReservationException.Reason reason, Runnable action) { assertEquals(reason, assertThrows(ModuleReservationException.class, action::run).getReason()); }
    private static String migration() throws Exception {
        Path p = Paths.get("sql/migration_w18_module_install_reservation_20261002.sql");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-admin").resolve(p);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
    private static String sha(byte[] bytes) {
        try { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 0xff)); return out.toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private interface Work { Object run(int index); }
    private static List<Object> race(int n, Work work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n); CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<Future<Object>>();
        try {
            for (int i = 0; i < n; i++) { final int index = i; futures.add(pool.submit(() -> { start.await(); try { return work.run(index); } catch (RuntimeException e) { return e; } })); }
            start.countDown(); List<Object> out = new ArrayList<Object>(); for (Future<Object> f : futures) out.add(f.get(15, TimeUnit.SECONDS)); return out;
        } finally { pool.shutdownNow(); }
    }
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<Instant>(NOW);
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }
    /** Test authority only. Real locking status row; other approved server repository facts are fixtures. */
    private final class FixtureAuthority implements ReservationAuthority {
        BuyerPrincipal buyer = BUYER; BuyerPrincipal grantBuyer = BUYER;
        int lockCalls; boolean free, noGrant, noSource, wrongHash, noUsage, failLock, revokeBuyerOnReleaseLock;
        Runnable afterLock;
        long revision = 1, themeLimit = 1, merchantLimit = 2, usedRuns, productId = 601;
        String evidence = "order-line:1";
        Instant validFrom = NOW.minusSeconds(1), validUntil = NOW.plusSeconds(120), bindingUntil = NOW.plusSeconds(100), advanceOnUsage;
        Set<Long> usedThemes = Collections.emptySet();
        ModuleInstallAuthority.OwnedDraft.State draftState = ModuleInstallAuthority.OwnedDraft.State.DRAFT;
        public BuyerPrincipal resolveBuyer(long actor, BuyerPrincipal requested) { return actor == 42 && requested.equals(buyer) ? buyer : null; }
        public void lockForReservation(long actor, ModuleInstallRequest request) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (failLock) throw new IllegalStateException("authority repository unavailable");
            jdbc.queryForObject("select state from fixture_grant where id=? for update", String.class, request.getLicenseId());
            lockCalls++;
            if (afterLock != null) afterLock.run();
        }
        public void lockBuyerForRelease(long actor, BuyerPrincipal requestedBuyer) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (revokeBuyerOnReleaseLock) buyer = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 999);
        }
        public ModuleLicenseGrant findVerifiedGrant(String id) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if (noGrant) return null;
            ModuleLicenseGrant.Status state = ModuleLicenseGrant.Status.valueOf(jdbc.queryForObject("select state from fixture_grant where id=? for update", String.class, id));
            BuyerPrincipal holder = "license-2".equals(id) ? buyer : grantBuyer;
            LicenseOfferSnapshot offer = new LicenseOfferSnapshot("offer-1", free ? "free-v1" : "paid-v1", "module-1", SELLER,
                    EnumSet.allOf(BuyerPrincipal.Kind.class), free ? LicenseOfferSnapshot.Acquisition.FREE : LicenseOfferSnapshot.Acquisition.PAID,
                    free ? 0 : 100, "CNY", terms());
            return new ModuleLicenseGrant(id, holder, offer, "version-1", Collections.singletonMap("version-1", wrongHash ? sha(TERMS) : sha(CONTENT)),
                    state, free ? ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM : ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE,
                    free ? "free-claim:1" : evidence, "source-event:1", validFrom, validUntil);
        }
        public ModuleVersionSnapshot findInstallableVersion(String id) {
            if (noSource) return null;
            return new ModuleVersionSnapshot(SELLER, "module-1", "version-1", "1", 1, ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE,
                    11, "reviewed-revision:1", CONTENT, sha(CONTENT), "review:1", Collections.singletonMap("reward", ModuleVersionSnapshot.BindingKind.PRODUCT));
        }
        public OwnedDraft findDraft(long id) { return new OwnedDraft(id, buyer, revision, "fixture-region", draftState); }
        public BindingApproval approveBinding(BuyerPrincipal buyer, long draftId, String placeholder, ModuleInstallRequest.ResourceRef resource, Instant now) {
            return new BindingApproval(placeholder, resource, buyer, draftId, resource.getResourceId() == 601 ? 7L : 8L,
                    "fixture-region", "binding-review:1", bindingUntil);
        }
        public UsageSnapshot findUsage(String id) {
            if (advanceOnUsage != null) clock.now.set(advanceOnUsage);
            return noUsage ? null : new UsageSnapshot(id, 1, usedThemes, Collections.<Long>emptySet(), usedRuns);
        }
    }
}
