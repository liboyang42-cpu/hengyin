package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CmsTopic;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PublicTopicManualCountContractTest {
    @Test
    void explicitCountUsesExactlyTheListsVisibilityAndFilterPredicates() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopic", CmsTopic.class);
        String resource = "mapper/business/CmsTopicMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        for (Long viewer : new Long[] {null, 101L}) {
            for (boolean filters : new boolean[] {false, true}) {
                CmsTopic query = new CmsTopic();
                query.setViewerMemberId(viewer);
                if (filters) {
                    query.setClubId(201L);
                    query.setName("fixture");
                    query.setCategoryIds("3");
                    query.setCityKeyword("Shanghai");
                    query.setProductType(1);
                    query.setSelfPlay(1);
                    query.setIsRecommend(1);
                    query.setIsLike(1);
                    query.setIsLikeMemberId(101L);
                }
                String namespace = "com.chengyinhub.business.mapper.CmsTopicMapper.";
                BoundSql rows = configuration.getMappedStatement(namespace + "selectPublicCmsTopicList").getBoundSql(query);
                BoundSql count = configuration.getMappedStatement(namespace + "selectPublicCmsTopicList_COUNT").getBoundSql(query);
                assertEquals(predicate(rows), predicate(count), "count must preserve private/member/purchased visibility");
                assertEquals(rows.getParameterMappings().stream().map(ParameterMapping::getProperty).collect(Collectors.toList()),
                        count.getParameterMappings().stream().map(ParameterMapping::getProperty).collect(Collectors.toList()));
                assertTrue(count.getSql().trim().startsWith("select count(*) from cms_topic"));
                assertFalse(count.getSql().toLowerCase().contains("order by"));
                assertTrue(predicate(count).contains("club_member_only"));
                assertTrue(predicate(count).contains("viewer_reg.payment_status = 2"));
                assertTrue(predicate(count).contains("circle_theme_code"));
            }
        }
    }

    private String predicate(BoundSql sql) {
        String text = sql.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
        int start = text.indexOf(" where ");
        int end = text.lastIndexOf(" order by ");
        return text.substring(start, end < 0 ? text.length() : end);
    }
}
