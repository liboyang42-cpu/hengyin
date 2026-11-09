package com.chengyinhub.business.it;

import com.apple.itunes.storekit.model.*;
import com.chengyinhub.business.appleiap.*;
import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.Expectation;
import com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction;
import com.chengyinhub.business.appleiap.mapper.AppleIapMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real migration/mapper/transactions/concurrency; SDK results and the order port fixture are not Apple sandbox evidence. */
class WorkshopAppleIapMyBatisIT {
    static final UUID TOKEN=UUID.fromString("17f638cc-bb0a-4cd5-8d5d-4d05d61635c3");
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static AppleIapMapper mapper;
    AppleSignedData sdk;
    AppleIapProperties config;
    WorkshopAppleIapService service;
    FixtureOrders orders;

    @BeforeAll static void schema() throws Exception {
        ds=new DriverManagerDataSource(MybatisItDatabase.url("workshop_apple_iap"),MybatisItDatabase.user(),MybatisItDatabase.password());
        jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()) { ItSchema.applyMigrations(connection,"sql/migration_z_workshop_apple_iap_20261109.sql"); }
        jdbc.execute("CREATE TABLE apple_iap_test_order(order_no varchar(128) primary key,buyer bigint not null,token char(36) not null unique,original_id varchar(128) unique,state varchar(16) not null default 'PENDING',activation_count int not null default 0)");
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/appleiap/AppleIapMapper.xml"));
        mapper=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(AppleIapMapper.class);
    }

    @BeforeEach @SuppressWarnings("unchecked") void fixture() {
        for(String table:List.of("workshop_apple_notification","workshop_apple_transaction","workshop_apple_original","apple_iap_test_order")) jdbc.execute("DELETE FROM "+table);
        config=new AppleIapProperties();config.setEnabled(true);config.setBundleId("org.chengyin.app");
        config.setSkuPrices(Map.of("template.v1",Map.of("CNY",1230L)));
        sdk=mock(AppleSignedData.class);when(sdk.transaction("purchase")).thenAnswer(c->transaction());
        orders=spy(new FixtureOrders());ObjectProvider<WorkshopAppleOrderPort> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(orders);
        service=new WorkshopAppleIapService(config,sdk,mapper,provider,new DataSourceTransactionManager(ds));
    }

    @Test void actualUniqueIdsAndOrderLocksSerializeConcurrentPurchaseAndRefundReplay() throws Exception {
        insertOrder();
        var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(pool.submit(()->{gate.await();return service.confirm(7L,"WKO_one","purchase");}));
            gate.countDown();for(var call:calls)assertEquals("VERIFIED",call.get(10,TimeUnit.SECONDS).get("state"));
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("select activation_count from apple_iap_test_order",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from workshop_apple_original",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from workshop_apple_transaction",Integer.class));
        String uuid=refund();service.notification("refund");service.notification("refund");
        assertEquals("REVOKED",service.confirm(7L,"WKO_one","purchase").get("state"));
        assertEquals("REVOKED",jdbc.queryForObject("select state from apple_iap_test_order",String.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from workshop_apple_notification where notification_uuid=? and state='APPLIED'",Integer.class,uuid));
        assertEquals(1,jdbc.queryForObject("select activation_count from apple_iap_test_order",Integer.class));
        when(sdk.transaction("conflict")).thenReturn(transaction().purchaseDate(101L));
        assertThrows(ServiceException.class,()->service.confirm(7L,"WKO_one","conflict"));
        assertThrows(ServiceException.class,()->service.restore(8L,"WKO_one","purchase"));
    }

    @Test void refundBeforeOrderAndFailedBusinessApplicationKeepDurableRevocationAndRetryInbox() {
        String uuid=refund();
        assertThrows(ServiceException.class,()->service.notification("refund"));
        assertEquals(1,jdbc.queryForObject("select revoked from workshop_apple_original",Integer.class));
        assertEquals("PENDING",jdbc.queryForObject("select state from workshop_apple_notification",String.class));
        insertOrder();
        doAnswer(call->{
            orders.revokeBody(call.getArgument(0),call.getArgument(1));
            throw new IllegalStateException("fail after order update");
        }).when(orders).revoke(any(),any());
        assertThrows(ServiceException.class,()->service.notification("refund"));
        assertEquals("PENDING",jdbc.queryForObject("select state from apple_iap_test_order",String.class),"Business update must roll back");
        assertEquals(1,jdbc.queryForObject("select revoked from workshop_apple_original",Integer.class),"Prior committed refund tombstone must survive");
        doCallRealMethod().when(orders).revoke(any(),any());
        assertEquals("REVOKED",service.confirm(7L,"WKO_one","purchase").get("state"));
        assertEquals("APPLIED",service.notification("refund").get("state"));
        assertEquals(1,jdbc.queryForObject("select count(*) from workshop_apple_notification where notification_uuid=?",Integer.class,uuid));
        assertEquals(0,jdbc.queryForObject("select activation_count from apple_iap_test_order",Integer.class));
    }

    @Test void changedSkuMappingCannotDelayRevocationOfExistingBoundLicense() {
        insertOrder(); service.confirm(7L,"WKO_one","purchase"); refund();
        config.setSkuPrices(Map.of());
        assertEquals("RESTORED",service.restore(7L,"WKO_one","purchase").get("state"));
        assertEquals("APPLIED",service.notification("refund").get("state"));
        assertEquals("REVOKED",jdbc.queryForObject("select state from apple_iap_test_order",String.class));
        assertEquals("APPLIED",jdbc.queryForObject("select state from workshop_apple_notification",String.class));
        assertEquals(1,jdbc.queryForObject("select revoked from workshop_apple_original",Integer.class));
        config.setSkuPrices(Map.of("template.v1",Map.of("CNY",1230L)));
        assertEquals("REVOKED",service.confirm(7L,"WKO_one","purchase").get("state"));
        assertEquals(1,jdbc.queryForObject("select activation_count from apple_iap_test_order",Integer.class));
        assertEquals("APPLIED",service.notification("refund").get("state"));
    }

    @Test void purchaseAndLedgerRollBackTogetherAndRestoreCannotCreateAnotherLicense() {
        insertOrder();
        doAnswer(call->{
            orders.activateBody(call.getArgument(0),call.getArgument(1));
            throw new IllegalStateException("fail after activation");
        }).when(orders).activate(any(),any());
        assertThrows(IllegalStateException.class,()->service.confirm(7L,"WKO_one","purchase"));
        assertEquals(0,jdbc.queryForObject("select count(*) from workshop_apple_original",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from workshop_apple_transaction",Integer.class));
        assertEquals("PENDING",jdbc.queryForObject("select state from apple_iap_test_order",String.class));
        assertThrows(ServiceException.class,()->service.restore(7L,"WKO_one","purchase"));
        doCallRealMethod().when(orders).activate(any(),any());
        service.confirm(7L,"WKO_one","purchase");
        assertEquals("RESTORED",service.restore(7L,"WKO_one","purchase").get("state"));
        assertEquals(1,jdbc.queryForObject("select activation_count from apple_iap_test_order",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from apple_iap_test_order",Integer.class));
    }

    static void insertOrder() { jdbc.update("insert into apple_iap_test_order(order_no,buyer,token) values('WKO_one',7,?)",TOKEN.toString()); }
    String refund() {
        String uuid="bb631b8b-6d3c-4579-b1c6-660206757a5c";
        when(sdk.notification("refund")).thenReturn(new ResponseBodyV2DecodedPayload().notificationUUID(uuid)
                .notificationType(NotificationTypeV2.REFUND).data(new Data().signedTransactionInfo("inner")));
        when(sdk.transaction("inner")).thenAnswer(c->transaction().signedDate(300L).revocationDate(250L));return uuid;
    }
    static JWSTransactionDecodedPayload transaction() {
        return new JWSTransactionDecodedPayload().transactionId("1001").originalTransactionId("1000")
                .bundleId("org.chengyin.app").environment(Environment.SANDBOX).productId("template.v1")
                .appAccountToken(TOKEN).type(Type.NON_CONSUMABLE).inAppOwnershipType(InAppOwnershipType.PURCHASED)
                .quantity(1).price(1230L).currency("CNY").purchaseDate(100L).signedDate(200L);
    }
    static class FixtureOrders implements WorkshopAppleOrderPort {
        public Expectation requireOrder(Long buyer,String reference) {
            var rows=jdbc.queryForList("select * from apple_iap_test_order where order_no=? and buyer=? for update",reference,buyer);
            if(rows.isEmpty())throw new ServiceException("ORDER_NOT_OWNED",403);
            var row=rows.get(0);return new Expectation(reference,buyer,"org.chengyin.app","Sandbox","template.v1",
                    UUID.fromString((String)row.get("token")),(String)row.get("original_id"),1230L,"CNY");
        }
        public Optional<Expectation> findOrder(VerifiedTransaction fact) {
            var rows=jdbc.queryForList("select order_no,buyer from apple_iap_test_order where token=?",fact.appAccountToken().toString());
            if(rows.isEmpty())return Optional.empty();var row=rows.get(0);
            return Optional.of(requireOrder(((Number)row.get("buyer")).longValue(),(String)row.get("order_no")));
        }
        public void activate(Expectation e,VerifiedTransaction f) { activateBody(e,f); }
        void activateBody(Expectation e,VerifiedTransaction f) {
            jdbc.update("update apple_iap_test_order set original_id=?,state='ACTIVE',activation_count=activation_count+1 where order_no=? and state='PENDING'",f.originalTransactionId(),e.orderReference());
        }
        public void revoke(Expectation e,VerifiedTransaction f) { revokeBody(e,f); }
        void revokeBody(Expectation e,VerifiedTransaction f) {
            jdbc.update("update apple_iap_test_order set original_id=?,state='REVOKED' where order_no=?",f.originalTransactionId(),e.orderReference());
        }
        public void revokeBoundOriginal(String bundle,String environment,String original,Long date) {
            if (!"org.chengyin.app".equals(bundle) || !"Sandbox".equals(environment) || date == null || date <= 0) return;
            var rows=jdbc.queryForList("select order_no from apple_iap_test_order where original_id=? for update",original);
            for(var row:rows) jdbc.update("update apple_iap_test_order set state='REVOKED' where order_no=?",row.get("order_no"));
        }
        public Object restore(Expectation e,VerifiedTransaction f) { return jdbc.queryForMap("select order_no,state from apple_iap_test_order where order_no=? and buyer=?",e.orderReference(),e.buyerId()); }
    }
}
