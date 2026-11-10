package com.chengyinhub.business.it;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.PlatformPassException;
import com.chengyinhub.business.platformpass.PlatformPassProperties;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.business.platformpass.XpayGateway;
import com.chengyinhub.business.platformpass.mapper.PlatformPassMapper;
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

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final long OFFICIAL_TOPIC = 9101L, MEMBER_TOPIC = 9102L, DRAFT_TOPIC = 9103L;
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
        P0AcceptanceSchema.baseTables(db, "cms_topic", "product_role");
        P0AcceptanceSchema.migrations(source, "migration_20261110_platform_pass.sql");
        mapper = P0AcceptanceSchema.sql(source, "PlatformPassMapper").getMapper(PlatformPassMapper.class);

        topic(OFFICIAL_TOPIC, 1, 3, 1, 0);
        topic(MEMBER_TOPIC, 1, 3, 1, 0);
        topic(DRAFT_TOPIC, 1, 0, 2, 0);
        db.update("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status) VALUES (?,'CONTENT','PLATFORM',NULL,1)", OFFICIAL_TOPIC);
        db.update("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status) VALUES (?,'CONTENT','MEMBER',77,1)", MEMBER_TOPIC);
        db.update("INSERT INTO product_role(topic_id,slot,entity_type,entity_id,status) VALUES (?,'CONTENT','PLATFORM',NULL,1)", DRAFT_TOPIC);
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

    private void topic(long id, int selfPlay, int lifecycle, int productType, int delFlag) {
        db.update("INSERT INTO cms_topic(id,name,self_play,lifecycle,product_type,del_flag,img_url) VALUES (?,?,?,?,?,?,?)",
                id, "主题" + id, selfPlay, lifecycle, productType, delFlag, "https://example.invalid/" + id + ".jpg");
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
        assertEquals(((Timestamp) rows.get(0).get("valid_to")).getTime(), ((Timestamp) rows.get(1).get("valid_from")).getTime(),
                "续买从上一段到期顺延");
        long monthMs = ((Timestamp) rows.get(0).get("valid_to")).getTime() - ((Timestamp) rows.get(0).get("valid_from")).getTime();
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
    void coverageAndLibraryOnlyIncludeOfficialSellingSelfPlayTopics() {
        paidXpay(531L, "MONTH", "it-req-0031");
        Date now = new Date();
        assertNotNull(service.coverage(531L, OFFICIAL_TOPIC, now));
        assertNull(service.coverage(531L, MEMBER_TOPIC, now), "玩家发布的主题不在通行证内");
        assertNull(service.coverage(531L, DRAFT_TOPIC, now), "草稿主题不在通行证内");
        assertNull(service.coverage(532L, OFFICIAL_TOPIC, now), "无通行证不覆盖");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.library(531L, null, 50).get("rows");
        assertEquals(1, rows.size());
        assertEquals(OFFICIAL_TOPIC, ((Number) rows.get(0).get("topicId")).longValue());
        assertEquals(Boolean.TRUE, service.library(531L, null, 50).get("covered"));
        assertEquals(0, ((List<?>) service.library(531L, 2, 50).get("rows")).size(), "按玩法筛选");
    }

    @Test
    void claimedPassCannotBeRefunded() {
        String no = paidXpay(541L, "MONTH", "it-req-0041");
        PlatformPassService.Coverage coverage = service.coverage(541L, OFFICIAL_TOPIC, new Date());
        new TransactionTemplate(manager).executeWithoutResult(s -> service.recordClaim(coverage, 541L, OFFICIAL_TOPIC, 880001L));
        assertEquals("PASS_ALREADY_USED", assertThrows(PlatformPassException.class,
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
        long secondFromBefore = db.queryForObject(
                "SELECT m.valid_from FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE o.order_no=?",
                Timestamp.class, second).getTime();
        when(xpay.refundOrder(anyString(), eq(first), anyString(), anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(JSONObject.of("errcode", 0));
        assertEquals("REFUNDING", service.requestRefund(first, "it-admin", "测试").get("status"));
        assertNull(service.coverage(551L, OFFICIAL_TOPIC, new Date()), "退款中的段不再覆盖");

        when(xpay.queryOrder(anyString(), eq(first), anyInt())).thenReturn(remote(first, 5, 3900));
        assertEquals("REFUNDED", service.refreshXpayRefund(first).get("status"));
        assertEquals(3, db.queryForObject(
                "SELECT m.status FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE o.order_no=?",
                Integer.class, first));
        long secondFromAfter = db.queryForObject(
                "SELECT m.valid_from FROM platform_pass_membership m JOIN platform_pass_order o ON o.id=m.order_id WHERE o.order_no=?",
                Timestamp.class, second).getTime();
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
        assertEquals(1, mapper.markXpayPaid(id, "WX1", 1, new Date()));
        assertEquals(0, mapper.markXpayPaid(id, "WX2", 1, new Date()), "已付不能再置已付");
        assertEquals(0, mapper.markCancelled(id, 561L, new Date()), "已付不能取消");
    }
}
