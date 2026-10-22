package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.ClubCalendarMapper;
import com.chengyinhub.business.service.impl.ClubCalendarServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W10a #19:俱乐部月历按月读真实场次 —— 跑真 SQL(H2 MySQL 模式),不是 mock 自证。 */
class ClubCalendarServiceTest {
    private static final String XML = "mapper/business/ClubCalendarMapper.xml";
    /** 仅成员主题闸 clubMemberOnlyGate 住在这里;月历 SQL 跨命名空间 include 它。 */
    private static final String TOPIC_XML = "mapper/business/CmsTopicMapper.xml";
    private static final long VIEWER = 900L;
    private static final long TOPIC_AUTHOR = 500L;
    private static final long CLUB_OWNER = 700L;
    private static final long CLUB_MEMBER = 600L;
    private static final long LEFT_MEMBER = 601L;
    private static final long PAID_OUTSIDER = 650L;

    private SqlSession session;
    private ClubCalendarServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_calendar;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        for (String resource : new String[]{TOPIC_XML, XML}) {
            try (InputStream input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("drop all objects");
            st.execute("create table cms_topic(id bigint primary key, del_flag int, status int, user_status int, "
                    + "club_id bigint, club_member_only int, member_id bigint)");
            st.execute("create table cms_activity(id bigint primary key, name varchar(100), topic_id bigint, club_id bigint, "
                    + "status bigint, publish_status bigint, del_flag int, cancel_time timestamp, start_date timestamp, "
                    + "end_date timestamp, create_time timestamp, update_time timestamp)");
            st.execute("create table club(id bigint primary key, member_id bigint, del_flag int)");
            st.execute("create table club_member(id bigint primary key, club_id bigint, member_id bigint, status int, del_flag int)");
            st.execute("create table cms_registration(id bigint primary key, member_id bigint, owner_type int, owner_id bigint, "
                    + "topic_id bigint, payment_status int, registration_status int)");
            st.execute("insert into cms_topic values (11,0,1,1,7,0,1),(12,0,2,1,7,0,1)");
            st.execute("insert into cms_activity values "
                    // 公开:本月两场(同日 + 跨日),updatedAt 取较新者
                    + "(101,'夜走',11,7,1,1,0,null,'2028-02-29 19:00:00','2028-02-29 23:59:59','2028-02-01 10:00:00','2028-02-20 08:00:00'),"
                    + "(102,'晨跑',11,7,1,1,0,null,'2028-02-01 00:00:00','2028-02-01 23:59:59','2028-02-01 09:00:00',null),"
                    // 待审(只给治理者)
                    + "(103,'待审场',11,7,0,0,0,null,'2028-02-10 10:00:00',null,'2028-02-05 10:00:00','2028-02-05 10:00:00'),"
                    // 已取消(只给治理者)
                    + "(104,'取消场',11,7,1,1,0,'2028-02-06 10:00:00','2028-02-11 10:00:00',null,'2028-02-05 10:00:00','2028-02-06 10:00:00'),"
                    // 父主题被驳回 ⇒ 公开不可见
                    + "(105,'驳回主题场',12,7,1,1,0,null,'2028-02-12 10:00:00',null,'2028-02-05 10:00:00','2028-02-05 10:00:00'),"
                    // 已删除 ⇒ 谁都看不到
                    + "(106,'删除场',11,7,1,1,1,null,'2028-02-13 10:00:00',null,'2028-02-05 10:00:00','2028-02-05 10:00:00'),"
                    // 月界:3 月 1 日 00:00 不属于 2 月
                    + "(107,'下月',11,7,1,1,0,null,'2028-03-01 00:00:00',null,'2028-02-05 10:00:00','2028-02-05 10:00:00'),"
                    // 别的俱乐部
                    + "(108,'别家',11,8,1,1,0,null,'2028-02-14 10:00:00',null,'2028-02-05 10:00:00','2028-02-05 10:00:00')");
        }
        service = new ClubCalendarServiceImpl(session.getMapper(ClubCalendarMapper.class));
    }

    @AfterEach
    void tearDown() {
        session.rollback();
        session.close();
    }

    @Test
    void publicViewerGetsOnlyLiveSessionsOfThisClubInThisMonthWithDatesAndUpdatedAt() {
        Map<String, Object> out = service.monthSessions(7L, "2028-02", false, VIEWER);

        assertEquals("2028-02", out.get("month"));
        assertEquals("PUBLIC", out.get("scope"));
        assertEquals(Boolean.FALSE, out.get("truncated"));
        List<Map<String, Object>> sessions = sessions(out);
        assertEquals(List.of(102L, 101L), ids(sessions), "按开始时间升序;待审/取消/驳回主题/已删/下月/别家都不出现");
        Map<String, Object> leap = sessions.get(1);
        assertEquals("2028-02-29", leap.get("date"));
        assertEquals(11L, leap.get("topicId"));
        assertEquals("PUBLISHED", leap.get("state"));
        assertEquals("2028-02-20 08:00:00", leap.get("updatedAt"));
        assertEquals("2028-02-01 09:00:00", sessions.get(0).get("updatedAt"), "update_time 为空退回 create_time");
        assertEquals("2028-02-20 08:00:00", out.get("updatedAt"), "整月 updatedAt = 场次里最新的一次变更");
    }

    @Test
    void governorAlsoSeesPendingAndCancelledButFlaggedNeverDeleted() {
        List<Map<String, Object>> sessions = sessions(service.monthSessions(7L, "2028-02", true, CLUB_OWNER));

        assertEquals(List.of(102L, 103L, 104L, 105L, 101L), ids(sessions));
        Map<Long, Object> states = sessions.stream().collect(Collectors.toMap(
                s -> (Long) s.get("activityId"), s -> s.get("state")));
        assertEquals("UNPUBLISHED", states.get(103L));
        assertEquals("CANCELLED", states.get(104L));
        assertEquals("PUBLISHED", states.get(105L));
    }

    @Test
    void emptyMonthIsAnEmptyListNotAGuess() {
        Map<String, Object> out = service.monthSessions(7L, "2028-04", false, VIEWER);
        assertTrue(sessions(out).isEmpty());
        assertNull(out.get("updatedAt"));
        assertFalse((Boolean) out.get("truncated"));
    }

    @Test
    void malformedMonthOrClubIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(7L, "2028-13", false, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(7L, "2028-2", false, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(7L, null, false, VIEWER));
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(null, "2028-02", false, VIEWER));
    }

    @Test
    void moreThanTheCapIsMarkedTruncated() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            StringBuilder sql = new StringBuilder("insert into cms_activity values ");
            for (int i = 0; i <= IClubCalendarService.MAX_SESSIONS_PER_MONTH; i++) {
                if (i > 0) sql.append(',');
                sql.append("(").append(1000 + i).append(",'批量',11,9,1,1,0,null,'2028-05-02 10:00:00',null,")
                        .append("'2028-05-01 10:00:00','2028-05-01 10:00:00')");
            }
            st.execute(sql.toString());
        }
        Map<String, Object> out = service.monthSessions(9L, "2028-05", false, VIEWER);
        assertEquals(Boolean.TRUE, out.get("truncated"), "超上限必须如实标截断,前端不能当完整排期");
        assertEquals(IClubCalendarService.MAX_SESSIONS_PER_MONTH, sessions(out).size());
    }

    /**
     * Batch-2 B1:公开俱乐部里「仅俱乐部成员可见」主题的场次,月历公开视角不得给非成员;
     * 判据复用 CmsTopicMapper.clubMemberOnlyGate(成员 / 主理人 / 主题作者 / 买过的人可见,未登录 fail-closed)。
     */
    @Test
    void membersOnlyTopicSessionsAreHiddenFromNonMembersButShownToMembersOwnerAndGovernor() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into cms_topic values (13,0,1,1,7,1," + TOPIC_AUTHOR + ")");
            st.execute("insert into club values (7," + CLUB_OWNER + ",0)");
            st.execute("insert into club_member values (1,7," + CLUB_MEMBER + ",1,0),(2,7," + LEFT_MEMBER + ",0,0)");
            st.execute("insert into cms_activity values (109,'成员专场',13,7,1,1,0,null,'2028-02-15 10:00:00',null,"
                    + "'2028-02-05 10:00:00','2028-02-05 10:00:00')");
            st.execute("insert into cms_registration values (1," + PAID_OUTSIDER + ",2,109,13,2,2)");
        }

        assertEquals(List.of(102L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, VIEWER))),
                "非成员:仅成员主题的场次名/时间都不能出现");
        assertEquals(List.of(102L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, LEFT_MEMBER))),
                "已退出(status≠1)的成员按非成员处理");
        assertEquals(List.of(102L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, null))),
                "看的人缺失 ⇒ fail-closed");
        assertEquals(List.of(102L, 109L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, CLUB_MEMBER))),
                "在册成员看得到");
        assertEquals(List.of(102L, 109L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, CLUB_OWNER))),
                "主理人看得到");
        assertEquals(List.of(102L, 109L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, TOPIC_AUTHOR))),
                "主题作者看得到");
        assertEquals(List.of(102L, 109L, 101L), ids(sessions(service.monthSessions(7L, "2028-02", false, PAID_OUTSIDER))),
                "已付款报名的人保留可见,与主题列表同口径");
        assertTrue(ids(sessions(service.monthSessions(7L, "2028-02", true, CLUB_OWNER))).contains(109L),
                "治理者视角不受此闸影响");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sessions(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("sessions");
    }

    private static List<Long> ids(List<Map<String, Object>> sessions) {
        return sessions.stream().map(s -> (Long) s.get("activityId")).collect(Collectors.toList());
    }
}
