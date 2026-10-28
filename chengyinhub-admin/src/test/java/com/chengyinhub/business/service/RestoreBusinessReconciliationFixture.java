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
    private ISettlementEntryService activityEntries;
    private com.chengyinhub.business.service.impl.AlarmService callbackAlarm;
    private final java.util.concurrent.atomic.AtomicReference<RuntimeException> mutationFailure = new java.util.concurrent.atomic.AtomicReference<>();
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
        wireActivityPayment(source);
        Object target = ReflectionTestUtils.getField(original, "registrationTarget");
        callbackAlarm = (com.chengyinhub.business.service.impl.AlarmService) ReflectionTestUtils.getField(target, "alarmService");
        ReflectionTestUtils.setField(target, "wxPaymentCallbackInboxService", PaymentCallbackInboxStubs.passThrough(
                (org.springframework.transaction.PlatformTransactionManager) ReflectionTestUtils.getField(original, "transactionManager"),
                facts, mutationFailure::set));
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
        var paidRows = economicRows();
        applySuccess(lost);
        require(effects == db.queryForObject("SELECT COUNT(*) FROM ums_member_points_detail", Integer.class),
                "Original callback replay must not add another reward");
        require(paidRows.equals(economicRows()), "Original callback replay changed committed financial rows");
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
        requireOriginalActivityCash(registration, originalActivity(registration.getOwnerId()));
        int fen = registration.getWechatPaymentAmount().movePointRight(2).intValueExact();
        // The production permanent-identity owner commits REQUIRES_NEW. No test INSERT of a SUBMIT_INTENT.
        String wxNo = ReflectionTestUtils.invokeMethod(intents, "freezePaymentIntent", registration.getRegistrationNo(),
                MERCHANT, APP, "JSAPI", "CNY", fen, "fixture-openid", "恢复隔离夹具", "https://notify.invalid/fixture",
                (Runnable) () -> { /* This new test-owned order has no prior controlled-provider entry. */ });
        var frozen = db.queryForMap("SELECT * FROM wx_pay_order_intent WHERE mchid=? AND out_trade_no=?", MERCHANT, wxNo);
        int attempt = frozenAttempt(frozen, registration, wxNo);
        var command = command(name, registration, wxNo, (String) frozen.get("request_hash"));
        command.put("attemptNo", attempt);
        commands.put(name, command);
    }

    static int frozenAttempt(Map<String, Object> frozen, CmsRegistration registration, String wxNo) {
        // Canonical PK is (mchid,out_trade_no); never substitute a business ID or invent a surrogate intent ID.
        require(MERCHANT.equals(frozen.get("mchid")) && APP.equals(frozen.get("appid"))
                && wxNo.equals(frozen.get("out_trade_no"))
                && registration.getRegistrationNo().equals(frozen.get("business_out_trade_no"))
                && "REGISTRATION".equals(frozen.get("biz_type"))
                && frozen.get("biz_id") instanceof Number biz
                && new BigDecimal(biz.toString()).longValueExact() == registration.getId()
                && "JSAPI".equals(frozen.get("trade_type")) && "CNY".equals(frozen.get("currency"))
                && frozen.get("total_fen") instanceof Number amount
                && new BigDecimal(amount.toString()).longValueExact() == registration.getWechatPaymentAmount().movePointRight(2).longValueExact()
                && frozen.get("request_hash") instanceof String hash && hash.matches("[a-f0-9]{64}")
                && frozen.containsKey("closed_confirmed_at") && frozen.get("closed_confirmed_at") == null,
                "Original frozen payment identity differs");
        require(frozen.get("attempt_no") instanceof Number, "Original frozen attempt missing");
        int attempt = new BigDecimal(frozen.get("attempt_no").toString()).intValueExact();
        require(attempt > 0, "Original frozen attempt invalid");
        return attempt;
    }

    private Map<String, Object> command(String name, CmsRegistration row, String wxNo, String hash) {
        var command = new LinkedHashMap<String, Object>();
        command.put("case", name); command.put("scope", scope); command.put("domain", "REGISTRATION");
        command.put("bizId", row.getId()); command.put("businessNo", row.getRegistrationNo());
        command.put("wxNo", wxNo == null ? row.getRegistrationNo() : wxNo);
        command.put("fingerprint", hash); command.put("attemptNo", null);
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
        if ("lost_success".equals(command.get("case")) && reference == null) {
            // The verified provider original already exists. Rejected projections may not consume it or change money/rights.
            var before = economicRows();
            for (String field : java.util.List.of("appid", "total", "verified_fact_hash", "wx_out_trade_no")) {
                var rejected = new LinkedHashMap<>(callback);
                rejected.put(field, switch (field) {
                    case "total" -> Long.toString(paid.totalFen() + 1);
                    case "verified_fact_hash" -> "0".repeat(64).equals(ref.hash()) ? "1".repeat(64) : "0".repeat(64);
                    default -> "MISMATCHED_ORIGINAL";
                });
                String result = observedCallback(rejected);
                if (!"FAIL".equals(result) || mutationFailure.get() != null
                        || callbackReason(paid.businessOutTradeNo()) != CallbackReason.VERIFIED_FACT_MISSING_OR_CONFLICTING)
                    throw new CallbackRejected(result, callbackReason(paid.businessOutTradeNo()), mutationFailure.get());
                require(before.equals(economicRows()), "Rejected original callback changed financial rows");
            }
        }
        var walletBalances = db.queryForList("SELECT id,balance FROM ums_member ORDER BY id");
        String result = observedCallback(callback);
        try {
            require("SUCCESS".equals(result), "Original financial business callback refused");
        } catch (IllegalStateException refused) {
            throw new CallbackRejected(result, callbackReason(paid.businessOutTradeNo()),
                    mutationFailure.get() == null ? refused : mutationFailure.get());
        }
        CmsRegistration committed = registrations.selectCmsRegistrationById(((Number) command.get("bizId")).longValue());
        require(committed != null && Integer.valueOf(2).equals(committed.getPaymentStatus())
                && Integer.valueOf(2).equals(committed.getRegistrationStatus())
                && paid.transactionId().equals(committed.getTransactionId()), "Original paid status/transaction did not commit");
        require(db.queryForObject("SELECT COUNT(*) FROM cms_registration_payment_fact WHERE registration_id=? AND payment_fact_id=? AND payment_fact_hash=?",
                Integer.class, committed.getId(), ref.id(), ref.hash()) == 1, "Original payment consumption did not commit");
        require(walletBalances.equals(db.queryForList("SELECT id,balance FROM ums_member ORDER BY id")),
                "Original WeChat callback changed wallet balances");
        if (Long.valueOf(2L).equals(committed.getOwnerType())) {
            requireOriginalActivityCash(committed, originalActivity(committed.getOwnerId()));
            // recordPaidRegistration uses registration as the detail source; ACTIVITY belongs to the claim context.
            require(db.queryForObject("SELECT COUNT(*) FROM settlement_detail WHERE source_type='registration' AND source_id=? AND status=0",
                    Integer.class, committed.getId()) == 2, "Original activity receivable components did not commit");
            require(db.queryForObject("SELECT COALESCE(SUM(settlement_amount),0) FROM settlement_detail WHERE source_type='registration' AND source_id=?",
                    BigDecimal.class, committed.getId()).compareTo(committed.getPayableAmount()) == 0,
                    "Original activity receivable components do not conserve cash");
            if ("lost_success".equals(command.get("case")) && reference == null)
                assertActivityAccrualGuards(committed);
        }
    }

    private com.chengyinhub.business.domain.CmsActivity originalActivity(Long id) {
        Object target = ReflectionTestUtils.getField(original, "registrationTarget");
        return ((com.chengyinhub.business.mapper.CmsActivityMapper) ReflectionTestUtils.getField(target, "cmsActivityMapper"))
                .selectCmsActivityById(id);
    }

    static void requireOriginalActivityCash(CmsRegistration order, com.chengyinhub.business.domain.CmsActivity activity) {
        require(order != null && order.getId() != null && Long.valueOf(7).equals(order.getMemberId())
                && Long.valueOf(2).equals(order.getOwnerType()) && Long.valueOf(55).equals(order.getOwnerId())
                && Long.valueOf(9).equals(order.getTicketId())
                && Integer.valueOf(com.chengyinhub.common.constant.PurchaseKind.GUIDED_TICKET).equals(order.getPurchaseKind())
                && order.getPayableAmount() != null && order.getPayableAmount().signum() > 0
                && order.getWechatPaymentAmount() != null
                && order.getWechatPaymentAmount().compareTo(order.getPayableAmount()) == 0
                && order.getFeePlatformRate() != null && order.getFeePlatformRate().signum() > 0
                && order.getFeePlatformRate().compareTo(BigDecimal.ONE) < 0
                && activity != null && Long.valueOf(55).equals(activity.getId())
                && Long.valueOf(1).equals(activity.getMemberId()) && activity.getTopicId() == null
                && Long.valueOf(1).equals(activity.getStatus()) && Long.valueOf(1).equals(activity.getPublishStatus())
                && Integer.valueOf(0).equals(activity.getDelFlag()),
                "Fixed original ordinary activity cash source differs");
    }

    private void assertActivityAccrualGuards(CmsRegistration paid) {
        var before = economicRows();
        // These are input projections, never UPDATEs to the original order, intent or provider fact.
        // Exercise the real entry proxy and its real activity mapper; no replacement success/no-op entry.
        for (int guard = 0; guard < 3; guard++) {
            var excluded = new CmsRegistration(); org.springframework.beans.BeanUtils.copyProperties(paid, excluded);
            if (guard == 0) excluded.setOwnerType(1L);
            if (guard == 1) excluded.setPurchaseKind(com.chengyinhub.common.constant.PurchaseKind.EXPLORE_PASS);
            if (guard == 2) excluded.setOwnerId(Long.MAX_VALUE);
            require(activityEntries.recordOnPayment(excluded) == 0, "Ineligible activity entry accrued cash");
            require(before.equals(economicRows()), "Ineligible activity entry changed financial rows");
        }
        var missingRate = new CmsRegistration(); org.springframework.beans.BeanUtils.copyProperties(paid, missingRate);
        missingRate.setFeePlatformRate(null);
        boolean rejected = false;
        try { activityEntries.recordOnPayment(missingRate); }
        catch (IllegalStateException expected) { rejected = true; }
        require(rejected, "Cash without frozen rate was admitted");
        require(before.equals(economicRows()), "Rejected cash entry changed financial rows");
    }

    private String observedCallback(Map<String, String> callback) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Original callback must begin outside business transaction");
        mutationFailure.set(null); org.mockito.Mockito.clearInvocations(callbackAlarm);
        return registrations.handleWechatPayCallback(callback);
    }

    enum CallbackReason { UNKNOWN, MUTATION_EXCEPTION, PAYLOAD_MISSING, REQUIRED_FIELD_MISSING, CALLBACK_INSIDE_TRANSACTION,
        VERIFIED_FACT_MISSING_OR_CONFLICTING, AMOUNT_MISMATCH, INBOX_IDENTITY_CONFLICT, VERIFIED_CASH_RECOVERY_NOT_COMPLETED,
        CALLBACK_NOT_COMPLETED, PAYMENT_STATE_CONFLICT, PAYMENT_ATTEMPT_CONFLICT, VERIFIED_ORIGINAL_ORDER_MISMATCH,
        CLOSED_REGISTRATION_ORIGINAL_PAYMENT_RECOVERY_REQUIRED, TOPIC_CLOSED_ORIGINAL_PAYMENT_RECOVERY_REQUIRED,
        ORDER_DISAPPEARED, FAILURE_STATE_CONFLICT, REFUND_REQUIRES_VERIFIED_ORIGINAL_REFUND }

    private CallbackReason callbackReason(String originalNo) {
        CallbackReason reason = observedCallbackReason(callbackAlarm, originalNo);
        return reason == CallbackReason.UNKNOWN && mutationFailure.get() != null ? CallbackReason.MUTATION_EXCEPTION : reason;
    }

    static CallbackReason observedCallbackReason(com.chengyinhub.business.service.impl.AlarmService alarm, String originalNo) {
        CallbackReason reason = CallbackReason.UNKNOWN;
        for (var invocation : org.mockito.Mockito.mockingDetails(alarm).getInvocations()) {
            Object[] args = invocation.getArguments();
            if (!"alarmThrottled".equals(invocation.getMethod().getName()) || args.length != 3
                    || !"payment.callback.registration".equals(args[0]) || !"支付回调报名处理失败".equals(args[1])
                    || !(args[2] instanceof String value)) continue;
            // Match a whole fixed envelope; never parse/echo arbitrary order, exception, SQL or payload text.
            for (var candidate : CallbackReason.values()) {
                String suffix = " reason=" + candidate.name().toLowerCase(java.util.Locale.ROOT) + " result=FAIL";
                if (value.equals("order=" + originalNo + suffix)) reason = candidate;
            }
        }
        return reason;
    }

    static final class CallbackRejected extends IllegalStateException {
        final CallbackReason reason;
        final String outcome;
        CallbackRejected(String result, CallbackReason reason, RuntimeException cause) {
            super("Original financial business callback refused", cause);
            this.reason = reason;
            this.outcome = result == null ? "NULL" : "FAIL".equals(result) ? "FAIL" : "OTHER";
        }
    }

    private Map<String, java.util.List<String>> economicRows() {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "Committed financial readback must be outside transaction");
        var result = new LinkedHashMap<String, java.util.List<String>>();
        for (String table : java.util.List.of("cms_registration", "wx_pay_order_intent", "wx_verified_payment_fact",
                "cms_registration_payment_fact", "ums_member", "ums_member_points_detail", "ums_member_balance_detail",
                "refund_application", "cms_registration_scan", "cms_activity", "oms_ticket", "settlement_rule",
                "settlement_claim", "settlement_detail")) {
            result.put(table, db.queryForList("SELECT * FROM `" + table + "`").stream()
                .map(row -> JSON.toJSONString(new java.util.TreeMap<>(row), com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue))
                .sorted().toList());
        }
        return result;
    }

    static String canonicalActivityTable(String base, String table) {
        require(java.util.Set.of("settlement_claim", "settlement_detail", "settlement_rule").contains(table), "Fixed activity tables only");
        int from = base.indexOf("CREATE TABLE `" + table + "` (");
        int close = base.indexOf("\n) ENGINE", from);
        int end = base.indexOf(';', close);
        require(from >= 0 && close > from && end > close, "Canonical activity payment DDL missing");
        return base.substring(from, end + 1);
    }

    private void wireActivityPayment(javax.sql.DataSource source) throws Exception {
        String base = Files.readString(Path.of("../sql/base_schema_20260818.sql"));
        for (String table : java.util.List.of("settlement_claim", "settlement_detail", "settlement_rule"))
            db.execute(canonicalActivityTable(base, table));
        Object registrationTarget = ReflectionTestUtils.getField(original, "registrationTarget");
        var pricing = ((ISettlementRuleService) ReflectionTestUtils.getField(registrationTarget, "settlementRuleService"))
                .getActiveRule(0L, "activity");
        require(pricing != null && pricing.getPlatformCommissionRate() != null, "Original activity pricing rule missing");
        BigDecimal rate = pricing.getPlatformCommissionRate();
        require(rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0, "Original activity pricing rule invalid");
        db.update("INSERT INTO settlement_rule(merchant_id,biz_type,rule_name,platform_commission_rate,merchant_rate,creator_rate,inviter_rate,status,del_flag) VALUES(0,'activity','original funds fixture',?,?,0,0,1,0)",
                rate, BigDecimal.ONE.subtract(rate));
        var factory = new org.mybatis.spring.SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/business/SettlementClaimMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/business/SettlementDetailMapper.xml"),
                new org.springframework.core.io.ClassPathResource("mapper/business/SettlementRuleMapper.xml"));
        var sql = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var rules = new com.chengyinhub.business.service.impl.SettlementRuleServiceImpl();
        ReflectionTestUtils.setField(rules, "settlementRuleMapper", sql.getMapper(com.chengyinhub.business.mapper.SettlementRuleMapper.class));
        var claims = new com.chengyinhub.business.service.impl.SettlementClaimServiceImpl(
                sql.getMapper(com.chengyinhub.business.mapper.SettlementClaimMapper.class));
        var entries = new com.chengyinhub.business.service.impl.SettlementEntryServiceImpl();
        ReflectionTestUtils.setField(entries, "settlementDetailMapper", sql.getMapper(com.chengyinhub.business.mapper.SettlementDetailMapper.class));
        ReflectionTestUtils.setField(entries, "settlementRuleService", rules);
        ReflectionTestUtils.setField(entries, "settlementClaimService", transactional(claims, ISettlementClaimService.class));
        ReflectionTestUtils.setField(entries, "cmsActivityMapper", ReflectionTestUtils.getField(registrationTarget, "cmsActivityMapper"));
        ReflectionTestUtils.setField(entries, "cmsTopicMapper", ReflectionTestUtils.getField(registrationTarget, "cmsTopicMapper"));
        activityEntries = (ISettlementEntryService) transactional(entries, ISettlementEntryService.class);
        ReflectionTestUtils.setField(registrationTarget, "settlementEntryService", activityEntries);
    }

    private Object transactional(Object target, Class<?> port) {
        var proxy = new org.springframework.aop.framework.ProxyFactory(target); proxy.setInterfaces(port);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
            (org.springframework.transaction.PlatformTransactionManager) ReflectionTestUtils.getField(original, "transactionManager"),
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return proxy.getProxy();
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
