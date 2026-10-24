package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.SettlementRule;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.ISettlementRuleService;
import com.chengyinhub.business.service.WechatPaymentOperations;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import com.chengyinhub.business.service.impl.OmsOrderServiceImpl;
import com.chengyinhub.business.service.impl.WechatVerifiedFactTestSupport;
import com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real generic service, unchanged E checkout/consumer, canonical XML/fact schema and Spring Tx.
 * The prepay transport is a declared double; this is not SDK/signature/HTTP/real-provider evidence. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsOrderCommerceGuardMySqlIT {
    private final String suite = "hcommerce_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
    private DriverManagerDataSource source;
    private JdbcTemplate db;
    private boolean ownedSchema;
    private DataSourceTransactionManager manager;
    private OmsOrderMapper orders;
    private IOmsOrderService generic;
    private MerchantCommerceService checkout;
    private WechatVerifiedPaymentFactService facts;
    private static final String REQUEST_HASH = "b".repeat(64);

    @BeforeAll void schemaAndRealOwners() throws Exception {
        assertEquals("github-hosted", System.getenv("RUNNER_ENVIRONMENT"));
        assertEquals("1", System.getenv("B_PAYMENT_FACT_DDL_APPROVED"));
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        for (String name : List.of("CHENGYIN_MYBATIS_IT_PORT","CHENGYIN_MYBATIS_IT_USER","CHENGYIN_MYBATIS_IT_PASSWORD"))
            assertNotNull(System.getenv(name), "Explicit scratch selector required: " + name);
        assertTrue(MybatisItDatabase.port().matches("[0-9]{1,5}"));
        int port = Integer.parseInt(MybatisItDatabase.port()); assertTrue(port > 0 && port < 65536);
        String schema = MybatisItDatabase.databaseName(suite); assertTrue(schema.matches("[a-z0-9_]{1,64}"));
        String endpoint = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + port + "/";
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(endpoint + "?useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()));
        admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4"); ownedSchema = true;
        source = new DriverManagerDataSource(endpoint + schema + "?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",
                MybatisItDatabase.user(), MybatisItDatabase.password()); db = new JdbcTemplate(source);
        assertTrue(db.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
        P0AcceptanceSchema.baseTables(db,"sys_config","ums_member","club","club_member","oms_order","merchant_subscription");
        P0AcceptanceSchema.migrations(source,"migration_club_open_settings_20260909.sql","migration_club_role_membership_20260823.sql",
                "migration_club_role_membership_resume_fence_20260826.sql","migration_20260821_commerce_order_idempotency.sql",
                "migration_business_audit_event_20260823.sql","migration_20261023_wx_pay_order_intent.sql",
                "migration_wx_payment_callback_inbox_outbox_20260725.sql","migration_20261024_wx_verified_payment_fact.sql",
                "migration_20261024_clm_commerce_payment_proof.sql");
        SqlSessionTemplate sql = P0AcceptanceSchema.sql(source,"OmsOrderMapper","MerchantSubscriptionMapper");
        orders = sql.getMapper(OmsOrderMapper.class);
        manager = new DataSourceTransactionManager(source); generic = genericOwner(orders);
        facts = WechatVerifiedFactTestSupport.realFacts(source);
        WechatPaymentOperations locks = WechatVerifiedFactTestSupport.realPaymentGuards(source,"merchant",facts);
        WechatPaymentOperations payments = mock(WechatPaymentOperations.class, call -> {
            if (call.getMethod().getDeclaringClass() == Object.class) return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
            if ("createGenericOrder".equals(call.getMethod().getName())) {
                String no = call.getArgument(0); BigDecimal amount = call.getArgument(1);
                OmsOrder original = orders.selectOmsOrderByOrderSn(no);
                assertNotNull(original, "checkout's own transaction must commit before the prepay seam");
                assertEquals(1, db.update("INSERT INTO wx_pay_order_intent(mchid,out_trade_no,business_out_trade_no,biz_type,biz_id,"
                        + "attempt_no,appid,trade_type,currency,total_fen,request_hash,payer_openid,request_description,notify_url) "
                        + "VALUES('merchant',?,?,'COMMERCE',?,1,'wx-original','JSAPI','CNY',?,?,'','','https://example.test/notify')",
                        no,no,original.getId(),amount.movePointRight(2).longValueExact(),REQUEST_HASH));
                return Map.of("timeStamp","1","nonceStr","fixture","package","prepay_id=fixture","signType","RSA","paySign","fixture");
            }
            if ("lockPaymentAttemptForUpdate".equals(call.getMethod().getName()))
                return locks.lockPaymentAttemptForUpdate(call.getArgument(0),call.getArgument(1));
            throw new AssertionError("Unexpected external/payment operation: " + call.getMethod().getName());
        });
        SettlementRule rule = new SettlementRule(); rule.setUnitPrice(new BigDecimal("19.90"));
        rule.setEntitlementMaxUsage(1); rule.setEntitlementDays(30);
        ISettlementRuleService rules = mock(ISettlementRuleService.class);
        when(rules.getActiveRule(0L,"premium_template")).thenReturn(rule);
        checkout = new MerchantCommerceService(true);
        ReflectionTestUtils.setField(checkout,"orderMapper",orders);
        ReflectionTestUtils.setField(checkout,"subscriptionMapper",sql.getMapper(MerchantSubscriptionMapper.class));
        ReflectionTestUtils.setField(checkout,"paymentTransactions",manager);
        ReflectionTestUtils.setField(checkout,"settlementRuleService",rules);
        ReflectionTestUtils.setField(checkout,"wechatPayService",payments);
        ReflectionTestUtils.setField(checkout,"verifiedPaymentFacts",facts);
    }

    @BeforeEach void cleanOwnedRows() {
        for (String table : List.of("oms_order","merchant_subscription","wx_payment_callback_fact_link",
                "wx_verified_payment_fact_rejection","wx_payment_callback_outbox","wx_payment_callback_inbox",
                "wx_verified_payment_fact","wx_pay_order_intent","ums_member")) db.update("DELETE FROM " + table);
        db.update("INSERT INTO ums_member(id) VALUES(70),(71)");
    }
    @AfterAll void cleanup() { if (ownedSchema && source != null) P0AcceptanceSchema.drop(new JdbcTemplate(source),suite); }

    @Test void forgedPostsCannotCreateCommerceEvenWithMissingBizType() {
        for (String type : List.of("premium_template","promotion_slot","custom_event")) {
            OmsOrder forged = new OmsOrder(); forged.setBizType(type); forged.setMemberId(70L);
            forged.setPayAmount(new BigDecimal("0.01")); forged.setEntitlementMaxUsage(1000);
            assertThrows(ServiceException.class, () -> generic.insertOmsOrder(forged));
        }
        OmsOrder hidden = new OmsOrder(); hidden.setOrderSn("TPLTM_hidden"); hidden.setMemberId(70L);
        assertThrows(ServiceException.class, () -> generic.insertOmsOrder(hidden));
        assertEquals(0, count("oms_order")); assertEquals(0, count("merchant_subscription"));
        assertEquals(0, count("wx_pay_order_intent"));
    }

    @Test void rejectedGenericMutationLeavesOriginalCheckoutAndOneEntitlementGrant() {
        OmsOrder original = createCheckout(); Map<String,Object> before = snapshot(original.getId());
        OmsOrder forged = new OmsOrder(); forged.setId(original.getId()); forged.setEntitlementMaxUsage(1000);
        forged.setEntitlementDays(36500); forged.setMemberId(71L); forged.setOrderNote("must roll back with refusal");
        assertThrows(ServiceException.class, () -> generic.updateOmsOrder(forged));
        assertEquals(before, snapshot(original.getId())); assertEquals(0, count("merchant_subscription"));
        var reference = observe(original);
        // Original durable recovery identity; consumer must complete it with its original grant/proof.
        db.update("INSERT INTO wx_payment_callback_outbox(idempotency_key,biz_type,biz_id,state,money_affecting,last_error_code,create_time,update_time) "
                + "VALUES(?,1,?,4,0,'FIXTURE_ORIGINAL_SUCCESS',NOW(),NOW())", "VERIFIED:"+reference.id()+":"+reference.hash(),original.getId());
        assertTrue(checkout.handlePaidOrder(original.getOrderSn(),true,1990,"TX-original",reference));
        assertTrue(checkout.handlePaidOrder(original.getOrderSn(),true,1990,"TX-original",reference));
        assertEquals(1, count("merchant_subscription"));
        assertEquals(1, db.queryForObject("SELECT max_usage FROM merchant_subscription WHERE merchant_id=70",Integer.class).intValue());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM merchant_subscription WHERE merchant_id=71",Integer.class).intValue());
        assertEquals(reference.id(), db.queryForObject("SELECT verified_payment_fact_id FROM oms_order WHERE id=?",Long.class,original.getId()).longValue());
        assertEquals(2, db.queryForObject("SELECT state FROM wx_payment_callback_outbox WHERE idempotency_key=?",Integer.class,
                "VERIFIED:"+reference.id()+":"+reference.hash()).intValue());
    }

    @Test void ordinaryOperationsAndCommerceNotesRemainUsableButRetaggingIsRejected() {
        OmsOrder mall = ordinary("MALL-normal"); assertEquals(1,generic.insertOmsOrder(mall));
        OmsOrder shipping = new OmsOrder(); shipping.setId(mall.getId()); shipping.setStatus(2L); shipping.setDeliverySn("real-number");
        assertEquals(1,generic.updateOmsOrder(shipping));
        assertEquals(2L,orders.selectOmsOrderById(mall.getId()).getStatus());
        Map<String,Object> before = snapshot(mall.getId());
        OmsOrder retag = new OmsOrder(); retag.setId(mall.getId()); retag.setBizType("premium_template");
        assertThrows(ServiceException.class, () -> generic.updateOmsOrder(retag)); assertEquals(before,snapshot(mall.getId()));
        assertEquals(1,generic.deleteOmsOrderById(mall.getId()));
        OmsOrder commerce = createCheckout();
        OmsOrder notes = new OmsOrder(); notes.setId(commerce.getId()); notes.setOrderNote("support note");
        assertEquals(1,generic.updateOmsOrder(notes));
        assertEquals("support note",orders.selectOmsOrderById(commerce.getId()).getOrderNote());
        assertEquals(1,orders.selectOmsOrderById(commerce.getId()).getEntitlementMaxUsage());
    }

    @Test void mixedBatchAndSingleDeleteRetainAllOriginalRowsIncludingPaidProof() {
        OmsOrder commerce = createCheckout(); var reference = observe(commerce);
        assertTrue(checkout.handlePaidOrder(commerce.getOrderSn(),true,1990,"TX-original",reference));
        OmsOrder mall = ordinary("MALL-retained"); assertEquals(1,generic.insertOmsOrder(mall));
        Map<String,Object> paid = snapshot(commerce.getId()), regular = snapshot(mall.getId());
        assertThrows(ServiceException.class, () -> generic.deleteOmsOrderByIds(new Long[]{mall.getId(),commerce.getId()}));
        assertThrows(ServiceException.class, () -> generic.deleteOmsOrderById(commerce.getId()));
        assertEquals(paid,snapshot(commerce.getId())); assertEquals(regular,snapshot(mall.getId()));
        assertEquals(1,count("merchant_subscription")); assertEquals(1,count("wx_verified_payment_fact"));
    }

    @Test void currentReadFindsCommerceInsertedAfterAnOuterRrSnapshot() {
        var tx = new TransactionTemplate(manager); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThrows(org.springframework.transaction.UnexpectedRollbackException.class, () -> tx.execute(status -> {
            assertEquals(0,count("oms_order")); // Deliberately establish the old read view.
            OmsOrder original = createCheckoutOutsideOuterTransaction();
            assertEquals(0,count("oms_order"), "keep the intentional old ordinary RR snapshot assertion");
            OmsOrder patch = new OmsOrder(); patch.setId(original.getId()); patch.setEntitlementMaxUsage(1000);
            assertThrows(ServiceException.class, () -> generic.updateOmsOrder(patch));
            return null;
        }));
        assertEquals(1,count("oms_order"));
        assertEquals(1,db.queryForObject("SELECT entitlement_max_usage FROM oms_order",Integer.class).intValue());
        assertEquals(0,count("merchant_subscription"));
    }

    @Test void affectedRowFailureAfterActualDeleteRollsBackOrdinaryRows() {
        OmsOrder mall = ordinary("MALL-rollback"); assertEquals(1,generic.insertOmsOrder(mall));
        AtomicBoolean executed = new AtomicBoolean();
        OmsOrderMapper fault = (OmsOrderMapper) Proxy.newProxyInstance(OmsOrderMapper.class.getClassLoader(),
                new Class<?>[]{OmsOrderMapper.class},(proxy,method,args) -> {
                    if ("deleteOmsOrderByIds".equals(method.getName())) {
                        assertEquals(1,orders.deleteOmsOrderByIds((Long[])args[0])); executed.set(true); return 0;
                    }
                    try { return method.invoke(orders,args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        assertThrows(ServiceException.class, () -> genericOwner(fault).deleteOmsOrderById(mall.getId()));
        assertTrue(executed.get(),"fault must occur after the actual DELETE SQL");
        assertNotNull(orders.selectOmsOrderById(mall.getId()));
    }

    private OmsOrder createCheckout() {
        Map<String,String> response = checkout.createOrder(70L,34L,"original-openid","premium_template",UUID.randomUUID().toString());
        assertTrue(response.get("orderSn").length() <= 32, "real checkout must fit canonical oms_order.order_sn, not a widened fixture");
        return orders.selectOmsOrderByOrderSn(response.get("orderSn"));
    }
    private OmsOrder createCheckoutOutsideOuterTransaction() {
        var suspended = new TransactionTemplate(manager);
        suspended.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return suspended.execute(status -> createCheckout());
    }
    private WechatVerifiedPaymentFact.Reference observe(OmsOrder order) {
        return facts.recordVerified(new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"merchant","wx-original",
                order.getOrderSn(),order.getOrderSn(),order.getOrderSn(),"TX-original","TX-original","JSAPI","CNY",1990,1990,
                Instant.parse("2026-10-24T00:00:00Z"),REQUEST_HASH,"SUCCESS"));
    }
    private IOmsOrderService genericOwner(OmsOrderMapper mapper) {
        OmsOrderServiceImpl target = new OmsOrderServiceImpl(); ReflectionTestUtils.setField(target,"omsOrderMapper",mapper);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (IOmsOrderService)proxy.getProxy();
    }
    private static OmsOrder ordinary(String no) {
        OmsOrder row = new OmsOrder(); row.setOrderSn(no); row.setMemberId(70L); row.setMmsMerchantId(34L);
        row.setOrderType(1L); row.setStatus(1L); row.setPayType(1L); row.setPayAmount(BigDecimal.ONE); return row;
    }
    private int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private Map<String,Object> snapshot(Long id) {
        Map<String,Object> snapshot = new LinkedHashMap<>(db.queryForMap("SELECT * FROM oms_order WHERE id=?",id));
        snapshot.replaceAll((key,value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
        return snapshot;
    }
}
