package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后超时转平台的 mapper 合同(拍板 2026-09-16 #12)。
 *
 * <p>单测(MerchantAftercareTimeoutEscalationTest)验的是服务层编排,这里验的是 SQL 本身:
 * 扫描口径、幂等条件、以及「只写转平台时间戳,不碰审核/打款状态」的边界 —— 撤掉任一守卫,本类判红。
 */
class MerchantAftercareTimeoutMapperContractTest {
    private static final String XML = "mapper/business/MerchantAftercareTimeoutMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantAftercareTimeoutMapper.";

    @Test
    void scanOnlyPicksPendingRefundsWithoutMerchantDecision() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("cutoff", new Date());
        String scan = sql(configuration, "selectStaleMerchantResponse", params);

        assertTrue(scan.contains("r.status = 0"), "只扫平台待审:" + scan);
        assertTrue(scan.contains("r.del_flag = 0"), scan);
        assertTrue(scan.contains("r.merchant_id is not null"), "没有商家归属的单不属于商家售后:" + scan);
        assertTrue(scan.contains("r.platform_takeover_at is null"), "已转过的不再扫(幂等):" + scan);
        assertTrue(scan.contains("r.create_time is not null and r.create_time <= ?"), "必须按建单时间过 24h 窗口:" + scan);
        assertTrue(scan.contains("not exists ( select 1 from merchant_aftercare_response resp"),
                "商家已回应的单不得转平台:" + scan);
        assertTrue(scan.contains("resp.decision in ('agree', 'reject')"),
                "回应口径与「待回应」桶一致:同意/驳回才算回应:" + scan);
        assertTrue(scan.contains("limit 200"), "单轮扫描必须有上限:" + scan);
    }

    @Test
    void takeoverUpdateIsIdempotentAndNeverTouchesReviewOrPayoutState() throws Exception {
        Configuration configuration = parse();
        Map<String, Object> params = new HashMap<>();
        params.put("refundId", 41L);
        params.put("takeoverAt", new Date());
        String update = sql(configuration, "markPlatformTakeover", params);

        assertTrue(update.contains("platform_takeover_at = ?"), update);
        assertTrue(update.contains("where id = ?"), "必须按单号窄写:" + update);
        assertTrue(update.contains("platform_takeover_at is null"), "条件更新才是幂等真源:" + update);
        assertTrue(update.contains("status = 0"), "只对仍待审的单生效:" + update);
        assertFalse(update.contains("set status"), "转平台不得改审核状态:" + update);
        assertFalse(update.contains("payout_status"), "转平台不得改打款状态:" + update);
    }

    private Configuration parse() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String id, Object parameter) {
        return normalized(configuration.getMappedStatement(NS + id).getBoundSql(parameter).getSql());
    }

    private String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
