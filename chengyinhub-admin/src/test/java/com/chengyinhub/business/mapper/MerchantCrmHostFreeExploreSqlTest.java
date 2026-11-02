package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.dto.MerchantCrmCustomerQuery;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走查 v2 R07(2026-11-01):自由探索不进通用名册;只有主题工作台按主题定向广播、且主题是本商家自己发布的
 * 自由探索时,名册口径多放开这一个主题。用真 MyBatis 解析 XML,确认没设标记时 SQL 与原来一致。
 */
class MerchantCrmHostFreeExploreSqlTest {
    private static final String APP_XML = "mapper/business/MerchantCrmAppMapper.xml";

    @Test
    void freeExploreTopicOpensOnlyWhenServiceSetsTheHostFlag() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(APP_XML)) {
            new XMLMapperBuilder(input, configuration, APP_XML, configuration.getSqlFragments()).parse();
        }
        MappedStatement candidates = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.MerchantCrmAppMapper.selectOwnedBroadcastCandidates");

        MerchantCrmCustomerQuery query = new MerchantCrmCustomerQuery();
        query.setMerchantId(3L);
        query.setTopicId(990068L);
        BoundSql plain = candidates.getBoundSql(params(query));
        String plainSql = plain.getSql().replaceAll("\\s+", " ");
        assertTrue(plainSql.contains("t.product_type <> 2"), "通用名册仍排除自由探索");
        assertFalse(plainSql.contains("r.owner_id = ? and r.payment_status in (2, 4)"), "没设标记不放开");

        query.setHostFreeExploreTopicId(990068L);
        BoundSql host = candidates.getBoundSql(params(query));
        String hostSql = host.getSql().replaceAll("\\s+", " ");
        assertTrue(hostSql.contains("or (r.owner_type = 1 and r.owner_id = ? and r.payment_status in (2, 4))"));
        assertEquals(plain.getParameterMappings().size() + 1, host.getParameterMappings().size());
        assertTrue(host.getParameterMappings().stream()
                .anyMatch(m -> "query.hostFreeExploreTopicId".equals(m.getProperty())));

        MappedStatement owned = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.MerchantCrmAppMapper.countFreeExploreTopicPublishedBy");
        Map<String, Object> ownedParams = new HashMap<>();
        ownedParams.put("ownerMemberId", 7L);
        ownedParams.put("topicId", 990068L);
        String ownedSql = owned.getBoundSql(ownedParams).getSql().replaceAll("\\s+", " ");
        assertTrue(ownedSql.contains("t.product_type = 2 and t.member_id = ?"), "只认本商家自己发布的自由探索主题");
    }

    private Map<String, Object> params(MerchantCrmCustomerQuery query) {
        Map<String, Object> params = new HashMap<>();
        params.put("ownerMemberId", 7L);
        params.put("query", query);
        params.put("limit", 200);
        return params;
    }
}
