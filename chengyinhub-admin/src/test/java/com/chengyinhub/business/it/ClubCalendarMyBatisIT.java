package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.enums.PublicActivityScope;
import com.chengyinhub.business.domain.vo.PublicActivityListVO;
import com.chengyinhub.business.mapper.ClubCalendarMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.ClubCalendarService;
import com.chengyinhub.business.service.MysqlIntegrationTestConfig;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.framework.config.ApplicationConfig;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL on the coordinator's isolated test VPS; never falls back to a local database. */
class ClubCalendarMyBatisIT {
    private JdbcTemplate jdbc;
    private ClubCalendarService service;
    private CmsActivityServiceImpl original;

    @BeforeEach
    void setup() throws Exception {
        assertNotNull(System.getenv("CHENGYIN_TEST_MYSQL_HOST"), "Explicit test VPS configuration required");
        MysqlIntegrationTestConfig target = MysqlIntegrationTestConfig.fromEnvironment("calendar");
        try (Connection c = DriverManager.getConnection(target.adminUrl(), target.user(), target.password());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + target.databaseName() + "`");
            s.execute("CREATE DATABASE `" + target.databaseName() + "` CHARACTER SET utf8mb4");
        }
        // Same JDBC DATETIME interpretation as application-druid.yml; application JSON uses Shanghai too.
        DriverManagerDataSource ds = new DriverManagerDataSource(
                target.databaseUrl().replace("connectionTimeZone=LOCAL", "connectionTimeZone=Asia/Shanghai"),
                target.user(), target.password());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table cms_activity(id bigint primary key,member_id bigint,topic_id bigint,club_id bigint,"
                + "name varchar(100),start_date datetime,end_date datetime,status int,publish_status int,del_flag int,"
                + "cancel_time datetime,description text,img_url varchar(200),img_arr text,category_ids varchar(100),"
                + "address_name varchar(100),longitude varchar(30),latitude varchar(30),address varchar(100),"
                + "reason varchar(100),template_id bigint,create_by varchar(64),create_time datetime,update_by varchar(64),update_time datetime)");
        jdbc.execute("create table cms_topic(id bigint primary key,club_id bigint,del_flag int,product_type int)");
        jdbc.execute("create table club(id bigint primary key,member_id bigint,public_visible int,del_flag int)");
        jdbc.execute("create table club_member(id bigint primary key,club_id bigint,member_id bigint,status int,del_flag int,role int,join_time datetime)");
        jdbc.update("insert into club values(7,1,0,0),(8,3,1,0),(9,1,0,2)");
        jdbc.update("insert into club_member(id,club_id,member_id,status,del_flag) values(1,7,2,1,0),(2,7,4,0,0),(3,7,5,1,2)");
        jdbc.update("insert into cms_topic values(1,7,0,1),(2,9,0,1),(3,7,2,1)");
        Configuration config = new Configuration(new Environment("calendar", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        config.getTypeAliasRegistry().registerAlias("ClubMember", ClubMember.class);
        for (String name : new String[]{"CmsActivity", "ClubCalendar", "ClubMember"}) {
            String resource = "mapper/business/" + name + "Mapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate sql = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        service = new ClubCalendarService();
        ReflectionTestUtils.setField(service, "mapper", sql.getMapper(ClubCalendarMapper.class));
        ReflectionTestUtils.setField(service, "membershipMapper", sql.getMapper(ClubMemberMapper.class));
        original = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(original, "cmsActivityMapper", sql.getMapper(CmsActivityMapper.class));
    }

    private void activity(long id, String start) {
        jdbc.update("insert into cms_activity(id,member_id,topic_id,club_id,name,start_date,end_date,status,publish_status,del_flag,create_time)"
                + " values(?,1,1,7,'scene',?,'2028-03-10 12:00:00',1,1,0,'2028-01-01 00:00:00')", id, start);
    }

    @SuppressWarnings("unchecked")
    private List<PublicActivityListVO> rows(Map<String, Object> page) {
        return (List<PublicActivityListVO>) page.get("rows");
    }

    private List<Long> ids(Map<String, Object> page) {
        return rows(page).stream().map(PublicActivityListVO::getId).collect(Collectors.toList());
    }

    @Test
    void monthEdgesLeapDayAndPublishedJsonUseExistingShanghaiDate() throws Exception {
        activity(1, "2028-01-31 23:59:59");
        activity(2, "2028-02-01 00:00:00");
        activity(3, "2028-02-29 23:59:59");
        activity(4, "2028-03-01 00:00:00");
        activity(5, null);
        activity(6, "2027-12-31 23:59:59");
        activity(7, "2020-02-01 00:00:00");
        jdbc.update("update cms_activity set end_date='2020-02-01 12:00:00' where id=7");
        Map<String, Object> feb = service.month(7L, 2L, "2028-02", null, null);
        assertEquals("CLUB_MEMBER", feb.get("visibilityScope"));
        assertEquals(List.of(2L, 3L), ids(feb));
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new ApplicationConfig().jacksonObjectMapperCustomization().customize(builder);
        String json = builder.build().writeValueAsString(feb);
        assertTrue(json.contains("2028-02-01 00:00:00"));
        assertTrue(json.contains("2028-02-29 23:59:59"));
        assertEquals(List.of(4L), ids(service.month(7L, 2L, "2028-03", null, null)));
        assertEquals(List.of(1L), ids(service.month(7L, 2L, "2028-01", null, null)));
        assertEquals(List.of(6L), ids(service.month(7L, 2L, "2027-12", null, null)));
        assertEquals(List.of(7L), ids(service.month(7L, 2L, "2020-02", null, null)), "Ended sessions remain dated facts, not availability");
        assertTrue(ids(service.month(7L, 2L, "2027-02", null, null)).isEmpty());
    }

    @Test
    void allPagesHaveStableIdsAndExcludeLaterAppends() {
        for (long id = 1; id <= 205; id++) activity(id, "2028-02-15 10:00:00");
        Map<String, Object> first = service.month(7L, 2L, "2028-02", null, null);
        assertEquals(100, rows(first).size());
        assertEquals(true, first.get("hasMore"));
        assertEquals(100L, first.get("nextAfterId"));
        activity(206, "2028-02-15 10:00:00");
        Map<String, Object> second = service.month(7L, 2L, "2028-02", 100L, 205L);
        Map<String, Object> third = service.month(7L, 2L, "2028-02", 200L, 205L);
        assertEquals(101L, rows(second).get(0).getId());
        assertEquals(List.of(201L, 202L, 203L, 204L, 205L), ids(third));
        assertEquals(false, third.get("hasMore"));
        assertEquals(205L, third.get("throughId"));
        assertEquals(206L, service.month(7L, 2L, "2028-02", null, null).get("throughId"));
    }

    @Test
    void visibilityKeepsPublicGatesAndActivityOwnerOrActiveMemberBoundary() {
        for (long id = 1; id <= 9; id++) activity(id, "2028-02-15 10:00:00");
        jdbc.update("update cms_activity set cancel_time=now() where id=2");
        jdbc.update("update cms_activity set del_flag=2 where id=3");
        jdbc.update("update cms_activity set publish_status=0 where id=4");
        jdbc.update("update cms_activity set status=0 where id=5");
        jdbc.update("update cms_activity set club_id=9 where id=6");
        jdbc.update("update cms_activity set topic_id=3 where id=7");
        jdbc.update("update cms_activity set topic_id=2 where id=8");
        jdbc.update("update cms_activity set member_id=6 where id=9");
        assertEquals(List.of(1L, 9L), ids(service.month(7L, 2L, "2028-02", null, null)));
        assertEquals(List.of(1L), ids(service.month(7L, 1L, "2028-02", null, null)));
        assertEquals(List.of(9L), ids(service.month(7L, 6L, "2028-02", null, null)));
        for (Long outsider : new Long[]{null, 3L, 4L, 5L}) {
            Map<String, Object> page = service.month(7L, outsider, "2028-02", null, null);
            assertTrue(rows(page).isEmpty(), "Private club nonmember must not see other creators' sessions");
            assertEquals(0L, page.get("throughId"));
            assertEquals(outsider == null ? "ANONYMOUS" : "ACTIVITY_OWNER", page.get("visibilityScope"));
        }
        assertTrue(rows(service.month(8L, 2L, "2028-02", null, null)).isEmpty());
        assertTrue(original.selectPublicCmsActivityList(new CmsActivity(), PublicActivityScope.PUBLIC_SHELF).isEmpty());
        assertEquals(List.of(1L, 9L), original.selectPublicCmsActivityList(new CmsActivity(), PublicActivityScope.MEMBER_FAVOURITES)
                .stream().map(CmsActivity::getId).sorted().collect(Collectors.toList()));
    }

    @Test
    void malformedAndMismatchedCursorOrChangedVisibilityFailInsteadOfCompleting() {
        for (String month : new String[]{"2028-13", "2028-2", "2028-02-01", "1969-12", "9999-12"})
            assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, month, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.month(0L, 2L, "2028-02", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, "2028-02", -1L, null));
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, "2028-02", 1L, null));
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, "2028-02", 2L, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, "2028-02", 0L, 0L));
        activity(1, "2028-02-01 00:00:00");
        activity(2, "2028-02-28 00:00:00");
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, "2028-03", 1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> service.month(8L, 2L, "2028-02", 1L, 2L));
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 3L, "2028-02", 1L, 2L));
        jdbc.update("update cms_activity set publish_status=0 where id=2");
        assertThrows(IllegalArgumentException.class, () -> service.month(7L, 2L, "2028-02", 1L, 2L));
    }
}
