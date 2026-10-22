package com.chengyinhub.business.service;

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

    private SqlSession session;
    private ClubCalendarServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_calendar;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("drop all objects");
            st.execute("create table cms_topic(id bigint primary key, del_flag int, status int, user_status int)");
            st.execute("create table cms_activity(id bigint primary key, name varchar(100), topic_id bigint, club_id bigint, "
                    + "status bigint, publish_status bigint, del_flag int, cancel_time timestamp, start_date timestamp, "
                    + "end_date timestamp, create_time timestamp, update_time timestamp)");
            st.execute("insert into cms_topic values (11,0,1,1),(12,0,2,1)");
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
        Map<String, Object> out = service.monthSessions(7L, "2028-02", false);

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
        List<Map<String, Object>> sessions = sessions(service.monthSessions(7L, "2028-02", true));

        assertEquals(List.of(102L, 103L, 104L, 105L, 101L), ids(sessions));
        Map<Long, Object> states = sessions.stream().collect(Collectors.toMap(
                s -> (Long) s.get("activityId"), s -> s.get("state")));
        assertEquals("UNPUBLISHED", states.get(103L));
        assertEquals("CANCELLED", states.get(104L));
        assertEquals("PUBLISHED", states.get(105L));
    }

    @Test
    void emptyMonthIsAnEmptyListNotAGuess() {
        Map<String, Object> out = service.monthSessions(7L, "2028-04", false);
        assertTrue(sessions(out).isEmpty());
        assertNull(out.get("updatedAt"));
        assertFalse((Boolean) out.get("truncated"));
    }

    @Test
    void malformedMonthOrClubIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(7L, "2028-13", false));
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(7L, "2028-2", false));
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(7L, null, false));
        assertThrows(IllegalArgumentException.class, () -> service.monthSessions(null, "2028-02", false));
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
        Map<String, Object> out = service.monthSessions(9L, "2028-05", false);
        assertEquals(Boolean.TRUE, out.get("truncated"), "超上限必须如实标截断,前端不能当完整排期");
        assertEquals(IClubCalendarService.MAX_SESSIONS_PER_MONTH, sessions(out).size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sessions(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("sessions");
    }

    private static List<Long> ids(List<Map<String, Object>> sessions) {
        return sessions.stream().map(s -> (Long) s.get("activityId")).collect(Collectors.toList());
    }
}
