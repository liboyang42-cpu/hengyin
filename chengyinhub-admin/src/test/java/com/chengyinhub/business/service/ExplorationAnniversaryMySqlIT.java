package com.chengyinhub.business.service;
import com.chengyinhub.business.mapper.ExplorationAnniversaryMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated VPS only. Production XML and exact shared gates against an exclusive real MySQL schema. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_TEN_FEATURES_MYSQL_IT",matches="1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExplorationAnniversaryMySqlIT {
    CloudTenMySqlFixture fixture; JdbcTemplate db; ExplorationAnniversaryService service;
    @BeforeAll void open() throws Exception {
        fixture=CloudTenMySqlFixture.open("anniversary");DriverManagerDataSource source=new DriverManagerDataSource(fixture.config().databaseUrl(),fixture.config().user(),fixture.config().password());db=new JdbcTemplate(source);
        db.execute("create table topic_route_session(id bigint primary key,member_id bigint,topic_id bigint,status int,completed_at datetime(3)) engine=InnoDB");
        db.execute("create table cms_topic(id bigint primary key,name varchar(100),status int,user_status int,del_flag int,is_template int,is_draft int,club_id bigint,member_id bigint,club_member_only int,circle_theme_code varchar(32),circle_reviewed_at datetime,start_date date,end_date date) engine=InnoDB");
        db.execute("create table club(id bigint primary key,member_id bigint,del_flag int) engine=InnoDB");
        db.execute("create table club_member(club_id bigint,member_id bigint,status int,del_flag int) engine=InnoDB");
        db.execute("create table cms_registration(member_id bigint,owner_type int,owner_id bigint,topic_id bigint,payment_status int,registration_status int,expires_at datetime) engine=InnoDB");
        db.execute("create table cms_activity(id bigint primary key,topic_id bigint) engine=InnoDB");
        db.execute("create table merchant_chapter_offer(id bigint primary key,merchant_id bigint,chapter_id bigint,topic_id bigint,status int,valid_end datetime) engine=InnoDB");
        db.execute("create table merchant_circle_supply_profile(offer_id bigint,is_current int,checked_at datetime) engine=InnoDB");
        db.execute("create table cms_topic_chapter(id bigint primary key,circle_enabled int) engine=InnoDB");
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setMapperLocations(gates(),new ClassPathResource("mapper/business/ExplorationAnniversaryMapper.xml"));
        ExplorationAnniversaryMapper mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ExplorationAnniversaryMapper.class);
        service=new ExplorationAnniversaryService(mapper){@Override protected long now(){return Instant.parse("2026-10-12T12:00:00Z").toEpochMilli();}};
    }
    private ByteArrayResource gates() throws Exception {
        String xml;try(InputStream in=new ClassPathResource("mapper/business/CmsTopicMapper.xml").getInputStream();Scanner scan=new Scanner(in,"UTF-8").useDelimiter("\\A")){xml=scan.next();}
        StringBuilder out=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\"><mapper namespace=\"com.chengyinhub.business.mapper.CmsTopicMapper\">");
        for(String id:Arrays.asList("primaryCirclePublicGate","clubMemberOnlyGate")){Matcher m=Pattern.compile("<sql id=\""+id+"\">.*?</sql>",Pattern.DOTALL).matcher(xml);assertTrue(m.find());out.append(m.group());}
        return new ByteArrayResource(out.append("</mapper>").toString().getBytes(StandardCharsets.UTF_8));
    }
    @BeforeEach void reset(){for(String table:Arrays.asList("topic_route_session","cms_topic","club","club_member","cms_registration","cms_activity","merchant_chapter_offer","merchant_circle_supply_profile","cms_topic_chapter"))db.update("delete from "+table);topic(1);}
    @AfterAll void close() throws Exception {if(fixture!=null)fixture.close();}
    void topic(long id){db.update("insert into cms_topic(id,name,status,user_status,del_flag,is_template,is_draft,member_id,club_member_only,start_date,end_date) values(?,?,1,1,0,0,0,99,0,'2020-01-01','2020-01-02')",id,"当前路线"+id);}
    void fact(long id,long owner,long topic,int status,String at){db.update("insert into topic_route_session values(?,?,?,?,?)",id,owner,topic,status,at);}
    Map<String,Object> page(Long owner,String month,String day,String cursor,String limit){return service.read(owner,month,day,cursor,limit);}
    List<?> items(){return (List<?>)page(7L,"10","12",null,null).get("items");}
    @Test void onlyOwnRealCompletionsInEarlierYearsOnExactDay(){fact(1,7,1,1,"2025-10-12 00:00:00");fact(2,8,1,1,"2025-10-12 00:00:00");fact(3,7,1,0,"2025-10-12 00:00:00");fact(4,7,1,1,null);fact(5,7,1,1,"2026-10-12 00:00:00");fact(6,7,1,1,"2025-10-13 00:00:00");fact(7,7,1,1,"2027-10-12 00:00:00");assertEquals(1,items().size());assertEquals("1",((Map<?,?>)items().get(0)).get("id"));}
    @Test void leapDayNeverMovesToFebruary28OrMarch1(){fact(1,7,1,1,"2024-02-29 23:59:59.999");fact(2,7,1,1,"2025-02-28 23:59:59.999");fact(3,7,1,1,"2024-03-01 00:00:00");List<?> result=(List<?>)page(7L,"2","29",null,null).get("items");assertEquals(1,result.size());assertEquals("1",((Map<?,?>)result.get(0)).get("id"));}
    @Test void timestampThenLargeStringIdKeysetNeverRepeatsTies(){fact(9007199254740999L,7,1,1,"2025-10-12 12:00:00.123");fact(9007199254740998L,7,1,1,"2025-10-12 12:00:00.123");fact(3,7,1,1,"2024-10-12 12:00:00");Map<String,Object> first=page(7L,"10","12",null,"1");assertEquals("9007199254740999",((Map<?,?>)((List<?>)first.get("items")).get(0)).get("id"));Map<String,Object> second=page(7L,"10","12",(String)first.get("nextCursor"),"1");assertEquals("9007199254740998",((Map<?,?>)((List<?>)second.get("items")).get(0)).get("id"));Map<String,Object> third=page(7L,"10","12",(String)second.get("nextCursor"),"1");assertEquals(false,third.get("hasMore"));assertNull(third.get("nextCursor"));}
    @Test void currentStateAndTemplateDraftGatesAlwaysApply(){fact(1,7,1,1,"2025-10-12 00:00:00");for(String column:Arrays.asList("status","user_status","del_flag","is_template","is_draft")){int blocked=Arrays.asList("status","user_status").contains(column)?0:1;db.update("update cms_topic set "+column+"=? where id=1",blocked);assertTrue(items().isEmpty(),column);db.update("update cms_topic set "+column+"=? where id=1",1-blocked);}}
    @Test void clubMembershipRevocationAndClubDeletionHideOldTitle(){fact(1,7,1,1,"2025-10-12 00:00:00");db.update("insert into club values(4,99,0)");db.update("update cms_topic set club_id=4,club_member_only=1 where id=1");assertTrue(items().isEmpty());db.update("insert into club_member values(4,7,1,0)");assertEquals(1,items().size());db.update("update club_member set status=0");assertTrue(items().isEmpty());db.update("update club_member set status=1");db.update("update club set del_flag=1");assertTrue(items().isEmpty());}
    @Test void ownHistoryHasNoCurrentTicketOrLegacyTopicDeadlineRequirement(){fact(1,7,1,1,"2025-10-12 00:00:00");assertEquals(1,items().size());db.update("insert into club values(4,99,0)");db.update("update cms_topic set club_id=4,club_member_only=1 where id=1");db.update("insert into cms_registration values(7,1,1,1,2,2,'2020-01-01')");assertEquals(1,items().size());db.update("update cms_registration set payment_status=3");assertTrue(items().isEmpty());}
    @Test void sharedCircleGateRequiresRecentReviewAndThreeToFourCurrentMerchants(){fact(1,7,1,1,"2025-10-12 00:00:00");db.update("update cms_topic set circle_theme_code='FITNESS',circle_reviewed_at=now() where id=1");assertTrue(items().isEmpty());for(int i=1;i<=3;i++){db.update("insert into cms_topic_chapter values(?,1)",i);db.update("insert into merchant_chapter_offer values(?,?,?,1,1,null)",i,i,i);db.update("insert into merchant_circle_supply_profile values(?,1,now())",i);}assertEquals(1,items().size());db.update("update merchant_circle_supply_profile set is_current=0 where offer_id=1");assertTrue(items().isEmpty());}
    @Test void currentMetadataOnlyAndNoImageOrSnapshotLeakWhenVisibilityChanges(){fact(1,7,1,1,"2025-10-12 00:00:00");Map<?,?> item=(Map<?,?>)items().get(0);assertEquals("当前路线1",item.get("title"));assertFalse(item.containsKey("imageUrl"));assertFalse(item.containsKey("configSnapshot"));db.update("update cms_topic set name='新名称' where id=1");assertEquals("新名称",((Map<?,?>)items().get(0)).get("title"));db.update("update cms_topic set del_flag=1 where id=1");assertTrue(items().isEmpty());}
    @Test void ownerCannotReuseAnotherOwnersCursor(){fact(1,7,1,1,"2025-10-12 00:00:00");fact(2,7,1,1,"2024-10-12 00:00:00");String cursor=(String)page(7L,"10","12",null,"1").get("nextCursor");assertThrows(com.chengyinhub.common.exception.ServiceException.class,()->page(8L,"10","12",cursor,"1"));}
}
