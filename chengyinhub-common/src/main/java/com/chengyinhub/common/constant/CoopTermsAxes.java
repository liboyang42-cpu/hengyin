package com.chengyinhub.common.constant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** 合同三的两条正交招商语义轴。 */
public final class CoopTermsAxes {
    private CoopTermsAxes() {}

    public static final String COMPENSATION_NONE = "NONE";
    public static final String COMPENSATION_FIXED_PER_FULFILLMENT = "FIXED_PER_FULFILLMENT";
    public static final String COMPENSATION_SALES_PERCENT = "SALES_PERCENT";
    public static final String BENEFIT_NONE = "NONE";
    public static final String BENEFIT_PERK = "PERK";

    public static final class Value {
        private final String compensationMode;
        private final String benefitMode;

        private Value(String compensationMode, String benefitMode) {
            this.compensationMode = compensationMode;
            this.benefitMode = benefitMode;
        }

        public String getCompensationMode() { return compensationMode; }
        public String getBenefitMode() { return benefitMode; }
    }

    /** 章节 terms_mode 的唯一映射。 */
    public static Value fromTermsMode(String termsMode) {
        if (TermsMode.TRAFFIC.equals(termsMode)) return new Value(COMPENSATION_NONE, BENEFIT_NONE);
        if (TermsMode.PERK.equals(termsMode)) return new Value(COMPENSATION_NONE, BENEFIT_PERK);
        if (TermsMode.REVSHARE.equals(termsMode)) {
            return new Value(COMPENSATION_FIXED_PER_FULFILLMENT, BENEFIT_NONE);
        }
        throw new IllegalArgumentException("条款档不合法，无法映射招商两轴");
    }

    /** 合作单 share_mode + 冻结权益快照的唯一映射。 */
    public static Value fromOrder(Integer shareMode, String perksSnapshot) {
        String compensation;
        if (shareMode == null || shareMode == 0) compensation = COMPENSATION_NONE;
        else if (shareMode == 2) compensation = COMPENSATION_FIXED_PER_FULFILLMENT;
        else if (shareMode == 1) compensation = COMPENSATION_SALES_PERCENT;
        else throw new IllegalArgumentException("报酬模式不合法，无法映射招商两轴");
        return new Value(compensation, isValidPerksSnapshot(perksSnapshot) ? BENEFIT_PERK : BENEFIT_NONE);
    }

    /** 冻结权益数组必须非空，且每项具备正零售价与正配额。 */
    public static boolean isValidPerksSnapshot(String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) return false;
        try {
            JSONArray array = JSON.parseArray(snapshot);
            if (array == null || array.isEmpty()) return false;
            for (Object raw : array) {
                if (!(raw instanceof JSONObject)) return false;
                JSONObject perk = (JSONObject) raw;
                if (perk.getBigDecimal("retailValue") == null
                        || perk.getBigDecimal("retailValue").signum() <= 0
                        || perk.getInteger("quota") == null
                        || perk.getInteger("quota") < 1) return false;
            }
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
