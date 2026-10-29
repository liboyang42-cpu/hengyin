package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.it.MybatisItDatabase;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Explicit scratch MySQL only. Real service, topic/invite/station/order SQL and Spring transactions.
 * Commercial-review invalidation and credit/perk ancillary inputs are controlled doubles; no external or money actions.
 * This tests the invite station contract, not G's evidence/claim, canonical migration or payout acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoopSupplyStationMySqlIT {
    private JdbcTemplate admin, db;
    private String ownedSchema;
    private CoopInviteMapper invites;
    private CoopInviteServiceImpl service;
    private TransactionTemplate tx;
    private TopicBarrier barrier;

    @BeforeAll void scratch() throws Exception {
        for(String suffix:List.of("HOST","PORT","USER","PASSWORD","DB_SUFFIX"))
            assertNotNull(System.getenv("CHENGYIN_MYBATIS_IT_"+suffix),"Explicit scratch connection required: "+suffix);
        assertTrue(List.of("127.0.0.1","localhost").contains(MybatisItDatabase.host()));
        String name=MybatisItDatabase.databaseName("estation_"+UUID.randomUUID().toString().replace("-","").substring(0,10));
        assertTrue(name.matches("chengyin_mybatis_estation_[a-z0-9_]+")&&name.length()<=64);
        String prefix="jdbc:mysql://"+MybatisItDatabase.host()+":"+MybatisItDatabase.port()+"/";
        String params="?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true";
        admin=new JdbcTemplate(new DriverManagerDataSource(prefix+params,MybatisItDatabase.user(),MybatisItDatabase.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).matches("^(8|9)\\..*"));
        admin.execute("CREATE DATABASE `"+name+"`"); ownedSchema=name;
        DriverManagerDataSource source=new DriverManagerDataSource(prefix+name+params,MybatisItDatabase.user(),MybatisItDatabase.password());
        db=new JdbcTemplate(source); assertEquals(ownedSchema,db.queryForObject("SELECT DATABASE()",String.class));
        new ResourceDatabasePopulator(new ClassPathResource("it-schema/coop-it-schema.sql"),
                new ClassPathResource("it-schema/cms-topic-it-schema.sql")).execute(source);
        // This house snapshot is one complete CREATE without a trailing semicolon;
        // Spring's newline fallback would execute only "CREATE TABLE mms_merchant (".
        try (java.sql.Connection connection = source.getConnection()) {
            com.chengyinhub.business.it.ItSchema.apply(connection, "it-schema/merchant-it-schema.sql");
        }
        Path root=Files.exists(Path.of("sql/base_schema_20260818.sql"))?Path.of(""):Path.of("..");
        String base=Files.readString(root.resolve("sql/base_schema_20260818.sql"));
        int start=base.indexOf("CREATE TABLE `cms_registration_merchant` (");
        int close=base.indexOf("\n) ENGINE",start),end=base.indexOf(";\n",close);
        assertTrue(start>=0&&close>start&&end>close); db.execute(base.substring(start,end+1));
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/CoopInviteMapper.xml"),
                new ClassPathResource("mapper/business/CoopOrderMapper.xml"),
                new ClassPathResource("mapper/business/CmsTopicMapper.xml"),
                new ClassPathResource("mapper/business/MmsMerchantMapper.xml"));
        barrier=new TopicBarrier(); factory.setPlugins(barrier);
        SqlSessionTemplate sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        invites=sql.getMapper(CoopInviteMapper.class);
        service=new CoopInviteServiceImpl();
        ReflectionTestUtils.setField(service,"coopInviteMapper",invites);
        ReflectionTestUtils.setField(service,"coopOrderMapper",sql.getMapper(CoopOrderMapper.class));
        ReflectionTestUtils.setField(service,"cmsTopicMapper",sql.getMapper(CmsTopicMapper.class));
        ReflectionTestUtils.setField(service,"mmsMerchantMapper",sql.getMapper(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service,"coopCreditLogMapper",mock(CoopCreditLogMapper.class));
        ReflectionTestUtils.setField(service,"coopPerkMapper",mock(CoopPerkMapper.class));
        ReflectionTestUtils.setField(service,"commercialReviewService",mock(ITopicCommercialReviewService.class));
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterAll void clean() {
        if(ownedSchema==null)return;
        admin.execute("DROP DATABASE `"+ownedSchema+"`");
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,ownedSchema));
        ownedSchema=null;
    }
    @BeforeEach void rows() {
        barrier.contenderAtTopic=new CountDownLatch(1);
        ReflectionTestUtils.setField(service,"canonicalMutationService",null);
        db.update("DELETE FROM coop_order"); db.update("DELETE FROM coop_invite");
        db.update("DELETE FROM cms_registration_merchant"); db.update("DELETE FROM cms_topic"); db.update("DELETE FROM mms_merchant");
        db.update("INSERT INTO cms_topic(id,member_id,product_type,lifecycle,recruit_deadline,del_flag) VALUES(7,11,1,1,DATE_ADD(NOW(),INTERVAL 1 DAY),0),(8,11,1,1,DATE_ADD(NOW(),INTERVAL 1 DAY),0)");
        db.update("INSERT INTO mms_merchant(id,member_id,status,account_status,del_flag) VALUES(1,22,1,1,0)");
        db.update("INSERT INTO cms_registration_merchant(id,member_id,topic_id,mode,address_name,audit_status,del_flag) VALUES(101,22,7,1,'原站甲',1,0),(102,22,7,1,'原站乙',1,0),(103,23,7,1,'别家',1,0),(104,22,8,1,'别主题',1,0),(105,22,7,1,'待审核',0,0),(106,22,7,1,'已删除',1,2)");
    }
    private void pending(long id,Long station) {
        db.update("INSERT INTO coop_invite(id,invite_type,from_type,from_id,to_type,to_id,topic_id,status,supply_station_id,share_mode,fixed_fee,create_time,del_flag) VALUES(?,0,'talent',11,'merchant',22,7,0,?,2,12,NOW(),0)",id,station);
    }
    private CoopOrder request(long inviteId) {
        CoopOrder order=new CoopOrder();order.setInviteId(inviteId);order.setTopicId(7L);order.setFromId(11L);order.setToId(22L);order.setToType("merchant");return order;
    }
    private int state(long id){return db.queryForObject("SELECT status FROM coop_invite WHERE id=?",Integer.class,id);}
    private int orderCount(){return db.queryForObject("SELECT COUNT(*) FROM coop_order",Integer.class);}

    @Test void actualXmlPickerAndOriginalReadNameDoNotLeakOtherScopeOrSubstituteDeletedStation() {
        pending(41,101L);
        assertEquals(List.of(101L,102L),service.listSupplyStations(7L,22L,11L).stream().map(CmsRegistrationMerchant::getId).toList());
        db.update("UPDATE cms_registration_merchant SET audit_status=2,del_flag=2 WHERE id=101");
        CoopInvite row=service.selectCoopInviteById(41L);
        assertEquals(101L,row.getSupplyStationId()); assertEquals("原站甲",row.getSupplyStationName()); assertEquals(0,state(41));
        db.update("DELETE FROM cms_registration_merchant WHERE id=101");
        assertNull(service.selectCoopInviteById(41L).getSupplyStationName());
        assertEquals(101L,service.selectCoopInviteById(41L).getSupplyStationId()); assertEquals(0,orderCount());
    }
    @Test void confirmedAcceptanceAndExactOriginalOrderReplayKeepOneImmutableServerContract() {
        pending(41,101L); tx.execute(s->service.acceptInviteWithLock(41L,null,101L));
        assertEquals(1,state(41)); assertEquals(1,orderCount());
        String terms=db.queryForObject("SELECT terms_snapshot FROM coop_order",String.class);
        var scope=JSON.parseObject(terms).getJSONObject("supplyBillingScope");
        assertEquals(101L,scope.getLong("stationId")); assertEquals(7L,scope.getLong("topicId"));assertEquals(22L,scope.getLong("merchantMemberId"));
        assertEquals("REGISTRATION_STATION_V1",scope.getString("version"));
        long originalId=db.queryForObject("SELECT id FROM coop_order",Long.class);
        Long replayId=tx.execute(s->service.createCoopOrder(request(41))); assertEquals(Long.valueOf(originalId),replayId);
        assertEquals(terms,db.queryForObject("SELECT terms_snapshot FROM coop_order",String.class)); assertEquals(1,orderCount());
        assertEquals(List.of(102L),service.listSupplyStations(7L,22L,11L).stream().map(CmsRegistrationMerchant::getId).toList());
    }
    @Test void changedMissingOrUnapprovedConfirmationHasZeroBusinessWrites() {
        pending(41,101L);
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.acceptInviteWithLock(41L,null)));
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.acceptInviteWithLock(41L,null,102L)));
        db.update("UPDATE cms_registration_merchant SET audit_status=0 WHERE id=101");
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.acceptInviteWithLock(41L,null,101L)));
        assertEquals(0,state(41));assertEquals(0,orderCount());assertEquals(101L,db.queryForObject("SELECT supply_station_id FROM coop_invite WHERE id=41",Long.class));
    }
    @Test void twoConnectionsSerializeOnOriginalTopicAndCannotConsumeSameStationTwice() throws Exception {
        pending(41,101L); pending(42,101L); // Adversarial pending rows: public create also blocks duplicate pending invites.
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            tx.execute(s->{
                db.queryForObject("SELECT id FROM cms_topic WHERE id=7 FOR UPDATE",Long.class);
                Future<?> contender=worker.submit(()->{barrier.contender.set(true);try {
                    assertThrows(IllegalStateException.class,()->tx.execute(t->service.acceptInviteWithLock(42L,null,101L)));
                } finally {barrier.contender.remove();}});
                barrier.result=contender;
                try {assertTrue(barrier.contenderAtTopic.await(5,TimeUnit.SECONDS),"contender must reach real topic lock");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                assertFalse(contender.isDone(),"other connection cannot pass held topic lock");
                service.acceptInviteWithLock(41L,null,101L); return null;
            });
            barrier.result.get(10,TimeUnit.SECONDS);
            assertEquals(1,state(41));assertEquals(0,state(42));assertEquals(1,orderCount());
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void postInsertFailureRollsBackAcceptanceOrderAndStationFactTogether() {
        pending(41,101L); ITopicSaleCanonicalMutationService failure=mock(ITopicSaleCanonicalMutationService.class);
        doThrow(new IllegalStateException("controlled invalidation failure")).when(failure).invalidateApprovedCanonical(any(),any());
        ReflectionTestUtils.setField(service,"canonicalMutationService",failure);
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.acceptInviteWithLock(41L,null,101L)));
        assertEquals(0,state(41));assertEquals(0,orderCount());assertEquals(101L,db.queryForObject("SELECT supply_station_id FROM coop_invite WHERE id=41",Long.class));
    }
    @Test void publicCreateCannotBypassOriginalInviteStationOrPendingState() {
        pending(41,101L); assertThrows(IllegalStateException.class,()->tx.execute(s->service.createCoopOrder(new CoopOrder())));
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.createCoopOrder(request(41))));
        tx.execute(s->service.acceptInviteWithLock(41L,null,101L)); pending(42,101L); db.update("UPDATE coop_invite SET status=1 WHERE id=42");
        assertThrows(IllegalStateException.class,()->tx.execute(s->service.createCoopOrder(request(42))));
        assertEquals(1,orderCount());
    }
    @Test void historicalNullNeverGetsBackfilledAndMapperUpdateCannotRebindSelectedStation() {
        pending(41,null);assertThrows(IllegalStateException.class,()->tx.execute(s->service.acceptInviteWithLock(41L,null,101L)));
        tx.execute(s->service.acceptInviteWithLock(41L,null));
        assertNull(db.queryForObject("SELECT supply_station_id FROM coop_invite WHERE id=41",Long.class));
        assertFalse(JSON.parseObject(db.queryForObject("SELECT terms_snapshot FROM coop_order",String.class)).containsKey("supplyBillingScope"));
        pending(42,101L); CoopInvite patch=new CoopInvite();patch.setId(42L);patch.setSupplyStationId(102L);patch.setMessage("display-only edit");
        invites.updateCoopInvite(patch);assertEquals(101L,db.queryForObject("SELECT supply_station_id FROM coop_invite WHERE id=42",Long.class));
        patch.setSupplyStationId(103L);assertThrows(IllegalStateException.class,()->tx.execute(s->service.updateCoopInvite(patch)));
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static class TopicBarrier implements Interceptor {
        final ThreadLocal<Boolean> contender=new ThreadLocal<>(); volatile CountDownLatch contenderAtTopic; Future<?> result;
        @Override public Object intercept(Invocation call)throws Throwable {
            if(Boolean.TRUE.equals(contender.get())&&((MappedStatement)call.getArgs()[0]).getId().endsWith(".selectCmsTopicByIdForUpdate"))
                contenderAtTopic.countDown();
            return call.proceed();
        }
    }
}
