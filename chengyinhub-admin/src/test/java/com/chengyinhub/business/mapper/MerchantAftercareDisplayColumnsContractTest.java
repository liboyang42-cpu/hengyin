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
class MerchantAftercareDisplayColumnsContractTest {
    private static final String XML = "mapper/business/MerchantAftercareMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantAftercareMapper.";

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
            ddl.execute("create table refund_application (id bigint primary key, refund_no varchar(40), source_type varchar(32),"
                    + " source_id bigint, merchant_id bigint, member_id bigint, refund_amount decimal(10,2), status int,"
                    + " reason varchar(200), payout_status int, del_flag int, create_time timestamp, platform_takeover_at timestamp)");
            ddl.execute("create table merchant_aftercare_response (id bigint primary key, refund_application_id bigint,"
                    + " merchant_id bigint, decision varchar(16))");
            ddl.execute("create table mms_merchant (id bigint primary key, member_id bigint, status int, account_status int, del_flag int)");
            ddl.execute("create table cms_registration (id bigint primary key, owner_type int, owner_id bigint, activity_title varchar(200))");
            ddl.execute("create table cms_activity (id bigint primary key, name varchar(255))");
            ddl.execute("create table cms_topic (id bigint primary key, name varchar(255))");
            ddl.execute("create table ums_member (id bigint primary key, nickname varchar(64), phone varchar(32), del_flag int)");

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
