package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.PlayerTopicTag;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTopicTagMapperSqlTest {

    @Test
    void ordinaryUpsertCannotResurrectConfirmedOrRevokedTag() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("PlayerTopicTag", PlayerTopicTag.class);
        String resource = "mapper/business/PlayerTopicTagMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String sql = configuration.getMappedStatement(
                        "com.chengyinhub.business.mapper.PlayerTopicTagMapper.upsertPending")
                .getBoundSql(new PlayerTopicTag()).getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        String duplicate = sql.substring(sql.indexOf("on duplicate key update"));

        assertTrue(duplicate.contains("if(status = 0, values(tag_value), tag_value)"), duplicate);
        assertTrue(duplicate.contains("if(status = 0, values(source_node_id), source_node_id)"), duplicate);
        assertFalse(duplicate.matches(".*(?:^|,)\\s*status\\s*=\\s*0(?:,|$).*"), duplicate);
    }
}
