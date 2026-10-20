package com.chengyinhub.business.it;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.impl.ClubCrmAppServiceImpl;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CL-08 / F-W5-03 + F-W1-08:「我的项目」主题卡的报名数(CmsActivityServiceImpl.countSignedUpRegistrationsByTopicIds →
 * CmsActivityMapper.selectTopicSignedUpRegistrationCounts)必须与俱乐部主题名册(ClubCrmAppServiceImpl.topicCustomers →
 * ClubCrmMapper.selectTopicCustomers)的「已售」一致。两边都走真实 mapper XML,落在同一个 H2(MySQL 模式)夹具上。
 */
class TopicSignupCountMatchesRosterV2Test {

    private static final long CLUB = 700L;
    private static final long OWNER = 7L;
    private static final long TOPIC = 900L;
    private static final long SESSION = 901L;
    private static final long DELETED_SESSION = 902L;
    private static final long FOREIGN_SESSION = 903L;

    @Test
    void topicCardSignupCountEqualsRosterSoldCount() throws Exception {
        Configuration cfg = new Configuration();
        cfg.getTypeAliasRegistry().registerAlias("CmsActivity", CmsActivity.class);
        cfg.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver", "jdbc:h2:mem:topic_signup_roster_v2;MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1", "sa", "")));
        for (String resource : new String[]{"mapper/business/ClubCrmMapper.xml", "mapper/business/CmsActivityMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        try (SqlSession session = new SqlSessionFactoryBuilder().build(cfg).openSession(true)) {
            try (Statement st = session.getConnection().createStatement()) {
                st.execute("CREATE TABLE cms_topic (id BIGINT PRIMARY KEY, club_id BIGINT, del_flag INT DEFAULT 0,"
                        + " name VARCHAR(64), start_date DATETIME NULL)");
                st.execute("CREATE TABLE cms_activity (id BIGINT PRIMARY KEY, topic_id BIGINT, club_id BIGINT,"
                        + " del_flag INT DEFAULT 0, name VARCHAR(64), start_date DATETIME NULL)");
                st.execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, nickname VARCHAR(64), avatar VARCHAR(255),"
                        + " phone VARCHAR(32), del_flag INT DEFAULT 0)");
                st.execute("CREATE TABLE merchant_review (id BIGINT PRIMARY KEY, registration_id BIGINT,"
                        + " status VARCHAR(16), create_time DATETIME)");
                st.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT, owner_id BIGINT,"
                        + " owner_type INT, payment_status INT, registration_status INT, verification_status INT,"
                        + " verification_time DATETIME NULL, payable_amount DECIMAL(10,2), point_used BIGINT,"
                        + " real_name VARCHAR(64), parent_order_no VARCHAR(64), create_time DATETIME)");

                st.execute("INSERT INTO cms_topic (id, club_id, del_flag, name) VALUES (" + TOPIC + ", " + CLUB + ", 0, 't')");
                st.execute("INSERT INTO cms_activity (id, topic_id, club_id, del_flag, name) VALUES"
                        + " (" + SESSION + ", " + TOPIC + ", " + CLUB + ", 0, 's'),"
                        + " (" + DELETED_SESSION + ", " + TOPIC + ", " + CLUB + ", 2, 'deleted'),"
                        + " (" + FOREIGN_SESSION + ", " + TOPIC + ", 701, 0, 'foreign')");
                st.execute("INSERT INTO ums_member (id, nickname, del_flag) VALUES"
                        + " (101,'a',0),(102,'b',0),(103,'c',0),(104,'d',0),(105,'e',0),(106,'f',0),(107,'g',0),(108,'h',0),(109,'i',2)");
                String cols = "(id, member_id, owner_id, owner_type, payment_status, registration_status,"
                        + " verification_status, payable_amount, point_used, create_time)";
                st.execute("INSERT INTO cms_registration " + cols + " VALUES"
                        + " (1, 101, " + TOPIC + ", 1, 2, 2, 0, 10.00, 0, now())," // 主题直售 已付已报名
                        + " (2, 102, " + SESSION + ", 2, 2, 2, 1, 10.00, 0, now())," // 本俱乐部场次 已付已核
                        + " (3, 103, " + SESSION + ", 2, 2, 2, 0, 0.00, 0, now()),"  // 本俱乐部场次 免费已报名
                        + " (4, 104, " + SESSION + ", 2, 4, 4, 0, 10.00, 0, now())," // 已退款
                        + " (5, 105, " + SESSION + ", 2, 4, 3, 0, 0.00, 0, now()),"  // 免费报名取消
                        + " (6, 106, " + SESSION + ", 2, 1, 1, 0, 10.00, 0, now())," // 拉起支付未付
                        + " (7, 107, " + DELETED_SESSION + ", 2, 2, 2, 0, 10.00, 0, now())," // 已删场次
                        + " (8, 108, " + FOREIGN_SESSION + ", 2, 2, 2, 0, 10.00, 0, now())," // 别家俱乐部场次
                        + " (9, 109, " + SESSION + ", 2, 2, 2, 0, 10.00, 0, now())"); // CL-08:已注销成员的有效报名
            }

            CmsActivityServiceImpl activities = new CmsActivityServiceImpl();
            ReflectionTestUtils.setField(activities, "cmsActivityMapper", session.getMapper(CmsActivityMapper.class));
            Map<Long, Integer> card = activities.countSignedUpRegistrationsByTopicIds(Collections.singletonList(TOPIC));

            ClubCrmAppServiceImpl crm = new ClubCrmAppServiceImpl();
            IClubService clubs = mock(IClubService.class);
            IClubMemberService members = mock(IClubMemberService.class);
            IClubAccessService access = mock(IClubAccessService.class);
            ReflectionTestUtils.setField(crm, "clubCrmMapper", session.getMapper(ClubCrmMapper.class));
            ReflectionTestUtils.setField(crm, "piiCryptoService", new com.chengyinhub.business.security.PiiCryptoService("", ""));
            ReflectionTestUtils.setField(crm, "clubService", clubs);
            ReflectionTestUtils.setField(crm, "clubMemberService", members);
            ReflectionTestUtils.setField(crm, "clubAccessService", access);
            Club club = new Club(); club.setId(CLUB); club.setMemberId(OWNER);
            when(clubs.selectClubById(CLUB)).thenReturn(club);
            when(members.canGovernClub(any(Club.class), eq(OWNER))).thenReturn(true);
            when(access.resolve(eq(OWNER), eq(CLUB), any())).thenReturn(new ClubAccessContext(OWNER, CLUB, OWNER, null,
                    Collections.singleton(ClubRoleCode.CLUB_OWNER)));
            ClubCustomerVO.TopicPage roster = crm.topicCustomers(OWNER, CLUB, TOPIC, "all");

            assertEquals(3, roster.getSoldCount().intValue(), "名单已售:主题直售 1 + 本俱乐部场次 2(已核 + 免费)");
            assertEquals(roster.getSoldCount(), card.getOrDefault(TOPIC, 0),
                    "主题卡报名数必须与名单已售一致:退款/免费取消/未付/已删场次/别家场次/已注销成员两边都不计");
        }
    }
}
