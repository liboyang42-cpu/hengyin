package com.chengyinhub.business.service.minor;

import java.time.LocalDate;
import java.time.Period;

/**
 * DOC2 F7 年龄段（业主定稿规则）：
 * <ul>
 *   <li>UNDER_8：不能自行注册或支付，由监护人购买并陪同；</li>
 *   <li>AGE_8_15：支付须监护人授权；城市定向仅白天低风险线路，且监护人同队；</li>
 *   <li>AGE_16_17：授权后可单独参与白天低风险线路；</li>
 *   <li>UNKNOWN：未填出生日期 —— 绝不按成年人放行支付。</li>
 * </ul>
 * 年龄只取会员自填 birthday，不读身份证、不做实名核验。
 */
public enum AgeBand {
    UNDER_8, AGE_8_15, AGE_16_17, ADULT, UNKNOWN;

    public boolean isMinor() {
        return this == UNDER_8 || this == AGE_8_15 || this == AGE_16_17;
    }

    public static AgeBand of(LocalDate birthday, LocalDate today) {
        if (birthday == null || today == null || birthday.isAfter(today)) return UNKNOWN;
        int age = Period.between(birthday, today).getYears();
        if (age < 8) return UNDER_8;
        if (age < 16) return AGE_8_15;
        if (age < 18) return AGE_16_17;
        return ADULT;
    }
}
