package com.chengyinhub.common.constant;

/**
 * P0-35 角色槽(D6,2026-07-16 第二轮拍板)—— 与 {@link ProductType} 正交的第二根轴。
 *
 * <p><b>为什么是槽而不是身份字段</b>:原设计是四个散字段(creator_member_id / publisher_member_id /
 * publisher_context_type / publisher_context_id)+ 一堆按身份分格的门禁,在写代码前就被推翻了 ——
 * 身份轴 × 形态轴的 4×4 矩阵长出三个自相矛盾(③ 票款归属 / CONTENT_ONLY 白拿 / 保证金收反)。
 * 换成「<b>角色声明挂在这一笔交易上,不挂在主体的全局身份上</b>」
 * (出处:Stripe Connect destination charge / 音乐版税 split sheet / Amazon FBA-vs-FBM)。
 *
 * <p><b>槽的填法直接推出原来那一堆规则</b>(施工图 §3.1):
 * <ul>
 *   <li>P0-34「删纯玩家直发」= 「① 的 ORGANIZER 槽必填,MEMBER 填不了」的校验自然拒绝</li>
 *   <li>P0-38「唯一承接位闸」= 单例槽的一条库约束,不需要应用层闸</li>
 *   <li>③ 票款归属(D8) = 票款归 CONTENT 槽持有者;{@code entity_type=PLATFORM} 即自营</li>
 * </ul>
 * ⚠️ 本轮只落地基(表 + PLATFORM 值),上面那些门禁(P0-34/37/38/39)不在范围内。
 */
public final class ProductSlot {
    private ProductSlot() {}

    /** 内容方:做内容 + 招商。**票款归它**(D8)。单例。 */
    public static final String CONTENT = "CONTENT";

    /** 组织方:带队履约。单例。①「填不了组织槽的,发 ① 都得邀俱乐部」(D1'/D2' 合并)。 */
    public static final String ORGANIZER = "ORGANIZER";

    /** 供给方:③ 的商家(条款三档见 D21)。**可多行**,且「每必选章 ≥1」(D17)。 */
    public static final String SUPPLY = "SUPPLY";

    /** 分销方(P0-46)。首三期产品真源只有唯一执行俱乐部，因此当前为单例。 */
    public static final String DISTRIBUTION = "DISTRIBUTION";

    /**
     * 单例槽 —— 同一主题同一槽只能有一个**生效**行。
     *
     * <p>⚠️ 库里由生成 guard 列 + UNIQUE 兜底(MySQL 8 没有「UNIQUE ... WHERE」)。
     * CONTENT/ORGANIZER 共用 {@code singleton_key}，DISTRIBUTION 用独立 guard，避免改变前两槽既有约束。
     * 这个方法只是给应用层做前置提示用,**它不是防线** —— 并发下真正拦住的是那条唯一键。
     */
    public static boolean isSingleton(String slot) {
        return CONTENT.equals(slot) || ORGANIZER.equals(slot) || DISTRIBUTION.equals(slot);
    }

    public static boolean isValid(String slot) {
        return CONTENT.equals(slot) || ORGANIZER.equals(slot)
                || SUPPLY.equals(slot) || DISTRIBUTION.equals(slot);
    }

    /** O-15 写入闸的机器错误码:槽不在上面四个封闭取值内。 */
    public static final String ERR_SLOT_INVALID = "PRODUCT_ROLE_SLOT_INVALID";

    /**
     * O-15 写入闸:槽是封闭取值,只认上面四个,大小写/空白都不容错。
     *
     * <p>只在**写**之前调用;读路径不校验 —— 存量里即便有越界行,也不能因此把读炸掉
     * (库里另有 {@code ck_role_slot} CHECK 兜底)。
     *
     * @throws IllegalArgumentException 消息为 {@link #ERR_SLOT_INVALID}
     */
    public static String requireValid(String slot) {
        if (!isValid(slot)) {
            throw new IllegalArgumentException(ERR_SLOT_INVALID);
        }
        return slot;
    }
}
