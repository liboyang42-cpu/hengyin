package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper;
import com.wechat.pay.java.service.payments.jsapi.JsapiService;
import com.wechat.pay.java.service.refund.RefundService;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit fixture only: real fact validation/readback with an in-memory mapper. Never a MySQL/transaction proof. */
public final class WechatVerifiedFactTestSupport {
    public static final String SUCCESS_TIME = "2026-10-24T00:00:00Z";
    private WechatVerifiedFactTestSupport() { }
    public static WechatPayServiceImpl gateway() { return WechatPayTestWiring.facade(); }
    static void installUnitFacts(WechatPaymentSdkGateway gateway) {
        WechatVerifiedPaymentFactService facts=new WechatVerifiedPaymentFactService();
        ReflectionTestUtils.setField(facts,"mapper",new MemoryMapper());
        wireUnitReads(facts);
        ReflectionTestUtils.setField(gateway,"verifiedFacts",facts);
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(gateway,"intents"),"verifiedFacts",facts);
    }
    public static void setField(Object target,String name,Object value) {
        WechatPayTestWiring.setField(target,name,value);
        if (target instanceof WechatVerifiedPaymentFactService service && "mapper".equals(name)) wireUnitReads(service);
    }
    public static void wireUnitReads(WechatVerifiedPaymentFactService service) {
        // Unit boundary doubles only. Real MySQL fixtures below install the original JDBC row-lock owner.
        var owner=org.mockito.Mockito.mock(com.chengyinhub.business.service.WechatPaymentOrderOwner.class);
        org.mockito.Mockito.when(owner.lock(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> {
            String no=call.getArgument(0);
            String kind=no.startsWith("CLM")?"CLM":no.startsWith("DEP")?"DEP":
                    (no.startsWith("TPLTM_") || no.startsWith("PROMOT_") || no.startsWith("CUSTOM_"))?"COMMERCE":"REGISTRATION";
            return new com.chengyinhub.business.service.WechatPaymentOrderOwner.Snapshot(kind,1L,java.math.BigDecimal.TEN,false,true);
        });
        ReflectionTestUtils.setField(service,"factBusinessOwner",owner);
        ReflectionTestUtils.setField(service,"factIntentJdbc",new org.springframework.jdbc.core.JdbcTemplate(){
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                if(sql.startsWith("SELECT appid")) return List.of(Map.of("appid","wx-original","trade_type","APP","currency","CNY","total_fen",1000L));
                if(sql.startsWith("SELECT business_out_trade_no")) {
                    String no="W1".equals(args[1])?"REG1":(String)args[1]; // Explicit original W1 refund fixture identity.
                    String kind=no.startsWith("CLM")?"CLM":no.startsWith("DEP")?"DEP":
                            (no.startsWith("TPLTM_") || no.startsWith("PROMOT_") || no.startsWith("CUSTOM_"))?"COMMERCE":"REGISTRATION";
                    return List.of(Map.of("business_out_trade_no",no,"biz_type",kind,"biz_id",1L));
                }
                if(sql.startsWith("SELECT request_hash")) return List.of(Map.of("request_hash","a".repeat(64)));
                return List.of();
            }
        });
        ReflectionTestUtils.setField(service,"factWriteTransactions",unitWriteTransactions());
        ReflectionTestUtils.setField(service,"factReadTransactions",new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                org.junit.jupiter.api.Assertions.assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
                org.junit.jupiter.api.Assertions.assertTrue(definition.isReadOnly());
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }
            public void commit(org.springframework.transaction.TransactionStatus status) { }
            public void rollback(org.springframework.transaction.TransactionStatus status) { }
        });
    }
    /** Explicit historical, unfrozen originals for memory-only fixtures. Never a SQL/SDK/transaction proof. */
    public static WechatVerifiedPaymentFactService memoryHistoricalFacts() {
        var facts=new WechatVerifiedPaymentFactService();
        ReflectionTestUtils.setField(facts,"mapper",new MemoryMapper());
        wireUnitReads(facts); // Includes the real canonical reader plus explicit unit write/owner/intent boundaries.
        ReflectionTestUtils.setField(facts,"factIntentJdbc",new org.springframework.jdbc.core.JdbcTemplate(){
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                if(sql.startsWith("SELECT appid,trade_type,currency,total_fen FROM wx_pay_order_intent")
                        || sql.startsWith("SELECT closed_confirmed_at FROM wx_pay_order_intent")) return List.of();
                if(sql.equals("SELECT * FROM wx_pay_order_intent WHERE biz_type=? AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE")) {
                    // Explicit historical fixture: independently declares that no intent was ever frozen.
                    // The real consumer still rejects a hash-bearing fact or a provider alias here.
                    org.junit.jupiter.api.Assertions.assertEquals(2,args.length);
                    return List.of();
                }
                throw new AssertionError("Unexpected SQL in explicit historical memory fixture: "+sql);
            }
        });
        return facts;
    }
    /** Business fixture boundary only; distinct from the independently committed fact writer. */
    public static org.springframework.transaction.PlatformTransactionManager unitBusinessTransactions() {
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){
                org.junit.jupiter.api.Assertions.assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED,definition.getPropagationBehavior());
                org.junit.jupiter.api.Assertions.assertFalse(definition.isReadOnly());
            }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        };
    }
    public static org.springframework.transaction.PlatformTransactionManager unitWriteTransactions() {
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager(){
            @Override protected Object doGetTransaction(){return new Object();}
            @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){
                org.junit.jupiter.api.Assertions.assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,definition.getPropagationBehavior());
                org.junit.jupiter.api.Assertions.assertFalse(definition.isReadOnly());
            }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        };
    }
    public static Object getField(Object target,String name) { return WechatPayTestWiring.getField(target,name); }
    public static <T> T invokeMethod(Object target,String name,Object... args) { return WechatPayTestWiring.invokeMethod(target,name,args); }
    /** Explicit SDK unit fixture. Memory provider facts and controlled original-order query; no SQL/provider proof. */
    static void installRefundOriginal(WechatPayServiceImpl facade,RefundService refunds,String businessNo,
            String wxNo,String transactionId,java.math.BigDecimal paid) {
        long total=paid.movePointRight(2).longValueExact();
        String kind=businessNo.startsWith("DEP")?"DEP":"REGISTRATION";
        var facts=new WechatVerifiedPaymentFactService();
        setField(facts,"mapper",new MemoryMapper());
        var owner=org.mockito.Mockito.mock(com.chengyinhub.business.service.WechatPaymentOrderOwner.class);
        org.mockito.Mockito.when(owner.lock(businessNo)).thenReturn(
                new com.chengyinhub.business.service.WechatPaymentOrderOwner.Snapshot(kind,1L,paid,false,true));
        ReflectionTestUtils.setField(facts,"factBusinessOwner",owner);
        var snapshot=Map.<String,Object>of("appid","wx-original","trade_type","JSAPI","currency","CNY","total_fen",total);
        ReflectionTestUtils.setField(facts,"factIntentJdbc",new org.springframework.jdbc.core.JdbcTemplate(){
            @Override public List<Map<String,Object>> queryForList(String sql,Object... params) {
                if(sql.startsWith("SELECT appid")) return List.of(snapshot);
                if(sql.startsWith("SELECT business_out_trade_no")) return List.of(Map.of(
                        "business_out_trade_no",businessNo,"biz_type",kind,"biz_id",1L));
                if(sql.startsWith("SELECT request_hash")) return List.of(Map.of("request_hash","a".repeat(64)));
                if(sql.startsWith("SELECT closed_confirmed_at")) return List.of();
                throw new AssertionError("Unexpected original-payment fixture SQL: "+sql);
            }
        });
        facts.recordVerified(new com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact(
                com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
                businessNo,wxNo,wxNo,transactionId,transactionId,"JSAPI","CNY",total,total,
                java.time.Instant.parse(SUCCESS_TIME),"a".repeat(64),"SUCCESS"));
        var intents=org.mockito.Mockito.mock(WechatPaymentIntentStore.class);
        org.mockito.Mockito.when(intents.businessPaymentOrderNo(wxNo,"merchant")).thenReturn(businessNo);
        org.mockito.Mockito.when(intents.frozenFacts("merchant",wxNo)).thenReturn(List.of(snapshot));
        org.mockito.Mockito.when(intents.frozenRequestHash("merchant",wxNo)).thenReturn("a".repeat(64));
        setField(facade,"verifiedFacts",facts);setField(facade,"intents",intents);setField(facade,"refundService",refunds);
        var payment=org.mockito.Mockito.mock(JsapiService.class);
        var original=new com.wechat.pay.java.service.payments.model.Transaction();
        original.setOutTradeNo(wxNo);original.setMchid("merchant");original.setAppid("wx-original");
        original.setTransactionId(transactionId);original.setTradeType(com.wechat.pay.java.service.payments.model.Transaction.TradeTypeEnum.JSAPI);
        original.setTradeState(com.wechat.pay.java.service.payments.model.Transaction.TradeStateEnum.REFUND);
        original.setSuccessTime(SUCCESS_TIME);
        var amount=new com.wechat.pay.java.service.payments.model.TransactionAmount();
        amount.setTotal(Math.toIntExact(total));amount.setCurrency("CNY");original.setAmount(amount);
        org.mockito.Mockito.when(payment.queryOrderByOutTradeNo(org.mockito.ArgumentMatchers.any())).thenReturn(original);
        bind(facade,payment,refunds,"merchant");
    }

    static void refreshSdkBinding(WechatPaymentSdkGateway gateway) {
        var config=(com.chengyinhub.common.config.WxPayConfig)ReflectionTestUtils.getField(gateway,"wxPayConfig");
        var payment=(JsapiService)ReflectionTestUtils.getField(gateway,"jsapiService");
        var refund=(RefundService)ReflectionTestUtils.getField(gateway,"refundService");
        if (config!=null && payment!=null) bind(gateway,payment,refund,config.getMerchantId());
        else ReflectionTestUtils.setField(gateway,"verifiedProviderClients",null);
    }
    public static void bind(Object target,JsapiService payment,RefundService refund,String mchid) {
        Object gateway=target instanceof WechatPayServiceImpl facade?WechatPayTestWiring.getField(facade,"gateway"):target;
        try {
            Class<?> type=Class.forName(WechatPaymentSdkGateway.class.getName()+"$VerifiedProviderClients");
            Constructor<?> constructor=type.getDeclaredConstructor(JsapiService.class,RefundService.class,String.class);
            constructor.setAccessible(true);
            ReflectionTestUtils.setField(gateway,"jsapiService",payment);
            ReflectionTestUtils.setField(gateway,"refundService",refund);
            ReflectionTestUtils.setField(gateway,"verifiedProviderClients",constructor.newInstance(payment,refund,mchid));
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
    }
    /** Explicit original request fixture, committed before the independently stored provider observation.
     * Does not infer request fields from a response, change business state, close or replace an attempt.
     * Only approved isolated SQL suites may call this; it makes no SDK/provider-verification claim. */
    public static void freezeRegistrationAttempt(javax.sql.DataSource source, long registrationId,
            String businessNo, String wxNo, String mchid, String appid, String tradeType,
            long totalFen, String requestHash) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Original request fixture must commit before the business transaction");
        if (registrationId <= 0 || totalFen <= 0 || businessNo == null || wxNo == null
                || mchid == null || appid == null || !Set.of("APP", "JSAPI").contains(tradeType)
                || requestHash == null || !requestHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Explicit original request fixture required");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(tx -> {
            var original = jdbc.queryForMap(
                    "SELECT registration_no,wechat_payment_amount FROM cms_registration WHERE id=? FOR UPDATE",
                    registrationId);
            org.junit.jupiter.api.Assertions.assertEquals(businessNo, original.get("registration_no"));
            org.junit.jupiter.api.Assertions.assertEquals(totalFen,
                    new java.math.BigDecimal(original.get("wechat_payment_amount").toString())
                            .movePointRight(2).longValueExact());
            var attempts = jdbc.queryForList("SELECT * FROM wx_pay_order_intent WHERE biz_type='REGISTRATION'"
                    + " AND biz_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE", registrationId);
            if (attempts.isEmpty()) {
                org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.update(
                        "INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,"
                        + "attempt_no,appid,trade_type,currency,total_fen,payer_openid,request_description,notify_url,request_hash)"
                        + " VALUES(?,?,?,'REGISTRATION',?,1,?,?,'CNY',?,'fixture-openid','registration fixture',"
                        + "'https://notify.invalid/fixture',?)",
                        mchid, wxNo, businessNo, registrationId, appid, tradeType, totalFen, requestHash));
            } else {
                var retained = attempts.getFirst();
                org.junit.jupiter.api.Assertions.assertEquals(mchid, retained.get("mchid"));
                org.junit.jupiter.api.Assertions.assertEquals(wxNo, retained.get("out_trade_no"));
                org.junit.jupiter.api.Assertions.assertEquals(businessNo, retained.get("business_out_trade_no"));
                org.junit.jupiter.api.Assertions.assertEquals(appid, retained.get("appid"));
                org.junit.jupiter.api.Assertions.assertEquals(tradeType, retained.get("trade_type"));
                org.junit.jupiter.api.Assertions.assertEquals("CNY", retained.get("currency"));
                org.junit.jupiter.api.Assertions.assertEquals(totalFen, ((Number) retained.get("total_fen")).longValue());
                org.junit.jupiter.api.Assertions.assertEquals(requestHash, retained.get("request_hash"));
                org.junit.jupiter.api.Assertions.assertNull(retained.get("closed_confirmed_at"),
                        "a fixture must never reopen or replace the original closed attempt");
            }
        });
    }

    /** Real MySQL mapper/transaction wiring. Caller must apply the exact proposed migration in its approved scratch DB. */
    public static WechatVerifiedPaymentFactService realFacts(javax.sql.DataSource source) {
        try {
            var factory = new org.mybatis.spring.SqlSessionFactoryBean(); factory.setDataSource(source);
            factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/business/WechatVerifiedPaymentFactMapper.xml"));
            var sql = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
            WechatVerifiedPaymentFactService target = new WechatVerifiedPaymentFactService();
            ReflectionTestUtils.setField(target,"mapper",sql.getMapper(WechatVerifiedPaymentFactMapper.class));
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
            ReflectionTestUtils.setField(target,"factReadTransactions",manager);
            ReflectionTestUtils.setField(target,"factWriteTransactions",manager);
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
            var business=new JdbcWechatPaymentOrderOwner();ReflectionTestUtils.setField(business,"paymentIntentJdbc",jdbc);
            ReflectionTestUtils.setField(target,"factBusinessOwner",business);
            ReflectionTestUtils.setField(target,"factIntentJdbc",jdbc);
            var proxy = new org.springframework.aop.framework.ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    manager,
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            return (WechatVerifiedPaymentFactService)proxy.getProxy();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    /** Real local attempt locks only; callers keep outbound provider doubles separate. */
    public static com.chengyinhub.business.service.WechatPaymentOperations realPaymentGuards(
            javax.sql.DataSource source, String merchant, WechatVerifiedPaymentFactService facts) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var owner = new JdbcWechatPaymentOrderOwner();
        ReflectionTestUtils.setField(owner, "paymentIntentJdbc", jdbc);
        var intents = new WechatPaymentIntentStore();
        ReflectionTestUtils.setField(intents, "paymentIntentJdbc", jdbc);
        ReflectionTestUtils.setField(intents, "paymentIntentTransactions", manager);
        ReflectionTestUtils.setField(intents, "businessOwner", owner);
        ReflectionTestUtils.setField(intents, "verifiedFacts", facts);
        var config = org.mockito.Mockito.mock(com.chengyinhub.common.config.WxPayConfig.class);
        org.mockito.Mockito.when(config.getMerchantId()).thenReturn(merchant);
        var gateway = new WechatPaymentSdkGateway();
        ReflectionTestUtils.setField(gateway, "wxPayConfig", config);
        ReflectionTestUtils.setField(gateway, "intents", intents);
        ReflectionTestUtils.setField(gateway, "verifiedFacts", facts);
        var payments = org.mockito.Mockito.mock(JsapiService.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() == Object.class)
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            throw new AssertionError("Local payment guards must not call the SDK: "
                    + invocation.getMethod().getName());
        });
        bind(gateway, payments, null, merchant);
        return gateway;
    }

    public static final class MemoryMapper implements WechatVerifiedPaymentFactMapper {
        final Map<Long,Map<String,Object>> rows = new LinkedHashMap<>();
        final Map<String,Map<String,Object>> links = new HashMap<>();
        final Map<String,Map<String,Object>> rejections = new HashMap<>();
        final Map<String,Map<String,Object>> recoveries = new HashMap<>();
        final AtomicLong sequence = new AtomicLong();
        public synchronized int insertIgnore(Map<String,Object> row) {
            if (!selectMatching((String)row.get("fact_kind"),(String)row.get("mchid"),
                    (String)row.get("provider_id"),(String)row.get("merchant_event_no")).isEmpty()) return 0;
            long id=sequence.incrementAndGet(); row.put("id",id); rows.put(id,new HashMap<>(row)); return 1;
        }
        public synchronized List<Map<String,Object>> selectMatching(String kind,String mchid,String provider,String event) {
            return rows.values().stream().filter(r -> kind.equals(r.get("fact_kind")) && mchid.equals(r.get("mchid"))
                    && (provider.equals(r.get("provider_id")) || event.equals(r.get("merchant_event_no"))))
                    .map(HashMap::new).map(r -> (Map<String,Object>)r).toList();
        }
        public synchronized List<Map<String,Object>> selectSuccessesByBusiness(String businessNo) {
            return rows.values().stream().filter(r->businessNo.equals(r.get("business_out_trade_no"))).map(HashMap::new).map(r->(Map<String,Object>)r).toList();
        }
        public synchronized List<Map<String,Object>> selectMatchingCurrent(String kind,String mchid,String provider,String event) {
            return selectMatching(kind,mchid,provider,event);
        }
        public synchronized Map<String,Object> selectById(long id) { var row=rows.get(id);return row==null?null:new HashMap<>(row); }
        public synchronized int insertRejection(Map<String,Object> r) {
            return rejections.putIfAbsent((String)r.get("rejection_key"),new HashMap<>(r))==null?1:0;
        }
        public synchronized int insertRecovery(Map<String,Object> row) {
            String key=(String)row.get("idempotency_key");if(recoveries.containsKey(key)) return 0;
            Map<String,Object> value=new HashMap<>(row);value.put("id",(long)recoveries.size()+1);value.put("biz_type",1);
            value.put("money_affecting",0);value.put("state",4);value.put("last_error_code",row.get("reason"));recoveries.put(key,value);return 1;
        }
        public synchronized Map<String,Object> selectRecovery(String key) { var row=recoveries.get(key);return row==null?null:new HashMap<>(row); }
        public synchronized Map<String,Object> selectRecoveryCurrent(String key) { return selectRecovery(key); }
        public synchronized int completeRecovery(String key,long businessId) {
            var row=recoveries.get(key);if(row==null || !Long.valueOf(businessId).equals(row.get("biz_id")) || !Integer.valueOf(4).equals(row.get("state"))) return 0;
            row.put("state",2);row.put("last_error_code",null);return 1;
        }
        public synchronized int insertInboxLink(Map<String,Object> r) {
            if (links.values().stream().anyMatch(old -> old.get("inbox_id").equals(r.get("inbox_id"))
                    || old.get("fact_id").equals(r.get("fact_id")))) return 0;
            return links.putIfAbsent((String)r.get("notify_hash"),new HashMap<>(r))==null?1:0;
        }
        public synchronized Map<String,Object> selectInboxLink(String hash) {
            var lower=links.get(hash.toLowerCase(java.util.Locale.ROOT));var upper=links.get(hash.toUpperCase(java.util.Locale.ROOT));
            if(lower!=null && upper!=null && lower!=upper) throw new IllegalStateException("Ambiguous hash encoding");
            var row=lower!=null?lower:upper;return row==null?null:new HashMap<>(row);
        }
    }
}
