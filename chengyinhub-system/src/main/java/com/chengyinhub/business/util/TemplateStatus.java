package com.chengyinhub.business.util;

/**
 * 主题模板状态(07-23 指南第十三节:「状态仅允许实验、试点、已验证、停用」)。
 *
 * <p>⚠️ 这不是 {@code cms_topic.status}(上下架)也不是 {@code is_template}(是否入库)。
 * 它回答的是「这套模板被验证到什么程度」—— 指南同时规定:未满足入库门槛的只能标
 * {@link #EXPERIMENTAL},<b>不能与 {@link #VERIFIED} 混排</b>,否则商家会把一套没人试玩过的
 * 模板当成已验证产品来采纳。
 *
 * <p><b>放在这里而不是 common/constant 的原因</b>:同类的 RoleEntityType / ProductSlot 确实住在
 * {@code chengyinhub-common},但本轮任务的文件边界只包含 system 与 admin 两个模块
 * (并行会话按模块分工,越界改动会撞车)。参照同模块内 {@link CoopAcceptanceStatus} 的先例
 * 落在 business/util。若后续要归位到 common/constant,是一次纯 move,无行为变化。
 */
public final class TemplateStatus {

    private TemplateStatus() {}

    /** 实验:未满足入库门槛,不得与已验证模板混排。 */
    public static final String EXPERIMENTAL = "EXPERIMENTAL";

    /** 试点:已有试点实例但尚未取得完整实测结论。 */
    public static final String PILOT = "PILOT";

    /** 已验证:满足入库门槛且有实测。 */
    public static final String VERIFIED = "VERIFIED";

    /** 停用:不再接受新的采纳。 */
    public static final String RETIRED = "RETIRED";

    public static boolean isValid(String s) {
        return EXPERIMENTAL.equals(s) || PILOT.equals(s) || VERIFIED.equals(s) || RETIRED.equals(s);
    }
}
