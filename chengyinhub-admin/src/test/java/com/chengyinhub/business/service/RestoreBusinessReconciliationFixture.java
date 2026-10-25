package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.service.impl.JdbcWechatPaymentOrderOwner;
import com.chengyinhub.business.service.impl.RegistrationOrderServiceImpl;
import com.chengyinhub.business.service.impl.WechatPaymentIntentStore;
import com.chengyinhub.business.service.impl.WechatPaymentSdkGateway;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.common.config.WxPayConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fixed test-classpath child. Reuses the original financial fixture; never packaged in the application JAR. */
public final class RestoreBusinessReconciliationFixture {
    private static final String MERCHANT = "mch-funds-it";
    private static final String APP = "wx-funds-it";
    private final RegistrationFundsMybatisIntegrationTest original = new RegistrationFundsMybatisIntegrationTest();
    private final Map<String, Map<String, Object>> commands = new LinkedHashMap<>();
    private final String scope;
    private final Path journal;
    private JdbcTemplate db;
    private ICmsRegistrationService registrations;
    private WechatVerifiedPaymentFactService facts;
    private WechatPaymentIntentStore intents;
    private long sequence;
    private String previous = "0".repeat(64);

    private RestoreBusinessReconciliationFixture(String scope, Path journal) {
        this.scope = scope;
        this.journal = journal;
    }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "No command-line endpoints or arbitrary phases");
            require("true".equals(System.getenv("CHENGYIN_CI_RESTORE_BUSINESS")), "Explicit hosted gate required");
            require("github-hosted".equals(System.getenv("RUNNER_ENVIRONMENT")), "Hosted only");
            String scope = System.getenv("CHENGYIN_RESTORE_SCOPE");
            require(scope != null && scope.matches("[a-f0-9]{32}"), "Exact scope required");
            var config = MysqlIntegrationTestConfig.fromEnvironment("funds_core_it");
            require(config.databaseName().equals("cy_restore_" + scope + "_funds_core_it"), "Foreign schema");
            require("127.0.0.1".equals(System.getenv("CHENGYIN_TEST_MYSQL_HOST")), "Owned loopback relay only");
            Path journal = Path.of(System.getenv("CHENGYIN_RESTORE_PROVIDER_JOURNAL")).toRealPath();
            require(!Files.isSymbolicLink(journal), "Owned regular journal required");
            if ("RESTORED_GUARD".equals(System.getenv("CHENGYIN_RESTORE_PHASE"))) {
                RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.OWNER_OBSERVATION);
                Path evidence = Path.of(System.getenv("CHENGYIN_RESTORE_GUARD_FILE"));
                require(evidence.toAbsolutePath().getParent().equals(journal.getParent()), "Private guard evidence only");
                Files.createFile(evidence, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
                Files.writeString(evidence, JSON.toJSONString(disabledGatewayEvidence()), StandardOpenOption.WRITE);
                return;
            }
            require(System.getenv("CHENGYIN_RESTORE_PHASE") == null && Files.size(journal) == 0, "Fresh fixed timeline only");
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_ASSEMBLY);
            var fixture = new RestoreBusinessReconciliationFixture(scope, journal);
            fixture.beforeBackup();
            RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.BACKUP_BOUNDARY);
            signal("BACKUP_READY");
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                require("AFTER_BACKUP".equals(input.readLine()), "Only frozen timeline is accepted");
                RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.AFTER_BACKUP);
                fixture.afterBackup();
                RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.FAULT_BOUNDARY);
                signal("FAULT_READY");
                require("STOP".equals(input.readLine()), "Only original child may finish");
            }
            // Host owns exact source/target cleanup. Never call the old fixture's DROP lifecycle here.
        } catch (Throwable failure) {
            RestoreChildDiagnostics.report(failure);
            System.err.println("Restore financial fixture rejected; inspect private evidence (no exception payload emitted)");
            System.exit(1);
        }
    }

    private void beforeBackup() throws Exception {
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_IDENTITY);
        var config = MysqlIntegrationTestConfig.fromEnvironment("funds_core_it");
        var admin = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                config.adminUrl(), config.user(), config.password()));
        require(admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class, config.databaseName()) == 0, "Original fixture must not drop an existing schema");
        require(System.getenv("CHENGYIN_RESTORE_SOURCE_UUID").equals(
                admin.queryForObject("SELECT @@server_uuid", String.class)), "Wrong source UUID");
        require("OFF".equals(admin.queryForObject("SELECT @@global.event_scheduler", String.class)), "Events must be off");
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_SCHEMA);
        original.createDatabaseAndService();
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_RESET);
        original.resetSchema();
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_ASSEMBLY);
        var source = (javax.sql.DataSource) ReflectionTestUtils.getField(original, "dataSource");
        db = new JdbcTemplate(source);
        registrations = (ICmsRegistrationService) ReflectionTestUtils.getField(original, "registrationService");
        facts = (WechatVerifiedPaymentFactService) ReflectionTestUtils.getField(original, "verifiedFacts");
        var owner = new JdbcWechatPaymentOrderOwner();
        ReflectionTestUtils.setField(owner, "paymentIntentJdbc", db);
        intents = new WechatPaymentIntentStore();
        ReflectionTestUtils.setField(intents, "paymentIntentJdbc", db);
        ReflectionTestUtils.setField(intents, "paymentIntentTransactions", ReflectionTestUtils.getField(original, "transactionManager"));
        ReflectionTestUtils.setField(intents, "businessOwner", owner);
        ReflectionTestUtils.setField(intents, "verifiedFacts", facts);
        // This original seed explicitly predates frozen request hashes. Do not convert it into a current-config intent.
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.HISTORICAL_PAYMENT);
        CmsRegistration historical = registrations.selectCmsRegistrationById(11L);
        require(historical != null && "T20260724PAY".equals(historical.getRegistrationNo()), "Historical original missing");
        Map<String, Object> legacy = command("historical_paid", historical, null, null);
        event(legacy, "SUBMIT"); event(legacy, "SUCCESS");
        applySuccess(legacy);
        require(db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact WHERE registration_id=11", Integer.class) == 1,
                "Real payment consumption must commit");
        require(db.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class) > 0,
                "Real reward ledger must commit before backup");
        db.update("UPDATE oms_ticket SET remaining_inventory=10 WHERE id=9"); // fixture availability, not money/effect fabrication
        RestoreChildDiagnostics.stage(RestoreChildDiagnostics.Stage.SOURCE_TIMELINE);
        create("late_success"); event(commands.get("late_success"), "SUBMIT"); event(commands.get("late_success"), "ACCEPTED");
        create("not_sent"); event(commands.get("not_sent"), "NOT_SENT");
        create("unknown"); event(commands.get("unknown"), "SUBMIT"); event(commands.get("unknown"), "UNKNOWN");
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Backup boundary cannot hold transaction locks");
    }

    private void afterBackup() throws Exception {
        event(commands.get("late_success"), "SUCCESS"); // Original ACK lost; no local callback or fabricated receipt.
        create("lost_success");
        var lost = commands.get("lost_success");
        event(lost, "SUBMIT"); event(lost, "SUCCESS");
        applySuccess(lost); // Entire original registration, intent, verified fact and reward are lost on restore.
        int effects = db.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class);
        applySuccess(lost);
        require(effects == db.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class),
                "Original callback replay must not add another reward");
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Fault boundary cannot hold transaction locks");
    }

    private void create(String name) throws Exception {
        var request = new RegistrationRequest();
        request.setOwnerType(2); request.setOwnerId(55L); request.setTicketId(9L); request.setIsUsePoint(0);
        request.setRealName("恢复隔离夹具"); request.setPhone("13800000000"); request.setRequestId("restore-" + scope + "-" + name);
        var orders = (RegistrationOrderServiceImpl) ReflectionTestUtils.getField(original, "registrationOrderService");
        var result = orders.createOrReplay(7L, request);
        require(!result.isReplayed() && result.getRegistrations().size() == 1, "One fresh original required");
        CmsRegistration registration = registrations.beginPaymentAttempt(result.getPrimary().getId(), 7L);
        int fen = registration.getWechatPaymentAmount().movePointRight(2).intValueExact();
        // The production permanent-identity owner commits REQUIRES_NEW. No test INSERT of a SUBMIT_INTENT.
        String wxNo = ReflectionTestUtils.invokeMethod(intents, "freezePaymentIntent", registration.getRegistrationNo(),
                MERCHANT, APP, "JSAPI", "CNY", fen, "fixture-openid", "恢复隔离夹具", "https://notify.invalid/fixture",
                (Runnable) () -> { /* This new test-owned order has no prior controlled-provider entry. */ });
        var frozen = db.queryForMap("SELECT * FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?", MERCHANT, wxNo);
        var command = command(name, registration, wxNo, (String) frozen.get("request_hash"));
        command.put("intentId", ((Number) frozen.get("id")).longValue());
        commands.put(name, command);
    }

    private Map<String, Object> command(String name, CmsRegistration row, String wxNo, String hash) {
        var command = new LinkedHashMap<String, Object>();
        command.put("case", name); command.put("scope", scope); command.put("domain", "REGISTRATION");
        command.put("bizId", row.getId()); command.put("businessNo", row.getRegistrationNo());
        command.put("wxNo", wxNo == null ? row.getRegistrationNo() : wxNo);
        command.put("fingerprint", hash); command.put("intentId", null);
        command.put("origin", hash == null ? "HISTORICAL_MASTER_ONLY:T20260724PAY:original-funds-fixture" : "FROZEN_ORIGINAL");
        command.put("mchid", MERCHANT); command.put("appid", APP); command.put("currency", "CNY");
        command.put("totalFen", row.getWechatPaymentAmount().movePointRight(2).longValueExact());
        command.put("providerId", "FIXTURE-TX-" + name + "-" + scope);
        return command;
    }

    private void applySuccess(Map<String, Object> command) {
        var paid = new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT, MERCHANT, APP,
                (String) command.get("businessNo"), (String) command.get("wxNo"), (String) command.get("wxNo"),
                (String) command.get("providerId"), (String) command.get("providerId"), "JSAPI", "CNY",
                ((Number) command.get("totalFen")).longValue(), ((Number) command.get("totalFen")).longValue(),
                Instant.now(), (String) command.get("fingerprint"), "SUCCESS");
        var reference = facts.findPaymentSuccess(MERCHANT, paid.wxOutTradeNo(), paid.transactionId());
        var ref = reference == null ? facts.recordVerified(paid) : reference.reference();
        Map<String, String> callback = new LinkedHashMap<>();
        callback.put("out_trade_no", paid.businessOutTradeNo()); callback.put("transaction_id", paid.transactionId());
        callback.put("trade_state", "SUCCESS"); callback.put("total", Long.toString(paid.totalFen()));
        callback.put("verified_fact_id", Long.toString(ref.id())); callback.put("verified_fact_hash", ref.hash());
        callback.put("wx_out_trade_no", paid.wxOutTradeNo()); callback.put("mchid", MERCHANT); callback.put("appid", APP);
        callback.put("trade_type", "JSAPI"); callback.put("currency", "CNY");
        require("SUCCESS".equals(registrations.handleWechatPayCallback(callback)), "Original financial business callback refused");
    }

    private synchronized void event(Map<String, Object> command, String state) throws Exception {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Controlled provider must be outside business transaction");
        var row = new LinkedHashMap<String, Object>();
        row.put("sequence", ++sequence); row.put("previous", previous); row.put("at", Instant.now().toString());
        row.put("command", command); row.put("state", state);
        String payload = JSON.toJSONString(row, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue);
        previous = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        String line = JSON.toJSONString(Map.of("payload", payload, "sha256", previous)) + "\n";
        try (var file = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                var lock = file.lock()) {
            ByteBuffer data = StandardCharsets.UTF_8.encode(line);
            while (data.hasRemaining()) file.write(data);
            file.force(true); // Journal path is external to both MySQL instances and never part of the dump.
        }
    }

    /** Fresh disabled production gateway: actual init/readiness/create guard, not an invented fixture switch. */
    public static Map<String, Object> disabledGatewayEvidence() {
        var config = new WxPayConfig(); // production default false, no credentials or SDK injected
        var gateway = new WechatPaymentSdkGateway();
        ReflectionTestUtils.setField(gateway, "wxPayConfig", config);
        gateway.init();
        require(!gateway.isReady() && !gateway.isAppReady(), "Disabled production gateway became ready");
        try {
            gateway.createGenericAppOrder("RESTORE_DISABLED_PROBE", BigDecimal.ONE, "isolated restore guard");
            throw new IllegalStateException("Disabled gateway admitted new write");
        } catch (PaymentPreorderException expected) {
            return Map.of("state", "SDK_DISABLED_ONLY", "createRejected", true,
                    "allDispatchDomainsFenced", false, "recoveryRelease", "BLOCKED");
        }
    }

    private static void signal(String phase) {
        System.out.println("RESTORE_BUSINESS_PHASE:" + phase); System.out.flush();
    }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalStateException(reason); }
}
