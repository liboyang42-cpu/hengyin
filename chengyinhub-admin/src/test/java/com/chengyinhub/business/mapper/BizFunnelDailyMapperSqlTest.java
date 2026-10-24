package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BizFunnelDailyMapperSqlTest {
    @Test
    void communityPostFunnelKeepsImpressionOpenAndQualifiedViewDistinct() throws Exception {
        String resource = "mapper/business/BizFunnelDailyMapper.xml";
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(
                "BizFunnelDaily", com.chengyinhub.business.domain.BizFunnelDaily.class);
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        BoundSql bound = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.BizFunnelDailyMapper.insertFromEventLog")
                .getBoundSql("2026-09-10");
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("community_post_engagement"));
        assertTrue(sql.contains("community_post_impression"));
        assertTrue(sql.contains("community_post_open"));
        assertTrue(sql.contains("community_post_qualified_view"));
        assertTrue(sql.contains("insert into biz_funnel_daily (stat_date, funnel_code, step_code, source, city_code, user_count"),
                "聚合写入必须使用真实迁移定义的列，不能引用不存在的旧模型列");
        assertTrue(sql.contains("occurred_at >= cast(? as date)"));
        assertTrue(sql.contains("occurred_at <= timestamp(cast(? as date), '23:59:59')"),
                "结束边界取当日最后一秒，不做会在 9999-12-31 溢出的日期加法");
        assertTrue(!sql.contains("where date(occurred_at)"), "时间范围必须可使用 occurred_at 前导索引");
        assertTrue(sql.contains("community_post cp"));
        assertTrue(sql.contains("cp.author_id <> event.member_id"),
                "有效阅读必须从聚合口径排除作者自阅，不能只相信客户端");
        assertTrue(sql.contains("over (partition by"));
        assertTrue(sql.contains("with normalized as"));
        assertTrue(sql.contains("left join funnel_starts starter"));
        assertTrue(sql.contains("starter.member_id is not null"),
                "下游人数只能统计同日同漏斗起点 cohort 中的用户");
        assertTrue(sql.contains("starter.first_started_at <= event.occurred_at"),
                "下游事件必须发生在该用户首次起点之后，不能用同日未来事件倒灌 cohort");
        assertTrue(sql.contains("cp.id is not null"),
                "合格阅读只能聚合仍能关联到真实帖文的事件");
        assertTrue(!sql.contains("0 as conversion_rate"),
                "漏斗转化率必须由首步分母计算，不能永久写假 0");
    }

    @Test
    void readModelKeepsLegacyAndCommunityEngagementStepOrdersDistinct() throws Exception {
        String resource = "mapper/business/BizFunnelDailyMapper.xml";
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(
                "BizFunnelDaily", com.chengyinhub.business.domain.BizFunnelDaily.class);
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        com.chengyinhub.business.domain.BizFunnelDaily filter =
                new com.chengyinhub.business.domain.BizFunnelDaily();
        BoundSql bound = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.BizFunnelDailyMapper.selectBizFunnelDailyList")
                .getBoundSql(filter);
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("when 'content_like' then case when funnel_code = 'community_post_engagement' then 4 else 2 end"));
        assertTrue(sql.contains("when 'content_favorite' then case when funnel_code = 'community_post_engagement' then 5 else 3 end"));
        assertTrue(sql.contains("when 'content_share' then case when funnel_code = 'community_post_engagement' then 6 else 4 end"));
        assertTrue(sql.contains("when 'signup_start' then 4 when 'signup_submit' then 5 when 'payment_success' then 6"),
                "活动转化漏斗起点 signup_start 必须排在提交/支付之前，不能落到 else 99 排最后");
        assertTrue(sql.contains("funnel_code as funnel_name"));
        assertTrue(sql.contains("source as biz_type"));
        assertTrue(sql.contains("user_count as member_count"));
    }

    @Test
    void pilotQualifiedDenominatorDeduplicatesUserPostAcrossTheWholeWindow() throws Exception {
        String resource = "mapper/business/BizFunnelDailyMapper.xml";
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(
                "BizFunnelDaily", com.chengyinhub.business.domain.BizFunnelDaily.class);
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("startDate", "2026-09-01");
        params.put("endDate", "2026-09-28");
        BoundSql bound = configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.BizFunnelDailyMapper.selectCommunityQualifiedPilotMetric")
                .getBoundSql(params);
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("group by event.member_id, event.biz_id, post.author_id"),
                "试点窗口必须先按 user-post 去重，不能把每日汇总相加");
        assertTrue(sql.contains("count(distinct member_id) as qualified_user_count"));
        assertTrue(sql.contains("count(1) as qualified_user_post_count"));
        assertTrue(sql.contains("count(distinct biz_id) as qualified_post_count"));
        assertTrue(sql.contains("left join community_post post"));
        assertTrue(sql.contains("count(distinct nullif(current_author_id, 0)) as current_qualified_author_count"),
                "作者覆盖明确是当前、未擦除作者口径，不能伪装成不可变历史快照");
        assertTrue(sql.contains("max(event.occurred_at) as last_qualified_at"));
        assertTrue(sql.contains("max(last_qualified_at) as last_qualified_at"));
        assertFalse(sql.contains("post.lifecycle = 'published'"),
                "历史试点分母不能因帖子后来隐藏或删除而无新事件回退");
    }
}
