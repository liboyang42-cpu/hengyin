package com.chengyinhub.business.opsconsole;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.RewardDropRule;

/** R10 新增/扩展的 mapper XML 都能被 MyBatis 解析，且每个接口方法都有对应语句。 */
class OpsConsoleMapperXmlTest
{
    @Test
    void allR10MapperXmlParseAndCoverTheirInterfaces() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("RewardDropRule", RewardDropRule.class);
        List<String> resources = List.of("mapper/business/RewardDropRuleMapper.xml",
                "mapper/business/rewardpledge/RewardPledgeAdminMapper.xml",
                "mapper/business/opsconsole/ChannelModerationAdminMapper.xml",
                "mapper/business/opsconsole/JourneyRulesAdminMapper.xml",
                "mapper/business/opsconsole/RoamPlayOpsMapper.xml");
        for (String resource : resources)
            try (InputStream input = Resources.getResourceAsStream(resource))
            {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        for (Class<?> type : List.of(com.chengyinhub.business.mapper.RewardDropRuleMapper.class,
                com.chengyinhub.business.mapper.rewardpledge.RewardPledgeAdminMapper.class,
                com.chengyinhub.business.mapper.opsconsole.ChannelModerationAdminMapper.class,
                com.chengyinhub.business.mapper.opsconsole.JourneyRulesAdminMapper.class,
                com.chengyinhub.business.mapper.opsconsole.RoamPlayOpsMapper.class))
            for (java.lang.reflect.Method m : type.getDeclaredMethods())
                assertTrue(configuration.hasStatement(type.getName() + "." + m.getName()), type.getSimpleName() + "." + m.getName() + " 缺少 SQL");

        // 生产数据源开着 Druid wall：每条新语句按全部可选条件展开后都必须过 wall。
        com.alibaba.druid.wall.WallConfig config = new com.alibaba.druid.wall.WallConfig(
                com.alibaba.druid.wall.spi.MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        com.alibaba.druid.wall.spi.MySqlWallProvider wall = new com.alibaba.druid.wall.spi.MySqlWallProvider(config);
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        for (String k : List.of("keyword", "sceneKey", "state", "status", "since", "until", "runState", "json", "expected", "note",
                "versionId", "pledgeId", "policyCode", "ruleVersion", "decision", "hash", "kind", "from", "to"))
            params.put(k, "x");
        for (String k : List.of("id", "topicId", "guildId", "channelId", "senderId", "beforeId", "memberId", "ownerId", "storeId",
                "targetMemberId", "messageId", "conversationId", "version", "sysUserId", "actorMemberId", "clubId", "teamId", "at"))
            params.put(k, 1L);
        for (String k : List.of("guildKind", "ownerType", "limit", "scope", "dropType", "weight"))
            params.put(k, 1);
        RewardDropRule probe = new RewardDropRule();
        probe.setScope(2); probe.setTopicId(1L); probe.setSceneKey("x"); probe.setDropType(1); probe.setStatus(1); probe.setWeight(1); probe.setParamJson("{}");
        int checked = 0;
        for (Object o : configuration.getMappedStatements())
        {
            if (!(o instanceof org.apache.ibatis.mapping.MappedStatement ms)) continue;
            Object arg = ms.getParameterMap().getType() == RewardDropRule.class || ms.getId().endsWith("selectAdminList")
                    || ms.getId().endsWith("insertRule") || ms.getId().endsWith("updateRule") ? probe : params;
            String sql = ms.getBoundSql(arg).getSql();
            com.alibaba.druid.wall.WallCheckResult result = wall.check(sql);
            assertTrue(result.getViolations().isEmpty(), ms.getId() + " 被 Druid wall 拒绝：" + result.getViolations() + "\n" + sql);
            checked++;
        }
        assertTrue(checked >= 30, "应检查全部新语句，实际 " + checked);
    }
}
