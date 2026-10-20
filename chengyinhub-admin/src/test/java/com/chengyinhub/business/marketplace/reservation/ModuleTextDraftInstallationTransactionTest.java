package com.chengyinhub.business.marketplace.reservation;

import com.chengyinhub.business.marketplace.contract.*;
import com.chengyinhub.business.marketplace.reservation.store.*;
import com.fasterxml.jackson.databind.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual migrations/MyBatis/Spring rollback and locking, isolated H2 only; no production authority. */
class ModuleTextDraftInstallationTransactionTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z"), DEADLINE = NOW.plusSeconds(90);
    private static final BuyerPrincipal BUYER = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 42);
    private static final BuyerPrincipal SELLER = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL, 8);
    private static final byte[] TERMS = "frozen reviewed license text".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT = ("{\"schema\":\"w18-member-text-v1\",\"title\":\"A text riddle\","
            + "\"description\":\"Reviewed description\",\"ruleInstructions\":\"Find the answer\","
            + "\"merchantGuide\":\"Private staff instructions\",\"questionName\":\"The question\","
            + "\"questionAnswer\":\"Private answer\",\"hint1\":\"First hint\",\"hint2\":\"Second hint\","
            + "\"answerReveal\":\"Explanation\",\"bindings\":{\"site\":\"LOCATION\",\"product\":\"PRODUCT\"}}")
            .getBytes(StandardCharsets.UTF_8);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private ModuleReservationStore reservations;
    private ModuleInstallationStore installs;
    private ModuleInstallReservationService holder;
    private ModuleTextDraftInstallationService service;
    private FixtureAuthority authority;
    private MutableClock clock;

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds = createDataSource();
        jdbc = new JdbcTemplate(ds); tx = new DataSourceTransactionManager(ds);
        applySchema(jdbc);
        jdbc.execute("create table fixture_buyer (id int primary key, allowed boolean not null)");
        jdbc.update("insert into fixture_buyer values (42, true)");
        jdbc.execute("create table fixture_grant (id varchar(40) primary key, state varchar(16) not null)");
        jdbc.update("insert into fixture_grant values ('license-1', 'ACTIVE')");
        jdbc.execute("create table fixture_version (id varchar(40) primary key, allowed boolean not null, content blob not null)");
        jdbc.update("insert into fixture_version values ('version-1', true, ?)", CONTENT);
        jdbc.execute("create table fixture_target (id bigint primary key, owner_kind varchar(20), owner_id bigint, revision bigint, state varchar(16))");
        jdbc.update("insert into fixture_target values (101, 'INDIVIDUAL', 42, 1, 'DRAFT'), (102, 'INDIVIDUAL', 42, 1, 'DRAFT')");
        Configuration config = new Configuration();
        config.setEnvironment(new Environment("w18-install", new SpringManagedTransactionFactory(), ds));
        for (String mapper : Arrays.asList("ModuleReservationStore", "ModuleInstallationStore")) {
            String path = "mapper/marketplace/" + mapper + ".xml";
            try (InputStream input = Resources.getResourceAsStream(path)) {
                new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        reservations = session.getMapper(ModuleReservationStore.class); installs = session.getMapper(ModuleInstallationStore.class);
        clock = new MutableClock(); authority = new FixtureAuthority();
        holder = new ModuleInstallReservationService(reservations, authority, tx, clock); service = installer(installs, authority, tx);
    }

    // Test-only hooks: MySQL suites reuse every assertion but replace the H2 datasource and SQL adaptation.
    protected DriverManagerDataSource createDataSource() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:w18_install_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        source.setDriverClassName("org.h2.Driver"); return source;
    }
    protected void applySchema(JdbcTemplate jdbc) throws Exception {
        for (String name : Arrays.asList("w18_module_install_reservation_20261002", "w18_owned_text_draft_install_20261002")) {
            for (String s : migration(name).replaceAll("(?m)^--.*$", "")
                    .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "").split(";")) {
                if (!s.trim().isEmpty()) jdbc.execute(s);
            }
        }
    }

    @Test void consumesExactlyOneHoldAndPersistsOwnedDraftWhitelistBindingsFrozenRights() throws Exception {
        hold("one", 101); ModuleInstallationReceipt receipt = consume("one", 101);
        assertEquals("CONSUMED", state("one")); assertEquals(1, count("w18_module_owned_draft"));
        assertEquals(1, count("w18_module_draft_component")); assertEquals(1, count("w18_module_install_usage"));
        Map<String,Object> row = jdbc.queryForMap("select * from w18_module_owned_draft");
        assertEquals("INDIVIDUAL", row.get("buyer_kind")); assertEquals(42L, ((Number) row.get("buyer_id")).longValue());
        assertEquals("DRAFT", row.get("state"));
        for (String field : Arrays.asList("review_status", "publish_status", "is_sync")) assertEquals(0, ((Number) row.get(field)).intValue());
        assertArrayEquals(CONTENT, jdbc.queryForObject("select source_content from w18_module_owned_draft", byte[].class));
        assertArrayEquals(TERMS, jdbc.queryForObject("select terms_document from w18_module_owned_draft", byte[].class));
        JsonNode content = json("select content_json from w18_module_draft_component");
        assertEquals("Private answer", content.get("questionAnswer").asText());
        assertEquals("Private staff instructions", content.get("merchantGuide").asText());
        assertEquals(601, content.at("/bindings/product/resourceId").asInt());
        assertEquals(501, content.at("/bindings/site/resourceId").asInt());
        assertEquals("binding:product", content.at("/bindings/product/approvalReference").asText());
        assertEquals(1, content.get("validationMethod").asInt());
        for (String field : Arrays.asList("memberId", "status", "draftStatus", "publishStatus", "collaborators", "couponId", "npcId", "imgUrl")) assertFalse(content.has(field));
        JsonNode rights = json("select frozen_rights from w18_module_owned_draft");
        assertEquals("PAID", rights.get("acquisition").asText());
        assertEquals("VERIFIED_PAID_ORDER_LINE", rights.get("evidenceKind").asText());
        assertEquals("PROHIBITED", rights.get("redistribution").asText());
        assertEquals(terms().getTermsHash(), rights.get("termsHash").asText());
        assertEquals("version-1", receipt.getVersionId()); assertEquals(sha(CONTENT), receipt.getContentHash());
        assertFalse(receipt.isExecutable()); assertFalse(receipt.isPublished()); assertFalse(receipt.grantsPlayerAdmission());
        assertEquals(2, count("w18_module_install_binding")); assertEquals(2, count("w18_module_install_scope"));
    }

    @Test void freeClaimPreservesFrozenFreeOfferAndDoesNotBecomePurchase() throws Exception {
        authority.free = true; hold("free", 101); consume("free", 101);
        JsonNode rights = json("select frozen_rights from w18_module_owned_draft");
        assertEquals("FREE", rights.get("acquisition").asText()); assertEquals(0, rights.get("priceMinor").asInt());
        assertEquals("VERIFIED_FREE_CLAIM", rights.get("evidenceKind").asText());
        assertEquals("free-claim:1", rights.get("evidenceReference").asText());
    }

    @Test void responseLossReplaySurvivesNewServiceExpiryRevocationAndLaterTargetEdit() {
        hold("lost", 101); ModuleInstallationReceipt first = consume("lost", 101);
        jdbc.update("update fixture_grant set state='REVOKED'"); jdbc.update("update fixture_version set allowed=false");
        jdbc.update("update fixture_target set revision=2, state='PUBLISHED'"); clock.now.set(NOW.plusSeconds(999));
        service = installer(installs, authority, tx); ModuleInstallationReceipt replay = consume("lost", 101);
        assertEquals(first.getOwnedDraftId(), replay.getOwnedDraftId()); assertEquals(first.getInstalledAt(), replay.getInstalledAt());
        assertEquals(1, count("w18_module_install_usage")); assertEquals(1, authority.contentLoads.get());
    }

    @Test void replayStillChecksExactPayloadAndCurrentBuyerAuthority() {
        hold("replay", 101); consume("replay", 101);
        reserved(ModuleReservationException.Reason.IDEMPOTENCY_CONFLICT, () -> consume("replay", 102));
        reserved(ModuleReservationException.Reason.IDEMPOTENCY_CONFLICT, () -> service.consume(42, request("replay", 101), DEADLINE.plusSeconds(1)));
        jdbc.update("update fixture_buyer set allowed=false");
        denied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED, () -> consume("replay", 101));
        assertEquals(1, count("w18_module_owned_draft"));
    }

    @Test void unknownRequestAndBuyerKindOrIdCannotConsumeAnotherOwnersHold() {
        hold("one", 101);
        reserved(ModuleReservationException.Reason.RESERVATION_NOT_FOUND, () -> consume("absent", 101));
        denied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED, () -> service.consume(999, request("one", 101), DEADLINE));
        for (BuyerPrincipal buyer : Arrays.asList(new BuyerPrincipal(BuyerPrincipal.Kind.MERCHANT,42), new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,43))) {
            ModuleInstallRequest r = request(buyer, "one", 101);
            denied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED, () -> service.consume(42, r, DEADLINE));
        }
        untouched("one");
    }

    @Test void foreignGrantAndChangedSellerAreDeniedAtConsume() {
        hold("one", 101); authority.grantBuyer = new BuyerPrincipal(BuyerPrincipal.Kind.ORGANIZATION, 42);
        denied(ModuleInstallDeniedException.Reason.LICENSE_SCOPE_MISMATCH, () -> consume("one",101));
        authority.grantBuyer = BUYER; authority.seller = new BuyerPrincipal(BuyerPrincipal.Kind.INDIVIDUAL,9);
        denied(ModuleInstallDeniedException.Reason.SOURCE_AUTHOR_MISMATCH, () -> consume("one",101)); untouched("one");
    }

    @Test void revokedSuspendedExpiredAndFutureLicenseFailBeforeContentRead() {
        hold("one",101);
        for (String s : Arrays.asList("REVOKED", "SUSPENDED", "EXPIRED")) {
            jdbc.update("update fixture_grant set state=?", s);
            denied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> consume("one",101));
        }
        jdbc.update("update fixture_grant set state='ACTIVE'"); authority.validUntil = NOW;
        denied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> consume("one",101));
        authority.validUntil = NOW.plusSeconds(120); authority.validFrom = NOW.plusSeconds(1);
        denied(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE, () -> consume("one",101));
        assertEquals(0, authority.contentLoads.get()); untouched("one");
    }

    @Test void emergencyPolicyRevocationIsSeparateFromGrantStatus() {
        hold("one",101); jdbc.update("update fixture_version set allowed=false");
        denied(ModuleInstallDeniedException.Reason.VERSION_NOT_INSTALLABLE, () -> consume("one",101));
        assertEquals("ACTIVE", jdbc.queryForObject("select state from fixture_grant",String.class)); untouched("one");
    }

    @Test void targetOwnerStateAndRevisionAreRevalidatedUnderLocks() {
        hold("one",101); jdbc.update("update fixture_target set owner_kind='MERCHANT'");
        denied(ModuleInstallDeniedException.Reason.DRAFT_SCOPE_DENIED, () -> consume("one",101));
        jdbc.update("update fixture_target set owner_kind='INDIVIDUAL', state='PUBLISHED'");
        denied(ModuleInstallDeniedException.Reason.DRAFT_NOT_EDITABLE, () -> consume("one",101));
        jdbc.update("update fixture_target set state='DRAFT', revision=2");
        denied(ModuleInstallDeniedException.Reason.DRAFT_REVISION_MISMATCH, () -> consume("one",101)); untouched("one");
    }

    @Test void sourceVersionHashTermsAndFrozenEvidenceChangesFailClosed() {
        hold("one",101); authority.version = "version-2";
        denied(ModuleInstallDeniedException.Reason.VERSION_MISMATCH, () -> consume("one",101));
        authority.version="version-1"; authority.grantedHash=sha(TERMS);
        denied(ModuleInstallDeniedException.Reason.VERSION_NOT_GRANTED, () -> consume("one",101));
        authority.grantedHash=sha(CONTENT); authority.themeLimit=2;
        denied(ModuleInstallDeniedException.Reason.TERMS_MISMATCH, () -> service.consume(42, originalRequest("one",101), DEADLINE));
        authority.themeLimit=1; authority.evidence="order-line:2";
        reserved(ModuleReservationException.Reason.AUTHORITY_CHANGED, () -> consume("one",101)); untouched("one");
    }

    @Test void contentAndTermsByteSubstitutionRollsBackAndRetainsHold() {
        hold("one",101); authority.returnedContent=TERMS;
        assertThrows(IllegalArgumentException.class, () -> consume("one",101)); authority.returnedContent=null;
        authority.returnedTerms=CONTENT; assertThrows(IllegalArgumentException.class, () -> consume("one",101)); untouched("one");
    }

    @Test void changedOrExpiredBindingCannotBeConsumed() {
        hold("one",101); authority.bindingUntil=NOW;
        denied(ModuleInstallDeniedException.Reason.BINDING_NOT_APPROVED, () -> consume("one",101));
        authority.bindingUntil=NOW.plusSeconds(100); authority.bindingReference="changed";
        reserved(ModuleReservationException.Reason.AUTHORITY_CHANGED, () -> consume("one",101)); untouched("one");
    }

    @Test void cancelledFailedAndExpiredHoldsNeverResurrect() {
        hold("cancel",101); holder.release(42,BUYER,"cancel",ModuleInstallReservationService.Release.CANCELLED);
        reserved(ModuleReservationException.Reason.RESERVATION_TERMINAL, () -> consume("cancel",101));
        hold("fail",101); holder.release(42,BUYER,"fail",ModuleInstallReservationService.Release.FAILED);
        reserved(ModuleReservationException.Reason.RESERVATION_TERMINAL, () -> consume("fail",101));
        hold("expiry",101); clock.now.set(DEADLINE);
        reserved(ModuleReservationException.Reason.RESERVATION_EXPIRED, () -> consume("expiry",101));
        assertEquals(0,count("w18_module_install_usage"));
    }

    @Test void committedScopeNeverExpiresOrGetsReleasedWithOriginalHold() {
        hold("one",101); consume("one",101); clock.now.set(NOW.plusSeconds(95));
        assertEquals("CONSUMED", holder.release(42,BUYER,"one",ModuleInstallReservationService.Release.CANCELLED).getState());
        denied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED,
                () -> holder.reserve(42,request("other",102),NOW.plusSeconds(99)));
        assertEquals(1,count("w18_module_install_usage"));
    }

    @Test void consumedScopeUnionsOverlappingHoldsAndDoesNotConsumeRunQuota() {
        hold("one",101); hold("two",101); consume("one",101); consume("two",101);
        assertEquals(2,count("w18_module_install_usage")); assertEquals(0,authority.usedRuns);
        denied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> hold("three",102));
        assertEquals(1,new HashSet<Long>(transaction(() -> reservations.activeScopes(ReservationIdentity.license("license-1"),"THEME",java.sql.Timestamp.from(NOW)))).size());
    }

    @Test void currentRunUsageOrOtherInstalledScopeCanInvalidateOutstandingHold() {
        hold("one",101); authority.usedRuns=11;
        denied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> consume("one",101));
        authority.usedRuns=0; authority.usedThemes=Collections.singleton(102L);
        denied(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED, () -> consume("one",101)); untouched("one");
    }

    @Test void everyWriteFailureRollsBackDraftComponentUsageAndConsumption() {
        hold("one",101);
        for (String step : Arrays.asList("draft","component","usage","consume","receipt")) {
            ModuleInstallationStore broken=mock(ModuleInstallationStore.class,delegatesTo(installs));
            if (step.equals("draft")) doAnswer(c->{ installs.insertOwnedDraft(c.getArgument(0)); throw new IllegalStateException("after draft"); }).when(broken).insertOwnedDraft(any());
            if (step.equals("component")) doAnswer(c->{ installs.insertTextComponent(c.getArgument(0)); throw new IllegalStateException("after component"); }).when(broken).insertTextComponent(any());
            if (step.equals("usage")) doAnswer(c->{ installs.insertUsage(c.getArgument(0),c.getArgument(1),c.getArgument(2)); throw new IllegalStateException("after usage"); }).when(broken).insertUsage(anyString(),anyLong(),any());
            if (step.equals("consume")) doAnswer(c->{ installs.consume(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3)); throw new IllegalStateException("after consume"); }).when(broken).consume(anyString(),anyString(),anyLong(),any());
            if (step.equals("receipt")) doThrow(new IllegalStateException("receipt unavailable")).when(broken).findReceipt(anyString(),anyString(),anyLong());
            ModuleTextDraftInstallationService failing=installer(broken,authority,tx);
            assertThrows(IllegalStateException.class,()->failing.consume(42,request("one",101),DEADLINE)); untouched("one");
        }
        consume("one",101); assertEquals(1,count("w18_module_install_usage"));
    }

    @Test void lateDeadlineDuringContentOrAfterConsumptionRollsBack() {
        hold("one",101); authority.afterLoad=()->clock.now.set(DEADLINE);
        reserved(ModuleReservationException.Reason.RESERVATION_EXPIRED,()->consume("one",101)); untouched("one");
        authority.afterLoad=null; clock.now.set(NOW);
        ModuleInstallationStore slow=mock(ModuleInstallationStore.class,delegatesTo(installs));
        doAnswer(c->{ int n=installs.consume(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3)); clock.now.set(DEADLINE); return n; })
                .when(slow).consume(anyString(),anyString(),anyLong(),any());
        reserved(ModuleReservationException.Reason.RESERVATION_EXPIRED,()->installer(slow,authority,tx).consume(42,request("one",101),DEADLINE)); untouched("one");
    }

    @Test void usesNewReadCommittedTransactionAndSurvivesCallerRollback() {
        hold("one",101); InstallationAuthority checking=mock(InstallationAuthority.class,delegatesTo(authority));
        doAnswer(c->{ assertEquals(Connection.TRANSACTION_READ_COMMITTED,DataSourceUtils.getConnection(tx.getDataSource()).getTransactionIsolation());
            authority.lockForReservation(c.getArgument(0),c.getArgument(1)); return null; }).when(checking).lockForReservation(anyLong(),any());
        PlatformTransactionManager observing=mock(PlatformTransactionManager.class,delegatesTo(tx));
        doAnswer(c->{ TransactionDefinition d=c.getArgument(0); assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,d.getPropagationBehavior());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,d.getIsolationLevel()); return tx.getTransaction(d); }).when(observing).getTransaction(any());
        TransactionTemplate outer=new TransactionTemplate(tx); outer.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        outer.execute(s->{ installer(installs,checking,observing).consume(42,request("one",101),DEADLINE); s.setRollbackOnly(); return null; });
        assertEquals("CONSUMED",state("one")); assertEquals(1,count("w18_module_owned_draft"));
    }

    @Test @Timeout(20) void concurrentSameReservationCreatesOneDraftAndUsage() throws Exception {
        hold("one",101); List<Object> results=race(8,i->consume("one",101)); Set<Long> ids=new HashSet<Long>();
        for(Object result:results) { assertTrue(result instanceof ModuleInstallationReceipt,String.valueOf(result)); ids.add(((ModuleInstallationReceipt)result).getOwnedDraftId()); }
        assertEquals(1,ids.size()); assertEquals(1,count("w18_module_install_usage")); assertEquals(1,authority.contentLoads.get());
    }

    @Test @Timeout(20) void consumeCancelRaceHasExactlyOneTerminalOutcome() throws Exception {
        hold("one",101); List<Object> results=race(2,i->i==0?consume("one",101):holder.release(42,BUYER,"one",ModuleInstallReservationService.Release.CANCELLED));
        if (state("one").equals("CONSUMED")) assertEquals(1,count("w18_module_install_usage"));
        else { assertEquals("CANCELLED",state("one")); assertEquals(0,count("w18_module_install_usage")); }
        for(Object result:results) if(result instanceof RuntimeException) assertEquals(ModuleReservationException.Reason.RESERVATION_TERMINAL,((ModuleReservationException)result).getReason());
    }

    @Test @Timeout(20) void concurrentOtherScopeCannotOversubscribeDuringConsume() throws Exception {
        hold("one",101); List<Object> results=race(2,i->i==0?consume("one",101):hold("other",102));
        assertTrue(results.get(0) instanceof ModuleInstallationReceipt); assertTrue(results.get(1) instanceof ModuleInstallDeniedException);
        assertEquals(ModuleInstallDeniedException.Reason.USE_LIMIT_EXCEEDED,((ModuleInstallDeniedException)results.get(1)).getReason());
        assertEquals(1,count("w18_module_owned_draft"));
    }

    @Test @Timeout(20) void realPolicyRevocationWriterBlocksUntilInstallCommit() throws Exception {
        hold("one",101); CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),writerStarted=new CountDownLatch(1);
        authority.afterLoad=()->{locked.countDown();await(release);}; ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<ModuleInstallationReceipt> install=pool.submit(()->consume("one",101)); assertTrue(locked.await(5,TimeUnit.SECONDS));
            Future<?> revoke=pool.submit(()->new TransactionTemplate(tx).execute(s->{writerStarted.countDown();jdbc.update("update fixture_version set allowed=false where id='version-1'");return null;}));
            assertTrue(writerStarted.await(5,TimeUnit.SECONDS)); assertThrows(TimeoutException.class,()->revoke.get(100,TimeUnit.MILLISECONDS));
            release.countDown(); install.get(5,TimeUnit.SECONDS); revoke.get(5,TimeUnit.SECONDS); authority.afterLoad=null;
            assertEquals(1,count("w18_module_install_usage"));
            denied(ModuleInstallDeniedException.Reason.VERSION_NOT_INSTALLABLE,()->hold("later",101));
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test @Timeout(20) void revocationCommittedBeforeAuthorityLockPreventsInstall() throws Exception {
        hold("one",101); CountDownLatch revoked=new CountDownLatch(1),commit=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> writer=pool.submit(()->new TransactionTemplate(tx).execute(s->{jdbc.update("update fixture_grant set state='REVOKED'");revoked.countDown();await(commit);return null;}));
            assertTrue(revoked.await(5,TimeUnit.SECONDS)); Future<?> install=pool.submit(()->consume("one",101));
            assertThrows(TimeoutException.class,()->install.get(100,TimeUnit.MILLISECONDS)); commit.countDown(); writer.get(5,TimeUnit.SECONDS);
            ExecutionException e=assertThrows(ExecutionException.class,()->install.get(5,TimeUnit.SECONDS));
            assertEquals(ModuleInstallDeniedException.Reason.LICENSE_INACTIVE,((ModuleInstallDeniedException)e.getCause()).getReason()); untouched("one");
        } finally {commit.countDown();pool.shutdownNow();}
    }

    @Test void unsupportedFieldsGraphsAndNonTextTypesFailClosedEvenWithMatchingReviewHash() {
        for(String extra:Arrays.asList("\"collaborators\":[8]","\"status\":1","\"draftStatus\":1","\"imgUrl\":\"seller-private\"", "\"npcId\":5",
                "\"routeId\":8","\"couponId\":9","\"rewardRulesJson\":\"[]\"","\"merchantId\":8","\"advancedConfigJson\":\"{}\"","\"script\":\"run\"")) {
            byte[] content=(new String(CONTENT,StandardCharsets.UTF_8).replaceFirst("\\{","{"+extra+",")).getBytes(StandardCharsets.UTF_8);
            assertCodecRejected(content);
        }
        assertCodecRejected(new String(CONTENT,StandardCharsets.UTF_8).replace("\"A text riddle\"","{\"nested\":true}").getBytes(StandardCharsets.UTF_8));
    }

    @Test void malformedDuplicateTrailingAndPlaceholderMismatchBytesFailClosed() {
        for(String text:Arrays.asList(new String(CONTENT,StandardCharsets.UTF_8)+" {}",
                new String(CONTENT,StandardCharsets.UTF_8).replace("\"title\":","\"title\":\"duplicate\",\"title\":"),
                new String(CONTENT,StandardCharsets.UTF_8).replace("\"site\":\"LOCATION\"","\"foreign\":\"LOCATION\""),
                new String(CONTENT,StandardCharsets.UTF_8).replace("A text riddle","\\uD800"))) assertCodecRejected(text.getBytes(StandardCharsets.UTF_8));
        byte[] invalid=CONTENT.clone(); invalid[20]=(byte)0xff; assertCodecRejected(invalid);
    }

    @Test void topicSourceAndUnknownSchemaAreExplicitlyUnsupported() {
        hold("one",101); authority.kind=ModuleVersionSnapshot.SourceKind.TOPIC_TEMPLATE;
        reserved(ModuleReservationException.Reason.AUTHORITY_CHANGED,()->consume("one",101)); untouched("one");
        // New matching reservation still cannot silently flatten a topic graph into a text template.
        hold("topic",101); assertThrows(IllegalArgumentException.class,()->consume("topic",101)); untouched("topic");
        authority.kind=ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE; authority.schema=2;
        hold("schema",101); assertThrows(IllegalArgumentException.class,()->consume("schema",101)); untouched("schema");
    }

    @Test void replayRechecksMembershipAfterTakingBuyerLock() {
        hold("one",101); consume("one",101);
        InstallationAuthority revoked=mock(InstallationAuthority.class,delegatesTo(authority));
        doAnswer(c->{ authority.lockBuyerForRelease(c.getArgument(0),c.getArgument(1));
            jdbc.update("update fixture_buyer set allowed=false"); return null; }).when(revoked).lockBuyerForRelease(anyLong(),any());
        denied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED,
                ()->installer(installs,revoked,tx).consume(42,request("one",101),DEADLINE));
        assertEquals(1,count("w18_module_install_usage"));
    }

    @Test void deadlineCrossedDuringFinalReceiptReadAlsoRollsBack() {
        hold("one",101); ModuleInstallationStore slow=mock(ModuleInstallationStore.class,delegatesTo(installs));
        doAnswer(c->{ ModuleOwnedDraftRow r=installs.findReceipt(c.getArgument(0),c.getArgument(1),c.getArgument(2));
            clock.now.set(DEADLINE); return r; }).when(slow).findReceipt(anyString(),anyString(),anyLong());
        reserved(ModuleReservationException.Reason.RESERVATION_EXPIRED,
                ()->installer(slow,authority,tx).consume(42,request("one",101),DEADLINE)); untouched("one");
    }

    @Test void missingOrOversizedBytesCannotCreateDraft() {
        hold("one",101); InstallationAuthority absent=mock(InstallationAuthority.class,delegatesTo(authority));
        doReturn(null).when(absent).loadReviewedContent(any());
        assertThrows(IllegalArgumentException.class,()->installer(installs,absent,tx).consume(42,request("one",101),DEADLINE)); untouched("one");
        authority.returnedContent=new byte[TextModuleDraftCodec.MAX_BYTES+1];
        assertThrows(IllegalArgumentException.class,()->consume("one",101)); untouched("one");
    }

    @Test void remainsUnregisteredAndOldWorkflowsAreNotCalled() throws Exception {
        for(Class<?> c:Arrays.asList(ModuleTextDraftInstallationService.class,TextModuleDraftCodec.class,InstallationAuthority.class,ModuleInstallationStore.class)) {
            assertEquals(0,c.getDeclaredAnnotations().length);
        }
        String source=read("chengyinhub-system/src/main/java/com/chengyinhub/business/marketplace/reservation/ModuleTextDraftInstallationService.java");
        for(String unsafe:Arrays.asList("copyTemplateForOwner(","copyCollaborators(","BeanUtils","@Service","@Component","@Transactional","publishTemplate")) assertFalse(source.contains(unsafe));
        assertTrue(read("chengyinhub-system/src/main/resources/mapper/marketplace/ModuleReservationStore.xml").contains("r.state = 'CONSUMED'"));
    }


    @Test void independentEscapedDuplicateRootAndBindingKeysAreRejected() {
        String original = new String(CONTENT, StandardCharsets.UTF_8);
        String slash = "\\";
        assertCodecRejected(original.replace("\"title\":", "\"" + slash + "u0074itle\":\"duplicate\",\"title\":").getBytes(StandardCharsets.UTF_8));
        assertCodecRejected(original.replace("\"site\":\"LOCATION\"", "\"site\":\"LOCATION\",\"" + slash + "u0073ite\":\"LOCATION\"").getBytes(StandardCharsets.UTF_8));
    }

    @Test void independentSupplementaryTextIsPreservedExactly() throws Exception {
        String value = "Cafe\u0301 \uD83D\uDE80";
        byte[] bytes = new String(CONTENT, StandardCharsets.UTF_8).replace("A text riddle", value).getBytes(StandardCharsets.UTF_8);
        authority.reviewedContent=bytes; authority.contentHash=sha(bytes); authority.grantedHash=sha(bytes); authority.requestHash=sha(bytes);
        jdbc.update("update fixture_version set content=?",bytes);
        hold("unicode",101); consume("unicode",101);
        assertEquals(value, json("select content_json from w18_module_draft_component").get("title").asText());
        assertArrayEquals(bytes, jdbc.queryForObject("select source_content from w18_module_owned_draft",byte[].class));
    }

    @Test void independentMissingAndMismatchedReceiptRollBack() {
        hold("one",101);
        ModuleInstallationStore broken = mock(ModuleInstallationStore.class,delegatesTo(installs));
        doReturn(null).when(broken).findReceipt(anyString(),anyString(),anyLong());
        reserved(ModuleReservationException.Reason.STORAGE_INVARIANT,()->installer(broken,authority,tx).consume(42,request("one",101),DEADLINE)); untouched("one");
        doAnswer(c->{ModuleOwnedDraftRow row=installs.findReceipt(c.getArgument(0),c.getArgument(1),c.getArgument(2));row.setBuyerKind("MERCHANT");return row;})
                .when(broken).findReceipt(anyString(),anyString(),anyLong());
        reserved(ModuleReservationException.Reason.STORAGE_INVARIANT,()->installer(broken,authority,tx).consume(42,request("one",101),DEADLINE)); untouched("one");
    }

    @Test void independentInitialConsumeRechecksBuyerAfterLock() {
        hold("one",101);
        InstallationAuthority revoked=mock(InstallationAuthority.class,delegatesTo(authority));
        doAnswer(c->{authority.lockForReservation(c.getArgument(0),c.getArgument(1));jdbc.update("update fixture_buyer set allowed=false");return null;})
                .when(revoked).lockForReservation(anyLong(),any());
        denied(ModuleInstallDeniedException.Reason.BUYER_SCOPE_DENIED,()->installer(installs,revoked,tx).consume(42,request("one",101),DEADLINE));
        untouched("one"); assertEquals(0,authority.contentLoads.get());
    }

    @Test void independentExpirationSweepRetainsConsumedAndDeduplicatesExternalUsage() {
        hold("one",101); consume("one",101); clock.now.set(NOW.plusSeconds(95));
        authority.usedThemes=Collections.singleton(101L);
        holder.reserve(42,request("same",101),NOW.plusSeconds(99));
        assertEquals("CONSUMED",state("one"));
        transaction(()->reservations.expireLicense(ReservationIdentity.license("license-1"),java.sql.Timestamp.from(NOW.plusSeconds(999))));
        assertEquals("CONSUMED",state("one")); assertEquals("EXPIRED",state("same"));
        assertEquals(1,count("w18_module_install_usage"));
        List<Long> scopes=transaction(()->reservations.activeScopes(ReservationIdentity.license("license-1"),"THEME",java.sql.Timestamp.from(NOW.plusSeconds(999))));
        assertEquals(Collections.singletonList(101L),scopes);
    }

    @Test void independentOrganizationOwnershipAndActualOperatorArePreserved() {
        independentPrincipalSuccess(new BuyerPrincipal(BuyerPrincipal.Kind.ORGANIZATION,42));
    }
    @Test void independentMerchantOwnershipAndActualOperatorArePreserved() {
        independentPrincipalSuccess(new BuyerPrincipal(BuyerPrincipal.Kind.MERCHANT,42));
    }
    private void independentPrincipalSuccess(BuyerPrincipal buyer) {
        authority.grantBuyer=buyer;
        jdbc.update("update fixture_target set owner_kind=?, owner_id=?",buyer.getKind().name(),buyer.getEntityId());
        InstallationAuthority scoped=mock(InstallationAuthority.class,delegatesTo(authority));
        doAnswer(c->{long actor=c.getArgument(0);BuyerPrincipal requested=c.getArgument(1);return (actor==42||actor==99)&&buyer.equals(requested)?buyer:null;})
                .when(scoped).resolveBuyer(anyLong(),any());
        ModuleInstallRequest request=request(buyer,"scoped",101);
        new ModuleInstallReservationService(reservations,scoped,tx,clock).reserve(42,request,DEADLINE);
        installer(installs,scoped,tx).consume(99,request,DEADLINE);
        assertEquals(buyer.getKind().name(),jdbc.queryForObject("select buyer_kind from w18_module_owned_draft",String.class));
        assertEquals(buyer.getEntityId(),jdbc.queryForObject("select buyer_id from w18_module_owned_draft",Long.class));
        assertEquals(99L,jdbc.queryForObject("select installed_by from w18_module_owned_draft",Long.class));
        assertEquals(42L,jdbc.queryForObject("select actor_id from w18_module_install_reservation",Long.class));
        assertEquals(1,count("w18_module_install_usage"));
    }

    private void assertCodecRejected(byte[] bytes) {
        authority.reviewedContent=bytes; authority.contentHash=sha(bytes); authority.grantedHash=sha(bytes); authority.requestHash=sha(bytes);
        jdbc.update("update fixture_version set content=?",bytes);
        String key="codec-"+UUID.randomUUID(); hold(key,101); assertThrows(IllegalArgumentException.class,()->consume(key,101)); untouched(key);
    }
    private ModuleTextDraftInstallationService installer(ModuleInstallationStore s,InstallationAuthority a,PlatformTransactionManager t) {return new ModuleTextDraftInstallationService(reservations,s,a,t,clock);}
    private ModuleReservationReceipt hold(String key,long draft) {return holder.reserve(42,request(key,draft),DEADLINE);}
    private ModuleInstallationReceipt consume(String key,long draft) {return service.consume(42,request(key,draft),DEADLINE);}
    private ModuleInstallRequest originalRequest(String key,long draft) {long current=authority.themeLimit;authority.themeLimit=1;try{return request(key,draft);}finally{authority.themeLimit=current;}}
    private ModuleInstallRequest request(String key,long draft) {return request(BUYER,key,draft);}
    private ModuleInstallRequest request(BuyerPrincipal buyer,String key,long draft) {
        Map<String,ModuleInstallRequest.ResourceRef> bindings=new LinkedHashMap<String,ModuleInstallRequest.ResourceRef>();
        bindings.put("site",new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.LOCATION,501));
        bindings.put("product",new ModuleInstallRequest.ResourceRef(ModuleVersionSnapshot.BindingKind.PRODUCT,601));
        return new ModuleInstallRequest(buyer,key,"license-1","module-1","version-1",authority.requestHash,
                authority.free?"free-v1":"paid-v1",terms().getTermsHash(),draft,1,true,EnumSet.of(ModuleInstallRequest.Change.BIND_RESOURCES),bindings);
    }
    private LicenseTermsSnapshot terms() {return new LicenseTermsSnapshot("terms-v1",TERMS,sha(TERMS),"document:1",LicenseTermsSnapshot.Permission.ALLOWED,
            LicenseTermsSnapshot.Adaptation.BIND_RESOURCES_ONLY,LicenseTermsSnapshot.Permission.PROHIBITED,LicenseTermsSnapshot.Updates.EXACT_PURCHASED_VERSION,
            LicenseTermsSnapshot.Redistribution.PROHIBITED,Collections.singleton("region"),LicenseTermsSnapshot.Limit.atMost(authority.themeLimit),
            LicenseTermsSnapshot.Limit.atMost(2),LicenseTermsSnapshot.Limit.atMost(10));}
    private int count(String table) {return jdbc.queryForObject("select count(*) from "+table,Integer.class);}
    private String state(String key) {return jdbc.queryForObject("select state from w18_module_install_reservation where reservation_key=?",String.class,ReservationIdentity.key(BUYER,key));}
    private void untouched(String key) {assertEquals("RESERVED",state(key));assertEquals(0,count("w18_module_owned_draft"));assertEquals(0,count("w18_module_draft_component"));assertEquals(0,count("w18_module_install_usage"));}
    private JsonNode json(String sql) throws Exception {return new ObjectMapper().readTree(jdbc.queryForObject(sql,String.class));}
    private <T>T transaction(java.util.concurrent.Callable<T> action){return new TransactionTemplate(tx).execute(s->{try{return action.call();}catch(Exception e){throw new IllegalStateException(e);}});}
    private static void reserved(ModuleReservationException.Reason r,Runnable action){assertEquals(r,assertThrows(ModuleReservationException.class,action::run).getReason());}
    private static void denied(ModuleInstallDeniedException.Reason r,Runnable action){assertEquals(r,assertThrows(ModuleInstallDeniedException.class,action::run).getReason());}
    private static String read(String name)throws Exception{Path p=Paths.get(name);if(!Files.exists(p))p=Paths.get("..").resolve(p);return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    private static String migration(String name)throws Exception{return read("chengyinhub-admin/sql/migration_"+name+".sql");}
    private static String sha(byte[] bytes){try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format("%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private interface Work{Object run(int index);}
    private static List<Object> race(int n,Work action)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(n);CountDownLatch start=new CountDownLatch(1);List<Future<Object>> fs=new ArrayList<Future<Object>>();try{for(int i=0;i<n;i++){final int j=i;fs.add(pool.submit(()->{start.await();try{return action.run(j);}catch(RuntimeException e){return e;}}));}start.countDown();List<Object> out=new ArrayList<Object>();for(Future<Object> f:fs)out.add(f.get(15,TimeUnit.SECONDS));return out;}finally{pool.shutdownNow();}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static final class MutableClock extends Clock{final AtomicReference<Instant>now=new AtomicReference<Instant>(NOW);public Instant instant(){return now.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}

    private final class FixtureAuthority implements InstallationAuthority {
        boolean free; long themeLimit=1,usedRuns; int schema=1;
        String version="version-1", contentHash=sha(CONTENT),grantedHash=sha(CONTENT),requestHash=sha(CONTENT),evidence="order-line:1",bindingReference="binding:";
        BuyerPrincipal grantBuyer=BUYER,seller=SELLER;
        Instant validFrom=NOW.minusSeconds(1),validUntil=NOW.plusSeconds(120),bindingUntil=NOW.plusSeconds(100);
        Set<Long> usedThemes=Collections.emptySet(); ModuleVersionSnapshot.SourceKind kind=ModuleVersionSnapshot.SourceKind.MEMBER_TEMPLATE;
        byte[] returnedContent,returnedTerms,reviewedContent=CONTENT; Runnable afterLoad;
        final AtomicInteger contentLoads=new AtomicInteger();
        public BuyerPrincipal resolveBuyer(long actor,BuyerPrincipal requested){return actor==42&&requested.equals(BUYER)&&jdbc.queryForObject("select allowed from fixture_buyer where id=42",Boolean.class)?BUYER:null;}
        public void lockForReservation(long actor,ModuleInstallRequest request){
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());lockBuyerForRelease(actor,request.getBuyer());
            jdbc.queryForObject("select state from fixture_grant where id='license-1' for update",String.class);
            jdbc.queryForObject("select allowed from fixture_version where id='version-1' for update",Boolean.class);
            jdbc.queryForObject("select revision from fixture_target where id=? for update",Long.class,request.getDraftId());
        }
        public void lockBuyerForRelease(long actor,BuyerPrincipal buyer){jdbc.queryForObject("select allowed from fixture_buyer where id=42 for update",Boolean.class);}
        public ModuleLicenseGrant findVerifiedGrant(String id){
            LicenseOfferSnapshot offer=new LicenseOfferSnapshot("offer-1",free?"free-v1":"paid-v1","module-1",seller,EnumSet.allOf(BuyerPrincipal.Kind.class),
                    free?LicenseOfferSnapshot.Acquisition.FREE:LicenseOfferSnapshot.Acquisition.PAID,free?0:100,"CNY",terms());
            return new ModuleLicenseGrant(id,grantBuyer,offer,"version-1",Collections.singletonMap("version-1",grantedHash),
                    ModuleLicenseGrant.Status.valueOf(jdbc.queryForObject("select state from fixture_grant where id=?",String.class,id)),
                    free?ModuleLicenseGrant.EvidenceKind.VERIFIED_FREE_CLAIM:ModuleLicenseGrant.EvidenceKind.VERIFIED_PAID_ORDER_LINE,
                    free?"free-claim:1":evidence,"source-event:1",validFrom,validUntil);
        }
        public ModuleVersionSnapshot findInstallableVersion(String id){
            if(!jdbc.queryForObject("select allowed from fixture_version where id=?",Boolean.class,id))return null;
            Map<String,ModuleVersionSnapshot.BindingKind> b=new LinkedHashMap<String,ModuleVersionSnapshot.BindingKind>();b.put("site",ModuleVersionSnapshot.BindingKind.LOCATION);b.put("product",ModuleVersionSnapshot.BindingKind.PRODUCT);
            byte[] bytes=contentHash.equals(sha(reviewedContent))?reviewedContent:TERMS;
            return new ModuleVersionSnapshot(SELLER,"module-1",version,"1",schema,kind,11,"source-revision:1",bytes,contentHash,"review:1",b);
        }
        public OwnedDraft findDraft(long id){Map<String,Object> r=jdbc.queryForMap("select * from fixture_target where id=?",id);return new OwnedDraft(id,new BuyerPrincipal(BuyerPrincipal.Kind.valueOf((String)r.get("owner_kind")),((Number)r.get("owner_id")).longValue()),((Number)r.get("revision")).longValue(),"region",OwnedDraft.State.valueOf((String)r.get("state")));}
        public BindingApproval approveBinding(BuyerPrincipal buyer,long draft,String name,ModuleInstallRequest.ResourceRef resource,Instant now){return new BindingApproval(name,resource,buyer,draft,resource.getKind()==ModuleVersionSnapshot.BindingKind.PRODUCT?7L:null,"region",bindingReference+name,bindingUntil);}
        public UsageSnapshot findUsage(String id){return new UsageSnapshot(id,1,usedThemes,Collections.<Long>emptySet(),usedRuns);}
        public byte[] loadReviewedContent(ModuleVersionSnapshot version){contentLoads.incrementAndGet();if(afterLoad!=null)afterLoad.run();return returnedContent!=null?returnedContent:jdbc.queryForObject("select content from fixture_version where id='version-1'",byte[].class);}
        public byte[] loadFrozenTerms(LicenseTermsSnapshot terms){return returnedTerms!=null?returnedTerms:TERMS;}
    }
}
