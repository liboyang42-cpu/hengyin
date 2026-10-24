package com.chengyinhub.business.community;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 SQL visibilityPolicy 与 CommunityPostLifecyclePolicy.canView 的分工：
 * 访问规则两边都有；负反馈过滤只在 SQL。改动任一侧时需同步另一侧的注释与本测试。
 */
class CommunityPostVisibilityPolicyParityTest {

    @Test
    void sqlFragmentCarriesCanViewAccessRulesPlusFeedFilters() throws Exception {
        String xml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CommunityPostMapper.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = xml.indexOf("<sql id=\"visibilityPolicy\">");
        assertTrue(start >= 0, "visibilityPolicy 片段缺失");
        String sql = xml.substring(start, xml.indexOf("</sql>", start)).replaceAll("\\s+", " ");

        // canView 的访问规则
        assertTrue(sql.contains("p.author_id = #{viewerId} AND p.lifecycle&lt;&gt;'DELETED'"), sql);
        assertTrue(sql.contains("p.lifecycle='PUBLISHED'"), sql);
        assertTrue(sql.contains("p.audience='PUBLIC'"), sql);
        assertTrue(sql.contains("p.audience='FOLLOWERS'"), sql);
        assertTrue(sql.contains("p.audience='COMMUNITY'"), sql);
        assertTrue(sql.contains("FROM im_block b"), sql);
        assertTrue(sql.contains("FROM community_member_mute mu"), sql);
        // 仅 SQL 有的个性化负反馈过滤
        assertTrue(sql.contains("'NOT_INTERESTED'"), sql);
        assertTrue(sql.contains("'SEE_LESS_AUTHOR'"), sql);
        assertTrue(sql.contains("'SEE_LESS_TOPIC'"), sql);
    }
}
