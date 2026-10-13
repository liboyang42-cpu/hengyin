package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.vo.PublicTicketVO;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.util.RefundPolicy;
import com.chengyinhub.business.util.RegistrationDetailDisplay;
import com.chengyinhub.common.constant.PurchaseKind;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5.9 场次退款钟与文案的**真库**核验:真实 base_schema DDL + 真实 Mapper 读回的行。
 *
 * <p>纯函数单测证明不了两件事:①列映射把 {@code cms_activity.start_date} 读到了
 * {@code getStartDate()}(判定入口就靠它);②{@code oms_ticket.owner_type/mode} 读对
 * (文案覆写与快照钟的分流靠它们)。本类用本机 3317 的**唯一 UUID scratch** 库落真实 DDL,
 * 插一队场次/票/报名行,再走真 Mapper 读回来断言;结束 DROP 并回读 schema 数为 0。
 *
 * <p>base_schema_20260818 落后于当前 mapper 的两列(oms_ticket.source_ticket_id、
 * cms_topic.team_mode/team_max_members、cms_registration.purchase_kind)按仓库迁移文件里的
 * 原定义补齐,列名/类型与迁移一致 —— 这正是「拿真 DDL 而不是手搓表」的代价,写在这里比
 * 悄悄绕过某列强。
 *
 * <p>只读真实服务器上的 MySQL(容器 cy-product-audit-0912),不碰任何既有库。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRefundClockMybatisIntegrationTest {

    private static final long H = 3600_000L;

    private MysqlIntegrationTestConfig mysql;
    private JdbcTemplate jdbc;
    private boolean created;
    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private CmsRegistrationMapper registrationMapper;
    private final Date now = new Date();

    /** 场次 A1:集合时间 = now+25h ⇒ 截止点 now+1h(窗口内)。 */
    private static final long ACT_IN_WINDOW = 9001L;
    /** 场次 A2:集合时间 = now+23h ⇒ 截止点 now−1h(已错过)。 */
    private static final long ACT_MISSED = 9002L;
    private static final long TICKET_GUIDED = 9101L;
    private static final long TICKET_EXPLORE = 9102L;
    private static final long TICKET_GUIDED_MISSED = 9103L;
    private static final long REG_GUIDED = 9201L;
    private static final long REG_GUIDED_MISSED = 9202L;
    private static final long REG_EXPLORE = 9203L;

    @BeforeAll
    void setup() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "cy_session_clock");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        mysql = MysqlIntegrationTestConfig.from(env, "it");
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("CREATE DATABASE " + mysql.databaseName());
        created = true;
        DriverManagerDataSource ds = new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        jdbc = new JdbcTemplate(ds);
        System.out.println("5.9 session clock MySQL version=" + jdbc.queryForObject("SELECT VERSION()", String.class));
        String base = new String(Files.readAllBytes(Paths.get("../sql/base_schema_20260818.sql")),
                StandardCharsets.UTF_8);
        for (String table : new String[]{"cms_topic", "cms_activity", "oms_ticket", "cms_registration"}) {
            jdbc.execute(createTableSql(base, table));
        }
        try (java.sql.Connection connection = ds.getConnection()) {
            com.chengyinhub.business.it.ItSchema.applyMigrations(connection,
                    "sql/migration_20260930_registration_first_successful_scan.sql");
        }
        // base_schema 之后的迁移列(定义抄自仓库迁移文件,见类注释)
        jdbc.execute("ALTER TABLE oms_ticket ADD COLUMN source_ticket_id BIGINT NULL "
                + "COMMENT '活动壳票对应的主题源票 id' AFTER owner_id");
        // 2-26 migration_oms_ticket_sale_fields_20260918.sql：OmsTicketMapper 的 selectOmsTicketVo
        // 现在无条件读这 5 列，base_schema_20260818 里没有 ⇒ 少任何一列，走该 mapper 的用例全报
        // ERROR 1054 Unknown column 'sale_start_time' in 'field list'。类型/可空/COMMENT/AFTER 逐字抄迁移。
        jdbc.execute("ALTER TABLE oms_ticket "
                + "ADD COLUMN sale_start_time DATETIME NULL "
                + "COMMENT '售票开始时间(发布页填写;仅落库回显,购票闸未接)' AFTER end_time, "
                + "ADD COLUMN sale_end_time DATETIME NULL "
                + "COMMENT '售票结束时间(发布页填写;仅落库回显,购票闸未接)' AFTER sale_start_time, "
                + "ADD COLUMN meeting_point_address VARCHAR(255) NULL "
                + "COMMENT '集合点地址(地图选点文本)' AFTER meeting_point, "
                + "ADD COLUMN refund_supported TINYINT(1) NULL "
                + "COMMENT '支持随时退款 0否1是(发布页填写;仅落库回显,退款规则未接)' AFTER refund_rule, "
                + "ADD COLUMN sync_with_theme TINYINT(1) NULL "
                + "COMMENT '售票时间与主题日期同步 0否1是(发布页填写;仅落库回显,同步逻辑未接)' AFTER refund_supported");
        jdbc.execute("ALTER TABLE cms_topic ADD COLUMN team_mode TINYINT NOT NULL DEFAULT 0, "
                + "ADD COLUMN team_max_members INT NOT NULL DEFAULT 4");

        Configuration config = new Configuration(new Environment(
                "session-clock", new SpringManagedTransactionFactory(), ds));
        config.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        config.addMapper(CmsActivityMapper.class);
        config.addMapper(OmsTicketMapper.class);
        config.addMapper(CmsRegistrationMapper.class);
        for (String resource : new String[]{
                "mapper/business/CmsActivityMapper.xml",
                "mapper/business/OmsTicketMapper.xml",
                "mapper/business/CmsRegistrationMapper.xml"}) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        activityMapper = template.getMapper(CmsActivityMapper.class);
        ticketMapper = template.getMapper(OmsTicketMapper.class);
        registrationMapper = template.getMapper(CmsRegistrationMapper.class);

        jdbc.update("INSERT INTO cms_topic(id, member_id, name, product_type, del_flag) VALUES(?,?,?,?,?)",
                7001L, 1L, "5.9 核验主题", 1, 0);
        insertActivity(ACT_IN_WINDOW, new Date(now.getTime() + 25 * H));
        insertActivity(ACT_MISSED, new Date(now.getTime() + 23 * H));
        insertTicket(TICKET_GUIDED, ACT_IN_WINDOW, 1, "发布者原文(应被平台规则覆写)");
        insertTicket(TICKET_EXPLORE, ACT_IN_WINDOW, 2, "票面有效期为准(不得覆写)");
        insertTicket(TICKET_GUIDED_MISSED, ACT_MISSED, 1, null);
        insertRegistration(REG_GUIDED, ACT_IN_WINDOW, TICKET_GUIDED, PurchaseKind.GUIDED_TICKET);
        insertRegistration(REG_GUIDED_MISSED, ACT_MISSED, TICKET_GUIDED_MISSED, PurchaseKind.GUIDED_TICKET);
        insertRegistration(REG_EXPLORE, ACT_IN_WINDOW, TICKET_EXPLORE, PurchaseKind.EXPLORE_PASS);
    }

    @AfterAll
    void cleanup() {
        if (!created) return;
        new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()))
                .execute("DROP DATABASE " + mysql.databaseName());
        Integer remain = new JdbcTemplate(new DriverManagerDataSource(
                mysql.adminUrl(), mysql.user(), mysql.password()))
                .queryForObject("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                        Integer.class, mysql.databaseName());
        System.out.println("5.9 session clock scratch dropped, schema remain=" + remain);
        assertEquals(0, remain, "scratch 库必须已 DROP(回读 0)");
    }

    /** 真行的集合时间经真 Mapper 读回后,判定必须落在 24h 窗口两侧。 */
    @Test
    void storedAssemblyTimeDrivesThe24hClock() {
        CmsActivity inWindow = activityMapper.selectCmsActivityById(ACT_IN_WINDOW);
        CmsRegistration rIn = registrationRow(REG_GUIDED);
        assertNotNull(inWindow);
        assertEquals(Long.valueOf(2L), rIn.getOwnerType(), "owner_type 列必须读回 2");
        assertEquals(Long.valueOf(ACT_IN_WINDOW), rIn.getOwnerId());
        assertEquals(PurchaseKind.GUIDED_TICKET, rIn.getPurchaseKind());
        RefundPolicy.Verdict ok = RefundPolicy.evaluate(2L, rIn.getPaymentStatus(),
                rIn.getVerificationStatus(), null, null, null, inWindow.getStartDate(),
                rIn.getPurchaseKind(), rIn.getRefundDeadline(), now);
        assertTrue(ok.refundable, "集合前 25h 必须可退,实际:" + ok.reason);
        assertEquals(inWindow.getStartDate().getTime() - 24 * H, ok.deadline.getTime(),
                "截止点必须是读回的集合时间 − 24h");

        CmsActivity missed = activityMapper.selectCmsActivityById(ACT_MISSED);
        CmsRegistration rMissed = registrationRow(REG_GUIDED_MISSED);
        RefundPolicy.Verdict no = RefundPolicy.evaluate(2L, rMissed.getPaymentStatus(),
                rMissed.getVerificationStatus(), null, null, null, missed.getStartDate(),
                rMissed.getPurchaseKind(), rMissed.getRefundDeadline(), now);
        assertFalse(no.refundable, "集合前 23h 必须不可退");
        assertFalse(no.manualReview, "超过窗口是终局,不转人工");
        assertTrue(no.reason.contains("集合前24小时"), "实际:" + no.reason);
    }

    /** 购买页文案:真票行按 owner_type/mode 分流 —— 带队覆写,自由探索保留。 */
    @Test
    void ticketCopyFollowsStoredOwnerTypeAndMode() {
        OmsTicket guided = ticketMapper.selectOmsTicketById(TICKET_GUIDED);
        assertEquals(Integer.valueOf(1), guided.getMode());
        assertEquals(RefundPolicy.SESSION_REFUND_RULE_TEXT,
                PublicTicketVO.from(guided, null).getRefundRule());

        OmsTicket explore = ticketMapper.selectOmsTicketById(TICKET_EXPLORE);
        assertEquals(Integer.valueOf(2), explore.getMode());
        assertEquals("票面有效期为准(不得覆写)", PublicTicketVO.from(explore, null).getRefundRule());
    }

    /** 订单页文案:真报名行(guided)带规则句,自由探索单不带。 */
    @Test
    void orderDisplayFollowsStoredPurchaseKind() {
        CmsActivity activity = activityMapper.selectCmsActivityById(ACT_IN_WINDOW);
        CmsRegistration guided = registrationRow(REG_GUIDED);
        guided.setRefundInfo(RefundPolicy.evaluate(2L, guided.getPaymentStatus(),
                guided.getVerificationStatus(), null, null, null, activity.getStartDate(),
                guided.getPurchaseKind(), guided.getRefundDeadline(), now));
        RegistrationDetailDisplay.enrich(guided, null);
        String guidedText = guided.getRefundDeadlineDisplay();
        assertNotNull(guidedText, "真行必须产出展示文案");
        assertTrue(guidedText.contains(RefundPolicy.SESSION_REFUND_RULE_TEXT), "实际:" + guidedText);
        assertTrue(guidedText.contains("可免费取消至"), "实际:" + guidedText);

        CmsRegistration explore = registrationRow(REG_EXPLORE);
        explore.setRefundInfo(RefundPolicy.evaluate(2L, explore.getPaymentStatus(),
                explore.getVerificationStatus(), null, null, null, activity.getStartDate(),
                explore.getPurchaseKind(), explore.getRefundDeadline(), now));
        RegistrationDetailDisplay.enrich(explore, null);
        String exploreText = explore.getRefundDeadlineDisplay();
        assertFalse(exploreText.contains(RefundPolicy.SESSION_REFUND_RULE_TEXT), "实际:" + exploreText);
        assertTrue(exploreText.contains("可免费取消至"), "实际:" + exploreText);
    }

    /** 真 Mapper 读回的报名行(owner_type/ticket_id/purchase_kind 的列映射是断言的一部分)。 */
    private CmsRegistration registrationRow(long id) {
        CmsRegistration r = registrationMapper.selectCmsRegistrationById(id);
        assertNotNull(r, "报名行 " + id + " 必须读回");
        return r;
    }

    private void insertActivity(long id, Date startDate) {
        jdbc.update("INSERT INTO cms_activity(id, member_id, name, status, publish_status, del_flag, "
                        + "start_date, end_date, topic_id, club_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, 1L, "5.9 核验场次", 1, 1, 0,
                new Timestamp(startDate.getTime()), new Timestamp(startDate.getTime() + 2 * H), 7001L, 6001L);
    }

    private void insertTicket(long id, long activityId, int mode, String refundRule) {
        jdbc.update("INSERT INTO oms_ticket(id, member_id, owner_type, owner_id, name, price, start_time, end_time, "
                        + "total_inventory, remaining_inventory, mode, refund_rule, del_flag) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, 1L, 2L, activityId, "5.9 核验票", new BigDecimal("99.00"),
                new Timestamp(now.getTime() + 25 * H), new Timestamp(now.getTime() + 27 * H),
                50L, 50L, mode, refundRule, 0);
    }

    private void insertRegistration(long id, long activityId, long ticketId, int purchaseKind) {
        jdbc.update("INSERT INTO cms_registration(id, registration_no, owner_type, owner_id, ticket_id, "
                        + "ticket_price, member_id, original_amount, total_amount, payable_amount, payment_status, "
                        + "registration_status, pay_expire_time, verification_status, purchase_kind) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, "R" + id, 2L, activityId, ticketId, new BigDecimal("99.00"), 42L,
                new BigDecimal("99.00"), new BigDecimal("99.00"), new BigDecimal("99.00"),
                2, 2, new Timestamp(now.getTime() + H), 0, purchaseKind);
    }

    private static String createTableSql(String dump, String table) {
        String marker = "CREATE TABLE `" + table + "` (";
        int start = dump.indexOf(marker);
        if (start < 0) throw new IllegalStateException("base_schema 缺表 " + table);
        int engine = dump.indexOf(") ENGINE=", start);
        if (engine < 0) throw new IllegalStateException("base_schema 表结构异常 " + table);
        int end = dump.indexOf(";", engine);
        return dump.substring(start, end + 1);
    }
}
