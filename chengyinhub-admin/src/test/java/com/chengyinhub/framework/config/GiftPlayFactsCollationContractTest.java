package com.chengyinhub.framework.config;

import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * qx-c3 #1: {@code cms_registration.parent_order_no} is utf8mb4_general_ci while {@code marketing_product_order}
 * (created with only {@code DEFAULT CHARSET=utf8mb4}) lands on MySQL 8's utf8mb4_0900_ai_ci. Comparing the two
 * IMPLICIT collations raises error 1267, which made {@code GiftEntitlementMapper.selectPlayFactsByOwner} — and with it
 * every self-play admission ({@code CurrentPlayRegistration.giftFacts}, e.g. {@code /api/play/theme/state}) — answer 500
 * on a fresh MySQL 8 schema. The join must carry an explicit collation and still pass the app's Druid wall.
 * The real-MySQL proof is GenericGameplayHttpE2eIT; this guards the statement shape without a database.
 */
class GiftPlayFactsCollationContractTest {

    private static String boundSql() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/business/GiftEntitlementMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.chengyinhub.business.mapper.GiftEntitlementMapper.selectPlayFactsByOwner", false)
                .getBoundSql(Map.of("memberId", 7L, "ownerType", 1, "ownerId", 9L)).getSql();
    }

    @Test
    void parentOrderJoinPinsAnExplicitCollation() throws Exception {
        String sql = boundSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.contains("join cms_registration r on r.parent_order_no = o.order_no collate utf8mb4_general_ci"),
                "the cross-table order-number join must not compare two IMPLICIT collations: " + sql);
    }

    @Test
    void statementPassesTheAppWall() throws Exception {
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        MySqlWallProvider wall = new MySqlWallProvider(config);
        String sql = boundSql();
        assertTrue(wall.check(sql).getViolations().isEmpty(), String.valueOf(wall.check(sql).getViolations()));
    }
}
