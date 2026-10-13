package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-50 计酬「全通关必亏」硬闸(D21)+ ③ 强制走招募。
 *
 * <p><b>闸拦的是什么</b>:③-自营 的票款全归平台,而计酬档的人头费是平台真金白银往外付的 ——
 * 收入固定、支出按核销次数累加。5 个必选章 × ¥20/人 = ¥100 &gt; ¥99 票价 ⇒ 每张全通关的票都亏本金。
 *
 * <p><b>为什么会走到这一步</b>:BD 是逐家谈的,单看每笔都合理(¥20 换一个到店客,比美团 CPC 便宜),
 * 谈第 5 家时没人回头把前 4 家加起来跟票价比。局部合理、全局爆炸。而且它**静默**:
 * 卖票时不亏(收了 ¥99),亏损分 5 次发生在之后,每次核销都成功、都不报错。
 * coop 那边有「margin&lt;0 → 守恒断言拒结」兜着,但 ③-自营 的计酬走**平台应付台账**,
 * 不经结算守恒 —— 没有任何现成的闸会碰到它。
 *
 * <p><b>为什么只拦「必亏」不用 40% KPI</b>:§8 的 25%/40% 是**实际支出占票款比**(事后统计,
 * 分母是真实核销次数);闸能算的是**最坏情况占票价比**(假设全通关)。两者差一个核销率 ——
 * 按 §4.5 的工作假设(20% 核销),同一套配置事后是 20% 健康、最坏情况却可能超 100%。
 * 拿 40% 当事前闸会误杀健康配置。风险的真实形状是「**产品越成功,亏得越多**」。
 *
 * <p>⚠️ <b>本类不测 confirmPrice 本身</b>:它依赖 6 个 mapper + previewTopic 的完整定价链,
 * 手搭代价过大。闸的**可达性**由 {@link RevshareGateWiringTest} 的源码断言守 ——
 * 那才是这次真正的风险点(2026-07-16 查生产库:17 个主题 lifecycle 全是 0,
 * 零个走过 confirmPrice ⇒ 闸挂上去若不同时堵住「不走招募」,就是死代码)。
 */
class RevshareAffordabilityTest {

    /**
     * 上界公式的口径:**每章取 max 再求和**,不是全部求和。
     *
     * <p>⚠️ 这条原本写的是 {@code assertEquals(30, 20 + 10)} —— 一个**重言式**:
     * 注释把口径讲得头头是道,断言测的却是字面量算术,SQL 怎么改它都绿。
     * 公式只活在 SQL 里,所以只能断言 SQL 的形状。
     */
    @Test
    void formula_maxPerChapterThenSum() throws Exception {
        String sql = mapperSql("sumMaxPerHeadFeeByRequiredChapters");

        assertTrue(sql.contains("max(o.per_head_fee)"),
                "每章必须取 max —— 玩家去哪家平台控制不了,只有最贵那家才是上界。实际: " + sql);
        assertTrue(sql.contains("group by o.chapter_id"),
                "必须按章分组 —— 不分组就成了全主题一个 max,严重低估。实际: " + sql);
        assertTrue(sql.contains("sum(mx)"),
                "分组后必须求和 —— 一张票要走完所有必选章。实际: " + sql);
        assertTrue(sql.contains("c.required = 1"),
                "只数必选章 —— 可选章玩家可以不去,不构成「全通关必付」的成本。实际: " + sql);
        assertTrue(sql.contains("o.terms_mode = 'revshare'") || sql.contains("terms_mode = 'revshare'"),
                "只数计酬档 —— 权益档的成本商家自己承担,平台为 0。实际: " + sql);
    }

    /**
     * 「生效」两条轴缺一不可。
     * <p>valid_end 到期**不会**自动翻 status(迁移文件原文警告,且没有任何任务在做这件事)⇒
     * 裸写 status=1 会把已过期的计酬供给算进成本上界,把闸卡得过严、误杀健康配置。
     */
    @Test
    void formula_liveOfferBothAxes() throws Exception {
        String sql = mapperSql("sumMaxPerHeadFeeByRequiredChapters");
        assertTrue(sql.contains("o.status = 1"), "缺 status 轴。实际: " + sql);
        assertTrue(sql.contains("valid_end is null") && sql.contains("valid_end >"),
                "**缺 valid_end 轴** —— 过期供给会被算进成本上界。实际: " + sql);
    }

    /** 解析真 XML 拿 BoundSql,不连库。 */
    private String mapperSql(String stmt) throws Exception {
        String res = "mapper/business/MerchantChapterOfferMapper.xml";
        org.apache.ibatis.session.Configuration cfg = new org.apache.ibatis.session.Configuration();
        cfg.getTypeAliasRegistry().registerAlias("MerchantChapterOffer",
                com.chengyinhub.business.domain.MerchantChapterOffer.class);
        try (java.io.InputStream in = org.apache.ibatis.io.Resources.getResourceAsStream(res)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, cfg, res, cfg.getSqlFragments()).parse();
        }
        java.util.Map<String, Object> p = new java.util.HashMap<String, Object>();
        p.put("topicId", 1L);
        return cfg.getMappedStatement("com.chengyinhub.business.mapper.MerchantChapterOfferMapper." + stmt)
                .getBoundSql(p).getSql().replaceAll("\\s+", " ").toLowerCase();
    }

    /** 常量契约:只有权益档走 quota,只有计酬档要平台掏钱。 */
    @Test
    void termsMode_contract() {
        assertTrue(TermsMode.usesQuota(TermsMode.PERK), "权益档承诺有限实物权益,卖超了商家赔不起");
        assertFalse(TermsMode.usesQuota(TermsMode.REVSHARE), "计酬档 MVP 不设接待上限(D21)");
        assertFalse(TermsMode.usesQuota(TermsMode.TRAFFIC), "引流档同上");

        assertTrue(TermsMode.platformPays(TermsMode.REVSHARE), "只有计酬档要平台掏钱 ⇒ 只有它需要必亏闸+应付台账");
        assertFalse(TermsMode.platformPays(TermsMode.PERK), "权益档的成本商家自己承担,平台为 0");
        assertFalse(TermsMode.platformPays(TermsMode.TRAFFIC), "引流档零结算");
    }

    /** 三档合法性,别让将来加枚举时漏掉一个。 */
    @Test
    void termsMode_validity() {
        assertTrue(TermsMode.isValid(TermsMode.TRAFFIC));
        assertTrue(TermsMode.isValid(TermsMode.REVSHARE));
        assertTrue(TermsMode.isValid(TermsMode.PERK));
        assertFalse(TermsMode.isValid("BOGUS"));
        assertFalse(TermsMode.isValid(null));
    }

    /** mapper 方法必须存在且签名对 —— 闸整个挂在它上面。 */
    @Test
    void aggregateQuery_exists() throws Exception {
        java.lang.reflect.Method m = MerchantChapterOfferMapper.class
                .getMethod("sumMaxPerHeadFeeByRequiredChapters", Long.class);
        assertEquals(java.math.BigDecimal.class, m.getReturnType(),
                "必须返 BigDecimal —— 钱不能用 double 算");
    }
}
