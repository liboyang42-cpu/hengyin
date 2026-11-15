package com.chengyinhub.business.gamemech;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.gamemech.mapper.GameMechanicsMapper;
import com.chengyinhub.business.locationtrust.LocationFix;
import com.chengyinhub.business.locationtrust.LocationTrustDecision;
import com.chengyinhub.business.locationtrust.LocationTrustPolicy;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.friend.FriendService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Runs the G2 migration's tables and every GameMechanicsMapper statement used by the services on H2 (MySQL mode),
 * through the real services with location trust, points, friends and notifications mocked.
 */
class GameMechanicsH2Test {
    private DriverManagerDataSource ds;
    private SqlSession session;
    private GameMechanicsMapper mapper;
    private LocationTrustService trust;
    private IPointsAwardService points;
    private INotifyService notify;
    private FriendService friends;

    @BeforeEach
    void setUp() throws Exception {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:gm_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        ds.setUsername("sa");
        String sql = Files.readString(Path.of("sql/migration_20261115_game_mechanics.sql"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("CREATE TABLE IF NOT EXISTS[\\s\\S]*?\\) ENGINE=InnoDB[^;]*;").matcher(sql);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            int tables = 0;
            while (m.find()) {
                s.execute(m.group().replaceAll("\\) ENGINE=InnoDB[^;]*;", ")"));
                tables++;
            }
            assertEquals(7, tables);
            s.execute("create table cms_topic (id bigint primary key, name varchar(255), status tinyint, member_id bigint)");
            s.execute("create table cms_topic_node (id bigint primary key, topic_id bigint, status tinyint, latitude varchar(255), longitude varchar(255))");
            s.execute("create table ums_member (id bigint primary key, nickname varchar(64))");
            s.execute("create table club (id bigint primary key, member_id bigint, status tinyint, del_flag tinyint)");
            s.execute("create table club_member (id bigint primary key, club_id bigint, member_id bigint, status tinyint, del_flag tinyint)");
            s.execute("create table topic_route_session (id bigint auto_increment primary key, member_id bigint, topic_id bigint,"
                    + " status tinyint, create_time datetime(3), completed_at datetime(3))");
            s.execute("insert into cms_topic values (5, '西湖一圈', 1, 100)");
            s.execute("insert into cms_topic_node values (51, 5, 1, '30.250000', '120.150000')");
            s.execute("insert into ums_member values (100, '阿青'), (200, '小周')");
        }
        Configuration configuration = new Configuration(new Environment("gm", new JdbcTransactionFactory(), ds));
        configuration.addMapper(GameMechanicsMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        session = factory.openSession(true);
        mapper = session.getMapper(GameMechanicsMapper.class);
        trust = mock(LocationTrustService.class);
        when(trust.assess(anyLong(), any(), any(), anyString(), any())).thenReturn(
                new LocationTrustService.Assessment(LocationTrustPolicy.Verdict.accept(), LocationTrustDecision.ACCEPT, 9L));
        points = mock(IPointsAwardService.class);
        when(points.award(anyLong(), eq(TreasureHuntService.POINTS_EVENT), anyLong(), anyString())).thenReturn(30);
        notify = mock(INotifyService.class);
        friends = mock(FriendService.class);
    }

    @AfterEach
    void tearDown() { session.close(); }

    private Map<String, Object> item(String name, double lat, double lng) {
        Map<String, Object> i = new LinkedHashMap<>();
        i.put("name", name); i.put("clue", "线索:" + name); i.put("lat", lat); i.put("lng", lng); i.put("radiusM", 50);
        return i;
    }

    private Map<String, Object> huntBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "断桥寻宝");
        body.put("items", List.of(item("铜铃", 30.2600, 120.1500), item("木牌", 30.2700, 120.1600)));
        return body;
    }

    @Test
    void creatorDraftReviewThenLocationTrustedFindsCompleteOnce() {
        TreasureHuntService svc = new TreasureHuntService(mapper, trust, points);
        Map<String, Object> saved = svc.creatorSave(100L, huntBody());
        Long id = (Long) saved.get("id");
        assertEquals(TreasureHuntService.DRAFT, saved.get("status"));
        assertThrows(ServiceException.class, () -> svc.playerDetail(id, 200L), "drafts are not public");
        svc.creatorSubmit(100L, id);
        assertThrows(ServiceException.class, () -> svc.creatorSave(100L, with(huntBody(), "id", id)), "in review is locked");
        svc.adminDecide(id, "approve", null, "ops");
        assertEquals(1, svc.liveList(null, 200L).size());

        Map<String, Object> detail = svc.playerDetail(id, 200L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertFalse(items.get(0).containsKey("name"), "name hidden before the find");
        assertFalse(items.get(0).containsKey("lat"), "exact spot never sent");

        Map<String, Object> miss = svc.find(id, 200L, LocationFix.of(30.2800, 120.1800));
        assertEquals(false, miss.get("found"));
        Map<String, Object> first = svc.find(id, 200L, LocationFix.of(30.26001, 120.15001));
        assertEquals(true, first.get("found"));
        assertEquals(false, first.get("completed"));
        Map<String, Object> again = svc.find(id, 200L, LocationFix.of(30.26001, 120.15001));
        assertEquals(false, again.get("found"), "found items do not count twice");
        Map<String, Object> last = svc.find(id, 200L, LocationFix.of(30.27, 120.16));
        assertEquals(true, last.get("completed"));
        assertEquals(30, last.get("rewardPoints"));
        verify(points, times(1)).award(eq(200L), eq(41L), eq(id), anyString());
        assertEquals(1, svc.adminList(null).get(0).get("finishers"));
    }

    @Test
    void withheldFixIsNotAFind() {
        TreasureHuntService svc = new TreasureHuntService(mapper, trust, points);
        Long id = (Long) svc.adminSave(huntBody(), "ops").get("id");
        svc.adminDecide(id, "approve", null, "ops");
        when(trust.assess(anyLong(), any(), any(), anyString(), any())).thenReturn(new LocationTrustService.Assessment(
                new LocationTrustPolicy.Verdict(LocationTrustDecision.ACCEPT_NO_REWARD, List.of(LocationTrustPolicy.SIMULATED), null),
                LocationTrustDecision.ACCEPT_NO_REWARD, 1L));
        Map<String, Object> out = svc.find(id, 200L, LocationFix.of(30.26, 120.15));
        assertEquals(true, out.get("uncredited"));
        assertTrue(mapper.selectFinds(id, 200L).isEmpty());
    }

    @Test
    void duelComparesServerCompletionsAndHidesPeerTimeUntilDecided() throws Exception {
        when(friends.relationship(100L, 200L)).thenReturn(Map.of("state", 1));
        DuelService svc = new DuelService(mapper, friends, notify);
        long now = System.currentTimeMillis();
        svc.clock = () -> now;
        Map<String, Object> created = svc.challenge(100L, 200L, 5L);
        Long id = (Long) created.get("id");
        assertEquals("FRIEND", created.get("relation"));
        verify(notify).notifyGameDuel(eq(200L), eq(id), contains("阿青"));
        assertThrows(ServiceException.class, () -> svc.challenge(100L, 200L, 5L), "one open duel per pair and journey");
        assertThrows(ServiceException.class, () -> svc.respond(100L, id, true), "challenger cannot accept");
        svc.respond(200L, id, true);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("insert into topic_route_session(member_id, topic_id, status, create_time, completed_at)"
                    + " values (100, 5, 1, dateadd('SECOND', 5, now()), dateadd('SECOND', 905, now()))");
        }
        session.clearCache(); // direct JDBC writes bypass the MyBatis local cache (Spring uses one session per call)
        svc.clock = () -> now + 3600_000L;
        Map<String, Object> half = svc.detail(100L, id);
        assertEquals("ACTIVE", half.get("status"));
        assertNotNull(half.get("mySeconds"));
        assertFalse(svc.detail(200L, id).containsKey("peerSeconds"), "peer time hidden while running");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("insert into topic_route_session(member_id, topic_id, status, create_time, completed_at)"
                    + " values (200, 5, 1, dateadd('SECOND', 10, now()), dateadd('SECOND', 610, now()))");
        }
        session.clearCache();
        Map<String, Object> done = svc.detail(200L, id);
        assertEquals("DONE", done.get("status"));
        assertEquals("WON", done.get("outcome"));
        assertEquals("LOST", svc.detail(100L, id).get("outcome"));
        verify(notify, times(2)).notifyGameDuel(anyLong(), eq(id), contains("有结果"));
    }

    @Test
    void strangersCannotDuelButClubMatesCan() throws Exception {
        when(friends.relationship(anyLong(), anyLong())).thenReturn(Map.of("state", 0));
        DuelService svc = new DuelService(mapper, friends, notify);
        assertThrows(ServiceException.class, () -> svc.challenge(100L, 200L, 5L));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("insert into club values (1, 100, 1, 0)");
            s.execute("insert into club_member values (1, 1, 200, 1, 0)");
        }
        session.clearCache();
        assertEquals("CLUB", svc.challenge(100L, 200L, 5L).get("relation"));
    }

    @Test
    void walkTestGateRecordsDeskAndOnsiteAndHonoursModeAndWaiver() {
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(WalkTestService.CONFIG_KEY)).thenReturn("ENFORCE");
        WalkTestService svc = new WalkTestService(mapper, trust, config);
        CmsTopic topic = new CmsTopic();
        topic.setId(5L);
        topic.setMemberId(100L);
        assertThrows(ServiceException.class, () -> svc.assertPublishable(topic));
        assertNotNull(svc.checkMessage(5L));
        svc.recordDesk(100L, topic, 1, "排练");
        assertThrows(ServiceException.class, () -> svc.recordOnsite(100L, topic, LocationFix.of(30.30, 120.20), null), "too far");
        Map<String, Object> s = svc.recordOnsite(100L, topic, LocationFix.of(30.2501, 120.1501), null);
        assertEquals(true, s.get("satisfied"));
        svc.assertPublishable(topic);
        assertNull(svc.checkMessage(5L));
        assertThrows(ServiceException.class, () -> svc.recordDesk(200L, topic, 1, null), "only the author records");

        CmsTopic other = new CmsTopic();
        other.setId(6L);
        assertThrows(ServiceException.class, () -> svc.waive(6L, "", "ops"), "note required");
        assertThrows(ServiceException.class, () -> svc.waive(6L, "老路线", "ops"), "unknown topic");
        assertEquals(true, svc.waive(5L, "官方路线已实地验证", "ops").get("satisfied"));
        assertEquals(1, svc.adminSummary().size());

        when(config.selectConfigByKey(WalkTestService.CONFIG_KEY)).thenReturn("ADVISE");
        CmsTopic fresh = new CmsTopic();
        fresh.setId(7L);
        fresh.setMemberId(100L);
        svc.assertPublishable(fresh); // advise never blocks
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(body);
        copy.put(key, value);
        return copy;
    }
}
