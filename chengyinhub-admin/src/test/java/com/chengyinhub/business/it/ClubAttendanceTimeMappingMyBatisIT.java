package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventAttendanceCorrection;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.ClubAttendanceCorrectionRequest;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.impl.ClubEventAttendanceServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Connector/J DATETIME mapping; also run with a JVM timezone different from JDBC. */
class ClubAttendanceTimeMappingMyBatisIT {
    private static String fixtureUrl;

    @BeforeAll
    static void createFixture() throws Exception {
        fixtureUrl = MybatisItDatabase.url("attendance");
        try (Connection connection = DriverManager.getConnection(fixtureUrl,
                MybatisItDatabase.user(), MybatisItDatabase.password());
             Statement sql = connection.createStatement()) {
            sql.execute("create table cms_registration (id bigint primary key, owner_type bigint,"
                    + "owner_id bigint, member_id bigint, payment_status int, registration_status int,"
                    + "verification_status int default 0, verification_time datetime)");
            sql.execute("create table ums_member (id bigint primary key, nickname varchar(64),"
                    + "name varchar(64), del_flag int)");
            sql.execute("create table club_lead_checkin (activity_id bigint, member_id bigint, arrived_at datetime)");
            sql.execute("create table player_node_progress (activity_id bigint, member_id bigint,"
                    + "checkin_time datetime, status int, del_flag int)");
            sql.execute("create table club_event_attendance_correction (id bigint auto_increment primary key,"
                    + "club_id bigint, activity_id bigint, member_id bigint, original_arrived boolean,"
                    + "new_arrived boolean, original_arrived_at datetime, new_arrived_at datetime,"
                    + "reason varchar(500), actor_member_id bigint, request_id varchar(64),"
                    + "version int, corrected_at datetime)");
            sql.execute("insert into ums_member values (51,'签到成员','',0),(52,'更正成员','',0)");
            for (long activityId : new long[]{31L, 32L}) {
                sql.execute("insert into cms_registration"
                        + " (id,owner_type,owner_id,member_id,payment_status,registration_status)"
                        + " values (" + activityId * 10 + ",2,"
                        + activityId + ",51,2,2),(" + (activityId * 10 + 1) + ",2,"
                        + activityId + ",52,2,2)");
                sql.execute("insert into club_lead_checkin values (" + activityId
                        + ",51,'2026-10-02 23:32:38'),(" + activityId + ",52,'2026-10-02 23:32:38')");
                sql.execute("insert into club_event_attendance_correction"
                        + " (club_id,activity_id,member_id,new_arrived,new_arrived_at,version)"
                        + " values (21," + activityId + ",51,1,'2026-10-02 23:33:38',1)");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "GMT+8"})
    void arrivalAndCorrectionDatesFollowJdbcTimezone(String jdbcZone) throws Exception {
        String url = fixtureUrl.replace("serverTimezone=GMT%2B8",
                "serverTimezone=" + jdbcZone.replace("+", "%2B"));
        PooledDataSource source = new PooledDataSource("com.mysql.cj.jdbc.Driver", url,
                MybatisItDatabase.user(), MybatisItDatabase.password());
        Configuration configuration = new Configuration(
                new Environment("it", new JdbcTransactionFactory(), source));
        String resource = "mapper/business/ClubEventOpsMapper.xml";
        try (InputStream xml = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Long activityId = "UTC".equals(jdbcZone) ? 31L : 32L;
        Date baseAt = Date.from(LocalDateTime.of(2026, 10, 2, 23, 32, 38)
                .atZone(ZoneId.of(jdbcZone)).toInstant());
        Date correctedAt = new Date(baseAt.getTime() + 60_000L);
        try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            ClubEventOpsMapper mapper = session.getMapper(ClubEventOpsMapper.class);
            Map<String, Object> roster = mapper.selectPaidRoster(activityId).get(0);
            assertDate(baseAt, roster.get("baseArrivedAt"));
            assertDate(correctedAt, roster.get("correctedArrivedAt"));
            Map<String, Object> target = mapper.selectAttendanceTargetForUpdate(activityId, 52L);
            assertDate(baseAt, target.get("baseArrivedAt"));

            IClubAccessService access = mock(IClubAccessService.class);
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            when(access.require(41L, 21L, activityId, ClubPermission.EVENT_CHECKIN))
                    .thenReturn(new ClubAccessContext(41L, 21L, 41L, activityId,
                            Collections.singleton(ClubRoleCode.EVENT_LEAD)));
            CmsActivity activity = new CmsActivity();
            activity.setId(activityId);
            activity.setClubId(21L);
            activity.setDelFlag(0);
            activity.setStartDate(baseAt);
            activity.setEndDate(new Date(baseAt.getTime() + 3_600_000L));
            when(activities.selectCmsActivityById(activityId)).thenReturn(activity);
            ClubEventAttendanceServiceImpl service = new ClubEventAttendanceServiceImpl(access, mapper, activities) {
                @Override protected Date now() { return correctedAt; }
            };
            ClubAttendanceCorrectionRequest request = new ClubAttendanceCorrectionRequest();
            request.setClubId(21L);
            request.setActivityId(activityId);
            request.setMemberId(52L);
            request.setArrived(false);
            request.setReason("现场误扫更正");
            request.setExpectedVersion(0);
            request.setRequestId("attendance-time-" + jdbcZone);
            ClubEventAttendanceCorrection correction = service.correct(41L, request);
            assertEquals(Boolean.TRUE, correction.getOriginalArrived());
            assertEquals(Boolean.FALSE, correction.getNewArrived());
            assertEquals(baseAt, correction.getOriginalArrivedAt(),
                    "original arrival must survive the actual correction insert and readback");
        } finally {
            source.forceCloseAll();
        }
    }

    private void assertDate(Date expected, Object actual) {
        assertTrue(actual instanceof Date, "DATETIME must use the JDBC Date handler, not LocalDateTime");
        assertEquals(expected.getTime(), ((Date) actual).getTime());
    }
}
