package com.chengyinhub.business.creatorearnings;

import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.CreatorEarningMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Runs CreatorEarningMapper.xml on H2 (MySQL mode) under the real service: accrual from every source, daily caps,
 * self-exclusion, held money, refund reversal, the creator_income_detail mirror, replay safety and the app reads.
 */
public class CreatorEarningsAccrualMyBatisTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 14);
    private static final String ORDER = "WKO_0123456789abcdef0123456789abcdef";
    private static final String VERSION = "WKV_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private DriverManagerDataSource ds;
    private SqlSession session;
    private UmsMemberMapper members;
    private IUmsMemberService memberService;
    private IUmsMemberPointsDetailService pointsDetails;
    private CreatorEarningsService service;

    /** H2 stand-in for MySQL json_extract(doc, '$.field'). */
    public static String jsonExtract(String json, String path) {
        if (json == null || path == null || !path.startsWith("$.")) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(path.substring(2)) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static String jsonUnquote(String value) { return value; }

    private static Timestamp at(int hour, int minute) {
        return Timestamp.from(DAY.atTime(LocalTime.of(hour, minute)).atZone(CreatorEarningsService.ZONE).toInstant());
    }

    @BeforeEach
    void setUp() throws Exception {
        ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:creator_earnings_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE");
        ds.setUsername("sa");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            String cls = CreatorEarningsAccrualMyBatisTest.class.getName();
            st.execute("create alias json_extract for \"" + cls + ".jsonExtract\"");
            st.execute("create alias json_unquote for \"" + cls + ".jsonUnquote\"");
            st.execute("create table creator_earning_ledger (id bigint auto_increment primary key, event_key varchar(191) not null unique,"
                    + " event_type varchar(16) not null, creator_member_id bigint not null, actor_member_id bigint, item_kind varchar(16) not null,"
                    + " item_ref varchar(64) not null, item_title varchar(255), source_ref varchar(64) not null, points int not null default 0,"
                    + " capped tinyint not null default 0, gross_minor bigint not null default 0, creator_share_bps int not null default 0,"
                    + " creator_minor bigint not null default 0, currency varchar(8), money_state varchar(20) not null default 'NONE',"
                    + " income_order_key char(32), occur_time timestamp(3) not null, occur_date date not null, create_time timestamp(3))");
            st.execute("create table template_adoption_receipt (actor_member_id bigint, request_id varchar(96), source_id bigint,"
                    + " status varchar(16), created_at timestamp(3))");
            st.execute("create table cms_template_library (id bigint primary key, member_id bigint, member_template_id bigint, title varchar(100))");
            st.execute("create table cms_member_template (id bigint primary key, original_template_id bigint, member_id bigint)");
            st.execute("create table cms_topic_node (id bigint primary key, template_id bigint)");
            st.execute("create table player_node_progress (id bigint primary key, member_id bigint, node_id bigint, status tinyint,"
                    + " del_flag int, checkin_time timestamp)");
            st.execute("create table workshop_version (version_id varchar(40) primary key, seller_id bigint, content_json varchar(2000))");
            st.execute("create table workshop_order (id bigint auto_increment primary key, order_no varchar(40) unique, buyer_id bigint,"
                    + " version_id varchar(40), amount_fen bigint, amount_milliunits bigint, currency char(3), paid_at timestamp(3),"
                    + " state varchar(20), refunded_at timestamp(3))");
            st.execute("create table workshop_install (id bigint auto_increment primary key, buyer_id bigint, order_no varchar(40),"
                    + " draft_id bigint, installed_at timestamp(3))");
            st.execute("create table workshop_draft_lineage (draft_id bigint primary key, order_no varchar(40))");
            st.execute("create table creator_income_detail (id bigint auto_increment primary key, creator_id bigint not null,"
                    + " source_type varchar(40) not null, source_id bigint not null, idempotency_key varchar(128) unique,"
                    + " amount decimal(10,2) not null default 0, points int, income_status tinyint not null default 0,"
                    + " occur_time datetime not null, create_time datetime, update_time datetime)");
            st.execute("create table creator_profile (id bigint primary key, member_id bigint, apply_status tinyint)");
            st.execute("create table ums_member (id bigint primary key, nickname varchar(64))");
            seed(c);
        }
        Configuration configuration = new Configuration(new Environment("ce", new JdbcTransactionFactory(), ds));
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(CreatorEarningMapper.class);
        String resource = "mapper/business/CreatorEarningMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        members = mock(UmsMemberMapper.class);
        when(members.selectPointsForUpdate(anyLong())).thenReturn(0L);
        memberService = mock(IUmsMemberService.class);
        when(memberService.addPoints(anyLong(), anyInt())).thenReturn(true);
        pointsDetails = mock(IUmsMemberPointsDetailService.class);
        when(pointsDetails.insertUmsMemberPointsDetail(any())).thenReturn(1);
        service = new CreatorEarningsService(session.getMapper(CreatorEarningMapper.class), members, memberService, pointsDetails,
                mock(PlatformTransactionManager.class), 5, 4, 1, 2, 1, 7000, false);
    }

    @AfterEach
    void tearDown() { if (session != null) session.close(); }

    private void seed(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("insert into ums_member values (100,'creator'),(200,'seller'),(300,'p3'),(400,'p4'),(500,'p5')");
            st.execute("insert into creator_profile values (900, 100, 1)");
            st.execute("insert into cms_template_library values (1, 100, 10, 'Old Town Hunt'), (2, null, null, 'Official')");
            st.execute("insert into cms_member_template values (10, 0, 100), (11, 1, 300), (20, 0, 300)");
            st.execute("insert into cms_topic_node values (1, 11), (2, 10), (3, 20)");
            st.execute("insert into workshop_version values ('" + VERSION + "', 200, '{\"title\":\"Paid Hunt\"}')");
        }
        var adopt = c.prepareStatement("insert into template_adoption_receipt values (?,?,?,?,?)");
        Object[][] adoptions = {{300L, "r1", 1L, "COMPLETED", at(9, 0)}, {300L, "r2", 1L, "COMPLETED", at(9, 5)},
                {100L, "r3", 1L, "COMPLETED", at(9, 6)}, {400L, "r4", 1L, "FAILED", at(9, 7)}, {400L, "r5", 2L, "COMPLETED", at(9, 8)}};
        for (Object[] a : adoptions) { for (int i = 0; i < 5; i++) adopt.setObject(i + 1, a[i]); adopt.executeUpdate(); }
        var play = c.prepareStatement("insert into player_node_progress values (?,?,?,1,0,?)");
        Object[][] plays = {{1L, 300L, 1L, at(10, 0)}, {2L, 300L, 2L, at(10, 1)}, {3L, 400L, 1L, at(10, 2)},
                {4L, 100L, 1L, at(10, 3)}, {5L, 500L, 1L, at(10, 4)}, {6L, 400L, 3L, at(10, 5)}};
        for (Object[] p : plays) { for (int i = 0; i < 4; i++) play.setObject(i + 1, p[i]); play.executeUpdate(); }
        var order = c.prepareStatement("insert into workshop_order(order_no,buyer_id,version_id,amount_fen,currency,paid_at,state,refunded_at)"
                + " values (?,300,?,1000,'CNY',?,'REFUNDED',?)");
        order.setString(1, ORDER); order.setString(2, VERSION); order.setTimestamp(3, at(8, 0)); order.setTimestamp(4, at(18, 0));
        order.executeUpdate();
        var install = c.prepareStatement("insert into workshop_install(buyer_id,order_no,draft_id,installed_at) values (300,?,20,?)");
        install.setString(1, ORDER); install.setTimestamp(2, at(8, 30)); install.executeUpdate();
    }

    private List<Map<String, Object>> ledger() {
        return service.adminLedger(null, null);
    }

    private Map<String, Object> event(String key) {
        return ledger().stream().filter(r -> key.equals(r.get("eventKey"))).findFirst().orElseThrow(() -> new AssertionError(key));
    }

    private static long n(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }

    @Test
    void accruesEverySourceOnceWithCapsAndHeldMoney() throws Exception {
        Map<String, Object> first = service.accrueDay(DAY);
        assertEquals(1, first.get("adopt"), "one adoption per (template, member); self and official adoptions do not count");
        assertEquals(1, first.get("install"));
        assertEquals(4, first.get("templatePlay"), "the creator's own play is not recorded");
        assertEquals(1, first.get("workshopPlay"));
        assertEquals(1, first.get("sale"));
        assertEquals(1, first.get("reversal"));
        assertEquals(3, first.get("mirrored"), "only the approved creator's rows that earned something");
        assertEquals("HELD_MONEY_OFF", first.get("moneyState"));

        assertEquals(5, n(event("ADOPT:T1:300"), "points"));
        assertEquals(1, n(event("PLAY:1"), "points"));
        assertEquals(0, n(event("PLAY:2"), "points"), "same player, same template, same day");
        assertEquals(1, n(event("PLAY:2"), "capped"));
        assertEquals(1, n(event("PLAY:3"), "points"));
        assertEquals(1, n(event("PLAY:5"), "capped"), "creator daily cap of 2 rewarded plays");
        assertEquals(1, n(event("PLAY:6"), "points"));
        assertEquals(200L, n(event("PLAY:6"), "creatorId"));
        assertEquals(4, n(event("INSTALL:" + VERSION + ":300"), "points"));

        Map<String, Object> sale = event("SALE:" + ORDER);
        assertEquals(0, n(sale, "points"));
        assertEquals(1000, n(sale, "grossMinor"));
        assertEquals(700, n(sale, "creatorMinor"));
        assertEquals("HELD_MONEY_OFF", sale.get("moneyState"));
        assertEquals(-700, n(event("SALE_REVERSAL:" + ORDER), "creatorMinor"));

        ArgumentCaptor<UmsMemberPointsDetail> details = ArgumentCaptor.forClass(UmsMemberPointsDetail.class);
        verify(pointsDetails, times(5)).insertUmsMemberPointsDetail(details.capture());
        for (UmsMemberPointsDetail d : details.getAllValues()) {
            assertEquals(CreatorEarningsPolicy.POINTS_EVENT_TYPE, d.getEventType());
            assertTrue(d.getRewardBusinessKey().startsWith("creator-earning:"));
        }
        verify(memberService).addPoints(100L, 5);
        verify(memberService, times(2)).addPoints(100L, 1);
        verify(memberService).addPoints(200L, 4);
        verify(memberService).addPoints(200L, 1);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*), sum(points), min(creator_id), max(creator_id) from creator_income_detail")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertEquals(7, rs.getInt(2));
            assertEquals(900, rs.getLong(3), "creator_income_detail is keyed by creator_profile.id");
            assertEquals(900, rs.getLong(4));
        }

        Map<String, Object> replay = service.accrueDay(DAY);
        for (String k : List.of("adopt", "install", "templatePlay", "workshopPlay", "sale", "reversal", "mirrored"))
            assertEquals(0, replay.get(k), "replay writes nothing: " + k);
        verify(pointsDetails, times(5)).insertUmsMemberPointsDetail(any());
        assertEquals(0, service.accrueDay(DAY.plusDays(1)).get("templatePlay"), "another day sees no events");
    }

    @Test
    void creatorReadsAndIncomeFactsMatchTheAppContract() {
        service.accrueDay(DAY);

        Map<String, Object> mine = service.mine(100L);
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) mine.get("summary");
        assertEquals(7L, summary.get("points"));
        assertEquals(4L, summary.get("plays"));
        assertEquals(2L, summary.get("cappedPlays"));
        assertEquals(1L, summary.get("uses"));
        assertEquals(1L, summary.get("items"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) mine.get("items");
        assertEquals("Old Town Hunt", items.get(0).get("title"));
        assertEquals(false, mine.get("moneyOn"));

        @SuppressWarnings("unchecked") List<Map<String, Object>> money = (List<Map<String, Object>>) service.mine(200L).get("money");
        assertEquals(1, money.size());
        assertEquals(0L, money.get(0).get("creatorMinor"), "sale minus refund");
        assertEquals("HELD_MONEY_OFF", money.get(0).get("moneyState"));

        Map<String, Object> ledger = service.ledger(100L, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) ledger.get("entries");
        assertEquals(5, entries.size());
        assertFalse(ledger.containsKey("nextBefore"));

        String key = CreatorEarningsPolicy.incomeOrderKey(ORDER);
        Map<String, Object> page = service.incomePage(200L, null);
        assertEquals("mod-creator-income-page-v1", page.get("schema"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> facts = (List<Map<String, Object>>) page.get("facts");
        assertEquals(1, facts.size());
        Map<String, Object> fact = facts.get(0);
        assertEquals(key, fact.get("orderKey"));
        assertEquals("MOD_PURCHASE", fact.get("source"));
        assertEquals(VERSION, fact.get("modReleaseId"));
        assertEquals(700L, fact.get("creatorAmount"));
        assertEquals(700L, fact.get("refundedAmount"));
        assertEquals(2L, fact.get("revision"));
        assertEquals(List.of("source", "orderKey", "creatorId", "modReleaseId", "currency", "creatorAmount", "refundedAmount",
                "chargebackAmount", "revision", "paidAt", "disputed"), List.copyOf(fact.keySet()));
        assertTrue(service.incomePage(100L, null).get("facts") instanceof List<?> l && l.isEmpty(), "no sales, no facts");

        Map<String, Object> detail = service.incomeDetail(200L, key);
        assertEquals(fact, detail.get("current"));
        assertNull(service.incomeDetail(100L, key), "another creator's order is not found");

        Map<String, Object> revisions = service.incomeRevisions(200L, key, 2, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> revs = (List<Map<String, Object>>) revisions.get("revisions");
        assertEquals(2, revs.size());
        assertEquals(fact, revs.get(0), "first revision is the anchor");
        assertEquals(0L, revs.get(1).get("refundedAmount"));
        assertEquals(revs.get(0).get("paidAt"), revs.get(1).get("paidAt"));
        assertFalse(revisions.containsKey("nextBeforeRevision"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> older =
                (List<Map<String, Object>>) service.incomeRevisions(200L, key, 2, 2L).get("revisions");
        assertEquals(1, older.size());
        assertEquals(1L, older.get(0).get("revision"));
        assertNull(service.incomeRevisions(200L, key, 3, null));

        List<Map<String, Object>> report = service.report(null, DAY, DAY);
        assertEquals(2, report.size());
        assertEquals(100L, n(report.get(0), "creatorId"));
        assertEquals(7L, n(report.get(0), "points"));
    }

    @Test
    void moneyOnStillPaysNothingButMarksPendingPayout() {
        service = new CreatorEarningsService(session.getMapper(CreatorEarningMapper.class), members, memberService, pointsDetails,
                mock(PlatformTransactionManager.class), 5, 4, 1, 2, 1, 7000, true);
        service.accrueDay(DAY);
        assertEquals("PENDING_PAYOUT", event("SALE:" + ORDER).get("moneyState"));
        verify(memberService, never()).addBalance(anyLong(), any());
    }
}
