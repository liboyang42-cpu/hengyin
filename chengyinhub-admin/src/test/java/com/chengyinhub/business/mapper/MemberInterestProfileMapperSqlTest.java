package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberInterestProfileMapperSqlTest {
    @Test
    void categorySignalIsAtomicallyIncrementedWithoutOverwritingCreatorWeights() throws Exception {
        String resource = "mapper/business/MemberInterestProfileMapper.xml";
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(
                "MemberInterestProfile", com.chengyinhub.business.domain.MemberInterestProfile.class);
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("memberId", 1001L);
        params.put("categoryPath", "$.community_post");
        params.put("weight", 1);
        BoundSql bound = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.MemberInterestProfileMapper.incrementMemberCategoryWeight")
                .getBoundSql(params);
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("category_weights_json = json_set"));
        assertTrue(sql.contains("json_extract"));
        assertTrue(sql.contains("+ ?"));
        assertTrue(sql.contains("city_code = coalesce"));
        assertFalse(sql.contains("creator_weights_json ="));
    }
}
