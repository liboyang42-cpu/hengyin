package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-50 硬闸的**可达性**测试 —— 这次真正的风险点。
 *
 * <p><b>为什么这类测试是本轮的重点</b>:2026-07-16 查生产库,**17 个主题 lifecycle 全是 0**,
 * 而 {@code confirmPrice} 第一行就是 {@code lifecycle != 2 → throw} ⇒ **零个主题走过它**。
 * 把必亏闸挂在 confirmPrice 上而不同时堵住「不走招募」这条路,就是写了一道
 * **从第一天起没人走到的死代码** —— 而这正是同一天 P0-06 真栽过的那一跤:
 * 「③ 首核销转人工」写进了 RefundPolicy,却被三处入口硬闸绕开,24 个单测全绿。
 *
 * <p><b>算得对 ≠ 有人调用</b>。纯函数测试再多也证明不了入口用了它。
 *
 * <p>⚠️ 源码断言只守「代码长这样」。守不住「运行时真走到了」—— 那只有部署后查
 * {@code SELECT lifecycle, COUNT(*) FROM cms_topic WHERE product_type=2 GROUP BY lifecycle}
 * 能回答:③ 主题若仍出现 lifecycle=0,说明招募闸被绕过了。
 *
 * ⚠️ 负控已实测:
 *  - 删 confirmPrice 里的 assertRevshareAffordable ⇒ gateIsInvokedAtConfirmPrice 红
 *  - 删 createTopic 里的 ③ 强制招募闸 ⇒ exploreTopicsMustEnterRecruit 红
 *  - 闸的比较从 &lt;= 改成 &lt; ⇒ gateRejectsEqualCost 红(等于票价也是必亏,别放行)
 */
class RevshareGateWiringTest {

    private static final String PRICING =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PricingServiceImpl.java";
    private static final String TOPIC =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java";

    private String flat(String p) throws Exception {
        return WiringSource.read(p).replaceAll("\\s+", "");
    }

    /**
     * <b>闸必须真的在 confirmPrice 里被调用</b>；锁价不得隐式进入 SELLING。
     */
    @Test
    void gateIsInvokedAtConfirmPrice() throws Exception {
        String s = flat(PRICING);
        int gate = s.indexOf("assertRevshareAffordable(topicId,guidedPrice,selfPrice)");
        int sell = s.indexOf("topicSaleFinalizationService.finalizeForSale(topicId)");
        assertTrue(gate > 0, "confirmPrice 必须调 assertRevshareAffordable —— 删掉它编译过、测试全绿、闸消失");
        assertTrue(sell < 0, "锁价必须停在 PRICING，等待 canonical15 草稿与显式开售提审");
    }

    /**
     * <b>★承重闸:③ 必须 lifecycle=3 才能卖。</b>
     *
     * <p>这是四道闸(地板价/费率快照/SUPPLY≥1/必亏)**唯一真正的承重点**。
     * 建题时那道「③ 必须传 recruitDeadline」拦不住:
     * <ul>
     *   <li>票在建题时就由 saveTickets 插好了,与 lifecycle 无关;</li>
     *   <li>{@code processRecruitDeadlines} 在 accepted==0 时把 lifecycle **推回 0**,
     *       且只改 lifecycle、不碰 status/user_status ⇒ 主题仍在架上;</li>
     *   <li>而 ③ 的 accepted **恒为 0** —— 它数 CoopInvite,③ 的供给却是 merchant_chapter_offer,
     *       后者全仓还没有 insert 调用点。</li>
     * </ul>
     * ⇒ 建题闸只把开卖推迟十分钟,然后主题自己滑回无闸状态。**闸放错层就是假闸。**
     */
    @Test
    void explorePurchaseRequiresLockedPrice() throws Exception {
        String s = flat("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        assertTrue(s.contains("ProductType.FREE_EXPLORE==(topic.getProductType()==null?-1:topic.getProductType())"),
                "购票闸必须按 product_type 认出 ③");
        assertTrue(s.contains("TopicLifecycleState.fromCode(topic.getLifecycle())")
                        && s.contains("!lifecycle.isDirectSaleOpen()"),
                "③ 必须 lifecycle=3 才可购 —— 放行 lifecycle=0 等于四道闸全部作废(生产实测 17 个主题全是 0)");
    }

    /** 承重闸只对 ③ —— ①② 维持原样(现网 17 个主题全是 ①,改了就是越界)。 */
    @Test
    void purchaseGateOnlyTightensExplore() throws Exception {
        String s = flat("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java");
        assertTrue(s.contains("TopicLifecycleState.fromCode(topic.getLifecycle())")
                        && s.contains("!lifecycle.isDirectSaleOpen()"),
                "全部产品必须走具名 SELLING 放行态；NULL、未知和 PENDING_REVIEW 均 fail-closed");
    }

    /** [P0-35] 副本也要落内容槽 —— copyCmsTopic 绕开了 service 层 insertCmsTopic。 */
    @Test
    void copyDeclaresContentSlot() throws Exception {
        String s = flat("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicContentCopyServiceImpl.java");
        int at = s.indexOf("LongnewTopicId=newTopic.getId();");   // 已 flatten,串里不能有空格
        assertTrue(at > 0, "找不到 copyCmsTopic 的落库点");
        int decl = s.indexOf("productRoleService.declareContentSlot(newTopicId,", at);
        // 窗口按「落库点 → 复制章节」这段算,而不是拍一个字符数:注释也会被 flatten 进来,
        // 拍数字的话每次改注释都可能让测试莫名其妙地红。
        int next = s.indexOf("if(copyType>=2)", at);
        assertTrue(decl > at && (next < 0 || decl < next),
                "副本必须在落库后、复制章节前落内容槽 —— copyCmsTopic 直接调 mapper、绕开了 service 层"
                        + "那条接线,漏了则副本没有内容槽:票款归属说不清(D8)+ REVSHARE 被 fail-closed 拒");
    }

    /**
     * <b>③ 必须强制走招募</b> —— 这是闸能被走到的唯一前提。
     *
     * <p>⚠️ <b>它不是承重闸</b> —— 承重的是 {@link #explorePurchaseRequiresLockedPrice}。
     * 这条只是把「你建了个卖不出去的主题」提前告诉创作者(UX),别再把它当安全边界:
     * 招募失败会把 lifecycle 推回 0,而它拦不住那条路。
     */
    @Test
    void exploreTopicsMustEnterRecruit() throws Exception {
        String s = flat(TOPIC);
        assertTrue(s.contains("ProductType.FREE_EXPLORE==(productType==null?-1:productType)&&dto.getRecruitDeadline()==null"),
                "③ 不传招募截止必须拒 —— 否则 lifecycle 停在 0,confirmPrice 永不执行,四道闸全成死代码");
    }

    /** 闸只对 ③ 生效,①② 的招募仍是可选的 —— 别顺手改了没授权的产品。 */
    @Test
    void recruitGateOnlyForExplore() throws Exception {
        String s = flat(TOPIC);
        int at = s.indexOf("ProductType.FREE_EXPLORE==(productType==null?-1:productType)");
        assertTrue(at > 0, "找不到 ③ 招募闸");
        assertTrue(!s.substring(at, Math.min(at + 400, s.length())).contains("ORIENTEERING"),
                "①(经典定向)不该被这道闸碰到 —— 现网 17 个主题全是它");
    }

    /**
     * <b>等于票价也必须拒</b>:Σ == 票价 ⇒ 毛利恰好为 0,再扣通道费(~0.6%)就是负的。
     * 用 {@code <} 而不是 {@code <=} 会放行这条。
     */
    @Test
    void gateRejectsEqualCost() throws Exception {
        String s = flat(PRICING);
        assertTrue(s.contains("guidedPrice.compareTo(cost)<=0") && s.contains("selfPrice.compareTo(cost)<=0"),
                "必须用 <=0:等于票价时毛利为 0,扣掉通道费即为负。实际用 <0 会放行必亏配置");
    }

    /** 两种票价各判各的 —— 拿一个的成本卡另一个是错的(它们是两个独立产品)。 */
    @Test
    void bothPricesCheckedIndependently() throws Exception {
        String s = flat(PRICING);
        assertTrue(s.contains("guidedPrice!=null&&guidedPrice.compareTo(cost)<=0"), "有人带票要判");
        assertTrue(s.contains("selfPrice!=null&&selfPrice.compareTo(cost)<=0"), "无人带票也要判");
    }

    /** 无 REVSHARE 供给时必须放行 —— 平台不掏钱就没有必亏这回事,别拦住纯权益档主题。 */
    @Test
    void noRevshareSupply_passes() throws Exception {
        String s = flat(PRICING);
        assertTrue(s.contains("cost==null||cost.compareTo(BigDecimal.ZERO)<=0"),
                "null/0 = 没有计酬供给 ⇒ 放行。漏了这条会把纯 PERK/TRAFFIC 主题也拦死");
    }
}
