package com.chengyinhub.business.service.impl;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.mapper.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real IM/club/member mappers and statement prepare counts. No production or external actions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(90)
class ImClubConversationBatchMySqlIT
{
    private final ImConversationBatchReadMySqlIT fixture=new ImConversationBatchReadMySqlIT();
    private JdbcTemplate db;
    private ImServiceImpl service;
    private ImConversationMemberMapper members;
    private AtomicInteger statements;
    private Configuration configuration;

    @BeforeAll void setup() throws Exception
    {
        fixture.setup(); // Existing explicit loopback env, exclusive UUID schema, owned cleanup/readback.
        db=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"db");
        new ResourceDatabasePopulator(new ClassPathResource("fixtures/im-club-conversation-batch.sql"))
                .execute(db.getDataSource());
        var counter=new ImConversationBatchReadMySqlIT.StatementCounter();
        statements=(AtomicInteger)ReflectionTestUtils.getField(counter,"count");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(db.getDataSource());
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");factory.setPlugins(counter);
        factory.setMapperLocations(new ClassPathResource("mapper/business/ImConversationMemberMapper.xml"),
                new ClassPathResource("mapper/business/ImConversationMapper.xml"),
                new ClassPathResource("mapper/business/ImMessageMapper.xml"),
                new ClassPathResource("mapper/business/ClubMapper.xml"),
                new ClassPathResource("mapper/business/ClubMemberMapper.xml"));
        var session=new SqlSessionTemplate(factory.getObject());configuration=session.getSqlSessionFactory().getConfiguration();
        members=session.getMapper(ImConversationMemberMapper.class);
        service=new ImServiceImpl();ReflectionTestUtils.setField(service,"memberMapper",members);
        ReflectionTestUtils.setField(service,"clubMapper",session.getMapper(ClubMapper.class));
        ReflectionTestUtils.setField(service,"clubMemberMapper",session.getMapper(ClubMemberMapper.class));
    }

    @BeforeEach void reset()
    {
        // Only the fixture's exclusive scratch schema, never a shared database.
        for(String table:List.of("im_message","im_conversation_member","im_conversation","club_member","club"))
            db.update("delete from "+table);
        statements.set(0);
    }

    @AfterAll void cleanup() { fixture.cleanup(); }

    @Test void currentClubPredicateAndCompleteRowsMatchOriginalMapperShadow()
    {
        club(11,1,1L,0);group(201,"club_11",1);
        club(12,99,1L,0);membership(12,1,1,0);
        group(202,"club_12",1);group(203,"club_+0012",1);group(204,"club_0012",1);
        club(13,99,1L,0);membership(13,1,0,0);group(205,"club_13",1);
        club(14,99,1L,0);membership(14,1,2,0);group(206,"club_14",1); // actual rejected-application state
        club(15,99,1L,0);membership(15,1,1,1);group(207,"club_15",1);
        club(16,1,0L,0);group(208,"club_16",1);
        club(17,1,1L,1);group(209,"club_17",1);
        club(18,1,null,0);group(210,"club_18",1);
        club(19,1,1L,null);group(211,"club_19",1);
        club(20,99,1L,0);membership(20,1,1,0);
        db.update("delete from club_member where club_id=20 and member_id=1"); // original quit mapper's deletion fact
        group(212,"club_20",1);group(213,"club_bad",1);group(214,"club_9223372036854775808",1);
        group(215,"club_ 12",1);club(-12,1,1L,0);group(216,"club_-12",1);
        club(99,1,1L,0);group(217,"club_99",2); // club ownership alone does not grant an IM index.
        group(218,"club_00011",1);db.update("update im_conversation_member set deleted=1 where conversation_id=218");
        group(219,"club_+11",1);db.update("update im_conversation set status=1 where id=219");
        for(int i=0;i<100;i++)membership(12,1000+i,2,0); // historical/foreign rows must not fan out.
        db.update("update club set name=null,logo=null,member_count=null where id=12");
        var before=snapshot();var expected=originalRows(1L);
        statements.set(0);var actual=service.listConversations(1L);
        assertEquals(expected,actual);assertEquals(Set.of(201L,202L,203L,204L,216L),ids(actual));assertEquals(2,statements.get());
        for(var row:actual)if(!Long.valueOf(201).equals(row.get("conversationId"))&&!Long.valueOf(216).equals(row.get("conversationId"))) {
            var cp=(Map<?,?>)row.get("counterparty");assertNull(cp.get("nickname"));assertNull(cp.get("memberCount"));assertEquals("",cp.get("avatar"));
        }
        assertEquals(before,snapshot(),"list/shadow are read only, including unread and business memberships");
    }

    @Test void realStatementBudgetIsTwoAtOneSixtyFourAnd257WhileOriginalQueriesGrow()
    {
        for(int size:new int[]{0,1,64,257})
        {
            reset();
            for(int i=0;i<size;i++) {club(1000+i,99,1L,0);membership(1000+i,1,1,0);group(2000+i,"club_"+(1000+i),1);}
            statements.set(0);var expected=originalRows(1L);assertEquals(1+3*size,statements.get(),"original summary + club/member/display per row");
            statements.set(0);assertEquals(expected,service.listConversations(1L));assertEquals(size==0?1:2,statements.get());
            statements.set(0);assertTrue(service.listConversations(999L).isEmpty());assertEquals(1,statements.get());
        }
        reset();
        for(int i=0;i<64;i++){club(1000+i,1,1L,0);group(2000+i,"club_"+(1000+i),1);}
        statements.set(0);var expected=originalRows(1L);assertEquals(1+2*64,statements.get(),"owner short-circuits the original member lookup");
        statements.set(0);assertEquals(expected,service.listConversations(1L));assertEquals(2,statements.get());
    }

    @Test void committedMembershipExitAndClubDisableRemovePreviewWithoutReadSideEffects()
    {
        club(42,99,1L,0);membership(42,1,1,0);group(201,"club_42",1);
        assertEquals(Set.of(201L),ids(service.listConversations(1L)));
        for(String mutation:List.of("delete from club_member","update club_member set del_flag=2",
                "update club set status=0","update club set del_flag=2"))
        {
            db.update("update club set status=1,del_flag=0");db.update("delete from club_member");membership(42,1,1,0);
            assertEquals(Set.of(201L),ids(service.listConversations(1L)));
            db.update(mutation); // Actual committed current predicate; not a claim to exercise club quit orchestration.
            var afterRevocation=snapshot();assertTrue(originalRows(1L).isEmpty());statements.set(0);
            assertTrue(service.listConversations(1L).isEmpty());assertEquals(2,statements.get());
            assertEquals(afterRevocation,snapshot(),"rejected preview may not change unread, messages or club rows");
        }
    }

    @Test void emptyOrSelectedIdsNeverLoadUnrelatedClubsAndActualExplainIsRecorded()
    {
        for(int i=1;i<=64;i++){club(i,1,1L,0);membership(i,1,1,0);}
        assertTrue(members.selectClubConversationSummaries(1L,List.of()).isEmpty());
        assertTrue(members.selectClubConversationSummaries(1L,null).isEmpty());
        assertEquals(Set.of(2L,4L),members.selectClubConversationSummaries(1L,List.of(2L,4L)).stream()
                .map(c->c.getId()).collect(java.util.stream.Collectors.toSet()));
        Map<String,Object> params=Map.of("memberId",1L,"clubIds",List.of(2L,4L));
        var bound=configuration.getMappedStatement("com.chengyinhub.business.mapper.ImConversationMemberMapper.selectClubConversationSummaries").getBoundSql(params);
        Object[] args=bound.getParameterMappings().stream().map(p->bound.hasAdditionalParameter(p.getProperty())
                ?bound.getAdditionalParameter(p.getProperty()):params.get(p.getProperty())).toArray();
        String plan=db.queryForObject("EXPLAIN FORMAT=JSON "+bound.getSql(),String.class,args);
        assertNotNull(com.alibaba.fastjson2.JSON.parseObject(plan));System.out.println("IM_CLUB_BATCH_EXPLAIN "+plan);
        // Record actual planner facts; no invented timing/cost/index-choice threshold.
    }

    @Test void realClubDatabaseFailurePropagatesWithoutEmptySuccessOrFallback()
    {
        club(42,1,1L,0);group(201,"club_42",1);var before=snapshot();
        db.execute("rename table club to club_unavailable");
        try {assertThrows(RuntimeException.class,()->service.listConversations(1L));}
        finally {db.execute("rename table club_unavailable to club");}
        assertEquals(before,snapshot());assertEquals(Set.of(201L),ids(service.listConversations(1L)));
    }

    /** Original 4197 list pipeline: original real per-item guard and counterparty helpers are unchanged. */
    private List<Map<String,Object>> originalRows(Long viewer)
    {
        List<Map<String,Object>> out=new ArrayList<>();Map<Long,ImMessage> latestById=new HashMap<>();
        for(var m:members.selectMyConversationSummaries(viewer))
        {
            var c=m.getConversation();
            if(c==null||!Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service,"hasActiveGroupAccess",c,viewer,false)))continue;
            ImMessage latest=m.getLatest();if(latest!=null)latestById.put(c.getId(),latest);
            Map<String,Object> row=new HashMap<>();row.put("conversationId",c.getId());row.put("type",c.getType());
            row.put("lastMsgType",latest==null?null:latest.getMsgType());
            ImMessage projected=latest==null?null:ReflectionTestUtils.invokeMethod(service,"projectRouteMessage",latest,viewer);
            row.put("lastMsgText",latest==null?"":Integer.valueOf(ImMessage.MSG_IMAGE).equals(latest.getMsgType())?"[图片]":projected.getContent());
            row.put("lastMsgAt",latest==null?null:latest.getCreateTime());row.put("unread",m.getUnreadCount());
            row.put("muted",m.getMuted()==null?0:m.getMuted());
            row.put("counterparty",ReflectionTestUtils.invokeMethod(service,"summaryCounterparty",m,viewer));out.add(row);
        }
        out.sort((a,b)->{
            ImMessage x=latestById.get(a.get("conversationId")),y=latestById.get(b.get("conversationId"));
            String xo=x==null||x.getVisibleOrderAt()==null?"":x.getVisibleOrderAt(),yo=y==null||y.getVisibleOrderAt()==null?"":y.getVisibleOrderAt();
            int order=yo.compareTo(xo);return order!=0?order:Long.compare(y==null||y.getId()==null?0:y.getId(),x==null||x.getId()==null?0:x.getId());
        });return out;
    }

    private void club(long id,long owner,Long status,Integer deleted)
    {db.update("insert into club(id,member_id,name,logo,member_count,status,del_flag) values(?,?,?,?,?,?,?)",id,owner,"club-"+id,"logo-"+id,9,status,deleted);}
    private void membership(long club,long member,int status,int deleted)
    {db.update("insert into club_member(club_id,member_id,status,del_flag) values(?,?,?,?)",club,member,status,deleted);}
    private void group(long id,String key,long member)
    {
        db.update("insert into im_conversation(id,type,biz_key,status,last_msg_text,last_msg_at) values(?,4,?,0,'stale summary','2026-10-23 08:00:00')",id,key);
        db.update("insert into im_conversation_member(conversation_id,member_id,unread_count,muted) values(?,?,5,0)",id,member);
        db.update("insert into im_message(id,conversation_id,sender_id,msg_type,status,check_status,content,create_time,visible_at) "
                +"values(?,?,99,1,0,1,?,'2026-10-23 10:00:00','2026-10-23 10:00:00')",id,id,"private-group-message-"+id);
    }
    private Set<Long> ids(List<Map<String,Object>> rows)
    {return rows.stream().map(r->(Long)r.get("conversationId")).collect(java.util.stream.Collectors.toSet());}
    private Map<String,List<Map<String,Object>>> snapshot()
    {
        Map<String,List<Map<String,Object>>> result=new LinkedHashMap<>();
        for(String table:List.of("im_conversation","im_conversation_member","im_message","club","club_member"))
            result.put(table,db.queryForList("select * from "+table+" order by id"));return result;
    }
}
