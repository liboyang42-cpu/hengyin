package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RewardDropRule;
import com.chengyinhub.business.service.IRewardDropService;
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

/** MyBatis 动态 SQL 闸：场景分流必须在真正发给数据库的 SQL 中生效。 */
class RewardDropRuleMapperSqlTest {
    private BoundSql rules(Long topicId, String sceneKey) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RewardDropRule", RewardDropRule.class);
        String resource = "mapper/business/RewardDropRuleMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("topicId", topicId);
        params.put("sceneKey", sceneKey);
        return configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.RewardDropRuleMapper.selectApplicableRules")
                .getBoundSql(params);
    }

    private String normalized(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    @Test
    void roamSceneExcludesTopicScopeAndRequiresExactScenePredicate() throws Exception {
        BoundSql bound = rules(0L, IRewardDropService.SCENE_ROAM_MERCHANT_POI);
        String sql = normalized(bound);

        assertFalse(sql.contains("scope = 1"), "roam 不得吃到主题规则。实际: " + sql);
        assertTrue(sql.contains("scope = 2 and scene_key = ?"),
                "scope=2 必须由 scene_key 精确收窄，禁止裸 scope 串场。实际: " + sql);
        assertTrue(bound.getParameterMappings().stream()
                        .anyMatch(mapping -> "sceneKey".equals(mapping.getProperty())),
                "scene_key 占位必须绑定调用方传入的场景，不得硬编码");
    }

    @Test
    void legacyTopicSceneKeepsTopicScopeAndCannotEatAnotherScene() throws Exception {
        String sql = normalized(rules(9L, IRewardDropService.SCENE_TOPIC_NODE));

        assertTrue(sql.contains("scope = 1 and topic_id = ?"), "旧主题规则分支不得回归。实际: " + sql);
        assertTrue(sql.contains("scope = 2 and scene_key = ?"),
                "场景规则必须按当前 scene_key 匹配，不得误吃 roam 规则。实际: " + sql);
    }
}
