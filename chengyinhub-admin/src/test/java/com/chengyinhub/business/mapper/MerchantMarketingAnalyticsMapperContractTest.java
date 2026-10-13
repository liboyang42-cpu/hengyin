package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantMarketingAnalyticsMapperContractTest {
    private static final String XML = "mapper/business/MerchantMarketingAnalyticsMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantMarketingAnalyticsMapper.";

    @Test
    void independentEventsUseTheirOwnTimeColumnsAndHalfOpenWindows() throws Exception {
        String sql = boundSql();
        assertTrue(sql.contains("b.browse_time as event_time"), sql);
        assertTrue(sql.contains("r.create_time as event_time"), sql);
        assertTrue(sql.contains("r.payment_time as event_time"), sql);
        assertTrue(sql.contains("rd.create_time as event_time"), sql);
        assertTrue(sql.contains("f.create_time as event_time"), sql);
        assertTrue(occurrences(sql, "event_time >= ? and event_time < ?") == 13,
                "十个阶段窗口与三个触点窗口都必须使用 [start,end) 半开边界:" + sql);
        assertTrue(sql.contains("b.browse_time >= ? and b.browse_time < ?"), sql);
        assertTrue(sql.contains("r.create_time >= ? and r.create_time < ?"), sql);
        assertTrue(sql.contains("r.payment_time >= ? and r.payment_time < ?"), sql);
        assertTrue(sql.contains("rd.create_time >= ? and rd.create_time < ?"), sql);
        assertTrue(sql.contains("f.create_time >= ? and f.create_time < ?"), sql);
        assertFalse(sql.contains("event_time <= ?"), "时间上界不得闭区间:" + sql);
        assertFalse(sql.contains("event_time > ?"), "时间下界不得排除恰好 00:00 的事件:" + sql);
        assertFalse(sql.contains("payment_status = 2"),
                "支付事件按 payment_time 固化；后续退款不得抹掉曾支付事实:" + sql);
    }

    @Test
    void everySourceIsServerOwnerScopedAndReversedRedemptionsNeverCount() throws Exception {
        String sql = boundSql();
        assertTrue(sql.contains("t.member_id = ?"), "自有主题必须按 ownerMemberId:" + sql);
        assertTrue(sql.contains("crm.member_id = ?") && sql.contains("crm.audit_status = 1"),
                "中标主题必须按服务端 owner 和中标态:" + sql);
        assertTrue(sql.contains("a.member_id = ?"), "自有活动必须按 ownerMemberId:" + sql);
        assertTrue(sql.contains("rd.merchant_id = ?"), "核销按商家主体 ownerMemberId:" + sql);
        assertTrue(sql.contains("rd.status = 'active'"), "冲正核销不得算事件:" + sql);
        assertTrue(sql.contains("f.merchant_id = ?") && sql.contains("f.del_flag = 0"),
                "退款申请按商家主体 ownerMemberId:" + sql);

        String source = normalized(new String(Files.readAllBytes(Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/MerchantMarketingAnalyticsMapper.xml")),
                StandardCharsets.UTF_8));
        assertFalse(source.contains("principalmemberid"), "mapper 只能接受 ownerMemberId");
        assertFalse(source.contains("source_channel_code"), "审计原文不是可信渠道归因");
        assertTrue(source.contains("union all"), "五类事实必须保留独立事件，不做相邻 join");
    }

    @Test
    void signupAndPaymentFactsMustNotFilterMissingCmsRegistrationDelFlag() throws Exception {
        String sql = boundSql();
        assertFalse(sql.contains("r.del_flag"),
                "cms_registration 没有 del_flag。过滤它会让 /api/merchant/funnel 和 marketing-home 在生产直接 500:" + sql);
        assertTrue(sql.contains("crm.del_flag = 0"), "中标承接表有 del_flag，必须继续排除软删:" + sql);
        assertTrue(sql.contains("f.del_flag = 0"), "退款申请表有 del_flag，必须继续排除软删:" + sql);
        assertTrue(sql.contains("from cms_registration r"), "报名/支付事实仍须来自 cms_registration:" + sql);
    }

    private String boundSql() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("ownerMemberId", 99L);
        params.put("previousStart", new Date(1_000L));
        params.put("currentStart", new Date(2_000L));
        params.put("endExclusive", new Date(3_000L));
        return normalized(configuration.getMappedStatement(NS + "selectAnalytics")
                .getBoundSql(params).getSql());
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
