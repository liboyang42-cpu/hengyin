package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real production progress SQL and scope enumeration; no MySQL transaction/locking claim. */
class ThemeFirstReadConsumersMyBatisTest {
    private static final String FIRST="theme-first:10:20";
    private static String source(String file) throws Exception {
        try(InputStream in=ThemeFirstReadConsumersMyBatisTest.class.getClassLoader().getResourceAsStream("mapper/business/"+file)) {
            assertNotNull(in);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static String element(String xml,String tag,String id) {
        int start=xml.indexOf("<"+tag+" id=\""+id+"\"");
        if(start<0)start=xml.indexOf("<"+tag+" type=\"PlayerNodeProgress\" id=\""+id+"\"");
        assertTrue(start>=0,id);return xml.substring(start,xml.indexOf("</"+tag+">",start)+tag.length()+3);
    }
    private static void mapper(Configuration c,String namespace,String body) {
        String xml="<?xml version=\"1.0\"?><!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\"><mapper namespace=\""+namespace+"\">"+body+"</mapper>";
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),c,namespace,c.getSqlFragments()).parse();
    }
    private static final class Fixture implements AutoCloseable {
        final SqlSession db;final Statement sql;final PlayerNodeProgressMapper progress;
        final CmsRegistrationMapper registrations;final TopicRouteSessionMapper routes=mock(TopicRouteSessionMapper.class);
        final ITopicRouteRuntimeService runtime=mock(ITopicRouteRuntimeService.class);
        final CmsTopicMapper topics=mock(CmsTopicMapper.class);final CmsTopicChapterMapper chapters=mock(CmsTopicChapterMapper.class);
        final CmsTopicNodeMapper nodes=mock(CmsTopicNodeMapper.class);final CmsActivityMapper activities=mock(CmsActivityMapper.class);
        final TopicRouteSession first=new TopicRouteSession();final PureContentExploreService pure=new PureContentExploreService();
        Fixture() throws Exception {
            Configuration c=new Configuration();c.getTypeAliasRegistry().registerAlias("PlayerNodeProgress",PlayerNodeProgress.class);
            c.setEnvironment(new Environment("h2",new JdbcTransactionFactory(),new PooledDataSource("org.h2.Driver","jdbc:h2:mem:first_reads_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","")));
            String p=source("PlayerNodeProgressMapper.xml");mapper(c,PlayerNodeProgressMapper.class.getName(),element(p,"resultMap","PlayerNodeProgressResult")+element(p,"sql","selectPlayerNodeProgressVo")+element(p,"select","selectPlayerNodeProgressList")+element(p,"select","selectPlayerNodeProgressListForInstance"));
            String r=source("CmsRegistrationMapper.xml");
            String result="<resultMap id=\"CmsRegistrationResult\" type=\"com.chengyinhub.business.domain.CmsRegistration\"><id property=\"id\" column=\"id\"/><result property=\"memberId\" column=\"member_id\"/><result property=\"ownerType\" column=\"owner_type\"/><result property=\"ownerId\" column=\"owner_id\"/><result property=\"paymentStatus\" column=\"payment_status\"/><result property=\"registrationStatus\" column=\"registration_status\"/><result property=\"purchaseKind\" column=\"purchase_kind\"/></resultMap>";
            mapper(c,CmsRegistrationMapper.class.getName(),result+element(r,"select","selectPureContentExploreScopes"));
            db=new SqlSessionFactoryBuilder().build(c).openSession(true);sql=db.getConnection().createStatement();progress=db.getMapper(PlayerNodeProgressMapper.class);registrations=mock(CmsRegistrationMapper.class,org.mockito.AdditionalAnswers.delegatesTo(db.getMapper(CmsRegistrationMapper.class)));
            sql.execute("create table player_node_progress(id bigint auto_increment,play_instance_id varchar(64),member_id bigint,activity_id bigint,topic_id bigint,node_id bigint,status int,checkin_time timestamp,pic_url varchar(100),review_status int,longitude double,latitude double,puzzle_score int,completion_mode varchar(32),del_flag int,create_time timestamp)");
            sql.execute("create table cms_activity(id bigint,topic_id bigint,del_flag int)");
            sql.execute("create table cms_registration(id bigint,member_id bigint,owner_type bigint,owner_id bigint,payment_status int,registration_status int,purchase_kind int)");
            sql.execute("insert into cms_activity values(20,10,0),(21,10,0),(22,10,1),(23,11,0)");
            sql.execute("insert into cms_registration values(70,101,2,20,2,2,3),(71,102,2,20,1,2,3),(72,103,2,20,2,3,3),(73,104,2,22,2,2,3),(74,105,2,23,2,2,3)");
            CmsRegistration order=new CmsRegistration();order.setId(70L);order.setMemberId(101L);order.setOwnerType(2L);order.setOwnerId(20L);order.setPaymentStatus(2);order.setRegistrationStatus(2);order.setPurchaseKind(3);
            doReturn(order).when(registrations).selectCmsRegistrationById(70L);
            CmsTopic topic=new CmsTopic();topic.setId(10L);topic.setProductType(2);topic.setPublishedThemeRuntimeConfigJson("{}");when(topics.selectCmsTopicById(10L)).thenReturn(topic);
            CmsTopicChapter chapter=new CmsTopicChapter();chapter.setId(30L);chapter.setTopicId(10L);chapter.setRequired(1);chapter.setRecruitEnabled(0);chapter.setTermsMode("TRAFFIC");
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));when(chapters.selectRequiredByTopic(10L)).thenReturn(Collections.singletonList(chapter));
            List<CmsTopicNode> games=new ArrayList<>();for(long id=40;id<=41;id++){CmsTopicNode n=new CmsTopicNode();n.setId(id);n.setTopicId(10L);n.setChapterId(30L);n.setTemplateId(id+10);games.add(n);}when(nodes.selectCmsTopicNodeList(any())).thenReturn(games);
            CmsActivity activity=new CmsActivity();activity.setId(20L);activity.setTopicId(10L);activity.setDelFlag(0);when(activities.selectCmsActivityById(20L)).thenReturn(activity);
            first.setMemberId(101L);first.setTopicId(10L);first.setActivityId(20L);first.setConfigSnapshotJson("{\"themeConfig\":{}}");ThemeFirstIdentity.stampNew(first);first.setId(500L);
            when(routes.selectCurrentByOwner(20L,10L,101L)).thenReturn(first);when(routes.selectHistoryPage(101L,10L,null,100)).thenReturn(Collections.singletonList(first));
            when(runtime.playInstance(101L,10L,20L)).thenReturn(FIRST);when(runtime.assertCurrentPlayInstance(101L,10L,20L,FIRST)).thenReturn(true);
            Object[][] fields={{"topicMapper",topics},{"chapterMapper",chapters},{"nodeMapper",nodes},{"offerMapper",mock(MerchantChapterOfferMapper.class)},{"registrationMapper",registrations},{"activityMapper",activities},{"progressMapper",progress},{"routeSessionMapper",routes},{"routeRuntimeService",runtime}};
            for(Object[] field:fields)ReflectionTestUtils.setField(pure,(String)field[0],field[1]);
        }
        void row(String instance,long member,long activity,long topic,long node,int status,int deleted) throws Exception {
            sql.execute("insert into player_node_progress(play_instance_id,member_id,activity_id,topic_id,node_id,status,del_flag,checkin_time) values('"+instance+"',"+member+","+activity+","+topic+","+node+","+status+","+deleted+",'2026-10-09 02:00:00')");
        }
        public void close() throws Exception {sql.close();db.close();}
    }
    @Test void singleTicketAndBatchNeverCombineDefaultOrReplayWithFirst() throws Exception {
        try(Fixture f=new Fixture()) {
            f.row("default",101,20,10,40,1,0);f.row("default",101,20,10,41,1,0);
            f.row(FIRST,101,20,10,40,1,0);f.row("replay:500",101,20,10,41,1,0);
            f.row(FIRST,101,20,10,41,1,1);f.row(FIRST,999,20,10,41,1,0);f.row(FIRST,101,21,10,41,1,0);
            assertFalse(f.pure.isCompleted(70L,10L));assertEquals(0,f.pure.completionFacts(10L).get(0).get("completed"));
            f.row(FIRST,101,20,10,41,1,0);
            assertTrue(f.pure.isCompleted(70L,10L));assertEquals(Collections.singleton(30L),f.pure.completedChapters(70L,10L));assertEquals(1,f.pure.completionFacts(10L).get(0).get("completed"));
        }
    }
    @Test void realScopeSqlExcludesUnpaidCancelledDeletedAndOtherTopic() throws Exception {
        try(Fixture f=new Fixture()) {
            List<CmsRegistration> page=f.registrations.selectPureContentExploreScopes(10L,null,100);
            assertEquals(1,page.size());assertEquals(70L,page.get(0).getId());assertEquals(101L,page.get(0).getMemberId());
            assertTrue(f.registrations.selectPureContentExploreScopes(10L,70L,100).isEmpty());
        }
    }
    @Test void readDuringInstanceChangeRejectsInsteadOfReturningMixedCompletion() throws Exception {
        try(Fixture f=new Fixture()) {
            f.row(FIRST,101,20,10,40,1,0);f.row(FIRST,101,20,10,41,1,0);
            when(f.runtime.playInstance(101L,10L,20L)).thenReturn(FIRST,"replay:500");
            assertThrows(ServiceException.class,()->f.pure.completedChapters(70L,10L));
        }
    }
    @Test void unstartedConfiguredThemeHasNoFactsAndDoesNotCreateRun() throws Exception {
        try(Fixture f=new Fixture()) {
            when(f.routes.selectCurrentByOwner(20L,10L,101L)).thenReturn(null);
            f.row("default",101,20,10,40,1,0);f.row("default",101,20,10,41,1,0);
            assertTrue(f.pure.completedChapters(70L,10L).isEmpty());assertTrue(f.pure.completionFacts(10L).isEmpty());
            verify(f.routes,never()).insertSession(any());verifyNoInteractions(f.runtime);
        }
    }
    @Test void explicitFirstStartDuringPreviouslyUnstartedReadRejectsWithoutDefaultFallback() throws Exception {
        try(Fixture f=new Fixture()) {
            when(f.routes.selectCurrentByOwner(20L,10L,101L)).thenReturn(null,f.first);
            f.row("default",101,20,10,40,1,0);f.row("default",101,20,10,41,1,0);
            assertThrows(ServiceException.class,()->f.pure.completedChapters(70L,10L));verifyNoInteractions(f.runtime);
        }
    }
    @Test void bingoAddsFirstButDoesNotDoubleCountOldOpportunityOrReplay() throws Exception {
        try(Fixture f=new Fixture()) {
            f.row("default",101,20,10,40,1,0);f.row(FIRST,101,20,10,40,1,0);f.row(FIRST,101,20,10,41,1,0);f.row("replay:500",101,20,10,42,1,0);
            PlayBingoServiceImpl bingo=new PlayBingoServiceImpl();ReflectionTestUtils.setField(bingo,"progressMapper",f.progress);ReflectionTestUtils.setField(bingo,"routeSessionMapper",f.routes);
            BingoCells.Progress facts=ReflectionTestUtils.invokeMethod(bingo,"loadProgress",101L,10L);
            int mask=BingoCells.evaluate(facts);assertTrue((mask&1)!=0);assertEquals(0,mask&(1<<1));assertEquals(0,mask&(1<<3),"default+FIRST same activity/node must not fabricate a third daily completion");
            f.row(FIRST,101,20,10,42,1,0);facts=ReflectionTestUtils.invokeMethod(bingo,"loadProgress",101L,10L);assertTrue((BingoCells.evaluate(facts)&(1<<3))!=0);
        }
    }
    @Test void puzzleRevealReadsOwnFirstInsteadOfOldDefaultCompletion() throws Exception {
        try(Fixture f=new Fixture()) {
            f.row("default",101,20,10,40,1,0);
            PlayAttemptLogMapper attempts=mock(PlayAttemptLogMapper.class);when(attempts.insertPlayAttemptLog(any())).thenReturn(1);
            UmsMemberMapper members=mock(UmsMemberMapper.class);when(members.lockMemberById(101L)).thenReturn(101L);
            PuzzlePlayServiceImpl puzzle=new PuzzlePlayServiceImpl(mock(ICmsMemberTemplateService.class),attempts,f.progress,members);ReflectionTestUtils.setField(puzzle,"routeRuntimeService",f.runtime);
            puzzle.recordReveal(101L,10L,40L,20L);verify(attempts,times(1)).insertPlayAttemptLog(any());
            f.row(FIRST,101,20,10,40,1,0);assertThrows(IllegalStateException.class,()->puzzle.recordReveal(101L,10L,40L,20L));verify(attempts,times(1)).insertPlayAttemptLog(any());
        }
    }
}
