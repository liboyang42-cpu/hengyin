package com.chengyinhub.business.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FE-STORY 5.5 / T-B5:队友到访的节点 = 同票(购票人 + 已领取席位)∪ 同队已加入成员 的到店行,不含本人。
 * 真 MyBatis XML + H2(MySQL 模式);不冒充生产库证据。
 */
class FreeExploreTeamVisitMyBatisTest {

    private static final long TOPIC = 70L;
    private static final long ACTIVITY = 71L;
    private static final long BUYER = 1L;
    private static final long INVITEE = 2L;
    private static final long RELEASED = 3L;
    private static final long TEAMMATE = 4L;
    private static final long STRANGER = 5L;

    private JdbcTemplate jdbc;
    private PlayerNodeProgressMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:fe_team_visit_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table player_node_progress(id bigint auto_increment primary key, play_instance_id varchar(64),"
                + " member_id bigint, activity_id bigint, topic_id bigint, node_id bigint, status int, checkin_time timestamp,"
                + " pic_url varchar(255), review_status int, longitude varchar(32), latitude varchar(32), puzzle_score int,"
                + " completion_mode varchar(16), del_flag int default 0, create_time timestamp)");
        jdbc.execute("create table cms_registration(id bigint primary key, member_id bigint, owner_type bigint, owner_id bigint, payment_status int)");
        jdbc.execute("create table cms_registration_seat_invitation(id bigint primary key, registration_id bigint, invitee_member_id bigint, status int)");
        jdbc.execute("create table play_team(id bigint primary key, owner_type int, owner_id bigint, status int)");
        jdbc.execute("create table play_team_member(id bigint primary key, team_id bigint, member_id bigint, status int)");

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/PlayerNodeProgressMapper.xml"));
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PlayerNodeProgressMapper.class);

        // 购票人的票(2 席):INVITEE 已领取,RELEASED 的邀请已释放(status=3)
        jdbc.update("insert into cms_registration values(100, ?, 2, ?, 2)", BUYER, ACTIVITY);
        jdbc.update("insert into cms_registration_seat_invitation values(1, 100, ?, 1)", INVITEE);
        jdbc.update("insert into cms_registration_seat_invitation values(2, 100, ?, 3)", RELEASED);
        // 同场另一支队伍:BUYER 与 TEAMMATE 都已加入
        jdbc.update("insert into play_team values(9, 2, ?, 2)", ACTIVITY);
        jdbc.update("insert into play_team_member values(1, 9, ?, 1)", BUYER);
        jdbc.update("insert into play_team_member values(2, 9, ?, 1)", TEAMMATE);
        // 每人在不同节点到店
        visit(BUYER, 501L);
        visit(INVITEE, 502L);
        visit(RELEASED, 503L);
        visit(TEAMMATE, 504L);
        visit(STRANGER, 505L);
        jdbc.update("insert into player_node_progress(member_id, activity_id, topic_id, node_id, status, del_flag)"
                + " values(?, ?, ?, 506, 0, 0)", INVITEE, ACTIVITY, TOPIC);   // 没到店的行不算
    }

    private void visit(long member, long node) {
        jdbc.update("insert into player_node_progress(member_id, activity_id, topic_id, node_id, status, checkin_time, del_flag)"
                + " values(?, ?, ?, ?, 0, current_timestamp, 0)", member, ACTIVITY, TOPIC, node);
    }

    private Set<Long> team(long member) {
        List<Long> ids = mapper.selectTeamVisitedNodeIds(member, ACTIVITY, TOPIC);
        return ids == null ? Collections.<Long>emptySet() : new HashSet<>(ids);
    }

    @Test
    void buyerSeesClaimedSeatAndTeamVisitsButNotReleasedSeatOrStrangers() {
        assertEquals(new HashSet<>(java.util.Arrays.asList(502L, 504L)), team(BUYER));
    }

    @Test
    void inviteeSeesBuyerVisitsThroughTheSharedTicket() {
        Set<Long> seen = team(INVITEE);
        assertTrue(seen.contains(501L), "受邀人看得到购票人到访的地点");
        assertTrue(!seen.contains(502L), "本人的到访不算队友到访");
        assertTrue(!seen.contains(503L) && !seen.contains(505L));
    }

    @Test
    void releasedSeatAndStrangerShareNothing() {
        assertEquals(Collections.emptySet(), team(RELEASED));
        assertEquals(Collections.emptySet(), team(STRANGER));
    }

    @Test
    void ownVisitsAreReadableForExpiredHistory() {
        assertEquals(1, mapper.selectOwnVisits(INVITEE, ACTIVITY, TOPIC).size(), "只认到店行");
    }
}
