package com.chengyinhub.business.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商家店铺参谋 mapper 的 SQL 守卫(模式同 AiLogMapperSqlTest:解析真 XML 生成 BoundSql,不连库不 mock)。
 *
 * 锁的口径:
 * - 三条 select 全部必须按 o.merchant_id 过滤 —— 漏了 = 商家看到别家数据(越权);
 * - redemption 必须只认 status = 'ACTIVE' —— 冲正的核销不算业绩;
 * - crowd 查询**禁止出现 nickname / phone 列** —— PII 白名单的 SQL 层防线
 *   (服务层测试只能证明「事实包里没有」,SQL 层不选出来才是根;两层各锁各的);
 * - registration 必须保留已支付+报名成功 (payment_status=2/registration_status=2) 的有效资格;
 *   cms_registration 真表没有 del_flag 列,不得引用它;商家主体必须过 mms_merchant 在营三重守卫。
 */
class MerchantInsightMapperSqlTest {

    private static final String XML = "mapper/business/MerchantInsightMapper.xml";
    private static final String NS = "com.chengyinhub.business.mapper.MerchantInsightMapper.";

    private Configuration parse() throws Exception {
        Configuration cfg = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(XML)) {
            new XMLMapperBuilder(in, cfg, XML, cfg.getSqlFragments()).parse();
        }
        return cfg;
    }

    private String sql(Configuration cfg, String statement) {
        Map<String, Object> param = new HashMap<>();
        param.put("merchantMemberId", 1L);
        param.put("since", new Date());
        return cfg.getMappedStatement(NS + statement)
                .getBoundSql(param)
                .getSql()
                .replaceAll("\\s+", " ")
                .toLowerCase();
    }

    @Test
    void allSelectsScopeByMerchantId() throws Exception {
        Configuration cfg = parse();
        for (String stmt : new String[] {"selectVisitFacts", "selectCrowdFacts", "selectSupplyFacts", "selectVisitorEvents"}) {
            assertTrue(sql(cfg, stmt).contains("o.merchant_id = ?"),
                    stmt + " 必须按 o.merchant_id 过滤,否则商家能看到别家数据");
        }
    }

    @Test
    void visitorEventsAllowDisplayFieldsButNoSensitivePii() throws Exception {
        // 单客行允许 nickname/avatar(商家名册页已有同语义先例),但手机号/实名是 PII 红线
        Configuration cfg = parse();
        String s = sql(cfg, "selectVisitorEvents");
        assertTrue(s.contains("m.nickname") && s.contains("m.avatar"), "单客行需要昵称与头像展示字段");
        assertFalse(s.contains("phone"), "单客行禁止选出手机号");
        assertFalse(s.contains("real_name"), "单客行禁止选出实名");
        assertTrue(s.contains("p.checkin_time is not null or rd.id is not null"),
                "事件行必须只取真有到店事件的行");
    }

    @Test
    void redemptionOnlyCountsActive() throws Exception {
        Configuration cfg = parse();
        assertTrue(sql(cfg, "selectVisitFacts").contains("rd.status = 'active'"),
                "核销必须只认 ACTIVE,冲正(REVERSED)不算业绩");
    }

    @Test
    void crowdSelectsNoPiiColumns() throws Exception {
        Configuration cfg = parse();
        String s = sql(cfg, "selectCrowdFacts");
        assertFalse(s.contains("nickname"), "画像查询禁止选出昵称");
        assertFalse(s.contains("phone"), "画像查询禁止选出手机号");
        // 正控:画像维度确实在选
        assertTrue(s.contains("m.sex") && s.contains("m.birthday"));
    }

    @Test
    void registrationGuardsPresent() throws Exception {
        Configuration cfg = parse();
        String s = sql(cfg, "selectVisitFacts");
        assertTrue(s.contains("r.payment_status = 2") && s.contains("r.registration_status = 2"),
                "报名必须保留已支付+报名成功的有效资格(退款/取消由状态排除)");
        assertFalse(s.contains("r.del_flag"), "cms_registration 没有 del_flag 列,不得再引用");
        assertTrue(s.contains("mm.status = 1"), "商家主体必须在营(mms_merchant.status=1)");
        assertTrue(s.contains("r.create_time >= ?"), "visit 事实必须收窄到时间窗口");
    }

    @Test
    void supplyGuardsValidEnd() throws Exception {
        Configuration cfg = parse();
        String s = sql(cfg, "selectSupplyFacts");
        assertTrue(s.contains("o.valid_end is null or o.valid_end > now()"),
                "在架供给必须排除已过期 offer");
        assertTrue(s.contains("t.del_flag = 0"), "主题必须排除软删行");
    }

    @Test
    void exposureVisitorsIsDistinctMemberCountOverSameScopeAsPv() throws Exception {
        // UV 版曝光必须按会员去重,且范围与 PV 版一致(只换聚合函数),否则两数不可比
        Configuration cfg = parse();
        String pv = sql(cfg, "countTopicExposure");
        String uv = sql(cfg, "countTopicExposureVisitors");
        assertTrue(uv.contains("count(distinct b.member_id)"), "UV 必须按 member_id 去重");
        assertTrue(pv.contains("count(*)"), "PV 仍为浏览次数");
        assertTrue(uv.replace("count(distinct b.member_id)", "count(*)").equals(pv), "UV 与 PV 只差聚合函数");
    }
}
