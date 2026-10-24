package com.chengyinhub.common.constant;

/** 模板 validation_method 的跨端契约。 */
public final class ValidationMethod {
    private ValidationMethod() {}

    /** 拍照打卡（可选 App 伪 AR 滤镜子类）。 */
    public static final int PHOTO = 2;

    /** GPS 到达:靠站点坐标判定"到没到",缺坐标不能退化成无验证节点。小程序侧同值见 utils/validation-method-labels.js VM.GPS。 */
    public static final int GPS = 5;

    /** 多步骤偏好题组。 */
    public static final int PREFERENCE_GROUP = 6;

    /** App 专属传感器挑战。 */
    public static final int APP_SENSOR = 7;

    /**
     * 估数题:商家给正确值与容差,玩家在数轴上猜,按偏差分档。
     *
     * <p>下面三种(8/9/10)与 1–7 有一处结构差异:它们的**题面与判定参数存在
     * {@code advanced_config_json} 的同名段里**,不各自平铺一批列
     * (模板表已 61 列,再摊就摊不动了)。玩家的作答走
     * {@code /api/play/advanced/action},完成动作仍是到达 —— 所以它们
     * 与 0/5 一样能从 arrive 收尾,由 completeGate 兜住"玩没玩完"。
     *
     * <p>它们是**完成方式**而不是叠加玩法:一个节点只有一种完成方式,
     * 编辑器里的摘要、预览、字段块都要认这个值。挂成叠加段的话,
     * 商家得先选"无需验证"再叠一个估数,摘要会写着"无需验证" —— 那是撒谎。
     */
    public static final int ESTIMATE = 8;

    /** 价格连击:两两比价,答对继续、答错收场。配置同样在 advanced_config_json。 */
    public static final int PRICE_PAIR = 9;

    /** 找东西:一张图上标 3–5 个热区,玩家点中即亮。配置同样在 advanced_config_json。 */
    public static final int HIDDEN_OBJECT = 10;

    /**
     * 判定动作不在节点本身、可以从 arrive 收尾的完成方式。
     *
     * <p>⚠️ 写成白名单而不是"拒绝这几个":黑名单形状下每加一个新完成方式都默认放行,
     * 等于新玩法一上线就能被 arrive 绕过判定,而且零报错。
     */
    public static boolean completesOnArrive(Integer vm) {
        if (vm == null) return true;              // 未配 = 到点即完成
        int v = vm;
        return v == 0 || v == GPS || v == ESTIMATE || v == PRICE_PAIR || v == HIDDEN_OBJECT;
    }

    /**
     * 完成方式的中文名。原来这串 if-else 在 ApiTemplateController 里抄了两份
     * (库模板详情一份、会员模板详情一份),加一个新值要改两处、漏一处就有一个入口显示空白。
     *
     * @return 未知或未配置时返回空串,由调用方决定怎么展示
     */
    public static String label(Integer vm) {
        if (vm == null) return "";
        switch (vm) {
            case 1:  return "文字验证";
            case PHOTO: return "拍照打卡";
            case 3:  return "选项验证";
            case 4:  return "扫码打卡";
            case 5:  return "GPS到达";   // = GPS;字面量被 xcx play-generic-flow 测试钉着
            case PREFERENCE_GROUP: return "偏好题组";
            case APP_SENSOR: return "传感器挑战";
            case ESTIMATE: return "估数题";
            case PRICE_PAIR: return "价格连击";
            case HIDDEN_OBJECT: return "找东西";
            default: return "";
        }
    }
}
