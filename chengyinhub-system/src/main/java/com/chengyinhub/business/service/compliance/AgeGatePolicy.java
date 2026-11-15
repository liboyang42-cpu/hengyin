package com.chengyinhub.business.service.compliance;

import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.stereotype.Component;

/**
 * P1b SEC-14：年龄政策（sys_config {@code app.agePolicy}）。
 *
 * <p>{@code adults}（缺省，含键缺失/空/读失败/未知值）= 只向 18 周岁及以上玩家开放，注册时须本人确认已满 18 岁。
 * {@code guardian} = 预留给「未成年人经监护人同意后使用」，本期未建：读到它时仍按 adults 执行，
 * 并在接口里如实下发 {@code guardianFlowAvailable=false}。
 * TODO(SEC-14 guardian)：监护人流程上线时在这里放开 GUARDIAN，并接 ApiMinorGuardianController 的邀请/确认流程。
 */
@Component
public class AgeGatePolicy {
    public static final String CONFIG_KEY = "app.agePolicy";
    public static final String ADULTS = "adults";
    public static final String GUARDIAN = "guardian";
    public static final int ADULT_AGE = 18;

    private final ISysConfigService configService;

    public AgeGatePolicy(ISysConfigService configService) {
        this.configService = configService;
    }

    /** 配置原值的规范化：只认 adults / guardian，其余一律 adults。 */
    public String configured() {
        String raw;
        try {
            raw = configService.selectConfigByKey(CONFIG_KEY);
        } catch (RuntimeException e) {
            return ADULTS;
        }
        return normalize(raw);
    }

    /** 实际执行的政策。guardian 流程未建，执行口径恒为 adults。 */
    public String effective() {
        return ADULTS;
    }

    public boolean guardianFlowAvailable() {
        return false;
    }

    static String normalize(String raw) {
        if (raw == null) return ADULTS;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return GUARDIAN.equals(value) ? GUARDIAN : ADULTS;
    }
}
