package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款售后列表/详情头的展示列(2026-09-17 Revolut 结构重做补字段):活动/主题名、申请人昵称、转平台时间。
 *
 * <p>两层:静态合同钉住「只读、同一归属范围、不取 PII」;H2 实跑钉住「标量子查询不放大行、别家门店的单看不到、取不到为 null」。
 */
public class MerchantAftercareDisplayColumnsContractTest {
    private static final String XML = "mapper/business/MerchantAftercareMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantAftercareMapper.";

    @Test
    void manualCasesRequireFrozenFulfilledChapterAndExactCurrentMerchantWithoutPiiOrMoney() throws Exception {
        Configuration configuration = parse();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:aftercare_manual_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (Connection connection = dataSource.getConnection(); Statement ddl = connection.createStatement()) {
            // 只替代 MySQL JSON 函数，查询本身直接执行生产 mapper；真实 MySQL 仍由运行态复测。
            String aliases = getClass().getName();
            ddl.execute("create alias JSON_VALID for \"" + aliases + ".jsonValid\"");
            ddl.execute("create alias JSON_TYPE for \"" + aliases + ".jsonType\"");
            ddl.execute("create alias JSON_CONTAINS for \"" + aliases + ".jsonContains\"");
            createSchema(ddl);
            ddl.execute("alter table refund_application add column update_time timestamp before platform_takeover_at");
            ddl.execute("alter table cms_registration add column parent_order_no varchar(64) before activity_title");
            ddl.execute("alter table cms_activity add column topic_id bigint before name");
            ddl.execute("create table redemption(id bigint primary key, registration_id bigint, chapter_id bigint, topic_id bigint, merchant_id bigint, status varchar(16))");
            ddl.execute("create table activity_cancel_manual_refund_case(id bigint primary key, case_type varchar(32), status varchar(48),"
                    + "activity_id bigint, topic_id bigint, payer_registration_id bigint, parent_order_no varchar(64),"
                    + "chapter_snapshot_json varchar(4000), create_time timestamp, owner_member_id bigint)");
            ddl.execute("insert into mms_merchant values(77,900,1,1,0),(88,901,1,1,0)");
            ddl.execute("insert into cms_topic values(401,'探店主题'),(402,'另一主题')");
            ddl.execute("insert into cms_activity values(301,401,'探店场次')");
            ddl.execute("insert into cms_registration values(501,1,401,'ORDER-1','主题'),(502,1,401,'ORDER-2','主题'),"
                    + "(503,1,401,'ORDER-3','主题'),(504,2,301,'ORDER-4','场次')");
            ddl.execute("insert into redemption values(1,501,31,401,900,'ACTIVE'),(2,503,31,401,900,'REVERSED'),"
                    + "(3,504,31,401,900,'ACTIVE')");
            ddl.execute("insert into refund_application values(41,'RF-41','registration',501,900,1001,25.00,0,'普通退款',"
                    + "null,0,timestamp '2026-11-06 10:00:00',null,null)");
            ddl.execute("insert into merchant_aftercare_response values(1,41,77,'AGREE')");
            insertCase(connection, 41, 501, 401L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT", snapshot(501, 1));
            insertCase(connection, 42, 502, 401L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT", snapshot(502, 1)); // 无核销
            insertCase(connection, 43, 501, 401L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT", snapshot(501, 0)); // 本店章未履约
            insertCase(connection, 44, 501, 401L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT", "broken");
            insertCase(connection, 45, 503, 401L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT", snapshot(503, 1)); // 撤销核销
            insertCase(connection, 46, 501, 402L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT", snapshot(501, 1)); // 不同主题
            insertCase(connection, 47, 501, 401L, null, "MIXED_TICKETS", "PENDING_ASSESSMENT", snapshot(501, 1));
            insertCase(connection, 48, 501, 401L, null, "PARTIAL_CHAPTERS", "NO_REFUND", snapshot(501, 1));
            insertCase(connection, 49, 501, 401L, null, "PARTIAL_CHAPTERS", "UNKNOWN", snapshot(501, 1));
            insertCase(connection, 50, 501, 401L, null, "PARTIAL_CHAPTERS", "PENDING_ASSESSMENT",
                    "{\"registrationId\":501,\"chapterId\":31,\"status\":1}"); // 非数组
            insertCase(connection, 51, 504, null, 301L, "PARTIAL_CHAPTERS", "MANUAL_REFUND_EVIDENCE_VERIFIED", snapshot(504, 1));

            Map<String, Object> processing = params("PROCESSING");
            List<Map<String, Object>> rows = run(connection, bound(configuration, "selectOwnedRefundList", processing));
            assertEquals(2, rows.size(), "普通退款与本店人工工单各一行，不因章数放大");
            assertEquals(2L, ((Number) run(connection, bound(configuration, "countOwnedRefundList", processing))
                    .get(0).values().iterator().next()).longValue());
            Map<String, Object> manual = rows.stream().filter(r -> "MANUAL_CASE".equals(r.get("record_type"))).findFirst().orElseThrow();
            assertEquals(41L, ((Number) manual.get("manual_case_id")).longValue());
            assertNull(manual.get("id"), "manualCaseId不冒充普通refundId，即使两类同号");
            assertNull(manual.get("refund_amount"));
            assertNull(manual.get("customer_nickname"));
            assertNull(manual.get("reason"));
            assertEquals("探店主题", manual.get("activity_title"));
            assertFalse(manual.containsKey("chapter_snapshot_json"));
            assertFalse(manual.containsKey("payer_registration_id"));
            assertEquals(2, run(connection, bound(configuration, "selectOwnedRefundList", params("COMPLETED"))).size());
            assertTrue(run(connection, bound(configuration, "selectOwnedRefundList", params("PENDING"))).isEmpty());

            processing.put("manualCaseId", 41L);
            assertEquals(1, run(connection, bound(configuration, "selectOwnedManualCaseDisplay", processing)).size());
            for (long id = 42; id <= 47; id++) {
                processing.put("manualCaseId", id);
                assertTrue(run(connection, bound(configuration, "selectOwnedManualCaseDisplay", processing)).isEmpty(), "拒绝未履约/坏快照工单" + id);
            }
            processing.put("manualCaseId", 50L);
            assertTrue(run(connection, bound(configuration, "selectOwnedManualCaseDisplay", processing)).isEmpty());
            processing.put("manualCaseId", 41L);
            processing.put("merchantId", 88L);
            processing.put("ownerMemberId", 901L);
            assertTrue(run(connection, bound(configuration, "selectOwnedManualCaseDisplay", processing)).isEmpty(), "他店只有未履约章不得读取");
            assertTrue(run(connection, bound(configuration, "selectOwnedRefundList", processing)).isEmpty());
            processing.put("merchantId", 77L);
            assertTrue(run(connection, bound(configuration, "selectOwnedManualCaseDisplay", processing)).isEmpty(), "不能伪造门店与owner组合");
            processing.put("ownerMemberId", 900L);
            ddl.execute("insert into mms_merchant values(79,900,1,1,0)");
            assertTrue(run(connection, bound(configuration, "selectOwnedManualCaseDisplay", processing)).isEmpty(), "只有member绑定的旧核销在同owner多门店时fail closed");
            assertEquals(1, run(connection, bound(configuration, "selectOwnedRefundList", processing)).size(), "已绑定具体门店的普通退款保持原行为");
        }
    }

    private static String snapshot(long registration, int ownStatus) {
        return "[{\"registrationId\":" + registration + ",\"chapterId\":31,\"chapterName\":\"本店章\",\"status\":"
                + ownStatus + "},{\"registrationId\":" + registration + ",\"chapterId\":32,\"chapterName\":\"他店章\",\"status\":"
                + (ownStatus == 1 ? 0 : 1) + "}]";
    }

    private void insertCase(Connection connection, long id, long registration, Long topic, Long activity,
                            String type, String status, String snapshot) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("insert into activity_cancel_manual_refund_case"
                + "(id,case_type,status,activity_id,topic_id,payer_registration_id,parent_order_no,chapter_snapshot_json,create_time,owner_member_id)"
                + " values(?,?,?,?,?,?,?,?,timestamp '2026-11-06 10:01:00',555)")) {
            ps.setLong(1, id); ps.setString(2, type); ps.setString(3, status); ps.setObject(4, activity);
            ps.setObject(5, topic); ps.setLong(6, registration); ps.setString(7, "CASE:" + id);
            ps.setString(8, snapshot);
            ps.executeUpdate();
        }
    }

    public static boolean jsonValid(String json) {
        try { return json != null && new com.fasterxml.jackson.databind.ObjectMapper().readTree(json) != null; }
        catch (Exception invalid) { return false; }
    }

    public static String jsonType(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).isArray() ? "ARRAY" : "OBJECT";
    }

    public static boolean jsonContains(String json, String target) throws Exception {
        if (json == null || target == null) return false;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json), expected = mapper.readTree(target);
        if (!root.isArray()) return false;
        for (com.fasterxml.jackson.databind.JsonNode item : root) {
            if (item.path("registrationId").equals(expected.path("registrationId"))
                    && item.path("chapterId").equals(expected.path("chapterId"))
                    && item.path("status").equals(expected.path("status"))) return true;
        }
        return false;
    }

    @Test
    void displayColumnsAreReadOnlySameScopeAndNeverSelectPii() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = params("PENDING");

        String list = normalized(bound(configuration, "selectOwnedRefundList", params).getSql());
        String display = normalized(bound(configuration, "selectOwnedRefundDisplay", params).getSql());
        String count = normalized(bound(configuration, "countOwnedRefundList", params).getSql());

        for (String statement : new String[] { list, display }) {
            assertTrue(statement.contains("as activity_title"), statement);
            assertTrue(statement.contains("as customer_nickname"), statement);
            assertTrue(statement.contains("r.platform_takeover_at"), statement);
            assertTrue(statement.contains("select a.name from cms_activity a where a.id = reg.owner_id"), statement);
            assertTrue(statement.contains("select t.name from cms_topic t where t.id = reg.owner_id"), statement);
            assertTrue(statement.contains("from cms_registration reg where reg.id = r.source_id"), statement);
            assertTrue(statement.contains("u.id = r.member_id and u.del_flag = 0"),
                    "昵称口径同商家客户页:已删会员不下发:" + statement);
            assertTrue(statement.contains("r.merchant_id = ?") && statement.contains("scoped.merchant_id = ?")
                            && statement.contains("sibling.member_id = ?"),
                    "展示列必须沿用同一门店归属范围:" + statement);
            for (String pii : new String[] { "phone", "openid", "real_name", "avatar", "email", "address" }) {
                assertFalse(statement.contains(pii), "展示列不得读取 PII 列 " + pii + ":" + statement);
            }
            assertFalse(statement.contains(" join ums_member") || statement.contains(" join cms_registration"),
                    "只许标量子查询,join 会放大退款行:" + statement);
        }
        assertTrue(display.contains("r.id = ?"), display);
        assertFalse(count.contains("customer_nickname"), "count 不需要展示列:" + count);
    }

    @Test
    void h2ListReturnsDisplayFactsWithoutRowMultiplicationOrCrossStoreLeak() throws Exception {
        Configuration configuration = parse();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:merchant_aftercare_display;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        try (Connection connection = dataSource.getConnection(); Statement ddl = connection.createStatement()) {
            createSchema(ddl);
            ddl.execute("alter table ums_member add column phone varchar(32) before del_flag");

            ddl.execute("insert into mms_merchant values (77, 900, 1, 1, 0)");
            ddl.execute("insert into cms_activity values (301, '外滩夜跑')");
            ddl.execute("insert into cms_topic values (401, '老城寻宝')");
            ddl.execute("insert into cms_registration values (501, 2, 301, '冗余名'), (502, 1, 401, null),"
                    + " (503, 2, 999, '报名快照名'), (504, 2, 999, null)");
            ddl.execute("insert into ums_member values (1001, '小王', '13800000000', 0), (1002, '', '13900000000', 0),"
                    + " (1003, '已注销', '13700000000', 1)");
            ddl.execute("insert into refund_application values"
                    + " (41, 'RF-41', 'registration', 501, 900, 1001, 25.00, 0, 'r', null, 0, timestamp '2026-09-17 10:05:00', timestamp '2026-09-18 10:06:00'),"
                    + " (42, 'RF-42', 'registration', 502, 900, 1002, 10.00, 0, 'r', null, 0, timestamp '2026-09-17 10:04:00', null),"
                    + " (43, 'RF-43', 'registration', 503, 900, 1003, 10.00, 0, 'r', null, 0, timestamp '2026-09-17 10:03:00', null),"
                    + " (44, 'RF-44', 'registration', 504, 900, 1001, 10.00, 0, 'r', null, 0, timestamp '2026-09-17 10:02:00', null),"
                    + " (45, 'RF-45', 'registration', 501, 555, 1001, 10.00, 0, 'r', null, 0, timestamp '2026-09-17 10:01:00', null)");

            List<Map<String, Object>> rows = run(connection, bound(configuration, "selectOwnedRefundList", params("PENDING")));
            assertEquals(4, rows.size(), "别家 owner 的 RF-45 不得出现,且一单一行:" + rows);
            assertEquals("外滩夜跑", rows.get(0).get("activity_title"), "活动报名取 cms_activity.name");
            assertEquals("小王", rows.get(0).get("customer_nickname"));
            assertTrue(rows.get(0).get("platform_takeover_at") != null);
            assertEquals("老城寻宝", rows.get(1).get("activity_title"), "主题报名取 cms_topic.name");
            assertNull(rows.get(1).get("customer_nickname"), "空昵称下发 null,前端回落");
            assertNull(rows.get(1).get("platform_takeover_at"));
            assertEquals("报名快照名", rows.get(2).get("activity_title"), "源活动已删时回落报名单冗余名");
            assertNull(rows.get(2).get("customer_nickname"), "已删会员不下发昵称");
            assertNull(rows.get(3).get("activity_title"), "取不到为 null");
            for (Map<String, Object> row : rows) {
                assertFalse(row.containsKey("phone"), "不得带出手机号:" + row);
            }

            Map<String, Object> detailParams = params("PENDING");
            detailParams.put("refundId", 41L);
            List<Map<String, Object>> own = run(connection, bound(configuration, "selectOwnedRefundDisplay", detailParams));
            assertEquals(1, own.size());
            assertEquals("外滩夜跑", own.get(0).get("activity_title"));
            detailParams.put("refundId", 45L);
            assertTrue(run(connection, bound(configuration, "selectOwnedRefundDisplay", detailParams)).isEmpty(),
                    "详情展示列不得越权读到别家 owner 的单");
        }
    }

    private void createSchema(Statement ddl) throws Exception {
        ddl.execute("create table refund_application(id bigint primary key, refund_no varchar(40), source_type varchar(32),"
                + "source_id bigint, merchant_id bigint, member_id bigint, refund_amount decimal(10,2), status int,"
                + "reason varchar(200), payout_status int, del_flag int, create_time timestamp, platform_takeover_at timestamp)");
        ddl.execute("create table merchant_aftercare_response(id bigint primary key, refund_application_id bigint, merchant_id bigint, decision varchar(16))");
        ddl.execute("create table mms_merchant(id bigint primary key, member_id bigint, status int, account_status int, del_flag int)");
        ddl.execute("create table cms_registration(id bigint primary key, owner_type int, owner_id bigint, activity_title varchar(200))");
        ddl.execute("create table cms_activity(id bigint primary key, name varchar(255))");
        ddl.execute("create table cms_topic(id bigint primary key, name varchar(255))");
        ddl.execute("create table ums_member(id bigint primary key, nickname varchar(64), del_flag int)");
    }

    private Map<String, Object> params(String bucket) {
        Map<String, Object> params = new HashMap<>();
        params.put("ownerMemberId", 900L);
        params.put("merchantId", 77L);
        params.put("bucket", bucket);
        params.put("offset", 0);
        params.put("limit", 20);
        return params;
    }

    private List<Map<String, Object>> run(Connection connection, BoundSql bound) throws Exception {
        Map<String, Object> source = castParams(bound.getParameterObject());
        try (PreparedStatement ps = connection.prepareStatement(bound.getSql())) {
            int index = 1;
            for (ParameterMapping mapping : bound.getParameterMappings()) {
                ps.setObject(index++, source.get(mapping.getProperty()));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
            return out;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castParams(Object value) {
        return (Map<String, Object>) value;
    }

    private Configuration parse() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private BoundSql bound(Configuration configuration, String id, Map<String, Object> parameter) {
        return configuration.getMappedStatement(NS + id).getBoundSql(parameter);
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
