package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ViewCreativeSquare;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 数据层负控：关注和 Block 资格必须在分页 SQL 里完成，不能取回后再前端过滤。 */
class ViewCreativeSquareCommunityPolicySqlTest {
    private static final String XML = "mapper/business/ViewCreativeSquareMapper.xml";
    private static final String STATEMENT = "com.chengyinhub.business.mapper.ViewCreativeSquareMapper.selectViewCreativeSquareList";

    @Test
    void followingAndBlockPoliciesAreAppliedBeforePagination() throws Exception {
        ViewCreativeSquare query = new ViewCreativeSquare();
        query.setStatus(1);
        query.setFollowingMemberId(8L);
        query.setViewerMemberId(8L);

        String sql = sql(query);

        assertTrue(sql.contains("exists (") && sql.contains("from ums_member_follow"));
        assertTrue(sql.contains("not exists (") && sql.contains("from im_block"));
        assertTrue(sql.indexOf("from im_block") < sql.indexOf("order by"),
                "Block 必须在 SQL 分页前过滤，不能产生空洞页");
    }

    @Test
    void favoritePolicyIsAppliedBeforePagination() throws Exception {
        ViewCreativeSquare query = new ViewCreativeSquare();
        query.setViewerMemberId(8L);
        query.setFavoriteOnly(true);

        String sql = sql(query);

        assertTrue(sql.contains("from community_post_member_action bookmark"));
        assertTrue(sql.indexOf("community_post_member_action") < sql.indexOf("order by"));
    }

    private String sql(ViewCreativeSquare query) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ViewCreativeSquare", ViewCreativeSquare.class);
        try (InputStream input = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(input, configuration, XML, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(STATEMENT).getBoundSql(query).getSql()
                .replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
