package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static com.chengyinhub.business.service.impl.FundsRecoveryProcessProbe.*;

/**
 * SOURCE ONLY: actual owned child JVM death/restart, production cash admission/SQL/TX.
 * Test provider is a durable controlled fixture, never evidence of real WeChat/money/UAT.
 * No environment skip: missing hosted/schema/process admission fails before creating resources.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class FundsProcessRecoveryMySqlIT {
    // Same installed Java-time support as JsonUtils/TopicContentGraphMySqlFixture. Keep
    // all other original mapper defaults and every original SQL row/column in the digest.
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final long MEMBER = 10001, OTHER = 10002;
    private final List<Child> children = new ArrayList<>();
    private final List<Map<String, Object>> cases = new ArrayList<>();
    private final List<Map<String, Object>> processes = new ArrayList<>();
    private JdbcTemplate admin, db;
    private Store store;
    private String schema, nonce, server, sourceSha, artifactHash, user, password;
    private int port;
    private Path repo, evidence;
    private boolean created;
    private Map<String, String> artifacts;
    private List<Map<String, Object>> protectedRows;
    private String caseName;

    @BeforeAll void admission() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_INTENT_DDL_APPROVED"));
        assertEquals("1", System.getenv("B_FUNDS_PROCESS_RECOVERY_APPROVED"));
        assertEquals(21, Runtime.version().feature());
        assertEquals("Linux", System.getProperty("os.name"), "physical SIGKILL evidence is Linux-specific");
        assertEquals("127.0.0.1", required("CHENGYIN_MYBATIS_IT_HOST"));
        port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT")); assertTrue(port > 0 && port <= 65535);
        user = required("CHENGYIN_MYBATIS_IT_USER"); password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
        assertNotNull(password);
        sourceSha = required("GITHUB_SHA"); assertTrue(sourceSha.matches("[a-f0-9]{40}"));
        repo = Path.of(System.getProperty("architecture.a.repo", "..")).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(repo.resolve("pom.xml")));
        artifacts = FundsRecoveryProcessProbe.artifacts(repo);
        artifactHash = sha(artifacts.toString().getBytes(StandardCharsets.UTF_8));
        evidence = Path.of("target", "funds-process-recovery", sourceSha, UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(evidence);
        admin = new JdbcTemplate(new DriverManagerDataSource(url(port, "mysql"), user, password));
        assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
        server = admin.queryForObject("SELECT @@server_uuid", String.class); assertNotNull(server);
        write("identity.json", Map.of("sourceSha", sourceSha, "parent", "fa5c263440573992701263d6bee60830844a49b8",
                "serverUuid", server, "artifacts", artifacts, "artifactHash", artifactHash,
                "javaExecutableSha256", sha(Files.readAllBytes(Path.of(System.getProperty("java.home"), "bin", "java")))));
    }

    @BeforeEach void schema(TestInfo info) throws Exception {
        assertFalse(created, "do not abandon/overwrite an owned schema whose cleanup is still unknown");
        caseName = info.getTestMethod().orElseThrow().getName();
        schema = "cy_bfunds_" + UUID.randomUUID().toString().replace("-", ""); nonce = UUID.randomUUID().toString().replace("-", "");
        assertTrue(schema.matches("cy_bfunds_[a-f0-9]{32}"));
        admin.execute("CREATE DATABASE " + schema); created = true;
        DriverManagerDataSource source = new DriverManagerDataSource(url(port, schema), user, password);
        db = new JdbcTemplate(source);
        write(caseName + "-owner.json", Map.of("schema", schema, "nonce", nonce, "serverUuid", server,
                "sourceSha", sourceSha, "stage", "EXACT_CREATE_SUCCEEDED_BEFORE_SCHEMA_SETUP"));
        assertEquals(server, db.queryForObject("SELECT @@server_uuid", String.class));
        assertEquals(schema, db.queryForObject("SELECT DATABASE()", String.class));
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("architecture-day-a-fixture.sql"));
            for (String file : List.of("migration_20261023_cash_withdraw_fund_fact.sql",
                    "migration_20261024_cash_withdraw_transfer_intent.sql",
                    "migration_20261025_cash_withdraw_fund_request_compat.sql"))
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(repo.resolve("chengyinhub-admin/sql/" + file)));
        }
        db.execute("CREATE TABLE b_funds_process_owner(nonce VARCHAR(32) PRIMARY KEY,source_sha CHAR(40) NOT NULL,provider_state VARCHAR(16) NOT NULL) ENGINE=InnoDB");
        db.update("INSERT INTO b_funds_process_owner VALUES(?,?,'NOT_FOUND')", nonce, sourceSha);
        db.execute("CREATE TABLE b_funds_process_boot(boot CHAR(32) PRIMARY KEY,member_id BIGINT NOT NULL,withdraw_id BIGINT,"
                + "command_name VARCHAR(32) NOT NULL,gate_name VARCHAR(32) NOT NULL,local_reject INT NOT NULL,corrupt_snapshot INT NOT NULL,"
                + "pid BIGINT,parent_pid BIGINT,start_instant VARCHAR(64),artifact_hash CHAR(64),ready INT NOT NULL DEFAULT 0,reached VARCHAR(32),"
                + "release_gate INT NOT NULL DEFAULT 0,finished INT NOT NULL DEFAULT 0,result_code VARCHAR(64),failure_code VARCHAR(64),failure_type VARCHAR(255),failure_evidence TEXT) ENGINE=InnoDB");
        db.execute("CREATE TABLE b_funds_provider(withdrawal_id BIGINT PRIMARY KEY,source_kind VARCHAR(32) NOT NULL,"
                + "request_hash CHAR(64) NOT NULL,wire_hash CHAR(64) NOT NULL,state_name VARCHAR(32) NOT NULL) ENGINE=InnoDB");
        db.execute("CREATE TABLE b_funds_process_call(id BIGINT AUTO_INCREMENT PRIMARY KEY,withdrawal_id BIGINT NOT NULL,"
                + "action_name VARCHAR(32) NOT NULL,result_code VARCHAR(32) NOT NULL,source_kind VARCHAR(32) NOT NULL,"
                + "request_hash CHAR(64) NOT NULL,wire_hash CHAR(64) NOT NULL,boot VARCHAR(64) NOT NULL) ENGINE=InnoDB");
        db.update("INSERT INTO ums_member(id,balance) VALUES(?,100),(?,123.45)", MEMBER, OTHER);
        db.update("INSERT INTO merchant_referral_bounty(id,inviter_member_id,status,bounty_amount,frozen_until,del_flag)"
                + " VALUES(1,?,0,10,DATE_ADD(NOW(),INTERVAL 1 DAY),'0'),(2,?,0,7,DATE_ADD(NOW(),INTERVAL 1 DAY),'0')", MEMBER, OTHER);
        db.update("INSERT INTO player_withdrawal_cooldown(id,member_id,amount,cooldown_until)"
                + " VALUES(1,?,5,DATE_ADD(NOW(),INTERVAL 1 DAY)),(2,?,6,DATE_ADD(NOW(),INTERVAL 1 DAY))", MEMBER, OTHER);
        store = new Store(source);
        protectedRows = protection();
        save("initial");
    }

    @AfterEach void cleanup() throws Throwable {
        Throwable failure = null;
        // Always attempt every exact owned child; preserve the original test failure via JUnit's lifecycle failures.
        for (Child child : children) try { child.close(); }
        catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        children.clear();
        if (created) {
            try {
                save("final");
                assertEquals(protectedRows, protection(), "unrelated actor and original frozen rows");
                cases.add(Map.of("case", caseName, "schema", schema, "nonce", nonce, "serverUuid", server,
                        "sourceSha", sourceSha, "status", "SQL_CAPTURED_NOT_JUNIT_VERDICT"));
            } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try {
                assertTrue(schema.matches("cy_bfunds_[a-f0-9]{32}"));
                assertEquals(server, admin.queryForObject("SELECT @@server_uuid", String.class));
                // Fresh CREATE succeeded on this exact server; partial setup need not have created the nonce table.
                // Do not let a missing report/owner table exempt our own newly-created schema from cleanup.
                // Child cleanup above must establish physical death before destroying its evidence store.
                assertTrue(processes.stream().filter(p -> schema.equals(p.get("schema"))).allMatch(p -> Boolean.TRUE.equals(p.get("dead"))));
                admin.execute("DROP DATABASE " + schema);
                // A distinct JDBC connection verifies actual absence, not a local 'dropped' flag.
                JdbcTemplate independent = new JdbcTemplate(new DriverManagerDataSource(url(port, "mysql"), user, password));
                assertEquals(0, independent.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, schema));
                write(caseName + "-cleanup.json", Map.of("schema", schema, "nonce", nonce,
                        "serverUuid", server, "independentAbsent", true, "status", "CONFIRMED_EXACT_OWNED_REMOVAL"));
                created = false;
            } catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (evidence != null) {
            try { write("processes.json", processes); write("cases.json", cases); }
            catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        if (failure != null) throw failure;
    }

    @AfterAll void noUnresolvedOwnedResource() throws Exception {
        if (evidence != null) write("resource-status.json", Map.of("sourceSha", sourceSha,
                "ownedSchemaOutstanding", created, "processes", processes,
                "status", created ? "CLEANUP_UNKNOWN" : "CONSULT_JUNIT_AND_PER_CASE_ABSENCE"));
        assertFalse(created, "original cleanup failure remains unresolved; never report CLEAN from an unset local flag");
    }

    @Test void killBeforeAdmissionHasNoDebitAndNeverCreatesAReplacementEconomicIntent() throws Exception {
        Child old = withdraw(Gate.BEFORE_DEBIT, false, false); long id = old.atGate();
        money("100"); counts(id, 0, 0, 0); assertEquals(0, intentCount(id));
        old.killAtGate();
        run(Command.REQUERY, id);
        money("100"); counts(id, 0, 0, 0); assertEquals(0, intentCount(id));
        denyWithoutEconomicChange(Command.DENY_REFUND, id); assertEquals(1, orderCount());
        noSubmit();
    }

    @Test void killInsideRealDebitTransactionRollsBackBalanceFactBindingAndSnapshotTogether() throws Exception {
        Child old = withdraw(Gate.DURING_DEBIT_TX, false, false); long id = old.atGate();
        money("100"); counts(id, 0, 0, 0); assertEquals(0, intentCount(id));
        assertNull(store.cash.selectById(id).getBalanceDeductId());
        old.killAtGate();
        run(Command.REQUERY, id); money("100"); counts(id, 0, 0, 0);
        assertEquals(0, intentCount(id)); assertNull(store.cash.selectById(id).getBalanceDeductId());
        denyWithoutEconomicChange(Command.DENY_REFUND, id); noSubmit();
    }

    @Test void committedDebitBeforeSubmitRestartsIntoOriginalTwoPersonRefundExactlyOnce() throws Exception {
        Child old = withdraw(Gate.DEBIT_COMMITTED, false, false); long id = old.atGate();
        money("70"); counts(id, 1, 0, 0); assertFalse(snapshot(id).submitted());
        Map<String, Object> identity = immutable(id); old.killAtGate();
        run(Command.REQUERY, id); assertEquals(3, status(id));
        run(Command.MAKE_REFUND, id); money("70"); assertEquals(4, status(id));
        denyWithoutEconomicChange(Command.SAME_MAKER, id);
        run(Command.CHECK_REFUND, id); run(Command.CHECK_REFUND, id); run(Command.REQUERY, id);
        money("100"); counts(id, 1, 0, 1); assertEquals(2, status(id)); assertEquals(identity, immutable(id));
        noSubmit(); assertEquals(1, orderCount());
    }

    @Test void committedSubmitBeforeProviderStillRemainsUnknownAfterPhysicalRestart() throws Exception {
        Child old = withdraw(Gate.SUBMIT_COMMITTED, false, false); long id = old.atGate();
        Map<String, Object> identity = immutable(id);
        counts(id, 1, 1, 0); assertTrue(snapshot(id).submitted()); noSubmit();
        old.killAtGate(); run(Command.REQUERY, id); run(Command.REQUERY, id);
        assertEquals(1, factCount(id, "UNKNOWN")); assertEquals(0, factCount(id, "NOT_ACCEPTED"));
        denyWithoutEconomicChange(Command.DENY_REFUND, id);
        money("70"); counts(id, 1, 1, 0); assertEquals(identity, immutable(id)); noSubmit();
    }

    @Test void providerAcceptedBeforeLocalAckSurvivesNotFoundAndLateSuccessWithoutResubmitOrRefund() throws Exception {
        Child old = withdraw(Gate.PROVIDER_ACCEPTED, false, false); long id = old.atGate();
        Map<String, Object> identity = immutable(id); assertEquals(0, factCount(id, "ACCEPTED")); acceptedOnce();
        old.killAtGate();
        run(Command.REQUERY, id); assertEquals(1, factCount(id, "UNKNOWN"));
        assertEquals(0, factCount(id, "NOT_ACCEPTED")); denyWithoutEconomicChange(Command.DENY_REFUND, id);
        provider(ProviderState.SUCCESS); run(Command.REQUERY, id); run(Command.REQUERY, id);
        money("70"); assertEquals(1, status(id)); counts(id, 1, 1, 0);
        assertEquals(1, factCount(id, "SUCCESS")); assertEquals(identity, immutable(id)); acceptedOnce();
    }

    @Test void acknowledgedOriginalIsReadBackAfterKillAndRebootWithoutASecondDispatch() throws Exception {
        Child old = withdraw(Gate.ACKED, false, false); long id = old.atGate();
        assertEquals(1, factCount(id, "ACCEPTED")); assertEquals("fixture-batch-" + id, store.cash.selectById(id).getTransferBatchId());
        Map<String, Object> identity = immutable(id); old.killAtGate();
        provider(ProviderState.SUCCESS); run(Command.REQUERY, id); run(Command.REQUERY, id);
        money("70"); counts(id, 1, 1, 0); assertEquals(1, status(id));
        assertEquals(identity, immutable(id)); acceptedOnce(); assertEquals(1, orderCount());
    }

    @Test void acceptedOriginalFinalDetailFailureRefundsOnceAfterPhysicalRestartAndKeepsBothV1Hashes() throws Exception {
        Child old = withdraw(Gate.PROVIDER_ACCEPTED, false, false); long id = old.atGate();
        Map<String, Object> identity = immutable(id); old.killAtGate();
        provider(ProviderState.FAIL); run(Command.REQUERY, id);
        money("100"); counts(id, 1, 1, 1); assertEquals(2, status(id));
        assertEquals(1, factCount(id, "FINAL_FAIL")); assertEquals(0, factCount(id, "NOT_ACCEPTED"));
        List<Map<String, Object>> terminal = financial();
        run(Command.REQUERY, id); denyWithoutEconomicChange(Command.DENY_REFUND, id);
        assertEquals(terminal, financial()); assertEquals(identity, immutable(id)); acceptedOnce();
    }

    @Test void realPreSdkNotSentProofSurvivesKillAndAuthorizesOnlyOriginalTwoPersonRecovery() throws Exception {
        long id = killedNoSend();
        assertTrue(snapshot(id).submitted()); assertTrue(snapshot(id).locallyRejected());
        assertEquals(1, factCount(id, "NOT_ACCEPTED")); assertEquals(0, factCount(id, "UNKNOWN"));
        Map<String, Object> identity = immutable(id);
        run(Command.REQUERY, id); run(Command.MAKE_REFUND, id); denyWithoutEconomicChange(Command.SAME_MAKER, id);
        run(Command.CHECK_REFUND, id); run(Command.CHECK_REFUND, id); run(Command.REQUERY, id);
        money("100"); assertEquals(2, status(id)); counts(id, 1, 1, 1); assertEquals(identity, immutable(id));
        assertEquals(0, callCount("QUERY")); noSubmit();
        assertEquals("process-maker", store.cash.selectById(id).getManualOperator());
        assertEquals("process-checker", store.cash.selectById(id).getManualVerifier());
    }

    @Test void killInsideActualRefundTransactionRollsBackCreditFactAndTerminalCasBeforeNewChecker() throws Exception {
        long id = killedNoSend(); run(Command.REQUERY, id); run(Command.MAKE_REFUND, id);
        List<Map<String, Object>> before = financial();
        Child checker = start(Command.CHECK_REFUND, Gate.DURING_REFUND_TX, id, false, false);
        checker.atGate(); assertEquals(before, financial(), "uncommitted local credit/receipt are invisible");
        checker.killAtGate(); assertEquals(before, financial(), "physical death rolls back all local effects");
        run(Command.CHECK_REFUND, id); run(Command.CHECK_REFUND, id);
        money("100"); counts(id, 1, 1, 1); assertEquals(2, status(id)); noSubmit();
    }

    @Test void originalAutomaticNoSendRefundSurvivesAckKillAndCannotBeRepeatedByManualRecovery() throws Exception {
        Child old = withdraw(Gate.ACKED, true, false); long id = old.atGate();
        money("100"); counts(id, 1, 1, 1); assertEquals(2, status(id));
        assertEquals(1, factCount(id, "NOT_ACCEPTED")); assertTrue(snapshot(id).locallyRejected());
        Map<String, Object> identity = immutable(id); old.killAtGate();
        List<Map<String, Object>> terminal = financial();
        run(Command.REQUERY, id); run(Command.CHECK_REFUND, id);
        denyWithoutEconomicChange(Command.DENY_REFUND, id);
        assertEquals(terminal, financial()); assertEquals(identity, immutable(id)); noSubmit();
    }

    @Test void staleOriginalAutomaticPathCannotOverwriteANewProcessesManualRefund() throws Exception {
        Child old = withdraw(Gate.NOT_SENT_COMMITTED, true, false); long id = old.atGate();
        assertEquals(1, factCount(id, "NOT_ACCEPTED")); money("70");
        run(Command.REQUERY, id); run(Command.MAKE_REFUND, id); run(Command.CHECK_REFUND, id);
        List<Map<String, Object>> finished = financial();
        old.release(); old.finish(0);
        assertEquals(finished, financial(), "old automatic claim loses the same original CAS");
        money("100"); counts(id, 1, 1, 1); noSubmit();
    }

    @Test void oldUnknownQueryCannotReverseSuccessCommittedByASeparateBoot() throws Exception {
        Child producer = withdraw(Gate.ACKED, false, false); long id = producer.atGate(); producer.killAtGate();
        provider(ProviderState.UNKNOWN);
        Child oldQuery = start(Command.REQUERY, Gate.QUERY_CAPTURED, id, false, false); oldQuery.atGate();
        provider(ProviderState.SUCCESS); run(Command.REQUERY, id);
        List<Map<String, Object>> terminal = financial();
        oldQuery.release(); oldQuery.finish(0);
        assertEquals(terminal, financial(), "late original UNKNOWN must not overwrite the successful receipt");
        money("70"); counts(id, 1, 1, 0); assertEquals(1, status(id)); acceptedOnce();
    }

    @Test void preIntegrationMasterOnlySnapshotSurvivesPhysicalQueryLossWithoutInventingLedgerProvenance() throws Exception {
        Child create = withdraw(Gate.BEFORE_DEBIT, false, false); long id = create.atGate(); create.killAtGate();
        // Explicit historical fixture, same as original master-only IT: not a claim that current admission omits fund_fact.
        CashWithdrawTransferIntent historic = new TransactionTemplate(store.tx).execute(tx -> {
            MemberCashWithdraw original = store.cash.selectByIdForUpdate(id);
            assertEquals(1, store.members.deductWithdrawableBalance(MEMBER, AMOUNT, new BigDecimal("15")));
            CashWithdrawTransferIntent record = CashWithdrawTransferIntent.create(original, "historical-mch", "historical-app",
                    "historical remark", new BigDecimal("100"), new BigDecimal("70"));
            assertEquals(1, store.intents.insert(record));
            assertEquals(1, store.intents.markSubmitted(id, record.requestHash()));
            return store.intents.selectByWithdrawId(id);
        });
        assertNotNull(historic); assertEquals(0, allFactCount(id)); assertNull(store.cash.selectById(id).getBalanceDeductId());
        store.recordProvider(historic, "HISTORICAL_CONTROLLED_ACCEPTANCE");
        Map<String, Object> identity = immutable(id);
        provider(ProviderState.UNKNOWN);
        Child old = start(Command.REQUERY, Gate.QUERY_CAPTURED, id, false, false); old.atGate(); old.killAtGate();
        provider(ProviderState.NOT_FOUND); run(Command.REQUERY, id); denyWithoutEconomicChange(Command.DENY_REFUND, id);
        provider(ProviderState.SUCCESS); run(Command.REQUERY, id); run(Command.REQUERY, id);
        money("70"); assertEquals(1, status(id)); assertEquals(0, allFactCount(id)); assertEquals(identity, immutable(id));
        assertEquals(CashWithdrawTransferIntent.OriginalSource.TRANSFER_INTENT, snapshot(id).originalSource()); noSubmit();
    }

    @Test void independentlyValidV1SourcesWithContradictoryDebitSnapshotsBlockRecoveryWithoutChoosingAWinner() throws Exception {
        // Test-only producer fault changes the snapshot proposed to the real INSERT, not a read return or later immutable row.
        Child old = withdraw(Gate.DEBIT_COMMITTED, false, true); long id = old.atGate();
        snapshot(id).requireValid(); money("70"); counts(id, 1, 0, 0);
        assertEquals(0, new BigDecimal("101").compareTo(snapshot(id).balanceBefore()));
        List<Map<String, Object>> before = financial(); old.killAtGate();
        Child recovery = start(Command.REQUERY, Gate.NONE, id, false, false); recovery.finish(2);
        assertEquals("ORIGINAL_SOURCE_CONFLICT", db.queryForObject(
                "SELECT failure_code FROM b_funds_process_boot WHERE boot=?", String.class, recovery.boot));
        assertEquals(before, financial()); denyWithoutEconomicChange(Command.DENY_REFUND, id);
        counts(id, 1, 0, 0); money("70"); noSubmit();
    }

    private Child withdraw(Gate gate, boolean localReject, boolean corrupt) throws Exception {
        return start(Command.WITHDRAW, gate, null, localReject, corrupt);
    }
    private Child start(Command command, Gate gate, Long id, boolean localReject, boolean corrupt) throws Exception {
        Child child = new Child(command, gate, id, localReject, corrupt); children.add(child); return child;
    }
    private void run(Command command, long id) throws Exception { start(command, Gate.NONE, id, false, false).finish(0); }
    private long killedNoSend() throws Exception {
        Child old = withdraw(Gate.NOT_SENT_COMMITTED, true, false); long id = old.atGate(); old.killAtGate(); return id;
    }
    private void provider(ProviderState state) { assertEquals(1, db.update("UPDATE b_funds_process_owner SET provider_state=?", state.name())); }
    private int status(long id) { return db.queryForObject("SELECT status FROM member_cash_withdraw WHERE id=?", Integer.class, id); }
    private CashWithdrawTransferIntent snapshot(long id) { return store.intents.selectByWithdrawId(id); }
    private int factCount(long id, String kind) { return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=? AND kind=?", Integer.class, id, kind); }
    private int allFactCount(long id) { return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=?", Integer.class, id); }
    private int intentCount(long id) { return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_transfer_intent WHERE withdraw_id=?", Integer.class, id); }
    private int orderCount() { return db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw WHERE member_id=?", Integer.class, MEMBER); }
    private int callCount(String action) { return db.queryForObject("SELECT COUNT(*) FROM b_funds_process_call WHERE action_name=?", Integer.class, action); }
    private void noSubmit() { assertEquals(0, callCount("SUBMIT")); assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM b_funds_provider WHERE state_name='ACCEPTED'", Integer.class)); }
    private void acceptedOnce() { assertEquals(1, callCount("SUBMIT")); assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM b_funds_provider", Integer.class)); }
    private void counts(long id, int debit, int submit, int refund) {
        assertEquals(debit, factCount(id, "DEBIT")); assertEquals(submit, factCount(id, "SUBMIT_INTENT")); assertEquals(refund, factCount(id, "REFUND"));
    }
    private void money(String expected) { assertEquals(0, new BigDecimal(expected).compareTo(db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, MEMBER))); }
    private void denyWithoutEconomicChange(Command command, long id) throws Exception {
        List<Map<String, Object>> before = financial(); run(command, id); assertEquals(before, financial());
    }
    private Map<String, Object> immutable(long id) {
        CashWithdrawTransferIntent original = snapshot(id); original.requireValid(); original.requireMatches(store.cash.selectById(id));
        Map<String, Object> identity = new TreeMap<>();
        identity.put("row", db.queryForMap("SELECT id,member_id,openid,amount,out_batch_no,out_detail_no,balance_deduct_id FROM member_cash_withdraw WHERE id=?", id));
        identity.put("snapshot", db.queryForMap("SELECT withdraw_id,member_id,mchid,appid,out_batch_no,out_detail_no,amount_fen,openid,remark,request_version,request_hash,balance_before,balance_after FROM member_cash_withdraw_transfer_intent WHERE withdraw_id=?", id));
        identity.put("submit", db.queryForList("SELECT id,withdrawal_id,member_id,amount,out_batch_no,out_detail_no,request_hash FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=? AND kind='SUBMIT_INTENT'", id));
        return identity;
    }
    private List<Map<String, Object>> protection() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.addAll(db.queryForList("SELECT * FROM ums_member WHERE id=?", OTHER));
        result.addAll(db.queryForList("SELECT * FROM merchant_referral_bounty ORDER BY id"));
        result.addAll(db.queryForList("SELECT * FROM player_withdrawal_cooldown ORDER BY id")); return result;
    }
    private List<Map<String, Object>> financial() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String table : List.of("ums_member", "member_cash_withdraw", "member_cash_withdraw_fund_fact",
                "member_cash_withdraw_transfer_intent")) {
            String order = table.equals("member_cash_withdraw_transfer_intent") ? "withdraw_id" : "id";
            result.add(Map.of("table", table, "rows", db.queryForList("SELECT * FROM " + table + " ORDER BY " + order)));
        } return result;
    }
    private void save(String phase) throws Exception {
        if (db == null || evidence == null) return;
        // Independent SQL, not a service return/log. Public evidence keeps full-row digests, not raw identity fields.
        List<Map<String, Object>> values = financial();
        Map<String, Object> report = new TreeMap<>();
        report.put("sourceSha", sourceSha); report.put("schema", schema); report.put("serverUuid", server);
        report.put("phase", phase); report.put("fullFinancialRowsSha256", sha(JSON.writeValueAsBytes(values)));
        report.put("actorBalance", db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, MEMBER));
        report.put("withdrawals", db.queryForList("SELECT id,member_id,amount,out_batch_no,out_detail_no,status,transfer_batch_id,wechat_detail_id,"
                + "balance_deduct_id,balance_refund_id,manual_operator,manual_verifier,audit_by,audit_time,SHA2(openid,256) AS openid_hash FROM member_cash_withdraw ORDER BY id"));
        report.put("intents", db.queryForList("SELECT withdraw_id,member_id,mchid,appid,out_batch_no,out_detail_no,amount_fen,request_version,request_hash,"
                + "balance_before,balance_after,submit_time,local_rejection_time,SHA2(openid,256) AS openid_hash,SHA2(remark,256) AS remark_hash "
                + "FROM member_cash_withdraw_transfer_intent ORDER BY withdraw_id"));
        report.put("facts", db.queryForList("SELECT id,withdrawal_id,member_id,amount,kind,out_batch_no,out_detail_no,submission_intent_id,"
                + "balance_before,balance_after,request_mchid,request_appid,request_amount_fen,request_version,request_hash,"
                + "channel_batch_id,channel_detail_id,channel_batch_status,channel_detail_status,SHA2(request_openid,256) AS request_openid_hash "
                + "FROM member_cash_withdraw_fund_fact ORDER BY id"));
        report.put("protectedActorBalance", db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, OTHER));
        report.put("providerCalls", db.queryForList("SELECT * FROM b_funds_process_call ORDER BY id"));
        report.put("protectionSha256", sha(JSON.writeValueAsBytes(protection())));
        report.put("boots", db.queryForList("SELECT * FROM b_funds_process_boot ORDER BY boot"));
        write(caseName + "-" + phase + ".json", report);
    }
    private void write(String filename, Object value) throws Exception { Files.write(evidence.resolve(filename), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private static void await(BooleanSupplier ready, String phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) { if (ready.getAsBoolean()) return; TimeUnit.MILLISECONDS.sleep(25); }
        fail("Missing owned child phase: " + phase);
    }

    private final class Child implements AutoCloseable {
        final String boot = UUID.randomUUID().toString().replace("-", "");
        final Process process;
        final Instant startInstant;
        final Gate gate;
        final Map<String, Object> record = new LinkedHashMap<>();
        boolean killed, closed;
        Child(Command command, Gate gate, Long id, boolean localReject, boolean corrupt) throws Exception {
            this.gate = gate;
            assertEquals(1, db.update("INSERT INTO b_funds_process_boot(boot,member_id,withdraw_id,command_name,gate_name,local_reject,corrupt_snapshot) VALUES(?,?,?,?,?,?,?)",
                    boot, MEMBER, id, command.name(), gate.name(), localReject ? 1 : 0, corrupt ? 1 : 0));
            String classpath = System.getProperty("surefire.test.class.path");
            if (classpath == null || classpath.isBlank()) classpath = System.getProperty("java.class.path");
            assertNotNull(classpath); assertFalse(classpath.isBlank());
            List<String> argv = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", classpath, FundsRecoveryProcessProbe.class.getName());
            ProcessBuilder launch = new ProcessBuilder(argv);
            launch.environment().clear();
            Map<String, String> env = launch.environment();
            env.put("RUNNER_ENVIRONMENT", "github-hosted"); env.put("B_PAYMENT_INTENT_DDL_APPROVED", "1");
            env.put("B_FUNDS_PROCESS_RECOVERY_APPROVED", "1");
            env.put("CHENGYIN_MYBATIS_IT_HOST", "127.0.0.1"); env.put("CHENGYIN_MYBATIS_IT_PORT", Integer.toString(port));
            env.put("CHENGYIN_MYBATIS_IT_USER", user); env.put("CHENGYIN_MYBATIS_IT_PASSWORD", password);
            env.put("BFP_SCHEMA", schema); env.put("BFP_NONCE", nonce); env.put("BFP_BOOT", boot);
            env.put("BFP_SERVER_UUID", server); env.put("GITHUB_SHA", sourceSha); env.put("BFP_REPO", repo.toString());
            env.put("BFP_ARTIFACT_HASH", artifactHash);
            launch.redirectOutput(evidence.resolve(boot + ".stdout").toFile());
            launch.redirectError(evidence.resolve(boot + ".stderr").toFile());
            process = launch.start();
            startInstant = process.toHandle().info().startInstant().orElse(null);
            record.put("boot", boot); record.put("schema", schema); record.put("sourceSha", sourceSha);
            record.put("pid", process.pid()); record.put("startInstant", startInstant == null ? "UNKNOWN" : startInstant.toString());
            record.put("parentPid", ProcessHandle.current().pid()); record.put("command", argv);
            record.put("artifactHash", artifactHash); record.put("dead", false); processes.add(record);
            try {
                assertNotNull(startInstant, "owned child start identity is unknown; still clean its exact creator handle");
                assertTrue(processes.stream().filter(previous -> previous != record).noneMatch(previous ->
                        previous.get("pid").equals(process.pid()) && previous.get("startInstant").equals(startInstant.toString())),
                        "new boot must be a distinct OS process identity");
                await(() -> !process.isAlive() || db.queryForObject(
                        "SELECT ready FROM b_funds_process_boot WHERE boot=?", Integer.class, boot) == 1, "READY");
                assertEquals(1, db.queryForObject("SELECT ready FROM b_funds_process_boot WHERE boot=?", Integer.class, boot),
                        "child failed before READY; preserve exact stdout/stderr and first failure");
                Map<String, Object> ready = db.queryForMap("SELECT pid,parent_pid,start_instant,artifact_hash FROM b_funds_process_boot WHERE boot=?", boot);
                assertEquals(process.pid(), ((Number) ready.get("pid")).longValue());
                assertEquals(startInstant.toString(), ready.get("start_instant")); assertEquals(artifactHash, ready.get("artifact_hash"));
                assertNotEquals(ProcessHandle.current().pid(), process.pid());
                assertEquals(ProcessHandle.current().pid(), ((Number) ready.get("parent_pid")).longValue());
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        long atGate() throws Exception {
            assertNotEquals(Gate.NONE, gate);
            await(() -> !process.isAlive() || gate.name().equals(db.queryForObject(
                    "SELECT reached FROM b_funds_process_boot WHERE boot=?", String.class, boot)), gate.name());
            assertTrue(process.isAlive(), "gate must block a living child");
            assertEquals(gate.name(), db.queryForObject("SELECT reached FROM b_funds_process_boot WHERE boot=?", String.class, boot));
            long id = db.queryForObject("SELECT withdraw_id FROM b_funds_process_boot WHERE boot=?", Long.class, boot);
            record.put("gate", gate.name()); record.put("originalWithdrawalId", id); save("gate-" + boot); return id;
        }
        void release() { assertEquals(1, db.update("UPDATE b_funds_process_boot SET release_gate=1 WHERE boot=?", boot)); }
        void killAtGate() throws Exception {
            assertTrue(process.isAlive()); assertNotEquals(ProcessHandle.current().pid(), process.pid());
            assertEquals(startInstant, process.toHandle().info().startInstant().orElseThrow());
            assertTrue(record.containsKey("gate"), "no physical kill until original gated boundary observed");
            killed = true; record.put("termination", "Process.destroyForcibly exact owned handle");
            process.destroyForcibly(); assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertFalse(process.isAlive());
            assertEquals(137, process.exitValue(), "held Linux child must exit from SIGKILL, not a fixture exception");
            record.put("exitCode", process.exitValue()); record.put("dead", true);
            long id = ((Number) record.get("originalWithdrawalId")).longValue();
            TransactionTemplate released = new TransactionTemplate(store.tx); released.setTimeout(10);
            released.executeWithoutResult(tx -> {
                assertNotNull(store.cash.selectByIdForUpdate(id));
                assertNotNull(store.members.selectBalanceForUpdate(MEMBER));
            }); // Real current-row acquisition is the server rollback/lock-release barrier.
            record.put("mysqlRollbackLocksReleased", true);
            write("processes.json", processes);
        }
        void finish(int expected) throws Exception {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "exact child must finish");
            assertEquals(expected, process.exitValue(), "see preserved child diagnostic, not a synthetic success");
            record.put("exitCode", process.exitValue()); record.put("dead", true);
            if (expected == 0) assertEquals(1, db.queryForObject("SELECT finished FROM b_funds_process_boot WHERE boot=?", Integer.class, boot));
        }
        @Override public void close() throws Exception {
            if (closed) return;
            if (process.isAlive()) {
                assertNotEquals(ProcessHandle.current().pid(), process.pid());
                if (startInstant != null) assertEquals(startInstant, process.toHandle().info().startInstant().orElseThrow());
                else record.put("startIdentity", "UNKNOWN_NOT_ACCEPTED"); // Cleanup uses the exact Process we created, never PID discovery.
                process.destroyForcibly();
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "owned cleanup death unconfirmed");
                record.put("termination", "cleanup exact owned handle");
            }
            assertFalse(process.isAlive()); record.put("exitCode", process.exitValue()); record.put("dead", true); closed = true;
            if (!killed && process.exitValue() != 0 && !"ORIGINAL_SOURCE_CONFLICT".equals(db.queryForObject(
                    "SELECT failure_code FROM b_funds_process_boot WHERE boot=?", String.class, boot)))
                fail("unexpected owned child exit; keep original failure and resource evidence");
        }
    }
}
