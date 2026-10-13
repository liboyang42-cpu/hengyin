package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RefundApplication;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商家售后超时转平台(拍板 2026-09-16 #12)在后台退款台的读侧合同:
 * 列表要能筛选「已转平台/未转」、能按转平台时间倒序,并且把 platform_takeover_at 真读回来。
 *
 * <p>撤掉任一守卫本类判红;筛选与排序参数只在查询里生效,不碰 insert/update 的写入面。
 */
class RefundApplicationPlatformTakeoverMapperContractTest {
    private static final String XML = "mapper/business/RefundApplicationMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.RefundApplicationMapper.";

    @Test
    void listSelectReadsPlatformTakeoverColumnAndDefaultsToIdOrder() throws Exception {
        String sql = sql(parse(), new RefundApplication());

        assertTrue(sql.contains("platform_takeover_at"), "列表必须把转平台时间读回来:" + sql);
        assertTrue(sql.contains("order by id desc"), "默认排序必须是原来的 id desc:" + sql);
        assertFalse(sql.contains("platform_takeover_at is not null"), "不带筛选时不得加转平台条件:" + sql);
    }

    @Test
    void platformTakeoverFilterSelectsTakenOverOrNot() throws Exception {
        RefundApplication taken = new RefundApplication();
        taken.setPlatformTakeover(1);
        String takenSql = sql(parse(), taken);
        assertTrue(takenSql.contains("platform_takeover_at is not null"), "只看已转平台:" + takenSql);

        RefundApplication notTaken = new RefundApplication();
        notTaken.setPlatformTakeover(0);
        String notTakenSql = sql(parse(), notTaken);
        assertTrue(notTakenSql.contains("platform_takeover_at is null"), "只看未转:" + notTakenSql);
    }

    @Test
    void takeoverOrderSortsTransferredFirstByTakeoverTime() throws Exception {
        RefundApplication ordered = new RefundApplication();
        ordered.setTakeoverOrder(1);
        String sql = sql(parse(), ordered);

        assertTrue(sql.contains("order by platform_takeover_at is null asc, platform_takeover_at desc, id desc"),
                "按转平台时间倒序,未转的排最后:" + sql);
    }

    private Configuration parse() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, Object parameter) {
        return normalized(configuration.getMappedStatement(NS + "selectRefundApplicationList")
                .getBoundSql(parameter).getSql());
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
