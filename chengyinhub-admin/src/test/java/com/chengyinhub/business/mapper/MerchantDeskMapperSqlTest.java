package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doc2 F4 MerchantDeskMapper 的 SQL 层守卫(服务测里 mapper 全是 mock,SQL 漏了归属那边照样全绿)。
 * 天花板同 MerchantPayableSqlTest:证明的是 SQL 长这样,不是 MySQL 跑起来对。
 */
class MerchantDeskMapperSqlTest {

    private static final String XML = "mapper/business/MerchantDeskMapper.xml";
    private static final Pattern STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\s+id=\"(\\w+)\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);
    /** 资金/履约事实表与门店主表:本 mapper 只许读,绝不许写(门店资料写仍走既有 /api/merchant/update、/decor/save) */
    private static final String[] READ_ONLY_TABLES = {"merchant_payable", "merchant_payable_batch", "settlement_claim",
            "redemption", "registration_entitlement", "merchant_chapter_offer", "coop_settlement",
            "ums_member_balance_detail", "verification_review", "mms_merchant"};

    private static String xml() throws Exception {
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String[]> statements() throws Exception {
        List<String[]> out = new ArrayList<>();
        Matcher m = STATEMENT.matcher(xml());
        while (m.find()) out.add(new String[]{m.group(1), m.group(2), m.group(3)});
        return out;
    }

    @Test
    void xmlParsesAndCoversEveryMapperMethod() throws Exception {
        Configuration cfg = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, cfg, XML, cfg.getSqlFragments()).parse();
        }
        long methods = java.util.Arrays.stream(MerchantDeskMapper.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic()).count();
        assertEquals(methods, statements().size());
    }

    @Test
    void everyStatementIsOwnerScoped() throws Exception {
        for (String[] s : statements()) {
            String body = s[2];
            assertTrue(body.contains("#{merchantId}") || body.contains("#{ownerMemberId}"),
                    s[1] + " 没有绑定 merchantId / ownerMemberId 归属");
            if (!"insert".equals(s[0]) || body.toLowerCase(Locale.ROOT).contains("select")) {
                String where = body.substring(body.toLowerCase(Locale.ROOT).lastIndexOf("where"));
                assertTrue(where.contains("#{merchantId}") || where.contains("#{ownerMemberId}"),
                        s[1] + " 的 WHERE 没有带归属");
            }
        }
    }

    @Test
    void noWriteTouchesMoneyOrFulfillmentTables() throws Exception {
        for (String[] s : statements()) {
            if ("select".equals(s[0])) continue;
            String head = s[2].trim().toLowerCase(Locale.ROOT).split("\\(|\\s+set\\s+|\\s+select\\s+")[0];
            for (String table : READ_ONLY_TABLES) {
                assertFalse(head.matches("(?s).*\\b" + table + "\\b.*"), s[1] + " 写了资金/履约表 " + table);
            }
            assertFalse("delete".equals(s[0]), s[1] + " 不允许 delete");
        }
    }

    @Test
    void liveProductsCannotBeEditedBySql() throws Exception {
        for (String[] s : statements()) {
            if (s[1].equals("updateProduct") || s[1].equals("insertSku") || s[1].equals("updateSku")) {
                assertTrue(s[2].contains("publish_status &lt;&gt; 1"), s[1] + " 必须排除上架中的商品");
            }
            if (s[1].equals("insertProduct")) assertTrue(s[2].contains("0, 0, #{price}"), "新建商品必须落待审核 0");
            if (s[1].equals("insertEvidence")) assertTrue(s[2].contains("v.status = 0"), "只能给待复核单补材料");
        }
    }
    @Test
    void frozenHistorySqlBindsOwnerAndStableDescendingCursor() throws Exception {
        Configuration cfg = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, cfg, XML, cfg.getSqlFragments()).parse();
        }
        var statement = cfg.getMappedStatement(MerchantDeskMapper.class.getName() + ".selectFrozenBatchPage");
        var bound = statement.getBoundSql(java.util.Map.of("ownerMemberId", 501L,
                "afterPeriodYm", "2026-10", "afterBatchId", 9007199254740993L));
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("merchant_member_id = ? and frozen = 1"));
        assertTrue(sql.contains("period_ym < ? or (period_ym = ? and id < ?)"));
        assertTrue(sql.contains("order by period_ym desc, id desc limit 25"));
        assertEquals(java.util.List.of("ownerMemberId", "afterPeriodYm", "afterPeriodYm", "afterBatchId"),
                bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
    }

}
