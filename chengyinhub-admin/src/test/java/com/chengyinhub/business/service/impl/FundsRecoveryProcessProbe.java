package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CashWithdrawFundFact;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.WithdrawalBalanceAdmissionService;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.common.config.WxPayConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test-classpath only; fixed funds commands, no HTTP/profile/production hook.
 * Parent alone kills its exact Process. Gates never claim a simulated exception is process death.
 */
public final class FundsRecoveryProcessProbe {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String MCH = "funds-process-merchant", APP = "funds-process-app", OPENID = "funds-process-openid";
    static final String REMARK = "城瘾分润提现";
    static final BigDecimal AMOUNT = new BigDecimal("30.00");
    private static Store activeStore;
    private static String activeBoot;
    private static String activePhase = "ADMISSION";
    enum Gate { NONE, BEFORE_DEBIT, DURING_DEBIT_TX, DEBIT_COMMITTED, SUBMIT_COMMITTED,
        PROVIDER_ACCEPTED, ACKED, NOT_SENT_COMMITTED, QUERY_CAPTURED, DURING_REFUND_TX }
    enum Command { WITHDRAW, REQUERY, MAKE_REFUND, CHECK_REFUND, SAME_MAKER, DENY_REFUND }
    enum ProviderState { NOT_FOUND, UNKNOWN, SUCCESS, FAIL }

    public static void main(String[] args) {
        try {
            require(args.length == 0, "fixed argv");
            require("github-hosted".equals(required("RUNNER_ENVIRONMENT")), "controlled runner");
            require("1".equals(required("B_FUNDS_PROCESS_RECOVERY_APPROVED")), "process admission");
            require("1".equals(required("B_PAYMENT_INTENT_DDL_APPROVED")), "original schema admission");
            require(Runtime.version().feature() == 21, "JDK21");
            require("Linux".equals(System.getProperty("os.name")), "Linux SIGKILL evidence contract");
            String schema = required("BFP_SCHEMA"), nonce = required("BFP_NONCE"), boot = required("BFP_BOOT");
            require(schema.matches("cy_bfunds_[a-f0-9]{32}") && nonce.matches("[a-f0-9]{32}")
                    && boot.matches("[a-f0-9]{32}"), "owned identities");
            int port = Integer.parseInt(required("CHENGYIN_MYBATIS_IT_PORT"));
            require(port > 0 && port <= 65535, "port");
            require("127.0.0.1".equals(required("CHENGYIN_MYBATIS_IT_HOST")), "loopback");
            String password = System.getenv("CHENGYIN_MYBATIS_IT_PASSWORD");
            require(password != null, "explicit credential binding");
            String url = url(port, schema);
            activePhase = "OWNERSHIP";
            Store store = new Store(new DriverManagerDataSource(url, required("CHENGYIN_MYBATIS_IT_USER"), password));
            require(schema.equals(store.db.queryForObject("SELECT DATABASE()", String.class)), "schema");
            require(required("BFP_SERVER_UUID").equals(store.db.queryForObject("SELECT @@server_uuid", String.class)), "server");
            require(nonce.equals(store.db.queryForObject("SELECT nonce FROM b_funds_process_owner", String.class)), "owner");
            String sourceSha = required("GITHUB_SHA");
            require(sourceSha.matches("[a-f0-9]{40}") && sourceSha.equals(
                    store.db.queryForObject("SELECT source_sha FROM b_funds_process_owner", String.class)), "source");
            Map<String, Object> row = store.db.queryForMap("SELECT * FROM b_funds_process_boot WHERE boot=?", boot);
            activeStore = store; activeBoot = boot; // Record only after exact schema/server/source/nonce ownership was checked.
            long member = ((Number) row.get("member_id")).longValue();
            Gate gate = Gate.valueOf((String) row.get("gate_name"));
            Command command = Command.valueOf((String) row.get("command_name"));
            boolean localReject = ((Number) row.get("local_reject")).intValue() == 1;
            boolean corruptSnapshot = ((Number) row.get("corrupt_snapshot")).intValue() == 1;
            activePhase = "ARTIFACT_IDENTITY";
            String artifact = artifacts(Path.of(required("BFP_REPO"))).toString();
            require(sha(artifact.getBytes(StandardCharsets.UTF_8)).equals(required("BFP_ARTIFACT_HASH")), "loaded/source bytes");
            var start = ProcessHandle.current().info().startInstant().orElseThrow();
            store.db.update("UPDATE b_funds_process_boot SET pid=?,parent_pid=?,start_instant=?,artifact_hash=?,ready=1 WHERE boot=?",
                    ProcessHandle.current().pid(), ProcessHandle.current().parent().orElseThrow().pid(),
                    start.toString(), required("BFP_ARTIFACT_HASH"), boot);
            GateObserver observer = new GateObserver(store, boot, gate, url,
                    required("CHENGYIN_MYBATIS_IT_USER"), password);
            activePhase = "SERVICE_WIRING";
            ICashWithdrawService service = store.service(member, observer, localReject, corruptSnapshot);
            Long id = row.get("withdraw_id") == null ? null : ((Number) row.get("withdraw_id")).longValue();
            String result;
            activePhase = command.name();
            switch (command) {
                case WITHDRAW -> {
                    try { id = service.withdraw(member, AMOUNT).getId(); result = "RETURNED"; }
                    catch (IllegalStateException expected) {
                        id = store.original(member).getId();
                        require(localReject && store.cash.selectById(id).getStatus() == MemberCashWithdraw.STATUS_FAILED_REFUNDED,
                                "unexpected withdrawal rejection");
                        result = "LOCAL_NOT_SENT_REFUNDED";
                    }
                    observer.hit(Gate.ACKED, id);
                }
                case REQUERY -> { require(id != null, "original id"); service.requery(id); result = "OBSERVED"; }
                case MAKE_REFUND -> { require(id != null, "original id");
                    require(service.manualRefund(id, "process-maker", "original no-send proof", 3), "maker CAS");
                    result = "MAKER_RECORDED";
                }
                case CHECK_REFUND -> { require(id != null, "original id");
                    result = service.manualRefund(id, "process-checker", "original no-send proof", 4)
                            ? "REFUNDED" : "ALREADY_TERMINAL";
                }
                case SAME_MAKER, DENY_REFUND -> {
                    require(id != null, "original id"); boolean denied = false;
                    try { denied = !service.manualRefund(id, command == Command.SAME_MAKER
                            ? "process-maker" : "process-denied", "must retain original", null); }
                    catch (IllegalStateException rejection) { denied = true; }
                    require(denied, "unsafe refund accepted"); result = "DENIED";
                }
                default -> throw new IllegalArgumentException("command");
            }
            store.db.update("UPDATE b_funds_process_boot SET withdraw_id=?,result_code=?,finished=1 WHERE boot=?", id, result, boot);
        } catch (Throwable failure) {
            // Do not print JDBC exceptions/configuration or raw channel data.
            String code = "UNEXPECTED_CHILD_FAILURE";
            if ("两侧原扣款快照冲突，保留人工调查".equals(failure.getMessage())) code = "ORIGINAL_SOURCE_CONFLICT";
            String diagnostic;
            try { diagnostic = JSON.writeValueAsString(failureEvidence(failure, code)); }
            catch (Exception cannotEncode) { diagnostic = "{\"status\":\"FAILURE_EVIDENCE_ENCODING_UNKNOWN\"}"; }
            if (activeStore != null && activeBoot != null) {
                try { activeStore.db.update("UPDATE b_funds_process_boot SET failure_code=?,failure_type=?,failure_evidence=? WHERE boot=?",
                        code, failure.getClass().getName(), diagnostic, activeBoot); }
                catch (Throwable evidenceFailure) { System.err.println("B_FUNDS_EVIDENCE_FAILURE " + evidenceFailure.getClass().getName()); }
            }
            System.err.println("B_FUNDS_CHILD_FAILURE " + failure.getClass().getName());
            System.err.println(diagnostic);
            System.exit(2);
        }
    }

    static final class Store {
        final DriverManagerDataSource source;
        final JdbcTemplate db;
        final DataSourceTransactionManager tx;
        final MemberCashWithdrawMapper cash;
        final CashWithdrawTransferIntentMapper intents;
        final UmsMemberMapper members;
        final WithdrawalFrozenAmountResolver frozen;

        Store(DriverManagerDataSource source) throws Exception {
            this.source = source; db = new JdbcTemplate(source); tx = new DataSourceTransactionManager(source);
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(source);
            factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
            factory.setMapperLocations(new ClassPathResource("mapper/business/MemberCashWithdrawMapper.xml"),
                    new ClassPathResource("mapper/business/CashWithdrawTransferIntentMapper.xml"),
                    new ClassPathResource("mapper/business/UmsMemberMapper.xml"),
                    new ClassPathResource("mapper/business/MerchantReferralBountyMapper.xml"),
                    new ClassPathResource("mapper/business/PlayerWithdrawalCooldownMapper.xml"));
            SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
            cash = sql.getMapper(MemberCashWithdrawMapper.class); intents = sql.getMapper(CashWithdrawTransferIntentMapper.class);
            members = sql.getMapper(UmsMemberMapper.class);
            frozen = new WithdrawalFrozenAmountResolver(null, null);
            ReflectionTestUtils.setField(frozen, "bountyMapper", sql.getMapper(MerchantReferralBountyMapper.class));
            ReflectionTestUtils.setField(frozen, "cooldownMapper", sql.getMapper(PlayerWithdrawalCooldownMapper.class));
        }

        <T> T transactional(T target) {
            ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
            @SuppressWarnings("unchecked") T value = (T) proxy.getProxy(); return value;
        }

        MemberCashWithdraw original(long member) {
            List<MemberCashWithdraw> values = cash.selectByMember(member);
            require(values.size() == 1, "one server business id, never replace by new withdraw"); return values.get(0);
        }

        ICashWithdrawService service(long member, GateObserver observer, boolean localReject, boolean corrupt) throws Exception {
            CashWithdrawTransferIntentMapper observedIntents = mock(CashWithdrawTransferIntentMapper.class, delegatesTo(intents));
            doAnswer(call -> {
                CashWithdrawTransferIntent proposed = call.getArgument(0);
                CashWithdrawTransferIntent persisted = corrupt ? CashWithdrawTransferIntent.create(
                        cash.selectByIdForUpdate(proposed.withdrawalId()), proposed.mchid(), proposed.appid(), proposed.effectiveRemark(),
                        proposed.balanceBefore().add(BigDecimal.ONE), proposed.balanceAfter().add(BigDecimal.ONE)) : proposed;
                int rows = intents.insert(persisted);
                observer.hit(Gate.DURING_DEBIT_TX, proposed.withdrawalId());
                return rows;
            }).when(observedIntents).insert(any());
            WithdrawalBalanceAdmissionService realAdmission = transactional(
                    new WithdrawalBalanceAdmissionService(members, frozen, cash, observedIntents));
            WithdrawalBalanceAdmissionService observedAdmission = mock(WithdrawalBalanceAdmissionService.class);
            when(observedAdmission.debitCash(anyLong(), anyLong(), any(), any(), any(), any())).thenAnswer(call -> {
                Long id = call.getArgument(0); observer.hit(Gate.BEFORE_DEBIT, id);
                var actual = realAdmission.debitCash(id, call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5));
                observer.hit(Gate.DEBIT_COMMITTED, id); return actual;
            });
            IUmsMemberService profile = mock(IUmsMemberService.class);
            when(profile.selectUmsMemberById(member)).thenAnswer(call -> {
                UmsMember value = new UmsMember(); value.setId(member); value.setWxOpenId(OPENID);
                value.setBalance(db.queryForObject("SELECT balance FROM ums_member WHERE id=?", BigDecimal.class, member));
                return value;
            });
            UmsMemberServiceImpl balance = new UmsMemberServiceImpl(); ReflectionTestUtils.setField(balance, "umsMemberMapper", members);
            when(profile.addBalance(anyLong(), any())).thenAnswer(call -> {
                require(TransactionSynchronizationManager.isActualTransactionActive(), "credit joins original receipt transaction");
                boolean changed = balance.addBalance(call.getArgument(0), call.getArgument(1));
                observer.hit(Gate.DURING_REFUND_TX, original(member).getId()); return changed;
            });
            IWechatPayService provider = mock(IWechatPayService.class);
            when(provider.transferToBalance(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> {
                CashWithdrawTransferIntent intent = call.getArgument(0); validateRequest(intent);
                observer.hit(Gate.SUBMIT_COMMITTED, intent.withdrawalId());
                if (localReject) {
                    // Real gateway's missing client binding proves NOT_SENT before any SDK action.
                    WechatPayServiceImpl facade = WechatPayTestWiring.facade();
                    var sdk = mock(com.wechat.pay.java.service.transferbatch.TransferBatchService.class);
                    WechatPayTestWiring.setField(facade, "transferBatchService", sdk);
                    WechatPayTestWiring.setField(facade, "transferClientBinding", null);
                    Map<String, String> rejected = facade.transferToBalance(intent);
                    require("false".equals(rejected.get("ok")) && "false".equals(rejected.get("ambiguous"))
                            && "true".equals(rejected.get("notSent")) && intent.requestHash().equals(rejected.get("requestHash")),
                            "real pre-SDK no-send certificate");
                    verifyNoInteractions(sdk); call(intent, "LOCAL_NOT_SENT", "NOT_SENT"); return rejected;
                }
                call(intent, "SUBMIT", "ACCEPTED");
                recordProvider(intent, "ACCEPTED"); observer.hit(Gate.PROVIDER_ACCEPTED, intent.withdrawalId());
                return Map.of("ok", "true", "batchStatus", "ACCEPTED", "batchId", batch(intent));
            });
            when(provider.queryTransferResult(any(CashWithdrawTransferIntent.class))).thenAnswer(call -> {
                CashWithdrawTransferIntent intent = call.getArgument(0); validateRequest(intent);
                ProviderState state = ProviderState.valueOf(db.queryForObject(
                        "SELECT provider_state FROM b_funds_process_owner", String.class));
                call(intent, "QUERY", state.name());
                Map<String, String> result;
                if (state == ProviderState.NOT_FOUND || state == ProviderState.UNKNOWN) {
                    result = Map.of("result", "UNKNOWN", "errorCode", state.name());
                } else {
                    Map<String, Object> accepted = db.queryForMap("SELECT * FROM b_funds_provider WHERE withdrawal_id=?", intent.withdrawalId());
                    require(wireHash(intent).equals(accepted.get("wire_hash")), "provider identity");
                    result = terminal(intent, state.name());
                }
                observer.hit(Gate.QUERY_CAPTURED, intent.withdrawalId()); return result;
            });
            CashWithdrawServiceImpl target = new CashWithdrawServiceImpl();
            ReflectionTestUtils.setField(target, "mapper", cash); ReflectionTestUtils.setField(target, "transferIntents", intents);
            ReflectionTestUtils.setField(target, "transactionManager", observingTransactions(observer, member));
            ReflectionTestUtils.setField(target, "umsMemberService", profile);
            ReflectionTestUtils.setField(target, "wechatPayService", provider);
            ReflectionTestUtils.setField(target, "balanceAdmissionService", observedAdmission);
            MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
            IPlayerWithdrawalCooldownService cooldown = mock(IPlayerWithdrawalCooldownService.class);
            when(bounty.frozenAmount(member)).thenAnswer(call -> db.queryForObject(
                    "SELECT COALESCE(SUM(bounty_amount),0) FROM merchant_referral_bounty WHERE inviter_member_id=? AND status=0 AND del_flag='0'", BigDecimal.class, member));
            when(cooldown.frozenAmount(member)).thenAnswer(call -> db.queryForObject(
                    "SELECT COALESCE(SUM(amount),0) FROM player_withdrawal_cooldown WHERE member_id=? AND cooldown_until>CURRENT_TIMESTAMP", BigDecimal.class, member));
            ReflectionTestUtils.setField(target, "frozenAmountResolver", new WithdrawalFrozenAmountResolver(bounty, cooldown));
            ReflectionTestUtils.setField(target, "alarmService", mock(AlarmService.class));
            WxPayConfig config = new WxPayConfig(); ReflectionTestUtils.setField(config, "appid", APP);
            ReflectionTestUtils.setField(config, "merchantId", MCH); ReflectionTestUtils.setField(target, "wxPayConfig", config);
            return transactional(target);
        }

        private PlatformTransactionManager observingTransactions(GateObserver observer, long member) {
            AtomicBoolean held = new AtomicBoolean();
            return new PlatformTransactionManager() {
                @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return tx.getTransaction(definition); }
                @Override public void rollback(TransactionStatus status) { tx.rollback(status); }
                @Override public void commit(TransactionStatus status) {
                    tx.commit(status);
                    require(!TransactionSynchronizationManager.isActualTransactionActive(), "after physical commit");
                    MemberCashWithdraw row = original(member);
                    if (db.queryForObject("SELECT COUNT(*) FROM member_cash_withdraw_fund_fact WHERE withdrawal_id=? AND kind='NOT_ACCEPTED'",
                            Integer.class, row.getId()) == 1 && held.compareAndSet(false, true))
                        observer.hit(Gate.NOT_SENT_COMMITTED, row.getId());
                }
            };
        }

        void validateRequest(CashWithdrawTransferIntent intent) {
            require(!TransactionSynchronizationManager.isActualTransactionActive(), "no business TX across provider");
            MemberCashWithdraw original = cash.selectById(intent.withdrawalId());
            intent.requireValid(); intent.requireMatches(original);
            CashWithdrawTransferIntent snapshot = intents.selectByWithdrawId(intent.withdrawalId());
            if (snapshot != null) {
                snapshot.requireMatches(original);
                require(wireHash(intent).equals(wireHash(snapshot)), "same immutable wire parameters");
            }
            if (intent.hasDebitSnapshot()) {
                require(snapshot != null, "master original snapshot");
                require(cash.selectFundFacts(intent.withdrawalId()).isEmpty(), "master-only provenance");
                require(snapshot.equals(intent), "master source cannot be converted");
            } else {
                List<CashWithdrawFundFact> facts = cash.selectFundFacts(intent.withdrawalId());
                var submission = facts.stream().filter(f -> "SUBMIT_INTENT".equals(f.getKind())).findFirst().orElseThrow();
                require(submission.transferIntent().equals(intent), "ledger original submission");
                require(facts.stream().anyMatch(f -> "DEBIT".equals(f.getKind()) && f.matches(original)
                        && f.getId().equals(original.getBalanceDeductId()) && f.getBalanceBefore() != null
                        && f.getBalanceAfter() != null && f.getBalanceBefore().subtract(f.getBalanceAfter()).compareTo(original.getAmount()) == 0),
                        "original durable debit");
                if (snapshot != null)
                    require(!snapshot.requestHash().equals(intent.requestHash()), "preserve the two independent V1 identities");
            }
        }
        void call(CashWithdrawTransferIntent intent, String action, String result) {
            db.update("INSERT INTO b_funds_process_call(withdrawal_id,action_name,result_code,source_kind,request_hash,wire_hash,boot) VALUES(?,?,?,?,?,?,?)",
                    intent.withdrawalId(), action, result, intent.originalSource().name(), intent.requestHash(), wireHash(intent),
                    System.getenv().getOrDefault("BFP_BOOT", "parent-history-seed"));
        }
        void recordProvider(CashWithdrawTransferIntent intent, String state) {
            intent.requireValid();
            require(db.update("INSERT INTO b_funds_provider(withdrawal_id,source_kind,request_hash,wire_hash,state_name) VALUES(?,?,?,?,?)",
                    intent.withdrawalId(), intent.originalSource().name(), intent.requestHash(), wireHash(intent), state) == 1,
                    "one provider acceptance for the original");
        }
    }

    static final class GateObserver {
        final Store store; final String boot, url, user, password; final Gate armed;
        GateObserver(Store store, String boot, Gate armed, String url, String user, String password) {
            this.store = store; this.boot = boot; this.armed = armed; this.url = url; this.user = user; this.password = password;
        }
        void hit(Gate reached, long id) {
            if (reached != armed) return;
            activePhase = "GATE_" + reached.name();
            boolean inside = TransactionSynchronizationManager.isActualTransactionActive();
            require(inside == (reached == Gate.DURING_DEBIT_TX || reached == Gate.DURING_REFUND_TX), "exact transaction boundary");
            // Independent control connection, never another business transaction on a locked original.
            try (Connection control = DriverManager.getConnection(url, user, password)) {
                try (PreparedStatement update = control.prepareStatement(
                        "UPDATE b_funds_process_boot SET reached=?,withdraw_id=? WHERE boot=? AND ready=1")) {
                    update.setString(1, reached.name()); update.setLong(2, id); update.setString(3, boot);
                    require(update.executeUpdate() == 1, "owned gate");
                }
                Instant deadline = Instant.now().plusSeconds(35);
                while (Instant.now().isBefore(deadline)) {
                    try (PreparedStatement read = control.prepareStatement("SELECT release_gate FROM b_funds_process_boot WHERE boot=?")) {
                        read.setQueryTimeout(3); read.setString(1, boot);
                        try (var row = read.executeQuery()) { require(row.next(), "owned gate lost"); if (row.getInt(1) == 1) return; }
                    }
                    TimeUnit.MILLISECONDS.sleep(25);
                }
                throw new IllegalStateException("Owned gate deadline");
            } catch (Exception failure) { throw new IllegalStateException("Owned gate failure", failure); }
        }
    }

    static Map<String, String> terminal(CashWithdrawTransferIntent intent, String result) {
        Map<String, String> value = new HashMap<>();
        value.put("result", result); value.put("detailStatus", result); value.put("batchStatus", "FINISHED");
        value.put("batchId", batch(intent)); value.put("detailId", "fixture-detail-" + intent.withdrawalId());
        value.put("mchid", intent.mchid()); value.put("appid", intent.appid());
        value.put("outBatchNo", intent.outBatchNo()); value.put("outDetailNo", intent.outDetailNo());
        value.put("openid", intent.openid()); value.put("transferAmount", Long.toString(intent.amountFen()));
        return value;
    }
    static String batch(CashWithdrawTransferIntent intent) { return "fixture-batch-" + intent.withdrawalId(); }
    static String wireHash(CashWithdrawTransferIntent intent) {
        return CashWithdrawTransferIntent.fromRequest(intent.withdrawalId(), intent.mchid(), intent.appid(),
                intent.outBatchNo(), intent.outDetailNo(), intent.amountYuan(), intent.openid(), intent.effectiveRemark()).requestHash();
    }
    static String url(int port, String schema) {
        return "jdbc:mysql://127.0.0.1:" + port + "/" + schema + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }
    static String required(String name) {
        String value = System.getenv(name); require(value != null && !value.isBlank(), "Missing test binding " + name); return value;
    }
    static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Map<String, Object> failureEvidence(Throwable failure, String code) throws Exception {
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && chain.size() < 8 && seen.add(cause); cause = cause.getCause()) {
            Map<String, Object> item = new TreeMap<>(); item.put("type", cause.getClass().getName());
            item.put("messageSha256", sha(String.valueOf(cause.getMessage()).getBytes(StandardCharsets.UTF_8)));
            item.put("frames", Arrays.stream(cause.getStackTrace()).limit(24)
                    .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber()).toList());
            item.put("framesTruncated", cause.getStackTrace().length > 24);
            if (cause instanceof java.sql.SQLException sql) {
                item.put("sqlState", String.valueOf(sql.getSQLState())); item.put("vendorCode", sql.getErrorCode());
            }
            chain.add(item);
        }
        return Map.of("phase", activePhase, "code", code, "causes", chain,
                "rawMessagesOmitted", true, "causeLimit", 8);
    }
    static SortedMap<String, String> artifacts(Path root) throws Exception {
        SortedMap<String, String> hashes = new TreeMap<>();
        for (Class<?> type : List.of(FundsRecoveryProcessProbe.class, FundsProcessRecoveryMySqlIT.class,
                CashWithdrawServiceImpl.class, WithdrawalBalanceAdmissionService.class,
                CashWithdrawTransferIntent.class, UmsMemberServiceImpl.class,
                WechatPayServiceImpl.class, WechatPaymentSdkGateway.class, WechatPayTestWiring.class,
                CashWithdrawFundFact.class, WithdrawalFrozenAmountResolver.class,
                MemberCashWithdrawMapper.class, CashWithdrawTransferIntentMapper.class, UmsMemberMapper.class,
                com.wechat.pay.java.service.transferbatch.TransferBatchService.class)) {
            String path = "/" + type.getName().replace('.', '/') + ".class";
            try (InputStream bytes = type.getResourceAsStream(path)) {
                require(bytes != null, "class bytes missing"); hashes.put("class:" + type.getName(), sha(bytes.readAllBytes()));
            }
            Path code = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(code)) hashes.put("jar:" + code.getFileName(), sha(Files.readAllBytes(code)));
            else {
                Path directory = code.resolve(type.getPackageName().replace('.', '/'));
                try (var files = Files.list(directory)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().startsWith(type.getSimpleName() + "$")
                            && p.getFileName().toString().endsWith(".class")).sorted().toList())
                        hashes.put("class:" + type.getPackageName() + "." + file.getFileName(), sha(Files.readAllBytes(file)));
                }
            }
        }
        for (String name : List.of("MemberCashWithdrawMapper", "CashWithdrawTransferIntentMapper", "UmsMemberMapper",
                "MerchantReferralBountyMapper", "PlayerWithdrawalCooldownMapper")) {
            try (InputStream bytes = FundsRecoveryProcessProbe.class.getResourceAsStream("/mapper/business/" + name + ".xml")) {
                require(bytes != null, "mapper bytes"); hashes.put("mapper:" + name, sha(bytes.readAllBytes()));
            }
        }
        for (String file : List.of(
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CashWithdrawServiceImpl.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/WithdrawalBalanceAdmissionService.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/WechatPaymentSdkGateway.java",
                "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/CashWithdrawTransferIntent.java",
                "chengyinhub-admin/src/test/resources/architecture-day-a-fixture.sql",
                "chengyinhub-admin/sql/migration_20261023_cash_withdraw_fund_fact.sql",
                "chengyinhub-admin/sql/migration_20261024_cash_withdraw_transfer_intent.sql",
                "chengyinhub-admin/sql/migration_20261025_cash_withdraw_fund_request_compat.sql",
                "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/FundsRecoveryProcessProbe.java",
                "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/FundsProcessRecoveryMySqlIT.java"))
            hashes.put("source:" + file, sha(Files.readAllBytes(root.resolve(file))));
        return hashes;
    }
}
