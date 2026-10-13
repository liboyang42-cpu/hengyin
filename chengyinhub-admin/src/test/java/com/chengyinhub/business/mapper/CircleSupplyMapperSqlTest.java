package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公开圈层供给的“有效”定义必须在列表和计数查询间复用。 */
class CircleSupplyMapperSqlTest {
    private static final String RESOURCE = "mapper/business/MerchantCircleSupplyProfileMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantCircleSupplyProfileMapper.";

    @Test
    void publicListFailsClosedOnStatusSelectionFreshnessAndEventEnd() throws Exception {
        String sql = sql("selectEffectiveByTopicId");

        assertTrue(sql.contains("o.status = 1"));
        assertTrue(sql.contains("o.valid_end is null or o.valid_end > now()"));
        assertTrue(sql.contains("ch.circle_enabled = 1"));
        assertTrue(sql.contains("p.is_current = 1"));
        assertTrue(sql.contains("t.is_template = 0"));
        assertTrue(sql.contains("date_sub(now(), interval 7 day)"));
        assertTrue(sql.contains("p.event_end_at > now()"));
    }

    @Test
    void visibilityCountsDistinctMerchantsInsteadOfOffers() throws Exception {
        String sql = sql("countEffectiveMerchantsByTopicId");
        assertTrue(sql.contains("count(distinct o.merchant_id)"));
        assertTrue(sql.contains("date_sub(now(), interval 7 day)"));
    }

    @Test
    void reconfirmationRetiresOnlyTheCurrentSnapshotByOfferId() throws Exception {
        String sql = sql("retireCurrentSnapshot");

        assertTrue(sql.contains("set is_current = 0"));
        assertTrue(sql.contains("where offer_id = ? and is_current = 1"));
    }

    private static String sql(String statement) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(
                "MerchantCircleSupplyProfile", MerchantCircleSupplyProfile.class);
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(NS + statement)
                .getBoundSql(Collections.singletonMap("topicId", 9L)).getSql()
                .replaceAll("\\s+", " ").toLowerCase();
    }
}
