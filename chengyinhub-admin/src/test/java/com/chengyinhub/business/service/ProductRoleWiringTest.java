package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-35 角色槽的**接线**测试 —— 补「算得对但没人调用」这个空档。
 *
 * <p>{@code ProductRoleServiceTest} 证明 declareContentSlot / isPlatformOwned 算得对。
 * 但它绿,**不能证明建题时真的调了**:删掉建题里那一行,编译过、全部测试绿、槽表恒空
 * ⇒ P0-50 的 REVSHARE 对**所有**主题恒拒 ⇒ 计酬档整个用不了,而且零告警。
 * 这与 P0-02 用 mutation 实测过的空档同类(见 {@link ProductSourceWiringTest}),
 * 也正是本轮 P0-06 真栽过的那一跤(转人工写好了却被入口硬闸绕开,24 个单测全绿)。
 *
 * <p>⚠️ 源码断言只守「代码长这样」。**守不住「运行时真写进去了」** —— 那只有迁移头里 P3/P4
 * 那两条落库后自检(查新建主题有没有 CONTENT 槽、③ 主题拿到的是不是 PLATFORM)能回答。
 *
 * ⚠️ 负控已实测:
 *  - 删 insertCmsTopic 里的 declareContentSlot ⇒ backendCreateDeclaresPlatform 红
 *  - 删 createTopic 里的 declareContentSlot ⇒ creatorCreateDeclaresSlot 红
 *  - 后台那条的 PLATFORM 改成 MEMBER ⇒ backendCreateDeclaresPlatform 红
 */
class ProductRoleWiringTest {

    private static final String TOPIC_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java";

    private String flatSource() throws Exception {
        return WiringSource.read(TOPIC_SERVICE).replaceAll("\\s+", "");
    }

    /**
     * <b>后台建题 ⇒ 内容槽 = PLATFORM</b>。这是 ③-自营(首轮标的 D12)进入系统的唯一入口,
     * 也是 P0-50 REVSHARE 唯一的放行来源。少了它,计酬档没有任何主题能用。
     */
    @Test
    void backendCreateDeclaresPlatform() throws Exception {
        String src = flatSource();
        assertTrue(src.contains("declareContentSlot(cmsTopic.getId(),RoleEntityType.PLATFORM,null)"),
                "后台建题必须声明 PLATFORM 内容槽 —— 删掉它编译过、测试全绿、槽表恒空,"
                        + "P0-50 的 REVSHARE 对所有主题恒拒,计酬档整个用不了且零告警");
    }

    /**
     * <b>创作者建题也要落槽</b>(CLUB 或 MEMBER)。
     * <p>不落的话这些主题没有内容槽 ⇒ 票款归属说不清(D8);而且 fail-closed 会让它们
     * 看起来「正确地」被 REVSHARE 拒绝 —— 用对的结果掩盖了断掉的接线,更难发现。
     */
    @Test
    void creatorCreateDeclaresSlot() throws Exception {
        String src = flatSource();
        assertTrue(src.contains("productRoleService.declareContentSlot(topicId,"),
                "创作者建题必须声明内容槽,否则票款归属无从判定");
        assertTrue(src.contains("RoleEntityType.CLUB:RoleEntityType.MEMBER"),
                "创作者槽按 clubId 分流 CLUB/MEMBER");
    }

    /**
     * <b>创作者链路永远不许落 PLATFORM</b>。
     *
     * <p>这条是范围闸的另一半:小程序进来的主题若能自称平台自营,REVSHARE 就形同虚设 ——
     * 任何人建个 ③ 就能让平台按人头给自己打钱。判据必须是**入口**(能过后台
     * {@code @PreAuthorize} 的才是平台),不是任何前端可传的字段。
     */
    @Test
    void creatorPathNeverDeclaresPlatform() throws Exception {
        String src = flatSource();
        int at = src.indexOf("productRoleService.declareContentSlot(topicId,");
        assertTrue(at > 0, "找不到创作者链路的声明点");
        String call = src.substring(at, Math.min(at + 220, src.length()));
        assertTrue(!call.contains("RoleEntityType.PLATFORM"),
                "创作者链路不得声明 PLATFORM —— 否则任何人建个 ③ 就能让平台按人头给自己打钱。实际: " + call);
    }
}
