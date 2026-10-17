package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ExploreChapterRedemptionResult;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.PerkLedgerServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真 MySQL/真实 Mapper；同一 SqlSession 显式提交事务，外部奖励/应付服务为计次桩，非财务入账验收。 */
class ExploreScanReplayRealDbIT {
    private static final ExploreItDatabase DATABASE = new ExploreItDatabase("explore_scan_replay_it");
    private static final long REG=9904001L, TOPIC=9904002L, ACT=9904003L, CH1=9904011L, CH2=9904012L, MERCHANT=9904021L;
    @AfterAll static void cleanup() throws Exception { DATABASE.drop(); }
    private SqlSessionFactory factory() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopicChapter", CmsTopicChapter.class);
        cfg.getTypeAliasRegistry().registerAlias("MerchantChapterOffer", MerchantChapterOffer.class);
        cfg.getTypeAliasRegistry().registerAlias("RegistrationEntitlement", RegistrationEntitlement.class);
        cfg.getTypeAliasRegistry().registerAlias("Redemption", Redemption.class);
        cfg.setEnvironment(new Environment("replay-it", new JdbcTransactionFactory(), DATABASE.freshDataSource()));
        for(String name : new String[]{"CmsTopic", "CmsActivity", "CmsRegistration", "CmsTopicChapter", "MerchantChapterOffer", "RegistrationEntitlement", "Redemption"}) {
            String resource="mapper/business/"+name+"Mapper.xml";
            try(InputStream in=Resources.getResourceAsStream(resource)) { new XMLMapperBuilder(in,cfg,resource,cfg.getSqlFragments()).parse(); }
        }
        SqlSessionFactory factory=new SqlSessionFactoryBuilder().build(cfg);
        try(SqlSession s=factory.openSession(true);Statement st=s.getConnection().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS activity_cancel_manual_refund_case(id BIGINT PRIMARY KEY AUTO_INCREMENT,activity_id BIGINT,topic_id BIGINT,parent_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,payer_member_id BIGINT,payer_registration_id BIGINT,case_type VARCHAR(32),status VARCHAR(48),verified_registration_ids_json JSON,unverified_registration_ids_json JSON) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            st.execute("INSERT INTO cms_topic(id,name,product_type) VALUES("+TOPIC+",'replay IT',2)");
            st.execute("INSERT INTO cms_activity(id,member_id,name,topic_id,del_flag) VALUES("+ACT+",9904031,'replay IT',"+TOPIC+",0)");
            st.execute("INSERT INTO cms_registration(id,registration_no,member_id,owner_type,owner_id,ticket_id,ticket_price,original_amount,payable_amount,total_amount,pay_expire_time,payment_status,registration_status,verification_status) VALUES("+REG+",'REPLAY-IT',9904032,2,"+ACT+",9904033,39,39,39,39,NOW()+INTERVAL 1 DAY,2,2,0)");
            for(long chapter:new long[]{CH1,CH2}) {
                st.execute("INSERT INTO cms_topic_chapter(id,topic_id,name,sort_id,required,terms_mode,recruit_enabled,max_merchant,category_id) VALUES("+chapter+","+TOPIC+",'IT chapter',1,1,'PERK',1,1,18)");
                st.execute("INSERT INTO merchant_chapter_offer(topic_id,chapter_id,merchant_id,perk_id,terms_mode,quota_total,quota_used,status) VALUES("+TOPIC+","+chapter+","+MERCHANT+",9904034,'PERK',10,0,1)");
                st.execute("INSERT INTO registration_entitlement(registration_id,chapter_id,topic_id,status) VALUES("+REG+","+chapter+","+TOPIC+",0)");
            }
        }
        return factory;
    }
    private static final class Services {
        final CmsRegistrationServiceImpl registrations=new CmsRegistrationServiceImpl();
        final PerkLedgerServiceImpl perks=new PerkLedgerServiceImpl();
        final IMerchantPayableService payable=mock(IMerchantPayableService.class);
        final CmsRegistrationScanMapper scans=mock(CmsRegistrationScanMapper.class);
        final BusinessAuditEventMapper audit=mock(BusinessAuditEventMapper.class);
        final IGrowthService growth=mock(IGrowthService.class);
        Services(SqlSession s) {
            ReflectionTestUtils.setField(perks,"topicMapper",s.getMapper(CmsTopicMapper.class));
            ReflectionTestUtils.setField(perks,"chapterMapper",s.getMapper(CmsTopicChapterMapper.class));
            ReflectionTestUtils.setField(perks,"offerMapper",s.getMapper(MerchantChapterOfferMapper.class));
            ReflectionTestUtils.setField(perks,"entitlementMapper",s.getMapper(RegistrationEntitlementMapper.class));
            ReflectionTestUtils.setField(perks,"redemptionMapper",s.getMapper(RedemptionMapper.class));
            ReflectionTestUtils.setField(perks,"cmsRegistrationMapper",s.getMapper(CmsRegistrationMapper.class));
            ReflectionTestUtils.setField(perks,"cmsActivityMapper",s.getMapper(CmsActivityMapper.class));
            ReflectionTestUtils.setField(perks,"merchantPayableService",payable);
            ReflectionTestUtils.setField(perks,"officialEventV2Service",mock(IOfficialEventV2Service.class));
            ReflectionTestUtils.setField(perks,"businessAuditEventMapper",audit);
            ReflectionTestUtils.setField(registrations,"cmsRegistrationMapper",s.getMapper(CmsRegistrationMapper.class));
            ReflectionTestUtils.setField(registrations,"cmsActivityMapper",s.getMapper(CmsActivityMapper.class));
            ReflectionTestUtils.setField(registrations,"perkLedgerService",perks);
            ReflectionTestUtils.setField(registrations,"redemptionMapper",s.getMapper(RedemptionMapper.class));
            ReflectionTestUtils.setField(registrations,"cmsRegistrationScanMapper",scans);
            ReflectionTestUtils.setField(registrations,"merchantReferralBountyService",mock(MerchantReferralBountyService.class));
            ReflectionTestUtils.setField(registrations,"merchantPayableService",payable);
            ReflectionTestUtils.setField(registrations,"businessAuditEventMapper",audit);
            ReflectionTestUtils.setField(registrations,"growthService",growth);
            ReflectionTestUtils.setField(registrations,"topicCompletionAwardService",mock(ITopicCompletionAwardService.class));
            when(scans.selectCmsRegistrationScanList(any())).thenReturn(Collections.emptyList());
            when(audit.insertBusinessAuditEvent(any())).thenReturn(1);
        }
        ExploreChapterRedemptionResult redeem(SqlSession s,long merchant) {
            // 等价于 controller 在事务外取得的票定位快照；core 必须锁读重新复核，不能信此支付快照。
            CmsRegistration reg=new CmsRegistration();reg.setId(REG);reg.setMemberId(9904032L);
            reg.setOwnerType(2L);reg.setOwnerId(ACT);reg.setPaymentStatus(2);reg.setRegistrationStatus(2);
            return registrations.redeemExploreChapterAndRecord(reg,CH1,TOPIC,merchant,merchant,null);
        }
    }
    private long scalar(SqlSession s,String query) throws Exception {
        try(Statement statement=s.getConnection().createStatement();java.sql.ResultSet r=statement.executeQuery(query)) { assertTrue(r.next());return r.getLong(1); }
    }
    @Test void lostReplyReplayForeignMerchantMixedChoiceAndRefundUseActualDatabaseFacts() throws Exception {
        SqlSessionFactory f=factory();long receipt;
        try(SqlSession s=f.openSession(false)) {
            Services svc=new Services(s);ExploreChapterRedemptionResult first=svc.redeem(s,MERCHANT);receipt=first.getRedemptionId();
            assertFalse(first.isReplayed());s.commit();
            clearInvocations(svc.payable,svc.scans,svc.audit,svc.growth);
            ExploreChapterRedemptionResult replay=svc.redeem(s,MERCHANT);
            assertTrue(replay.isReplayed());assertEquals(receipt,replay.getRedemptionId());assertEquals(0,replay.getCompletionEarnedXp());
            verifyNoInteractions(svc.payable,svc.scans,svc.audit,svc.growth);s.commit();
            Map<Long,Long> choices=svc.perks.resolveScannableChapters(REG,TOPIC,MERCHANT);
            assertEquals(2,choices.size());assertEquals(receipt,choices.get(CH1));assertTrue(choices.containsKey(CH2));assertNull(choices.get(CH2));
            assertEquals(1,scalar(s,"SELECT count(*) FROM redemption WHERE registration_id="+REG+" AND status='ACTIVE'"));
            assertEquals(1,scalar(s,"SELECT quota_used FROM merchant_chapter_offer WHERE chapter_id="+CH1));
            assertEquals(0,scalar(s,"SELECT status FROM registration_entitlement WHERE registration_id="+REG+" AND chapter_id="+CH2));
            assertThrows(IllegalStateException.class,()->svc.redeem(s,MERCHANT+1));s.rollback();
        }
        try(SqlSession s=f.openSession(true);Statement st=s.getConnection().createStatement()) {st.execute("UPDATE cms_registration SET payment_status=4,registration_status=3 WHERE id="+REG);}
        try(SqlSession s=f.openSession(false)) {Services svc=new Services(s);assertThrows(IllegalStateException.class,()->svc.redeem(s,MERCHANT));verifyNoInteractions(svc.payable,svc.scans,svc.audit,svc.growth);s.rollback();}
    }
    @Test void simultaneousSameTicketScansSerializeAndReturnOneOriginalReceipt() throws Exception {
        SqlSessionFactory f=factory();ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<ExploreChapterRedemptionResult> scan=()->{start.await();try(SqlSession s=f.openSession(false)){Services svc=new Services(s);ExploreChapterRedemptionResult result=svc.redeem(s,MERCHANT);s.commit();return result;}};
            Future<ExploreChapterRedemptionResult> a=workers.submit(scan),b=workers.submit(scan);start.countDown();
            ExploreChapterRedemptionResult one=a.get(15,TimeUnit.SECONDS),two=b.get(15,TimeUnit.SECONDS);
            assertEquals(one.getRedemptionId(),two.getRedemptionId());assertNotEquals(one.isReplayed(),two.isReplayed());
            try(SqlSession s=f.openSession(true)){assertEquals(1,scalar(s,"SELECT quota_used FROM merchant_chapter_offer WHERE chapter_id="+CH1));assertEquals(1,scalar(s,"SELECT count(*) FROM redemption WHERE registration_id="+REG+" AND status='ACTIVE'"));}
        } finally {workers.shutdownNow();}
    }
    @Test void reversalAndReplayShareLockOrderAndReversedReceiptCannotReplay() throws Exception {
        SqlSessionFactory f=factory();long id;
        try(SqlSession s=f.openSession(false)){Services svc=new Services(s);id=svc.redeem(s,MERCHANT).getRedemptionId();s.commit();}
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch reverseLocked=new CountDownLatch(1),releaseReverse=new CountDownLatch(1);
        try {
            Future<Boolean> reverse=workers.submit(()->{try(SqlSession s=f.openSession(false)){Services svc=new Services(s);
                CmsRegistrationMapper real=s.getMapper(CmsRegistrationMapper.class);
                CmsRegistrationMapper observed=(CmsRegistrationMapper)java.lang.reflect.Proxy.newProxyInstance(
                    CmsRegistrationMapper.class.getClassLoader(),new Class<?>[]{CmsRegistrationMapper.class},(proxy,method,args)->{
                        Object result;
                        try {result=method.invoke(real,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                        if(method.getName().equals("selectCmsRegistrationByIdForUpdate")){
                            reverseLocked.countDown();if(!releaseReverse.await(5,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");
                        }
                        return result;
                    });
                ReflectionTestUtils.setField(svc.perks,"cmsRegistrationMapper",observed);
                boolean result=svc.perks.reverseRedemption(id,"IT reversal",88L);s.commit();return result;}});
            assertTrue(reverseLocked.await(5,TimeUnit.SECONDS));
            Future<ExploreChapterRedemptionResult> retry=workers.submit(()->{try(SqlSession s=f.openSession(false)){Services svc=new Services(s);ExploreChapterRedemptionResult result=svc.redeem(s,MERCHANT);s.commit();return result;}});
            releaseReverse.countDown();assertTrue(reverse.get(15,TimeUnit.SECONDS));ExploreChapterRedemptionResult after=retry.get(15,TimeUnit.SECONDS);
            assertFalse(after.isReplayed());assertNotEquals(id,after.getRedemptionId());
            try(SqlSession s=f.openSession(true)){assertEquals(1,scalar(s,"SELECT count(*) FROM redemption WHERE registration_id="+REG+" AND status='REVERSED'"));assertEquals(1,scalar(s,"SELECT quota_used FROM merchant_chapter_offer WHERE chapter_id="+CH1));}
        } finally {releaseReverse.countDown();workers.shutdownNow();}
    }
    @Test void parentOrderBatchUsesRealChapterMutationsAndRollsBackEverythingOnSecondAuditFailure() throws Exception {
        SqlSessionFactory f=factory();
        try(SqlSession s=f.openSession(true);Statement st=s.getConnection().createStatement()) {
            st.execute("UPDATE cms_registration SET parent_order_no='REPLAY-PARENT' WHERE id="+REG);
        }
        try(SqlSession s=f.openSession(false)) {
            Services svc=new Services(s);svc.redeem(s,MERCHANT);s.commit();
            CmsRegistration reg=new CmsRegistration();reg.setId(REG);reg.setMemberId(9904032L);reg.setOwnerType(2L);reg.setOwnerId(ACT);
            svc.registrations.redeemExploreChapterAndRecord(reg,CH2,TOPIC,MERCHANT,MERCHANT,null);s.commit();
        }
        try(SqlSession s=f.openSession(false)) {
            Services svc=new Services(s);when(svc.audit.insertBusinessAuditEvent(any())).thenReturn(1,0);
            assertThrows(RuntimeException.class,()->svc.perks.reverseParentOrderRedemptions("REPLAY-PARENT","IT reversal",88L,false));s.rollback();
        }
        try(SqlSession s=f.openSession(true)) {
            assertEquals(2,scalar(s,"SELECT count(*) FROM redemption WHERE registration_id="+REG+" AND status='ACTIVE'"));
            assertEquals(2,scalar(s,"SELECT count(*) FROM registration_entitlement WHERE registration_id="+REG+" AND status=1"));
            assertEquals(2,scalar(s,"SELECT sum(quota_used) FROM merchant_chapter_offer WHERE topic_id="+TOPIC));
            assertEquals(1,scalar(s,"SELECT verification_status FROM cms_registration WHERE id="+REG));
        }
        try(SqlSession s=f.openSession(false)) {
            Services svc=new Services(s);assertEquals(2,svc.perks.reverseParentOrderRedemptions("REPLAY-PARENT","IT reversal",88L,false).size());s.commit();
        }
        try(SqlSession s=f.openSession(true)) {
            assertEquals(2,scalar(s,"SELECT count(*) FROM redemption WHERE registration_id="+REG+" AND status='REVERSED'"));
            assertEquals(2,scalar(s,"SELECT count(*) FROM registration_entitlement WHERE registration_id="+REG+" AND status=0"));
            assertEquals(0,scalar(s,"SELECT sum(quota_used) FROM merchant_chapter_offer WHERE topic_id="+TOPIC));
            assertEquals(0,scalar(s,"SELECT verification_status FROM cms_registration WHERE id="+REG));
        }
        try(SqlSession s=f.openSession(false)) {
            Services svc=new Services(s);assertThrows(IllegalStateException.class,()->svc.perks.reverseParentOrderRedemptions("REPLAY-PARENT","IT repeated",88L,false));verifyNoInteractions(svc.payable,svc.audit);s.rollback();
        }
    }
}
