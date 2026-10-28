package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real application mapper/CAS, service and Spring transactions + existing real IM ledger/consumer.
 * Parent locks use JDBC on canonical topic/chapter rows; qualification, node work and other notices are doubles.
 * No financial, full qualification, HTTP, process-kill or real WX acceptance is claimed. */
class ChapterApplicationRoundMySqlIT {
    @Test void firstInvitationPersistsRoundOneAndPendingReplayDoesNotAllocateAnotherAttempt() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.invite();
            assertEquals(1L,first.getApplicationRound()); assertEquals(1L,f.round(first.getId()));
            assertEquals(1,f.storage.count("im_notification_intent"));
            assertEquals(f.key(first.getId(),1),f.storage.db.queryForObject("select event_key from im_notification_intent",String.class));
            assertThrows(IllegalStateException.class,f::invite);
            assertEquals(1L,f.round(first.getId())); assertEquals(1,f.storage.count("merchant_chapter_application"));
            assertEquals(1,f.storage.count("committed_official_notice_wx")); verifyNoInteractions(f.storage.wx,f.otherNotices);
            f.storage.wakes.clear(); f.storage.recover(); f.storage.recover(); f.assertMessages(1);
            verify(f.storage.wx).send(any());
        }
    }

    @Test void withdrawAndReinviteReuseTheOriginalIdButNeverTheOriginalUnknownNotice() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.invite(); Long original=f.noticeId(first.getId(),1);
            when(f.storage.wx.send(any())).thenReturn(false); f.storage.notice.deliver(original);
            assertEquals("UNKNOWN",f.storage.state(original));
            String owner=f.storage.db.queryForObject("select owner_token from committed_official_notice_wx where id=?",String.class,original);
            f.service.revokeInvite(first.getId(),1001L);
            MerchantChapterApplication second=f.invite();
            assertEquals(first.getId(),second.getId()); assertEquals(2L,second.getApplicationRound());
            assertEquals(2,f.storage.count("im_notification_intent")); assertEquals(2L,f.round(first.getId()));
            when(f.storage.wx.send(any())).thenReturn(true); f.storage.recover(); f.storage.notice.deliver(original);
            f.assertMessages(2); assertEquals("UNKNOWN",f.storage.state(original));
            assertEquals(owner,f.storage.db.queryForObject("select owner_token from committed_official_notice_wx where id=?",String.class,original));
            verify(f.storage.wx,times(2)).send(any());
        }
    }

    @Test void rejectedApplicationReapplyAdvancesOnlyTheSuccessfulOriginalCas() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.service.apply(73L,2001L,"original application");
            assertEquals(1L,first.getApplicationRound());
            f.service.audit(first.getId(),1001L,false,"not suitable");
            MerchantChapterApplication second=f.service.apply(73L,2001L,"new application");
            assertEquals(first.getId(),second.getId()); assertEquals(2L,second.getApplicationRound());
            assertThrows(IllegalStateException.class,() -> f.service.apply(73L,2001L,"pending repeat"));
            assertEquals(2L,f.round(first.getId()));
            int lost=f.storage.tx.execute(tx -> f.mapper.reapply(first.getId(),"CAS lost","2001"));
            assertEquals(0,lost); assertEquals(2L,f.round(first.getId()));
            assertEquals(0,f.storage.count("im_notification_intent")); verifyNoInteractions(f.storage.wx);
        }
    }

    @Test void staleRejectedReadWithZeroCasCannotReturnAFabricatedPendingRound() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.service.apply(73L,2001L,"original");
            MerchantChapterApplication stale=f.mapper.selectMerchantChapterApplicationById(first.getId());
            stale.setStatus(MerchantChapterApplication.STATUS_REJECTED);
            MerchantChapterApplicationMapper staleRead=mock(MerchantChapterApplicationMapper.class,
                    org.mockito.AdditionalAnswers.delegatesTo(f.mapper));
            when(staleRead.selectAnyByChapterAndMerchant(73L,2001L)).thenReturn(stale);
            ReflectionTestUtils.setField(f.target,"applicationMapper",staleRead);
            assertThrows(IllegalStateException.class,() -> f.service.apply(73L,2001L,"cannot claim"));
            assertEquals(1L,f.round(first.getId()));
            verify(staleRead).reapply(first.getId(),"cannot claim","2001");
            verify(staleRead,never()).selectApplicationRoundForUpdate(anyLong());
            assertEquals(0,f.storage.count("im_notification_intent"));
        }
    }

    @Test void withdrawnApplicationRestoreStartsOneNewRoundAndPendingRetryStartsNone() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.service.apply(73L,2001L,"first");
            f.service.withdraw(first.getId(),2001L);
            MerchantChapterApplication restored=f.service.apply(73L,2001L,"restored");
            assertEquals(first.getId(),restored.getId()); assertEquals(2L,restored.getApplicationRound());
            assertThrows(IllegalStateException.class,() -> f.service.apply(73L,2001L,"repeat"));
            assertEquals(Integer.valueOf(0),f.storage.tx.execute(tx -> f.mapper.restoreWithdrawn(first.getId(),"lost","2001")));
            assertEquals(2L,f.round(first.getId())); assertEquals(0,f.storage.count("im_notification_intent"));
        }
    }

    @Test void migrationLeavesLegacyPendingUnverifiedAndOnlyANewLegalReinviteStartsRoundOne() throws Exception {
        try (var f=new Fixture(true)) {
            assertNull(f.round(91L)); assertEquals(0,f.storage.count("im_notification_intent"));
            f.storage.recover(); assertThrows(IllegalStateException.class,f::invite);
            assertNull(f.round(91L)); verifyNoInteractions(f.storage.wx);
            f.service.revokeInvite(91L,1001L); MerchantChapterApplication fresh=f.invite();
            assertEquals(91L,fresh.getId()); assertEquals(1L,fresh.getApplicationRound());
            assertEquals(1,f.storage.count("im_notification_intent"));
            assertEquals(f.key(91L,1),f.storage.db.queryForObject("select event_key from im_notification_intent",String.class));
        }
    }

    @Test void originalTransactionRollbackRemovesFreshApplicationRoundAndBothIntents() throws Exception {
        try (var f=new Fixture()) {
            assertThrows(IllegalStateException.class,() -> f.storage.tx.executeWithoutResult(tx -> {
                f.invite(); throw new IllegalStateException("fixture later business failure");
            }));
            assertEquals(0,f.storage.count("merchant_chapter_application"));
            assertEquals(0,f.storage.count("im_notification_intent")); assertEquals(0,f.storage.count("committed_official_notice_wx"));
            assertTrue(f.storage.wakes.isEmpty()); verifyNoInteractions(f.storage.wx);
        }
    }

    @Test void reinviteRollbackRetainsTheOldRoundAndRetryCommitsTheSameNextIdentity() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.invite(); f.service.revokeInvite(first.getId(),1001L);
            f.storage.wakes.clear();
            assertThrows(IllegalStateException.class,() -> f.storage.tx.executeWithoutResult(tx -> {
                assertEquals(2L,f.invite().getApplicationRound()); throw new IllegalStateException("fixture later failure");
            }));
            assertEquals(1L,f.round(first.getId()));
            assertEquals(2,f.storage.db.queryForObject("select del_flag from merchant_chapter_application",Integer.class));
            assertEquals(1,f.storage.count("im_notification_intent")); assertTrue(f.storage.wakes.isEmpty());
            assertEquals(2L,f.invite().getApplicationRound()); assertEquals(2,f.storage.count("im_notification_intent"));
        }
    }

    @Test void competingReinvitesHaveOneBusinessWinnerAndOneNextRoundIntent() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication first=f.invite(); f.service.revokeInvite(first.getId(),1001L);
            ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
            try {
                Callable<Boolean> call=() -> { assertTrue(start.await(10,TimeUnit.SECONDS));
                    try { f.invite(); return true; } catch (IllegalStateException rejected) { return false; } };
                Future<Boolean> a=pool.submit(call),b=pool.submit(call); start.countDown();
                assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
                assertEquals(2L,f.round(first.getId())); assertEquals(1,f.storage.count("merchant_chapter_application"));
                assertEquals(2,f.storage.count("im_notification_intent")); f.storage.recover(); f.assertMessages(2);
            } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
        }
    }

    @Test void firstInsertIgnoresClientRoundAndConflictingPayloadCannotReplaceTheFrozenInvite() throws Exception {
        try (var f=new Fixture()) {
            MerchantChapterApplication input=new ObjectMapper().readValue("{\"applicationRound\":999}",MerchantChapterApplication.class);
            assertNull(input.getApplicationRound());
            MerchantChapterApplication first=f.invite(); String key=f.key(first.getId(),1);
            assertEquals(1L,f.round(first.getId()));
            assertThrows(RuntimeException.class,() -> f.storage.enqueue(key,2002L,"changed","changed"));
            assertEquals(2001L,f.storage.db.queryForObject("select recipient from committed_official_notice_wx",Long.class));
            f.storage.recover(); f.assertMessages(1);
            assertEquals("章节承接邀请\n请在主题承接进度中接受或拒绝邀请",f.storage.db.queryForObject("select content from im_message",String.class));
            String longest="chapter-app:"+Long.MAX_VALUE+":"+Long.MAX_VALUE+":"+Long.MAX_VALUE+":invite";
            assertTrue(longest.length()<=96);
        }
    }

    static final class Fixture implements AutoCloseable {
        final CommittedOfficialNoticeMySqlFixture storage=new CommittedOfficialNoticeMySqlFixture();
        final MerchantChapterApplicationServiceImpl target=new MerchantChapterApplicationServiceImpl();
        final CommittedBusinessNotice otherNotices=mock(CommittedBusinessNotice.class);
        MerchantChapterApplicationMapper mapper;
        IMerchantChapterApplicationService service;
        Fixture() throws Exception { this(false); }
        Fixture(boolean legacy) throws Exception {
            try {
                storage.open();
                String snapshot=Files.readString(storage.sqlRoot.resolve("../../sql/base_schema_20260818.sql").normalize());
                for (String table:List.of("merchant_chapter_application","merchant_chapter_offer","cms_topic","cms_topic_chapter"))
                    storage.db.execute(create(snapshot,table));
                if (legacy) storage.db.update("insert into merchant_chapter_application(id,topic_id,chapter_id,merchant_id,status,source,del_flag) values(91,41,73,2001,0,1,0)");
                new ResourceDatabasePopulator(new FileSystemResource(storage.sqlRoot.resolve("migration_20261003_merchant_offer_withdrawal_columns.sql")),
                        new FileSystemResource(storage.sqlRoot.resolve("migration_20261024_d_chapter_application_round.sql")))
                        .execute(storage.manager.getDataSource());
                storage.db.update("insert into cms_topic(id,member_id,name) values(41,1001,'topic')");
                storage.db.update("insert into cms_topic_chapter(id,topic_id,name) values(73,41,'chapter')");
                SqlSessionFactoryBean factory=new SqlSessionFactoryBean(); factory.setDataSource(storage.manager.getDataSource());
                factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
                factory.setMapperLocations(new ClassPathResource("mapper/business/MerchantChapterApplicationMapper.xml"));
                mapper=new SqlSessionTemplate(factory.getObject()).getMapper(MerchantChapterApplicationMapper.class);
                CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class); CmsTopicMapper topics=mock(CmsTopicMapper.class);
                TopicSupplyReviewService supply=mock(TopicSupplyReviewService.class);
                doAnswer(call -> { storage.db.queryForList("select id from cms_topic where id=41 for update"); return null; })
                        .when(supply).lockBeforeChapter(73L);
                when(chapters.lockChaptersForCapacity(List.of(73L))).thenAnswer(call ->
                        storage.db.queryForList("select id from cms_topic_chapter where id=73 for update",Long.class));
                CmsTopicChapter chapter=new CmsTopicChapter(); chapter.setId(73L); chapter.setTopicId(41L);
                chapter.setRecruitEnabled(1); chapter.setMaxMerchant(0); chapter.setCategoryId(18);
                when(chapters.selectCmsTopicChapterById(73L)).thenReturn(chapter);
                when(chapters.selectContentByIdForUpdate(73L)).thenAnswer(call ->
                        storage.db.queryForList("select id from cms_topic_chapter where id=73 for update").isEmpty() ? null : chapter);
                CmsTopic topic=new CmsTopic(); topic.setId(41L); topic.setMemberId(1001L); topic.setProductType(ProductType.FREE_EXPLORE);
                topic.setStatus(1); topic.setUserStatus(1); topic.setLifecycle(1); topic.setRecruitDeadline(new Date(Long.MAX_VALUE));
                when(topics.selectCmsTopicByIdForUpdate(41L)).thenAnswer(call -> {
                    storage.db.queryForList("select id from cms_topic where id=41 for update"); return topic; });
                when(topics.selectCmsTopicById(41L)).thenReturn(topic);
                MmsMerchantMapper merchants=mock(MmsMerchantMapper.class); MmsMerchant merchant=new MmsMerchant();
                merchant.setMemberId(2001L); merchant.setCategoryId(18); merchant.setStatus(1L); merchant.setAccountStatus(1L); merchant.setDelFlag(0);
                when(merchants.selectMmsMerchantByMemberId(2001L)).thenReturn(merchant);
                ISysCategoryService categories=mock(ISysCategoryService.class); SysCategory category=new SysCategory();
                category.setId(18L); category.setType(1L); category.setParentId(0L); category.setStatus(1); category.setDelFlag(0);
                when(categories.selectSysCategoryById(18L)).thenReturn(category);
                ReflectionTestUtils.setField(target,"supplyReviewService",supply);
                ReflectionTestUtils.setField(target,"applicationMapper",mapper); ReflectionTestUtils.setField(target,"chapterMapper",chapters);
                ReflectionTestUtils.setField(target,"topicMapper",topics); ReflectionTestUtils.setField(target,"merchantMapper",merchants);
                ReflectionTestUtils.setField(target,"offerMapper",mock(MerchantChapterOfferMapper.class));
                ReflectionTestUtils.setField(target,"sysCategoryService",categories);
                ReflectionTestUtils.setField(target,"chapterMerchantNodeService",mock(IChapterMerchantNodeService.class));
                ReflectionTestUtils.setField(target,"auditTaskMapper",mock(AuditTaskMapper.class));
                // Preserve the original outcome-notification double for these invitation/round cases only.
                // The outcome suite replaces this spy with the real audit/delivery owner.
                ChapterApplicationInviteNotice invite=spy(new ChapterApplicationInviteNotice(storage.notice));
                doAnswer(call -> {
                    ChapterApplicationInviteNotice.Outcome event=call.getArgument(3);
                    String eventKey=switch (event) {
                        case WITHDRAW -> "chapter-application-withdraw";
                        case REVIEW -> "chapter-application-reviewed";
                        case INVITE_DECISION -> "chapter-invite-decide";
                        case INVITE_REVOKE -> "chapter-invite-revoke";
                    };
                    otherNotices.sendAfterCommit(call.getArgument(4),call.getArgument(5),call.getArgument(6),eventKey,call.getArgument(1));
                    return null;
                }).when(invite).submitOutcome(any(),any(),any(),any(),any(),any(),any());
                ReflectionTestUtils.setField(target,"inviteNotice",invite);
                ProxyFactory proxy=new ProxyFactory(target); proxy.setInterfaces(IMerchantChapterApplicationService.class);
                proxy.addAdvice(new TransactionInterceptor(storage.manager,new AnnotationTransactionAttributeSource()));
                service=(IMerchantChapterApplicationService)proxy.getProxy();
            } catch (Exception | Error failure) { close(); throw failure; }
        }
        MerchantChapterApplication invite() { return service.invite(73L,1001L,2001L,"invitation"); }
        Long round(Long id) { return storage.db.queryForObject("select application_round from merchant_chapter_application where id=?",Long.class,id); }
        String key(Long id,long round) { return "chapter-app:41:"+id+":"+round+":invite"; }
        Long noticeId(Long id,long round) { return storage.db.queryForObject("select id from committed_official_notice_wx where event_key=?",Long.class,key(id,round)); }
        void assertMessages(int count) {
            assertEquals(count,storage.count("im_message"));
            assertEquals(count,storage.db.queryForObject("select unread_count from im_conversation_member where member_id=2001",Integer.class));
            assertEquals(count,storage.db.queryForObject("select count(*) from im_notification_intent where status='DELIVERED'",Integer.class));
        }
        private static String create(String snapshot,String table) {
            int start=snapshot.indexOf("CREATE TABLE `"+table+"` ("); assertTrue(start>=0);
            int tail=snapshot.indexOf("\n) ENGINE=",start); assertTrue(tail>start);
            return snapshot.substring(start,snapshot.indexOf(";\n",tail));
        }
        @Override public void close() { storage.close(); }
    }
}
