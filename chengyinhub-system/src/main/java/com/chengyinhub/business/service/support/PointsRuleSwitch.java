package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsPointsSetting;

/**
 * cms_points_setting 后台「停用」开关的唯一判定(2026-11-04 业主:停用必须真停发放)。
 *
 * <p>启用 = {@code status = 1} 且 {@code del_flag} 为空或 0。停用、已删除或查不到的规则一分不发,
 * 调用方照常完成业务,只是不写余额也不写流水。第十一轮 h3 先收了 2/3/5/6,第十二轮 k1 把
 * 读 {@code value} 发积分的 1(注册赠送)、4(评价/投票)、7(推荐商户注册)、11/12/13(按 event_type)
 * 一起接到这里。
 */
public final class PointsRuleSwitch {

    private PointsRuleSwitch() {
    }

    public static boolean active(CmsPointsSetting rule) {
        return rule != null
                && Long.valueOf(1L).equals(rule.getStatus())
                && (rule.getDelFlag() == null || rule.getDelFlag() == 0L);
    }

    /** 启用规则的 {@code value} 取整;停用/删除/缺值一律 0。 */
    public static int activeValue(CmsPointsSetting rule) {
        if (!active(rule) || rule.getValue() == null) {
            return 0;
        }
        return rule.getValue().intValue();
    }
}
