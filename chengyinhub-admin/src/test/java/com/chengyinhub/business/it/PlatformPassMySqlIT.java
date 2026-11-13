package com.chengyinhub.business.it;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.business.platformpass.PlatformPassProperties;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.business.platformpass.XpayGateway;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * l-pass 通行证真库：新迁移原文建表 + PlatformPassMapper.xml + 真事务。
 * 只有微信虚拟支付 HTTP 网关是桩（回查返回值由用例给定）；不触达任何真实支付。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_MARKETING_DB_IT_APPROVED", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformPassMySqlIT {
    private static final long OFFICIAL_TOPIC = 9101L, MEMBER_TOPIC = 9102L, DRAFT_TOPIC = 9103L,
            CLUB_TOPIC = 9104L, UNLISTED_TOPIC = 9105L, TEMPLATE_TOPIC = 9106L, DELETED_TOPIC = 9107L,
            PRIVATE_TOPIC = 9108L, DISSOLVED_CLUB_TOPIC = 9109L, UNREVIEWED_CIRCLE_TOPIC = 9110L,
            NO_SELF_PLAY_TOPIC = 9111L, CLOSED_TOPIC = 9112L;
    private static final Set<Long> CLAIMABLE_TOPICS = Set.of(OFFICIAL_TOPIC, MEMBER_TOPIC, CLUB_TOPIC);
    private static final Set<Long> PUBLIC_TOPICS = Set.of(OFFICIAL_TOPIC, MEMBER_TOPIC, CLUB_TOPIC, NO_SELF_PLAY_TOPIC, CLOSED_TOPIC);
    private final String suite = "ppass_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private JdbcTemplate db;
    private DataSourceTransactionManager manager;
    private PlatformPassMapper mapper;
    private PlatformPassService service;
    private XpayGateway xpay;

    @BeforeAll
    void schema() throws Exception {
        assertTrue(List.of("127.0.0.1", "localhost").contains(MybatisItDatabase.host()));
        DriverManagerDataSource source = P0AcceptanceSchema.dataSource(suite);
        db = new JdbcTemplate(source);
        assertEquals(MybatisItDatabase.databaseName(suite), db.queryForObject("SELECT DATABASE()", String.class));
        manager = new DataSourceTransactionManager(source);
        P0AcceptanceSchema.baseTables(db, "cms_topic", "product_role", "club", "cms_topic_chapter", "merchant_chapter_offer", "cms_registration");
        P0AcceptanceSchema.migrations(source, "migration_20261110_platform_pass.sql",
                "migration_20261113_platform_pass_hardening.sql",
                "migration_circle_theme_batch2_20260821.sql", "migration_circle_theme_primary_schema_20260821.sql",
                "migration_club_topic_display_settings_20260906.sql");
        mapper = passMapper(source);

        topic(OFFICIAL_TOPIC, 1, 3, 1, 1, 1, 0, 0, "PLATFORM", null);
        topic(MEMBER_TOPIC, 1, 3, 1, 1, 1, 0, 0, "MEMBER", 77L);
        topic(CLUB_TOPIC, 1, 3, 2, 1, 1, 0, 0, "CLUB", 701L);
        topic(DRAFT_TOPIC, 1, 3, 1, 0, 1, 0, 0, "PLATFORM", null);
        topic(UNLISTED_TOPIC, 1, 3, 1, 1, 0, 0, 0, "MEMBER", 77L);
        topic(TEMPLATE_TOPIC, 1, 3, 2, 1, 1, 0, 1, "CLUB", 701L);
        topic(DELETED_TOPIC, 1, 3, 1, 1, 1, 2, 0, "MEMBER", 77L);
        topic(PRIVATE_TOPIC, 1, 3, 1, 1, 1, 0, 0, "CLUB", 701L);
        topic(DISSOLVED_CLUB_TOPIC, 1, 3, 1, 1, 1, 0, 0, "CLUB", 702L);
        topic(UNREVIEWED_CIRCLE_TOPIC, 1, 3, 1, 1, 1, 0, 0, "PLATFORM", null);
        topic(NO_SELF_PLAY_TOPIC, 0, 0, 1, 1, 1, 0, 0, "MEMBER", 77L);
        topic(CLOSED_TOPIC, 1, 6, 2, 1, 1, 0, 0, "CLUB", 701L);
        db.update("INSERT INTO club(id,member_id,name,del_flag) VALUES (701,77,'公开俱乐部',0),(702,78,'已解散俱乐部',2)");
        db.update("UPDATE cms_topic SET club_member_only=1 WHERE id=?", PRIVATE_TOPIC);
        db.update("UPDATE cms_topic SET circle_theme_code='FRIENDS',circle_reviewed_at=NOW() WHERE id=?", UNREVIEWED_CIRCLE_TOPIC);
        db.update("UPDATE platform_pass_plan SET status=1, xpay_product_id=CONCAT('prod_', LOWER(plan_code))");

        PlatformPassProperties props = new PlatformPassProperties();
        props.setEnabled(true);
        props.getXpay().setEnabled(true);
        props.getXpay().setOfferId("OFFER_IT");
        props.getXpay().setAppKey("it-only-key");
        props.getXpay().setEnv(1);
        service = new PlatformPassService(mapper, props, manager);
        xpay = mock(XpayGateway.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.selectUmsMemberById(anyLong())).thenAnswer(i -> {
            UmsMember m = new UmsMember();
            m.setId(i.getArgument(0));
            m.setWxOpenId("openid-" + i.getArgument(0));
            return m;
        });
        ReflectionTestUtils.setField(service, "xpay", xpay);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "miniSessions",
                mock(com.chengyinhub.business.service.IWeChatMiniSessionService.class));
    }

    @AfterAll
    void drop() {
        if (db != null) P0AcceptanceSchema.drop(db, suite);
    }

    private PlatformPassMapper passMapper(DriverManagerDataSource source) throws Exception {
        Configuration config = new Configuration();
        // Register the actual production fragments before parsing their includes; never copy visibility SQL.
        fragment(config, "CmsTopicMapper", "primaryCirclePublicGate");
        fragment(config, "PublicContentCatalogMapper", "catalogPublicGate");
        String resource = "mapper/business/PlatformPassMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        return new SqlSessionTemplate(factory.getObject()).getMapper(PlatformPassMapper.class);
    }

    private static void fragment(Configuration config, String mapper, String id) throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mapper/business/" + mapper + ".xml")) {
            XPathParser parser = new XPathParser(in, true, null, new XMLMapperEntityResolver());
            config.getSqlFragments().put("com.chengyinhub.business.mapper." + mapper + "." + id,
                    parser.evalNode("/mapper/sql[@id='" + id + "']"));
        }
    }

    private void topic(long id, int selfPlay, int lifecycle, int productType, int status, int userStatus,
                       int delFlag, int isTemplate, String source, Long sourceId) {
        db.update("INSERT INTO cms_topic(id,name,self_play,lifecycle,product_type,status,user_status,del_flag,is_template,club_id,img_url)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, "主题" + id, selfPlay, lifecycle, productType,
                status, userStatus, delFlag, isTemplate, "CLUB".equals(source) ? sourceId : null,
                "https://example.invalid/" + id + ".jpg");
        db.update("UPDATE cms_topic SET self_play_price=100.00,selling_started_at=?,sale_end_at=? WHERE id=?",
                new Timestamp(1_600_000_000_000L), new Timestamp(4_102_444_800_000L), id);
        db.update("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status) VALUES (?,'CONTENT',?,?,1)",
                id, source, sourceId);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> libraryRows(Map<String, Object> catalog) {
        return (List<Map<String, Object>>) catalog.get("rows");
    }

    private static Set<Long> libraryIds(Map<String, Object> catalog) {
        return libraryRows(catalog).stream().map(row -> ((Number) row.get("topicId")).longValue())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static long ms(Object v) {
        if (v instanceof java.util.Date d) return d.getTime();
        if (v instanceof java.time.LocalDateTime t) return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        throw new AssertionError("unexpected time type " + v);
    }

    private static JSONObject remote(String orderNo, int status, long fee) {
        JSONObject order = new JSONObject();
        order.put("order_id", orderNo); order.put("status", status); order.put("paid_fee", fee);
        order.put("order_fee", fee); order.put("refund_fee", fee); order.put("wx_order_id", "WX-" + orderNo);
        JSONObject r = new JSONObject();
        r.put("errcode", 0); r.put("order", order);
        return r;
    }

    private String paidXpay(long member, String plan, String requestId) {
        Map<String, Object> created = service.createOrder(member, plan, "XPAY", requestId);
        String no = (String) created.get("orderNo");
        long fee = ((Number) created.get("priceFen")).longValue();
        when(xpay.queryOrder(eq("openid-" + member), eq(no), anyInt())).thenReturn(remote(no, 4, fee));
        assertEquals("PAID", service.confirmXpay(member, no).get("status"));
        return no;
    }

    @Test
    void requestIdIsUniquePerMemberAndConflictsOnDifferentPayload() {
        Map<String, Object> first = service.createOrder(501L, "YEAR", "XPAY", "it-req-0001");
        Map<String, Object> replay = service.createOrder(501L, "YEAR", "XPAY", "it-req-0001");
        assertEquals(first.get("orderNo"), replay.get("orderNo"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM platform_pass_order WHERE member_id=501", Integer.class));
        assertEquals("REQUEST_CONFLICT", assertThrows(PlatformPassException.class,
                () -> service.createOrder(501L, "MONTH", "XPAY", "it-req-0001")).getReasonCode());
        // 另一个会员可以用同一个 requestId
        service.createOrder(502L, "YEAR", "XPAY", "it-req-0001");
        assertEquals(29900, db.queryForObject("SELECT price_fen FROM platform_pass_order WHERE order_no=?", Integer.class, first.get("orderNo")));
    }

    @Test
    void xpayConfirmActivatesOnceAndRenewalStacks() {
        String a = paidXpay(511L, "MONTH", "it-req-0011");
        // 重复回查不重复开通
        service.confirmXpay(511L, a);
        String b = paidXpay(511L, "YEAR", "it-req-0012");
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT m.valid_from, m.valid_to, o.order_no FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE m.member_id=511 ORDER BY m.valid_from");
        assertEquals(2, rows.size());
        assertEquals(a, rows.get(0).get("order_no"));
        assertEquals(b, rows.get(1).get("order_no"));
        assertEquals(ms(rows.get(0).get("valid_to")), ms(rows.get(1).get("valid_from")),
                "续买从上一段到期顺延");
        long monthMs = ms(rows.get(0).get("valid_to")) - ms(rows.get(0).get("valid_from"));
        assertEquals(30L * 86_400_000L, monthMs);
    }

    @Test
    void unpaidRemoteDoesNotActivate() {
        Map<String, Object> created = service.createOrder(521L, "MONTH", "XPAY", "it-req-0021");
        String no = (String) created.get("orderNo");
        when(xpay.queryOrder(anyString(), eq(no), anyInt())).thenReturn(remote(no, 1, 3900));
        assertEquals("PENDING", service.confirmXpay(521L, no).get("status"));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM platform_pass_membership WHERE member_id=521", Integer.class));
        assertEquals("ORDER_NOT_FOUND", assertThrows(PlatformPassException.class,
                () -> service.confirmXpay(999L, no)).getReasonCode(), "他人订单不可回查");
    }

    @Test
    void publishedSourcesAreCoveredRegardlessOfLifecycleOrSelfPlayAndUnpublishedAreRejected() {
        paidXpay(531L, "MONTH", "it-req-0031");
        Date now = new Date();
        for (long topicId : PUBLIC_TOPICS) {
            assertEquals(1, mapper.isPublishedTopic(topicId));
            assertNotNull(service.coverage(531L, topicId, now));
        }
        List<Long> rejected = List.of(DRAFT_TOPIC, UNLISTED_TOPIC, TEMPLATE_TOPIC, DELETED_TOPIC);
        for (long topicId : rejected) {
            assertEquals(0, mapper.isPublishedTopic(topicId));
            assertNull(service.coverage(531L, topicId, now), "Publication gates apply to every source");
        }
        List<Long> requested = List.of(OFFICIAL_TOPIC, MEMBER_TOPIC, CLUB_TOPIC, NO_SELF_PLAY_TOPIC, CLOSED_TOPIC,
                DRAFT_TOPIC, UNLISTED_TOPIC, TEMPLATE_TOPIC, DELETED_TOPIC);
        assertEquals(PUBLIC_TOPICS, Set.copyOf(mapper.selectPublishedTopicIds(requested)));
        assertEquals(PUBLIC_TOPICS, service.coveredTopics(531L, requested, now));
        assertNull(service.coverage(532L, OFFICIAL_TOPIC, now), "An active pass is required");

        Map<String, Object> catalog = service.library(531L, null, 50);
        assertEquals(PUBLIC_TOPICS, libraryIds(catalog));
        for (Map<String, Object> row : libraryRows(catalog)) {
            assertEquals(CLAIMABLE_TOPICS.contains(row.get("topicId")), row.get("passCovered"),
                    "Broad published policy alone does not make a self-play claim currently available");
        }
        assertEquals(Boolean.FALSE, catalog.get("covered"));
        assertEquals(5, mapper.countLibrary(Set.of()));
        assertEquals(Set.of(CLUB_TOPIC, CLOSED_TOPIC), libraryIds(service.library(531L, 2, 50)), "Product type still filters the catalog");
    }

    @Test
    void libraryUsesPublicCatalogGatesButDoesNotRequireAnActivePassOrEnabledSales() {
        // These are published, but the stricter public catalog gates exclude them.
        for (long topicId : List.of(PRIVATE_TOPIC, DISSOLVED_CLUB_TOPIC, UNREVIEWED_CIRCLE_TOPIC)) {
            assertEquals(1, mapper.isPublishedTopic(topicId));
        }
        PlatformPassService disabled = new PlatformPassService(mapper, new PlatformPassProperties(), manager);
        for (Map<String, Object> catalog : List.of(service.library(null, null, 50),
                service.library(533L, null, 50), disabled.library(533L, null, 50))) {
            assertEquals(PUBLIC_TOPICS, libraryIds(catalog));
            assertEquals(Boolean.FALSE, catalog.get("covered"));
            assertTrue(libraryRows(catalog).stream().allMatch(row -> Boolean.FALSE.equals(row.get("passCovered"))));
        }
        assertEquals(5, mapper.countLibrary(Set.of()));
        assertEquals(0, mapper.countLibrary(Set.of(PRIVATE_TOPIC, DISSOLVED_CLUB_TOPIC, UNREVIEWED_CIRCLE_TOPIC)));
    }

    @Test
    void allowlistIsSharedByCoverageBatchCatalogFlagsAndLibraryCount() {
        paidXpay(534L, "MONTH", "it-req-0034");
        PlatformPassProperties configured = new PlatformPassProperties();
        configured.setEnabled(true);
        configured.setCoverageTopicIds(Set.of(MEMBER_TOPIC, CLUB_TOPIC, DRAFT_TOPIC));
        PlatformPassService restricted = new PlatformPassService(mapper, configured, manager);
        Date now = new Date();

        assertNull(restricted.coverage(534L, OFFICIAL_TOPIC, now));
        assertNotNull(restricted.coverage(534L, MEMBER_TOPIC, now));
        assertNotNull(restricted.coverage(534L, CLUB_TOPIC, now));
        assertNull(restricted.coverage(534L, DRAFT_TOPIC, now));
        assertEquals(Set.of(MEMBER_TOPIC, CLUB_TOPIC), restricted.coveredTopics(534L,
                List.of(OFFICIAL_TOPIC, MEMBER_TOPIC, CLUB_TOPIC, DRAFT_TOPIC), now));
        Map<String, Object> catalog = restricted.library(534L, null, 50);
        assertEquals(PUBLIC_TOPICS, libraryIds(catalog), "The allowlist limits benefits, not public catalog visibility");
        for (Map<String, Object> row : libraryRows(catalog)) {
            assertEquals(Set.of(MEMBER_TOPIC, CLUB_TOPIC).contains(row.get("topicId")), row.get("passCovered"));
        }
        assertEquals(Boolean.FALSE, catalog.get("covered"));
        assertEquals(2, mapper.countLibrary(configured.getCoverageTopicIds()));
        assertEquals(2, restricted.overview(534L).get("libraryCount"));
        assertFalse(configured.coversTopic(OFFICIAL_TOPIC));
    }

    @Test
    void claimedPassNeedsManualReviewBeforeRefund() {
        String no = paidXpay(541L, "MONTH", "it-req-0041");
        PlatformPassService.Coverage coverage = service.coverage(541L, OFFICIAL_TOPIC, new Date());
        new TransactionTemplate(manager).executeWithoutResult(s -> service.recordClaim(coverage, 541L, OFFICIAL_TOPIC, 880001L));
        assertEquals("MANUAL_REVIEW_REQUIRED", assertThrows(PlatformPassException.class,
                () -> service.requestRefund(no, "it-admin", "测试")).getReasonCode());
        assertEquals(1, db.queryForObject("SELECT status FROM platform_pass_order WHERE order_no=?", Integer.class, no));
        // 同一报名行不能记两次领取
        assertThrows(Exception.class, () -> new TransactionTemplate(manager).executeWithoutResult(
                s -> service.recordClaim(coverage, 541L, OFFICIAL_TOPIC, 880001L)));
    }

    @Test
    void refundRevokesSegmentShiftsLaterOneAndRefundingBlocksCoverage() {
        String first = paidXpay(551L, "MONTH", "it-req-0051");
        String second = paidXpay(551L, "MONTH", "it-req-0052");
        long secondFromBefore = ms(db.queryForObject(
                "SELECT m.valid_from FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE o.order_no=?",
                Timestamp.class, second));
        when(xpay.refundOrder(anyString(), eq(first), anyString(), anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(JSONObject.of("errcode", 0));
        assertEquals("REFUNDING", service.requestRefund(first, "it-admin", "测试").get("status"));
        assertNull(service.coverage(551L, OFFICIAL_TOPIC, new Date()), "退款中的段不再覆盖");

        when(xpay.queryOrder(anyString(), eq(first), anyInt())).thenReturn(remote(first, 5, 3900));
        assertEquals("REFUNDED", service.refreshXpayRefund(first).get("status"));
        assertEquals(3, db.queryForObject(
                "SELECT m.status FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE o.order_no=?",
                Integer.class, first));
        long secondFromAfter = ms(db.queryForObject(
                "SELECT m.valid_from FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE o.order_no=?",
                Timestamp.class, second));
        assertTrue(secondFromBefore - secondFromAfter > 29L * 86_400_000L, "后一段前移约一个月");
        assertTrue(secondFromAfter <= System.currentTimeMillis() + 1000, "后一段立即生效");
        assertNotNull(service.coverage(551L, OFFICIAL_TOPIC, new Date()), "后一段接上覆盖");
    }

    @Test
    void guardedUpdatesAreSingleShot() {
        Map<String, Object> created = service.createOrder(561L, "MONTH", "XPAY", "it-req-0061");
        long id = db.queryForObject("SELECT id FROM platform_pass_order WHERE order_no=?", Long.class, created.get("orderNo"));
        assertEquals(0, mapper.markCancelled(id, 999L, new Date()), "他人不能取消");
        assertEquals(0, mapper.markJsapiPaid(id, 1L, "a".repeat(64), "TX", new Date()), "渠道不符不能置已付");
        assertEquals(1, mapper.markXpayPaid(id, "WX1", 1, new Date(), 2));
        assertEquals(0, mapper.markXpayPaid(id, "WX2", 1, new Date(), 2), "已付不能再置已付");
        assertEquals(0, mapper.markCancelled(id, 561L, new Date()), "已付不能取消");
    }
}
