package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.PersonalExplorationExportMapper;
import com.chengyinhub.business.service.CloudTenMySqlFixture;
import com.chengyinhub.business.service.PersonalExplorationExportService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.InputStream;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated VPS only. Actual MyBatis SQL and existing gates, exclusive fresh schema. No export job table. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_TEN_FEATURES_MYSQL_IT",matches="1")
class PersonalExplorationExportMySqlIT {
    private static CloudTenMySqlFixture database;
    private static PooledDataSource pool;
    private static SqlSessionFactory factory;
    private static final long CLOCK=OffsetDateTime.parse("2026-10-12T12:00:00+08:00").toInstant().toEpochMilli();
    @BeforeAll static void setup()throws Exception{
        database=CloudTenMySqlFixture.open("exploration_export");
        try(Connection connection=database.connection();Statement st=connection.createStatement()){
            st.execute("CREATE TABLE cms_topic(id BIGINT PRIMARY KEY,member_id BIGINT,club_id BIGINT,name VARCHAR(120),address_name VARCHAR(120),img_url VARCHAR(200),status INT,user_status INT,del_flag INT,is_template INT,is_draft INT,self_play INT,route_mode VARCHAR(32),published_config_version BIGINT,published_route_graph_json TEXT,total_time BIGINT,circle_theme_code VARCHAR(32),circle_reviewed_at DATETIME,club_member_only INT,start_date DATE,end_date DATE)");
            st.execute("CREATE TABLE club(id BIGINT PRIMARY KEY,member_id BIGINT,del_flag INT)");
            st.execute("CREATE TABLE club_member(id BIGINT PRIMARY KEY,club_id BIGINT,member_id BIGINT,status INT,del_flag INT)");
            st.execute("CREATE TABLE cms_activity(id BIGINT PRIMARY KEY,topic_id BIGINT)");
            st.execute("CREATE TABLE cms_registration(id BIGINT PRIMARY KEY,member_id BIGINT,owner_type INT,owner_id BIGINT,topic_id BIGINT,payment_status INT,registration_status INT)");
            st.execute("CREATE TABLE merchant_chapter_offer(id BIGINT PRIMARY KEY,topic_id BIGINT,chapter_id BIGINT,merchant_id BIGINT,status INT,valid_end DATETIME)");
            st.execute("CREATE TABLE merchant_circle_supply_profile(offer_id BIGINT,is_current INT,checked_at DATETIME)");
            st.execute("CREATE TABLE cms_topic_chapter(id BIGINT PRIMARY KEY,circle_enabled INT)");
            st.execute("CREATE TABLE topic_route_session(id BIGINT PRIMARY KEY,member_id BIGINT,topic_id BIGINT,status INT,config_version BIGINT,completed_at DATETIME(3),create_time DATETIME(3),state_json TEXT)");
        }
        pool=new PooledDataSource("com.mysql.cj.jdbc.Driver",database.config().databaseUrl(),database.config().user(),database.config().password());
        Configuration config=new Configuration(new Environment("personal-exploration-export",new JdbcTransactionFactory(),pool));config.getTypeAliasRegistry().registerAlias("CmsTopic",CmsTopic.class);
        for(String resource:new String[]{"mapper/business/CmsTopicMapper.xml","mapper/business/PersonalExplorationExportMapper.xml"})try(InputStream input=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();}
        factory=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterAll static void cleanup()throws Exception{if(pool!=null)pool.forceCloseAll();if(database!=null)database.close();}
    @BeforeEach void seed()throws Exception{
        try(Connection c=database.connection();Statement st=c.createStatement()){
            for(String table:new String[]{"topic_route_session","cms_topic","club_member","club","cms_activity","cms_registration","merchant_chapter_offer","merchant_circle_supply_profile","cms_topic_chapter"})st.executeUpdate("DELETE FROM "+table);
            st.executeUpdate("INSERT INTO cms_topic(id,member_id,name,status,user_status,del_flag,is_template,is_draft,club_member_only,start_date,end_date) VALUES(3,99,'公开旧路线',1,1,0,0,0,0,'2020-01-01','2020-01-02')");
            st.executeUpdate("INSERT INTO topic_route_session VALUES(8,41,3,1,2,'2026-10-12 10:00:00.123','2026-10-12 09:00:00.000','raw-private-state'),(9,41,3,1,3,'2026-10-12 11:00:00.000','2026-10-12 10:00:00.000','raw-private-state')");
        }
    }
    private void execute(String sql)throws Exception{try(Connection c=database.connection();Statement s=c.createStatement()){s.executeUpdate(sql);}}
    private PersonalExplorationExportService service(SqlSession s){return new PersonalExplorationExportService(s.getMapper(PersonalExplorationExportMapper.class)){@Override protected long now(){return CLOCK;}};}
    private Map<String,Object> prepare(int size){try(SqlSession s=factory.openSession(true)){return service(s).prepare(41L,"2026-10-12","2026-10-13",size);}}
    private Map<String,Object> page(String cursor){try(SqlSession s=factory.openSession(true)){return service(s).page(41L,cursor);}}
    private Map<?,?> first(Map<String,Object> page){return (Map<?,?>)((List<?>)page.get("items")).get(0);}
    @Test void onlyRealCompletedOwnerFactsWithinShanghaiHalfOpenWindowAreExported()throws Exception{
        execute("INSERT INTO topic_route_session VALUES(10,42,3,1,2,'2026-10-12 10:00:00',NULL,NULL),(11,41,3,0,2,'2026-10-12 10:00:00',NULL,NULL),(12,41,3,2,2,'2026-10-12 10:00:00',NULL,NULL),(13,41,3,1,2,NULL,NULL,NULL),(14,41,3,1,2,'2026-10-11 23:59:59.999',NULL,NULL),(15,41,3,1,2,'2026-10-13 00:00:00',NULL,NULL),(16,41,3,1,2,'2026-10-12 12:00:00.001',NULL,NULL),(17,41,3,1,2,'2026-10-12 00:00:00',NULL,NULL)");
        Map<String,Object> prepared=prepare(100);assertEquals(3,prepared.get("expectedRows"));Map<String,Object> result=page((String)prepared.get("cursor"));assertEquals(true,result.get("complete"));assertEquals(3,((List<?>)result.get("items")).size());
        for(Object raw:(List<?>)result.get("items")){Map<?,?> row=(Map<?,?>)raw;assertFalse(row.containsKey("state_json"));assertFalse(row.containsKey("memberId"));}
    }
    @Test void expiredTopicCalendarAndMissingCurrentTicketDoNotHideOwnCompletion()throws Exception{
        Map<String,Object> result=page((String)prepare(100).get("cursor"));assertEquals("公开旧路线",first(result).get("routeTitle"));assertEquals("AVAILABLE",first(result).get("routeAvailability"));assertEquals(3600L,((Number)first(result).get("elapsedSeconds")).longValue());
    }
    @Test void metadataUsesCurrentClubCircleAndPublicationGatesWithoutRemovingFacts()throws Exception{
        String cursor=(String)prepare(100).get("cursor");execute("INSERT INTO club VALUES(1,99,0)");execute("UPDATE cms_topic SET club_id=1,club_member_only=1 WHERE id=3");
        assertNull(first(page(cursor)).get("routeTitle"));execute("INSERT INTO club_member VALUES(1,1,41,1,0)");assertEquals("AVAILABLE",first(page(cursor)).get("routeAvailability"));
        execute("UPDATE cms_topic SET circle_theme_code='FITNESS',circle_reviewed_at=NOW() WHERE id=3");assertEquals("UNAVAILABLE",first(page(cursor)).get("routeAvailability"));
        execute("UPDATE cms_topic SET circle_theme_code=null WHERE id=3");execute("UPDATE club SET del_flag=1 WHERE id=1");assertNull(first(page(cursor)).get("routeTitle"));
        execute("UPDATE club SET del_flag=0 WHERE id=1");execute("UPDATE cms_topic SET is_draft=1 WHERE id=3");assertNull(first(page(cursor)).get("routeTitle"));assertEquals(2,page(cursor).get("emittedRows"));
    }
    @Test void laterHighIdAndAfterAsOfCompletionsDoNotEnterFrozenWatermark()throws Exception{
        String cursor=(String)prepare(1).get("cursor");Map<String,Object> first=page(cursor);assertEquals("9",first(first).get("recordId"));
        execute("INSERT INTO topic_route_session VALUES(100,41,3,1,2,'2026-10-12 10:30:00',NULL,NULL),(7,41,3,1,2,'2026-10-12 13:00:00',NULL,NULL)");
        Map<String,Object> second=page((String)first.get("nextCursor"));assertEquals("8",first(second).get("recordId"));assertEquals(true,second.get("complete"));assertEquals(2,second.get("emittedRows"));
    }
    @Test void deletionBetweenPagesIsExplicitSourceChanged()throws Exception{
        Map<String,Object> first=page((String)prepare(1).get("cursor"));execute("DELETE FROM topic_route_session WHERE id=8");Map<String,Object> second=page((String)first.get("nextCursor"));assertEquals("SOURCE_CHANGED",second.get("state"));assertEquals(false,second.get("complete"));assertTrue(((List<?>)second.get("items")).isEmpty());
    }
    @Test void routeDeletionRetainsHistoricalFactsAndInvalidElapsedIsNull()throws Exception{
        execute("DELETE FROM cms_topic WHERE id=3");execute("UPDATE topic_route_session SET create_time='2026-10-12 14:00:00' WHERE id=9");Map<String,Object> result=page((String)prepare(100).get("cursor"));assertEquals(2,result.get("emittedRows"));assertNull(first(result).get("elapsedSeconds"));assertNull(first(result).get("routeTitle"));
    }
    @Test void duplicateTimestampUsesSessionIdKeysetAndPreservesMillisecondText()throws Exception{
        execute("UPDATE topic_route_session SET completed_at='2026-10-12 10:00:00.123'");Map<String,Object> first=page((String)prepare(1).get("cursor"));assertEquals("9",first(first).get("recordId"));assertEquals("2026-10-12T10:00:00.123+08:00",first(first).get("completedAt"));Map<String,Object> second=page((String)first.get("nextCursor"));assertEquals("8",first(second).get("recordId"));assertEquals(true,second.get("complete"));
    }
}
