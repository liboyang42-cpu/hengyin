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
    public static Object getField(Object target,String name) { return WechatPayTestWiring.getField(target,name); }
    public static <T> T invokeMethod(Object target,String name,Object... args) { return WechatPayTestWiring.invokeMethod(target,name,args); }
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
            ReflectionTestUtils.setField(gateway,"verifiedProviderClients",constructor.newInstance(payment,refund,mchid));
        } catch (ReflectiveOperationException failed) { throw new AssertionError(failed); }
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
            var proxy = new org.springframework.aop.framework.ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                    manager,
                    new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
            return (WechatVerifiedPaymentFactService)proxy.getProxy();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    public static final class MemoryMapper implements WechatVerifiedPaymentFactMapper {
        final Map<Long,Map<String,Object>> rows = new LinkedHashMap<>();
        final Map<String,Map<String,Object>> links = new HashMap<>();
        final Map<String,Map<String,Object>> rejections = new HashMap<>();
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
        public synchronized Map<String,Object> selectById(long id) { var row=rows.get(id);return row==null?null:new HashMap<>(row); }
        public synchronized int insertRejection(Map<String,Object> r) {
            return rejections.putIfAbsent((String)r.get("rejection_key"),new HashMap<>(r))==null?1:0;
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
