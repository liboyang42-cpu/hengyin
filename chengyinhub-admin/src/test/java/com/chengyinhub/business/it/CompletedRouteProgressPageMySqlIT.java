package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.PlayerNodeProgressServiceImpl;
import com.chengyinhub.business.service.support.CompletedRouteFactReader;
import com.chengyinhub.business.service.support.CompletedRouteFactReader.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** WP06-B: real MySQL/old mapper oracle. Only approved CI/VPS; no H2/skip/default connection. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletedRouteProgressPageMySqlIT {
    private String createdDatabase;
    private JdbcTemplate db;
    private CompletedRouteFactMapper mapper;
    private CompletedRouteFactReader reader;
    private PlayerNodeProgressServiceImpl legacy;
    private final QueryCounter counter=new QueryCounter();

    @BeforeAll void schema() throws Exception {
        for(String name:new String[]{"HOST","PORT","USER","PASSWORD","DB_SUFFIX"}) {
            String value=System.getenv("CHENGYIN_MYBATIS_IT_"+name);
            assertNotNull(value,"explicit isolated IT variable required: "+name);
            if(!"PASSWORD".equals(name))assertFalse(value.isEmpty());
        }
        assertTrue(Set.of("127.0.0.1","localhost").contains(System.getenv("CHENGYIN_MYBATIS_IT_HOST")));
        int port=Integer.parseInt(MybatisItDatabase.port());assertTrue(port>0 && port<=65535);
        assertTrue(System.getenv("CHENGYIN_MYBATIS_IT_DB_SUFFIX").matches("_[a-z0-9_]{1,20}"));
        String suite="g_wp06b_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String url=MybatisItDatabase.url(suite);createdDatabase=MybatisItDatabase.databaseName(suite);
        DriverManagerDataSource source=new DriverManagerDataSource(url,MybatisItDatabase.user(),MybatisItDatabase.password());
        db=new JdbcTemplate(source);
        db.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY,complete_rule_json JSON NULL)");
        try(Connection c=source.getConnection()) {ItSchema.applyMigrations(c,"sql/migration_20260823_topic_route_graph.sql");}
        String ddl;
        try(java.io.InputStream in=new ClassPathResource("it-schema/journey-full-flow-it-schema.sql").getInputStream()) {
            ddl=new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
        java.util.regex.Matcher pg=Pattern.compile("(?s)CREATE TABLE `player_node_progress` \\(.*?\\) ENGINE=.*?;").matcher(ddl);
        assertTrue(pg.find(),"existing real PG fixture DDL required");db.execute(pg.group());
        // Exact existing instance keys from migration_theme_runtime_20261004.sql, fixture only.
        db.execute("ALTER TABLE player_node_progress ADD UNIQUE KEY uk_progress(member_id,topic_id,node_id,activity_id,play_instance_id), ADD UNIQUE KEY uk_member_activity_node(member_id,activity_id,node_id,play_instance_id)");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");factory.setPlugins(counter);
        factory.setMapperLocations(new ClassPathResource("mapper/business/CompletedRouteFactMapper.xml"),
                new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"),
                new ClassPathResource("mapper/business/TopicRouteSessionMapper.xml"));
        SqlSessionTemplate sql=new SqlSessionTemplate(factory.getObject());
        mapper=sql.getMapper(CompletedRouteFactMapper.class);reader=new CompletedRouteFactReader(mapper);
        legacy=new PlayerNodeProgressServiceImpl();
        ReflectionTestUtils.setField(legacy,"playerNodeProgressMapper",sql.getMapper(PlayerNodeProgressMapper.class));
        ReflectionTestUtils.setField(legacy,"routeSessions",sql.getMapper(TopicRouteSessionMapper.class));
    }

    @AfterAll void cleanup() throws Exception {
        if(createdDatabase==null)return;
        assertTrue(createdDatabase.matches("chengyin_mybatis_g_wp06b_[a-f0-9]{12}_[a-z0-9_]{1,20}"));
        try(Connection c=DriverManager.getConnection("jdbc:mysql://"+MybatisItDatabase.host()+":"+MybatisItDatabase.port()
                +"/?serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true",MybatisItDatabase.user(),MybatisItDatabase.password());
            Statement st=c.createStatement()) {st.execute("DROP DATABASE `"+createdDatabase+"`");}
    }

    @BeforeEach void seed() {
        db.execute("DELETE FROM topic_route_action");db.execute("DELETE FROM player_node_progress");db.execute("DELETE FROM topic_route_session");
        route(1,7,0,10,"default",1,3);route(2,7,0,10,"replay:1",0,9);
        route(90,8,0,10,"replay:1",1,99);route(3,7,501,10,"default",0,5);
        route(4,7,0,20,"default",1,6);route(5,7,0,30,"default",1,7);
        progress(1,7,0,10,11,"default",1,null,1,"SOLVED",0);
        progress(2,7,0,10,11,"replay:1",0,null,null,null,0);
        progress(3,7,0,10,12,"replay:1",0,"pending.jpg",0,null,0);
        progress(4,7,0,10,13,"replay:1",1,"machine.jpg",0,"SOLVED",0);
        progress(5,7,0,10,14,"replay:1",1,null,1,"REVEALED",0);
        progress(6,8,0,10,11,"replay:1",1,"other-member.jpg",1,"SOLVED",0);
        progress(7,7,501,10,11,"default",0,null,null,null,0);
        progress(8,7,0,20,21,"default",1,null,1,"SOLVED",0);
        progress(9,7,0,10,15,"replay:1",1,null,1,"SOLVED",2);
        progress(10,7,0,10,16,"replay:1",1,null,1,"SOLVED",0);
        db.update("UPDATE player_node_progress SET checkin_time=NULL WHERE id=10");
        progress(11,7,0,40,41,"default",1,null,1,"SOLVED",0); // no route: old default source, unknown frozen version
        counter.reset();
    }

    @Test void everyInputHasBidirectionallyEqualLegacyRowsAndCompletionObservation() {
        List<NodeProgressRequest> inputs=page();
        Map<String,List<Object>> expected=new LinkedHashMap<>();
        for(NodeProgressRequest input:inputs)expected.put(input.getInputId(),signature(old(input)));
        Set<Long> oldCompanionCompleted=companionCompleted();
        counter.reset();Map<String,NodeProgressFact> actual=reader.findNodeProgressPage(7L,inputs);
        assertEquals(1,counter.queries.get());assertEquals(1,counter.batch.get());
        assertBothDirections(inputs,expected,actual);
        Set<Long> batchCompanionCompleted=actual.values().stream()
                .filter(f->f.getRequest().getActivityId()==0L && f.getRequest().getTopicId()==10L
                        && f.getRequest().getPlayInstanceId()==null && f.getNodeCompleted()==Observation.OBSERVED)
                .map(f->f.getRequest().getNodeId()).collect(java.util.stream.Collectors.toSet());
        assertEquals(oldCompanionCompleted,batchCompanionCompleted,"whole original companionLine self-play completed set, not only selected row equality");
    }

    @Test void currentUsesNewestBeforeStatusWhileExactOldRunStaysReadable() {
        Map<String,NodeProgressFact> rows=reader.findNodeProgressPage(7L,List.of(req("current",0,10,11,null),req("old",0,10,11,"default")));
        assertEquals("replay:1",rows.get("current").getResolvedPlayInstanceId());
        assertEquals(2L,rows.get("current").getRouteSessionId());assertEquals(9L,rows.get("current").getFrozenConfigVersion());
        assertEquals(Observation.NOT_OBSERVED,rows.get("current").getNodeCompleted());
        assertEquals(Observation.OBSERVED,rows.get("old").getNodeCompleted());assertEquals(3L,rows.get("old").getFrozenConfigVersion());
        assertEquals(7L,rows.get("current").getSourceProgress().getMemberId());
    }

    @Test void scopeAndInstanceDifferencesCannotBorrowAnotherSource() {
        List<NodeProgressRequest> inputs=List.of(req("wrong-topic",0,20,11,null),req("wrong-activity",502,10,11,null),
                req("wrong-run",0,10,11,"replay:missing"),req("deleted",0,10,15,null),req("right-topic",0,20,21,null));
        Map<String,NodeProgressFact> rows=reader.findNodeProgressPage(7L,inputs);
        for(String id:List.of("wrong-topic","wrong-activity","wrong-run","deleted")) {
            assertFalse(rows.get(id).hasSource());assertEquals(Observation.UNKNOWN,rows.get(id).getNodeCompleted());
        }
        assertEquals(8L,rows.get("right-topic").getSourceProgress().getId());
        assertEquals("replay:missing",rows.get("wrong-run").getResolvedPlayInstanceId());
        assertNull(rows.get("wrong-run").getFrozenConfigVersion());
    }

    @Test void photosSolvedAndRevealedRemainDistinctWithoutInventingTaskVerification() {
        Map<String,NodeProgressFact> rows=reader.findNodeProgressPage(7L,List.of(req("photo",0,10,12,null),req("machine",0,10,13,null),req("revealed",0,10,14,null),req("no-time",0,10,16,null)));
        assertEquals(Observation.NOT_OBSERVED,rows.get("photo").getNodeCompleted());assertEquals(0,rows.get("photo").getSourceProgress().getReviewStatus());
        assertEquals(Observation.OBSERVED,rows.get("machine").getNodeCompleted());assertEquals(0,rows.get("machine").getSourceProgress().getReviewStatus());
        assertEquals("REVEALED",rows.get("revealed").getSourceProgress().getCompletionMode());
        assertEquals(Observation.NOT_OBSERVED,rows.get("no-time").getArrival());
        assertTrue(rows.values().stream().allMatch(f->f.getTaskVerified()==Observation.UNKNOWN));
    }

    @Test void completedRouteWithoutProgressDoesNotBecomeCompletedNodeFallback() {
        NodeProgressFact fact=reader.findNodeProgressPage(7L,List.of(req("no-pg",0,30,31,null))).get("no-pg");
        assertEquals(5L,fact.getRouteSessionId());assertFalse(fact.hasSource());
        assertEquals(Observation.UNKNOWN,fact.getArrival());assertEquals(Observation.UNKNOWN,fact.getNodeCompleted());
        assertEquals(Observation.UNKNOWN,fact.getTaskVerified());
        assertTrue(reader.findCurrentCompletedRoutes(7L).stream().anyMatch(r->r.getId()==5L),"theme and node are different source facts");
    }

    @Test void personalAndSharedBasicArrivalsStayMemberFactsWithNoTeamCompletionClaim() {
        Map<String,NodeProgressFact> rows=reader.findNodeProgressPage(7L,List.of(req("personal",0,10,11,null),req("shared-basic",501,10,11,null)));
        for(NodeProgressFact fact:rows.values()) {
            assertEquals("MEMBER",fact.getSubjectKind());assertEquals("LEGACY_PROGRESS",fact.getSourceKind());
            assertEquals(Observation.OBSERVED,fact.getArrival());assertEquals(Observation.NOT_OBSERVED,fact.getNodeCompleted());
            assertEquals(Observation.UNKNOWN,fact.getTaskVerified());
        }
        assertNotEquals(rows.get("personal").getRequest().getActivityId(),rows.get("shared-basic").getRequest().getActivityId());
    }

    @Test void oneAndHundredInputsEachUseOneActualMyBatisStatement() {
        counter.reset();assertEquals(1,reader.findNodeProgressPage(7L,List.of(req("one",0,10,11,null))).size());
        assertEquals(1,counter.queries.get());assertEquals(1,counter.batch.get());
        List<NodeProgressRequest> hundred=new ArrayList<>();
        for(int i=1;i<=100;i++)hundred.add(req("node-"+i,0,10,i,null));
        counter.reset();Map<String,NodeProgressFact> rows=reader.findNodeProgressPage(7L,hundred);
        assertEquals(100,rows.size());assertEquals(1,counter.queries.get());assertEquals(1,counter.batch.get());
        Map<String,List<Object>> expected=new LinkedHashMap<>();
        for(NodeProgressRequest input:hundred)expected.put(input.getInputId(),signature(old(input)));
        assertBothDirections(hundred,expected,rows);
    }

    @Test void invalidScopeEmptyAndOversizedPagesIssueNoSql() {
        counter.reset();assertTrue(reader.findNodeProgressPage(7L,List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,List.of(new NodeProgressRequest("foreign",8L,0L,10L,11L,null))));
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,List.of(req("blank",0,10,11," "))));
        assertThrows(IllegalArgumentException.class,()->reader.findNodeProgressPage(7L,Collections.nCopies(101,req("too-many",0,10,11,null))));
        assertEquals(0,counter.queries.get());
    }

    @Test void repeatedReadsLeaveAllProgressSessionsAndReceiptsUnchanged() {
        List<Map<String,Object>> progress=db.queryForList("SELECT * FROM player_node_progress ORDER BY id");
        List<Map<String,Object>> sessions=db.queryForList("SELECT id,status,version,CAST(state_json AS CHAR) state_json FROM topic_route_session ORDER BY id");
        List<Map<String,Object>> receipts=db.queryForList("SELECT * FROM topic_route_action ORDER BY id");
        reader.findNodeProgressPage(7L,page());reader.findNodeProgressPage(7L,page());
        assertEquals(progress,db.queryForList("SELECT * FROM player_node_progress ORDER BY id"));
        assertEquals(sessions,db.queryForList("SELECT id,status,version,CAST(state_json AS CHAR) state_json FROM topic_route_session ORDER BY id"));
        assertEquals(receipts,db.queryForList("SELECT * FROM topic_route_action ORDER BY id"));
    }

    @Test void aPartialInputUniverseMustNotBeAdvertisedAsTheWholeCompanionCount() {
        progress(12,7,0,10,999,"replay:1",1,null,1,"SOLVED",0);
        Set<Long> oldCompleted=companionCompleted();
        Map<String,NodeProgressFact> selected=reader.findNodeProgressPage(7L,List.of(req("only-13",0,10,13,null)));
        Set<Long> selectedCompleted=selected.values().stream().filter(f->f.getNodeCompleted()==Observation.OBSERVED)
                .map(f->f.getRequest().getNodeId()).collect(java.util.stream.Collectors.toSet());
        Set<Long> outsidePage=new LinkedHashSet<>(oldCompleted);outsidePage.removeAll(selectedCompleted);
        assertEquals(Set.of(14L,16L,999L),outsidePage,"a page is not an implicit whole-scope aggregate or current active-node list");
        assertNotEquals(oldCompleted.size(),selectedCompleted.size(),"keep original consumer unless full input-universe parity is proven");
    }

    @Test void directMapperEmptyOrForeignInputsCannotBecomeUnscopedRead() {
        assertTrue(mapper.selectNodeProgressPage(7L,List.of()).isEmpty());
        assertTrue(mapper.selectNodeProgressPage(7L,List.of(new NodeProgressRequest("foreign",8L,0L,10L,11L,null))).isEmpty());
        assertTrue(mapper.selectNodeProgressPage(null,List.of(req("none",0,10,11,null))).isEmpty());
    }

    private void assertBothDirections(List<NodeProgressRequest> inputs,Map<String,List<Object>> expected,Map<String,NodeProgressFact> actual) {
        Set<String> missing=new LinkedHashSet<>(expected.keySet());missing.removeAll(actual.keySet());
        Set<String> extra=new LinkedHashSet<>(actual.keySet());extra.removeAll(expected.keySet());
        assertEquals(Set.of(),missing,"every input id must be retained, including missing source");assertEquals(Set.of(),extra,"no extra input may appear");
        Map<String,List<Object>> observed=new LinkedHashMap<>();
        for(NodeProgressRequest input:inputs) {
            NodeProgressFact fact=actual.get(input.getInputId());PlayerNodeProgress source=fact.getSourceProgress();
            observed.put(input.getInputId(),signature(source));
            assertEquals(source==null?Observation.UNKNOWN:source.getStatus()==1?Observation.OBSERVED:Observation.NOT_OBSERVED,fact.getNodeCompleted());
        }
        assertEquals(expected,observed,"all source columns must agree with original per-item owner/mapper");
    }
    private Set<Long> companionCompleted() {
        // Actual companionLine self-play probe and status1 rule, including historical/deactivated node ids.
        PlayerNodeProgress probe=new PlayerNodeProgress();probe.setMemberId(7L);probe.setActivityId(0L);probe.setTopicId(10L);
        return legacy.selectPlayerNodeProgressList(probe).stream().filter(p->Integer.valueOf(1).equals(p.getStatus()))
                .map(PlayerNodeProgress::getNodeId).collect(java.util.stream.Collectors.toSet());
    }
    private PlayerNodeProgress old(NodeProgressRequest input) {
        PlayerNodeProgress probe=new PlayerNodeProgress();probe.setMemberId(input.getMemberId());probe.setActivityId(input.getActivityId());
        probe.setTopicId(input.getTopicId());probe.setNodeId(input.getNodeId());probe.setPlayInstanceId(input.getPlayInstanceId());
        List<PlayerNodeProgress> rows=legacy.selectPlayerNodeProgressList(probe);
        assertTrue(rows.size()<=1);return rows.isEmpty()?null:rows.get(0);
    }
    private List<Object> signature(PlayerNodeProgress r) {
        if(r==null)return null;
        return Arrays.asList(r.getId(),r.getMemberId(),r.getActivityId(),r.getTopicId(),r.getNodeId(),r.getPlayInstanceId(),
                r.getStatus(),r.getCheckinTime(),r.getPicUrl(),r.getReviewStatus(),r.getLongitude(),r.getLatitude(),
                r.getPuzzleScore(),r.getCompletionMode(),r.getDelFlag(),r.getCreateTime());
    }
    private List<NodeProgressRequest> page() {
        return List.of(req("current",0,10,11,null),req("old",0,10,11,"default"),req("pending",0,10,12,null),
                req("machine",0,10,13,null),req("revealed",0,10,14,null),req("deleted",0,10,15,null),
                req("no-time",0,10,16,null),req("missing",0,10,99,null),req("activity",501,10,11,null),
                req("topic",0,20,21,null),req("wrong-topic",0,20,11,null),req("no-route",0,40,41,null));
    }
    private NodeProgressRequest req(String id,long activity,long topic,long node,String instance) {return new NodeProgressRequest(id,7L,activity,topic,node,instance);}
    private void progress(long id,long member,long activity,long topic,long node,String instance,int status,String pic,Integer review,String mode,int deleted) {
        db.update("INSERT INTO player_node_progress(id,member_id,activity_id,topic_id,node_id,play_instance_id,status,checkin_time,pic_url,review_status,completion_mode,puzzle_score,longitude,latitude,del_flag,create_time) VALUES(?,?,?,?,?,?,?,'2026-10-01 10:00:00',?,?,?,80,'121.4','31.2',?,'2026-10-01 09:59:00')",
                id,member,activity,topic,node,instance,status,pic,review,mode,deleted);
    }
    private void route(long id,long member,long activity,long topic,String instance,int status,long version) {
        db.update("INSERT INTO topic_route_session(id,member_id,activity_id,topic_id,play_instance_id,config_version,config_snapshot_json,state_json,status,seed,version,completed_at,create_time,update_time) VALUES(?,?,?,?,?,?,JSON_OBJECT(),JSON_OBJECT(),?,1,0,CASE WHEN ?=1 THEN '2026-10-01 11:00:00' ELSE NULL END,'2026-10-01 10:00:00','2026-10-01 11:00:00')",
                id,member,activity,topic,instance,version,status,status);
    }

    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static class QueryCounter implements Interceptor {
        final AtomicInteger queries=new AtomicInteger(),batch=new AtomicInteger();
        public Object intercept(Invocation invocation) throws Throwable {
            queries.incrementAndGet();String id=((MappedStatement)invocation.getArgs()[0]).getId();
            if(id.equals(CompletedRouteFactMapper.class.getName()+".selectNodeProgressPage"))batch.incrementAndGet();
            return invocation.proceed();
        }
        void reset(){queries.set(0);batch.set(0);}
    }
}
