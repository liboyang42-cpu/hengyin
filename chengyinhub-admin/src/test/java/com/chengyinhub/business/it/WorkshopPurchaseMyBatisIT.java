package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.business.mapper.WorkshopMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit VPS/CI MySQL suite; provider verification is mocked, never presented as a real charge. */
class WorkshopPurchaseMyBatisIT {
    @Test void actualMapperSerializesInstallRefundAndRollsBackDraftWithReceipt() throws Exception {
        var ds=new DriverManagerDataSource(MybatisItDatabase.url("workshop_purchase"),MybatisItDatabase.user(),MybatisItDatabase.password());
        var jdbc=new JdbcTemplate(ds);
        try(var connection=ds.getConnection()){ItSchema.applyMigrations(connection,"sql/migration_z_workshop_purchase_20261109.sql","sql/migration_z_workshop_paid_review_20261109.sql");}
        fixtureTable(jdbc,"CmsTemplateLibraryMapper","CmsTemplateLibraryResult","cms_template_library");
        fixtureTable(jdbc,"CmsMemberTemplateMapper","CmsMemberTemplateResult","cms_member_template");
        fixtureNodeReferences(jdbc);
        jdbc.update("insert into cms_member_template(id,member_id,title,question_answer,status,draft_status,publish_status,del_flag,interaction_type) values(80,99,'purchased title','frozen answer',0,0,0,0,'A')");
        jdbc.update("insert into cms_template_library(id,member_template_id,member_id,title,question_answer,status,del_flag) values(8,800,99,'legacy free title','free answer',1,0)");
        jdbc.update("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,del_flag,update_time) values(20,7,'target',0,0,0,0,'2026-11-09 00:00:00.000')");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setTypeAliases(CmsTemplateLibrary.class,TemplateLibraryVO.class,CmsMemberTemplate.class,MemberTemplateVO.class);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTemplateLibraryMapper.xml"),
                new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"),new ClassPathResource("mapper/business/WorkshopMapper.xml"));
        var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));var mapper=session.getMapper(WorkshopMapper.class);
        var drafts=new TemplatePublishServiceImpl();
        var memberWrites=mock(ICmsMemberTemplateService.class);
        var mutableLibrary=mock(ICmsTemplateLibraryService.class);
        ReflectionTestUtils.setField(drafts,"memberTemplateService",memberWrites);
        ReflectionTestUtils.setField(drafts,"templateLibraryService",mutableLibrary);
        ReflectionTestUtils.setField(drafts,"capabilityGuard",mock(ICapabilityGuard.class));
        ReflectionTestUtils.setField(drafts,"mediaPublicationGuard",mock(com.chengyinhub.business.service.support.AppMediaPublicationGuard.class));
        var pay=mock(IWechatPayService.class);var facts=mock(WechatVerifiedPaymentFactService.class);
        var transactions=new DataSourceTransactionManager(ds);
        var paymentOrders=new WorkshopWechatPaymentOrderAdapter(mapper,facts,transactions);
        var service=new WorkshopService(mapper,drafts,pay,paymentOrders,transactions);
        ReflectionTestUtils.setField(service,"appEnabled",true);when(pay.isAppReady()).thenReturn(true);
        String originalConfig="{\"schemaVersion\":1,\"qa\":{\"enabled\":true,\"mode\":\"PICK\",\"title\":\"frozen question\",\"options\":[{\"id\":\"a\",\"label\":\"old right\",\"correct\":true,\"fb\":\"old feedback\"},{\"id\":\"b\",\"label\":\"wrong\",\"correct\":false}]}}";
        jdbc.update("update cms_member_template set advanced_config_json=?,validation_method=1 where id=80",originalConfig);
        var source=service.authorSource(99,80);
        var offer=service.freeze(99,new WorkshopService.Freeze("freeze1",80,(String)source.get("contentHash"),123,
                new WorkshopService.Terms("terms1","author price and license",false,List.of("310100"),3)));
        String version=(String)offer.get("purchasedVersionId");
        var versionReview=new com.chengyinhub.business.service.support.WorkshopVersionReview(mapper);
        var auditSubmission=mock(IAuditTaskService.class);
        when(auditSubmission.submit(anyString(),anyLong(),eq(99L),eq("auto_gate"),anyString())).thenReturn(500L);
        ReflectionTestUtils.setField(versionReview,"audits",auditSubmission);
        ReflectionTestUtils.setField(versionReview,"drafts",drafts);
        ReflectionTestUtils.setField(service,"versionReview",versionReview);
        assertThrows(ServiceException.class,()->service.offer(version));
        service.submitVersion(99,new WorkshopService.ReviewSubmission(version,(String)offer.get("contentHash"),(String)offer.get("termsHash"),123));
        var reviewed=jdbc.queryForMap("select * from workshop_version where version_id=?",version);
        var auditTask=new com.chengyinhub.business.domain.AuditTask();
        auditTask.setId(500L);auditTask.setBizId(((Number)reviewed.get("id")).longValue());auditTask.setSubmitterId(99L);
        auditTask.setSnapshotJson((String)reviewed.get("review_snapshot"));
        new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds))
                .execute(status->{versionReview.apply(auditTask,1);return null;});
        assertEquals("APPROVED",service.offer(version).get("reviewState"));
        assertEquals(1L,jdbc.queryForObject("select status from cms_template_library where id=8",Long.class));
        jdbc.update("update cms_member_template set question_answer='new canonical answer',advanced_config_json=? where id=80",originalConfig.replace("old right","new canonical right"));
        var purchase=new WorkshopService.Purchase("purchase1",version,(String)offer.get("contentHash"),(String)offer.get("termsHash"),"WECHAT_APP");
        String no=(String)service.purchase(7,purchase).get("orderNo");
        assertEquals(no,service.purchase(7,purchase).get("orderNo"));
        assertEquals(1,jdbc.queryForObject("select count(*) from workshop_order",Integer.class));
        var paid=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"mch","app",no,"wx1","wx1","tx1","tx1","APP","CNY",123,123,Instant.ofEpochSecond(1),null,"SUCCESS");
        var reference=new WechatVerifiedPaymentFact.Reference(3,paid.factHash());
        when(facts.requireConsumablePayment(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,paid));
        when(facts.requireVerified(reference)).thenReturn(new WechatVerifiedPaymentFactService.Stored(reference,paid));
        Map<String,String> observation=Map.of("out_trade_no",no,"trade_state","SUCCESS","verified_fact_id","3","verified_fact_hash",reference.hash());
        var router=new WechatPaymentCallbackRouter(null,null,null,null,null,null,paymentOrders);
        assertEquals("SUCCESS",router.payment(observation).result());assertEquals("SUCCESS",router.payment(observation).result());
        var targets=service.targets(7,no,null);Map<?,?> target=(Map<?,?>)((List<?>)targets.get("items")).get(0);
        var json=new ObjectMapper();
        var command=new WorkshopService.Install("workshop-private-draft-install-command-v2","install1",no,80,version,
                (String)offer.get("contentHash"),(String)offer.get("termsHash"),20,((Number)target.get("targetRevision")).longValue(),
                "MEMBER_TEMPLATE",(String)target.get("targetPayloadHash"),"310100",false);
        String raw=json.writeValueAsString(command);
        AtomicLong serial=new AtomicLong(90);AtomicBoolean fail=new AtomicBoolean();
        CountDownLatch creating=new CountDownLatch(1),release=new CountDownLatch(1);
        when(memberWrites.insertCmsMemberTemplate(any())).thenAnswer(call -> {
            CmsMemberTemplate snapshot=call.getArgument(0);assertEquals("frozen answer",snapshot.getQuestionAnswer());
            assertTrue(snapshot.getAdvancedConfigJson().contains("old right")); assertTrue(snapshot.getAdvancedConfigJson().contains("old feedback"));
            assertFalse(snapshot.getAdvancedConfigJson().contains("new right"));
            long id=serial.incrementAndGet();
            snapshot.setId(id);
            jdbc.update("insert into cms_member_template(id,member_id,title,question_answer,advanced_config_json,status,draft_status,publish_status,del_flag,update_time) values(?,7,?,?,?,0,0,0,0,current_timestamp(3))",id,snapshot.getTitle(),snapshot.getQuestionAnswer(),snapshot.getAdvancedConfigJson());
            creating.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));
            if(fail.get())throw new IllegalStateException("injected after draft insertion");return 1;
        });
        when(pay.submitRefund(any(WechatVerifiedPaymentFact.Reference.class),anyString(),anyString(),any(),any(),anyString())).thenReturn(RefundRequestResult.UNKNOWN);
        var pool=Executors.newFixedThreadPool(5);
        try {
            var first=pool.submit(()->service.install(7,raw,"buyer"));assertTrue(creating.await(5,TimeUnit.SECONDS));
            var refund=pool.submit(()->service.refund(7,no));
            assertThrows(TimeoutException.class,()->refund.get(200,TimeUnit.MILLISECONDS),"Refund must share the license/order row lock");
            release.countDown();var installed=first.get(10,TimeUnit.SECONDS);
            refund.get(10,TimeUnit.SECONDS);
            assertEquals("SUSPENDED",service.license(7,no).get("status"));
            assertEquals(installed,service.install(7,raw,"buyer"));
            assertEquals(3,jdbc.queryForObject("select count(*) from cms_member_template",Integer.class));
            assertEquals(1,jdbc.queryForObject("select count(*) from workshop_install",Integer.class));
            assertEquals("target",jdbc.queryForObject("select title from cms_member_template where id=20",String.class));
            assertEquals("frozen answer",jdbc.queryForObject("select question_answer from cms_member_template where id=?",String.class,((Number)installed.get("ownedDraftId")).longValue()));
            assertTrue(jdbc.queryForObject("select advanced_config_json from cms_member_template where id=?",String.class,((Number)installed.get("ownedDraftId")).longValue()).contains("old feedback"));
            verifyNoInteractions(mutableLibrary);
            assertThrows(ServiceException.class,()->service.installedText(7,no,"install1"));
            var publicationGuard=new com.chengyinhub.business.service.support.WorkshopLicenseGuard(mapper);
            var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
            assertThrows(ServiceException.class,()->transaction.execute(status -> {publicationGuard.requirePublication(((Number)installed.get("ownedDraftId")).longValue(),false);return null;}));
            assertThrows(ServiceException.class,()->service.install(7,raw.replace("install1","install2"),"buyer"));
        } finally {release.countDown();pool.shutdownNow();}

        // A second real order exercises concurrency, changed-command conflict and transaction rollback.
        String second=(String)service.purchase(7,new WorkshopService.Purchase("purchase2",version,(String)offer.get("contentHash"),(String)offer.get("termsHash"),"WECHAT_APP")).get("orderNo");
        var secondPaid=new WechatVerifiedPaymentFact(WechatVerifiedPaymentFact.Kind.PAYMENT,"mch","app",second,"wx2","wx2","tx2","tx2","APP","CNY",123,123,Instant.ofEpochSecond(2),null,"SUCCESS");
        var secondRef=new WechatVerifiedPaymentFact.Reference(4,secondPaid.factHash());
        when(facts.requireConsumablePayment(secondRef)).thenReturn(new WechatVerifiedPaymentFactService.Stored(secondRef,secondPaid));
        assertEquals("SUCCESS",router.payment(Map.of("out_trade_no",second,"trade_state","SUCCESS","verified_fact_id","4","verified_fact_hash",secondRef.hash())).result());
        String secondRaw=raw.replace(no,second).replace("install1","concurrent1");
        var parallel=Executors.newFixedThreadPool(4);CountDownLatch gate=new CountDownLatch(1);
        try {
            List<Future<Map<String,Object>>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(parallel.submit(()->{gate.await();return service.install(7,secondRaw,"buyer");}));
            gate.countDown();Object draft=null;
            for(var call:calls){var result=call.get(10,TimeUnit.SECONDS);if(draft==null)draft=result.get("ownedDraftId");assertEquals(((Number)draft).longValue(),((Number)result.get("ownedDraftId")).longValue());}
        } finally {parallel.shutdownNow();}
        int before=jdbc.queryForObject("select count(*) from cms_member_template",Integer.class);
        fail.set(true);String rollback=secondRaw.replace("concurrent1","rollback1");
        assertThrows(IllegalStateException.class,()->service.install(7,rollback,"buyer"));
        assertEquals(before,jdbc.queryForObject("select count(*) from cms_member_template",Integer.class));
        assertEquals("NOT_FOUND",service.installStatus(7,"rollback1").get("state"));
        fail.set(false);assertEquals("INSTALLED_PRIVATE_DRAFT",service.install(7,rollback,"buyer").get("state"));
        assertThrows(ServiceException.class,()->service.install(7,secondRaw+" ","buyer"));
        assertThrows(ServiceException.class,()->service.license(8,second));
        assertEquals("NOT_FOUND",service.installStatus(8,"concurrent1").get("state"));
        jdbc.update("update cms_member_template set title='changed' where id=20");
        assertThrows(ServiceException.class,()->service.install(7,secondRaw.replace("concurrent1","stale1"),"buyer"));
        Map<?,?> refreshed=((List<?>)service.targets(7,second,null).get("items")).stream().map(v -> (Map<?,?>)v)
                .filter(v -> ((Number)v.get("targetDraftId")).longValue()==20L).findFirst().orElseThrow();
        var third=json.readTree(secondRaw);((com.fasterxml.jackson.databind.node.ObjectNode)third).put("requestId","limit3")
                .put("targetPayloadHash",(String)refreshed.get("targetPayloadHash"));
        assertEquals("INSTALLED_PRIVATE_DRAFT",service.install(7,third.toString(),"buyer").get("state"));
        assertEquals("INSTALL_LIMIT_REACHED",assertThrows(ServiceException.class,()->service.install(7,third.toString().replace("limit3","limit4"),"buyer")).getMessage());
        assertEquals(3,jdbc.queryForObject("select count(*) from workshop_install where order_no=?",Integer.class,second));
        long installedId=((Number)service.installStatus(7,"concurrent1").get("ownedDraftId")).longValue();
        var guard=new com.chengyinhub.business.service.support.WorkshopLicenseGuard(mapper);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
        CountDownLatch holdingLicense=new CountDownLatch(1),commitPublication=new CountDownLatch(1);
        var licenseRace=Executors.newFixedThreadPool(2);
        try {
            var publication=licenseRace.submit(()->transaction.execute(status -> {
                guard.requirePublication(installedId,false); holdingLicense.countDown();
                try {assertTrue(commitPublication.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;
            }));
            assertTrue(holdingLicense.await(5,TimeUnit.SECONDS));
            var revoke=licenseRace.submit(()->service.revoke(7,second));
            assertThrows(TimeoutException.class,()->revoke.get(200,TimeUnit.MILLISECONDS));
            commitPublication.countDown();publication.get(5,TimeUnit.SECONDS);revoke.get(5,TimeUnit.SECONDS);
            assertThrows(ServiceException.class,()->transaction.execute(status -> {guard.requirePublication(installedId,false);return null;}));
            assertEquals("frozen answer",jdbc.queryForObject("select question_answer from cms_member_template where id=?",String.class,installedId));
        } finally {commitPublication.countDown();licenseRace.shutdownNow();}
    }


    @Test void actualAppleOrderRowsSerializeRefundFirstReplayAndForcedCopyLineage() throws Exception {
        var ds=new DriverManagerDataSource(MybatisItDatabase.url("workshop_paid_apple"),MybatisItDatabase.user(),MybatisItDatabase.password());
        var jdbc=new JdbcTemplate(ds);
        try(var c=ds.getConnection()){ItSchema.applyMigrations(c,"sql/migration_z_workshop_purchase_20261109.sql","sql/migration_z_workshop_paid_review_20261109.sql");}
        fixtureTable(jdbc,"CmsTemplateLibraryMapper","CmsTemplateLibraryResult","cms_template_library");
        fixtureTable(jdbc,"CmsMemberTemplateMapper","CmsMemberTemplateResult","cms_member_template");
        fixtureNodeReferences(jdbc);
        jdbc.update("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,del_flag) values(80,99,'private paid',0,0,0,0)");
        // qx-c1 #1: mapper SQL runs through the app's Druid wall (multi-statement off), like production; fixtures stay raw.
        var walled=appWalledDataSource(MybatisItDatabase.url("workshop_paid_apple"));
        var factory=new SqlSessionFactoryBean();factory.setDataSource(walled);
        factory.setTypeAliases(CmsTemplateLibrary.class,TemplateLibraryVO.class,CmsMemberTemplate.class,MemberTemplateVO.class);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CmsTemplateLibraryMapper.xml"),
                new ClassPathResource("mapper/business/CmsMemberTemplateMapper.xml"),new ClassPathResource("mapper/business/WorkshopMapper.xml"));
        var sessions=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));var mapper=sessions.getMapper(WorkshopMapper.class);
        var manager=new DataSourceTransactionManager(walled);var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        var service=new WorkshopService(mapper,mock(ITemplatePublishService.class),mock(IWechatPayService.class),
                new WorkshopWechatPaymentOrderAdapter(mapper,mock(WechatVerifiedPaymentFactService.class),manager),manager);
        var review=new com.chengyinhub.business.service.support.WorkshopVersionReview(mapper);
        var audit=mock(IAuditTaskService.class);when(audit.submit(anyString(),anyLong(),eq(99L),eq("auto_gate"),anyString())).thenReturn(500L);
        ReflectionTestUtils.setField(review,"audits",audit);ReflectionTestUtils.setField(review,"drafts",mock(ITemplatePublishService.class));ReflectionTestUtils.setField(service,"versionReview",review);
        var signed=mock(com.chengyinhub.business.appleiap.AppleSignedData.class);when(signed.isReady()).thenReturn(true);
        var config=new com.chengyinhub.business.appleiap.AppleIapProperties();
        config.setEnabled(true);config.setBundleId("org.chengyin.app");config.setEnvironment("Sandbox");
        config.setSkuPrices(Map.of("paid.edition",Map.of("USD",990L)));
        ReflectionTestUtils.setField(service,"appleSignedData",signed);ReflectionTestUtils.setField(service,"appleProperties",config);
        var source=service.authorSource(99,80);
        var offer=service.freeze(99,new WorkshopService.Freeze("freeze",80,(String)source.get("contentHash"),123,
                new WorkshopService.Terms("t1","explicit USD mapping and copy limit",false,List.of("310100"),2)));
        String version=offer.get("purchasedVersionId").toString();
        jdbc.update("insert into workshop_apple_product(version_id,bundle_id,environment,product_id,price_milliunits,currency,enabled) values(?,'org.chengyin.app','Sandbox','paid.edition',990,'USD',1)",version);
        service.submitVersion(99,new WorkshopService.ReviewSubmission(version,(String)offer.get("contentHash"),(String)offer.get("termsHash"),123));
        var row=jdbc.queryForMap("select * from workshop_version where version_id=?",version);
        var task=new com.chengyinhub.business.domain.AuditTask();task.setId(500L);task.setBizId(((Number)row.get("id")).longValue());
        task.setSubmitterId(99L);task.setSnapshotJson((String)row.get("review_snapshot"));
        tx.execute(s->{review.apply(task,1);return null;});
        var purchase=new WorkshopService.Purchase("buy1",version,(String)offer.get("contentHash"),(String)offer.get("termsHash"),"APPLE_IAP",(String)row.get("review_hash"));
        var first=service.purchase(7,purchase);String no=first.get("orderNo").toString();
        assertNull(jdbc.queryForMap("select * from workshop_order where order_no=?",no).get("amount_fen"));
        assertEquals("USD",first.get("currency"));assertEquals(990L,((Number)first.get("amountMilliunits")).longValue());
        var adapter=new com.chengyinhub.business.service.impl.WorkshopAppleOrderAdapter(mapper);
        var fact=new com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction("1001","1000","org.chengyin.app","Sandbox","paid.edition",
                UUID.fromString(first.get("appAccountToken").toString()),"Non-Consumable",990L,"USD",100L,200L,null);
        var refunded=new com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction("1001","1000",fact.bundleId(),fact.environment(),fact.productId(),
                fact.appAccountToken(),fact.type(),fact.priceMilliunits(),fact.currency(),fact.purchaseDate(),400L,300L);
        // Officially verified refund first binds the still unpaid order. A later purchase never restores it.
        tx.execute(s->{adapter.revoke(adapter.requireOrder(7L,no),refunded);return null;});
        tx.execute(s->{adapter.activate(adapter.requireOrder(7L,no),fact);return null;});
        assertEquals("REVOKED",service.license(7,no).get("status"));
        assertNull(jdbc.queryForMap("select * from workshop_order where order_no=?",no).get("payment_fact_id"));
        assertThrows(ServiceException.class,()->tx.execute(s->adapter.requireOrder(8L,no)));

        var second=service.purchase(7,new WorkshopService.Purchase("buy2",version,purchase.contentHash(),purchase.termsHash(),"APPLE_IAP",purchase.reviewHash()));
        String no2=second.get("orderNo").toString();
        var fact2=new com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction("2001","2000",fact.bundleId(),fact.environment(),fact.productId(),
                UUID.fromString(second.get("appAccountToken").toString()),fact.type(),990L,"USD",100L,200L,null);
        var refund2=new com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction("2001","2000",fact2.bundleId(),fact2.environment(),fact2.productId(),
                fact2.appAccountToken(),fact2.type(),990L,"USD",100L,400L,300L);
        CountDownLatch activated=new CountDownLatch(1),commit=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var activation=pool.submit(()->tx.execute(s->{adapter.activate(adapter.requireOrder(7L,no2),fact2);activated.countDown();
                try{assertTrue(commit.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;}));
            assertTrue(activated.await(5,TimeUnit.SECONDS));
            var refund=pool.submit(()->tx.execute(s->{adapter.revoke(adapter.requireOrder(7L,no2),refund2);return null;}));
            assertThrows(TimeoutException.class,()->refund.get(200,TimeUnit.MILLISECONDS));
            commit.countDown();activation.get(5,TimeUnit.SECONDS);refund.get(5,TimeUnit.SECONDS);
        } finally {commit.countDown();pool.shutdownNow();}
        tx.execute(s->{adapter.activate(adapter.requireOrder(7L,no2),fact2);return null;});
        assertEquals("REVOKED",service.license(7,no2).get("status"));

        // A separate paid row makes lineage/copy-limit/rollback checks independent of refund fixtures.
        var third=service.purchase(7,new WorkshopService.Purchase("buy3",version,purchase.contentHash(),purchase.termsHash(),"APPLE_IAP",purchase.reviewHash()));
        String no3=third.get("orderNo").toString();
        var fact3=new com.chengyinhub.business.appleiap.WorkshopAppleOrderPort.VerifiedTransaction("3001","3000",fact.bundleId(),fact.environment(),fact.productId(),
                UUID.fromString(third.get("appAccountToken").toString()),fact.type(),990L,"USD",100L,200L,null);
        tx.execute(s->{adapter.activate(adapter.requireOrder(7L,no3),fact3);return null;});
        jdbc.update("insert into cms_member_template(id,member_id,title,status,draft_status,publish_status,del_flag) values(21,7,'installed',0,0,0,0),(22,7,'forced copy',0,0,0,0)");
        jdbc.update("insert into workshop_install(buyer_id,request_id,order_no,command_hash,command_json,draft_id) values(7,'install',?,?,'{}',21)",no3,"a".repeat(64));
        var guard=new com.chengyinhub.business.service.support.WorkshopLicenseGuard(mapper);
        assertLicenseLockTargetsOnlyTheOrderRow(tx,mapper,ds,no3);
        tx.execute(s->{guard.inheritCopy(21L,22L,7L);return null;});
        assertEquals(2,mapper.installedCount(no3));
        assertThrows(ServiceException.class,()->tx.execute(s->{guard.inheritCopy(21L,23L,7L);return null;}));
        assertThrows(ServiceException.class,()->tx.execute(s->{guard.requireCopy(22L,8L);return null;}));
        // Removing a SKU cannot delay a verified refund of an already bound original purchase.
        jdbc.update("update workshop_apple_product set enabled=0 where version_id=?",version);
        tx.execute(s->{adapter.revokeBoundOriginal("org.chengyin.app","Sandbox","3000",300L);return null;});
        assertEquals("REVOKED",service.license(7,no3).get("status"));
        tx.execute(s->{adapter.revokeBoundOriginal("org.chengyin.app","Sandbox","3000",300L);return null;});
        service.revoke(7,no3);
        assertThrows(ServiceException.class,()->tx.execute(s->{guard.requirePublication(22L,false);return null;}));
        assertEquals(1,jdbc.queryForObject("select count(*) from workshop_draft_lineage where draft_id=22",Integer.class));
    }

    /** Same wall the app datasource runs (application-druid.yml: wall.enabled, multi-statement-allow=false). */
    static com.alibaba.druid.pool.DruidDataSource appWalledDataSource(String url) throws Exception {
        var config=new com.alibaba.druid.wall.WallConfig(com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        var wall=new com.alibaba.druid.wall.WallFilter();wall.setDbType("mysql");wall.setConfig(config);
        var walled=new com.alibaba.druid.pool.DruidDataSource();
        walled.setUrl(url);walled.setUsername(MybatisItDatabase.user());walled.setPassword(MybatisItDatabase.password());
        walled.setProxyFilters(List.of(wall));walled.init();
        return walled;
    }

    /**
     * The license lock goes through the wall and keeps the old alias-scoped semantics: inside the transaction the
     * license order row is locked; the draft and installation rows it reads are not.
     */
    private static void assertLicenseLockTargetsOnlyTheOrderRow(org.springframework.transaction.support.TransactionTemplate tx,
            WorkshopMapper mapper,javax.sql.DataSource raw,String orderNo) throws Exception {
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var holder=pool.submit(()->tx.execute(s->{
                var row=mapper.lockLicenseForDraft(21L);
                assertEquals(orderNo,row.get("order_no"));assertEquals(7L,((Number)row.get("installation_owner")).longValue());
                assertEquals(7L,((Number)row.get("draft_owner")).longValue());assertEquals("{}",row.get("installation_command"));
                locked.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                return null;}));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            try(var other=raw.getConnection();var st=other.createStatement()){
                other.setAutoCommit(false);
                assertThrows(java.sql.SQLException.class,()->st.executeQuery("select id from workshop_order where order_no='"+orderNo+"' for update nowait"),
                        "license order row must be locked");
                st.executeQuery("select id from cms_member_template where id=21 for update nowait").close();
                st.executeQuery("select id from workshop_install where draft_id=21 for update nowait").close();
                other.rollback();
            }
            release.countDown();holder.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
        assertNull(tx.execute(s->mapper.lockLicenseForDraft(80L)),"a draft without a license has no locked row");
    }

@org.junit.jupiter.api.AfterAll
    static void dropScratchDatabases() throws Exception {
        for (String suite : java.util.List.of("workshop_purchase", "workshop_paid_apple")) {
            String database = MybatisItDatabase.databaseName(suite);
            assertTrue(database.matches("chengyin_mybatis_[a-z0-9_]+"));
            String url = "jdbc:mysql://" + MybatisItDatabase.host() + ":" + MybatisItDatabase.port()
                    + "/?useSSL=false&allowPublicKeyRetrieval=true";
            try (var connection = java.sql.DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
                 var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
            try (var connection = java.sql.DriverManager.getConnection(url, MybatisItDatabase.user(), MybatisItDatabase.password());
                 var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                query.setString(1, database);
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "Only the owned scratch schema must be removed");
                }
            }
        }
    }

private void fixtureNodeReferences(JdbcTemplate jdbc) {
        jdbc.execute("create table cms_topic_node(id bigint primary key,topic_id bigint,template_id bigint,merchant_member_id bigint)");
    }

    private void fixtureTable(JdbcTemplate jdbc,String mapper,String resultMap,String table) throws Exception {
        String xml=new ClassPathResource("mapper/business/"+mapper+".xml").getContentAsString(StandardCharsets.UTF_8);
        var section=Pattern.compile("(?s)<resultMap[^>]*id=\""+resultMap+"\"[^>]*>(.*?)</resultMap>").matcher(xml);assertTrue(section.find());
        var columns=Pattern.compile("column=\"([^\"]+)\"").matcher(section.group(1));Set<String> ddl=new LinkedHashSet<>();
        while(columns.find()){
            String column=columns.group(1);String type=column.equals("id")?" bigint primary key":
                    column.equals("update_time")||column.equals("create_time")?" datetime(3) null":" longtext null";
            ddl.add(column+type);
        }
        jdbc.execute("create table "+table+"("+String.join(",",ddl)+") engine=InnoDB");
    }
}
