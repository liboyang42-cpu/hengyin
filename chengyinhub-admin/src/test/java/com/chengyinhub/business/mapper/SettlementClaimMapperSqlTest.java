package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SettlementClaim;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementClaimMapperSqlTest {
    private static final String CLAIM_XML = "mapper/business/SettlementClaimMapper.xml";
    private static final String DETAIL_XML = "mapper/business/CoopSettlementDetailMapper.xml";

    @Test
    void claimInsertAndLockUseAllSevenEconomicKeyColumnsButNotRoute() throws Exception {
        Configuration configuration = parse(CLAIM_XML, "SettlementClaim", SettlementClaim.class);
        SettlementClaim claim = SettlementClaim.request("TOPIC_EDITION", 1L, "REGISTRATION", 2L,
                "MERCHANT", 3L, "MERCHANT_PARTICIPATION", "COOP_ORDER", "COOP_ORDER", 4L);
        String insert = normalized(configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.SettlementClaimMapper.insertClaim")
                .getBoundSql(claim).getSql());
        String lock = normalized(configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.SettlementClaimMapper.selectByEconomicKeyForUpdate")
                .getBoundSql(claim).getSql());

        for (String column : new String[] {"commercial_context_type", "commercial_context_id",
                "fulfillment_type", "fulfillment_id", "beneficiary_role", "beneficiary_id", "service_unit"}) {
            assertTrue(insert.contains(column), "claim insert 缺七列之一:" + column + ",实际:" + insert);
            assertTrue(lock.contains(column + " = ?"), "冲突回读 WHERE 缺七列之一:" + column + ",实际:" + lock);
        }
        assertFalse(lock.substring(lock.indexOf(" where ")).contains("settlement_route ="),
                "route 不得进入 economicClaimKey,否则换轨可重复领");
        assertTrue(lock.endsWith("for update"), "重复/冲突判定必须锁定当前 claim");
    }

    @Test
    void coopHeaderRefreshOnlyAggregatesOriginDetails() throws Exception {
        Configuration configuration = parse(DETAIL_XML, "CoopSettlementDetail",
                com.chengyinhub.business.domain.CoopSettlementDetail.class);
        BoundSql sql = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.CoopSettlementDetailMapper.refreshOriginAggregate")
                .getBoundSql(99L);
        String value = normalized(sql.getSql());
        assertTrue(value.contains("sum(detail.origin_amount)"), "头金额必须来自 detail.origin_amount SUM:" + value);
        assertTrue(value.contains("count(distinct detail.registration_id)"),
                "头人头必须来自 detail.registration_id 去重数:" + value);
    }

    @Test
    void migrationLocksNotNullUniqueAndEventIdempotency() throws Exception {
        Path migration = Paths.get("sql/migration_settlement_claim_20260811.sql");
        String sql = normalized(new String(Files.readAllBytes(migration), StandardCharsets.UTF_8));
        assertFalse(sql.contains("create table if not exists"), "MySQL8 migration 禁 IF NOT EXISTS 静默吞半迁移");
        String unique = sql.substring(sql.indexOf("unique key uk_settlement_claim_key"),
                sql.indexOf("key idx_claim_source"));
        for (String column : new String[] {"commercial_context_type", "commercial_context_id",
                "fulfillment_type", "fulfillment_id", "beneficiary_role", "beneficiary_id", "service_unit"}) {
            assertTrue(unique.contains(column), "DB UNIQUE 缺 claimKey 列:" + column);
        }
        assertFalse(unique.contains("settlement_route"), "route 不得进入 DB UNIQUE");
        assertTrue(sql.contains("unique key uk_coop_settlement_detail_claim (claim_id)"),
                "一个 claim 不能挂进两个 coop 聚合头");
        assertTrue(sql.contains("uk_merchant_payable_claim_event (claim_id, entry_type, event_key)"),
                "merchant_payable 缺 claim 事件幂等唯一");
        assertTrue(sql.contains("uk_settlement_detail_claim_event (claim_id, entry_kind, event_key)"),
                "settlement_detail 缺 claim 事件幂等唯一");
        assertTrue(sql.contains("uk_coop_adjustment_event (claim_id, entry_kind, event_key)"),
                "coop 调整账缺 claim 事件幂等唯一");
    }

    private static Configuration parse(String resource, String alias, Class<?> type) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(alias, type);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("resource missing:" + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
