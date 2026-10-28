package com.chengyinhub.business.service.compliance;

import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.stereotype.Component;

/**
 * DOC2 F7 合规能力的运行时开关（sys_config）。键不存在 / 空 / 非 true 一律视为关闭，
 * 所以不落任何 seed 也是「缺省关闭、现网行为不变」。
 *
 * <p>开关值判定与 NpcFeatureFlags.flagOn 同口径："true"（忽略大小写）或 "1" 即为开 ——
 * 本类在 system 模块里，引不到 admin 的 NpcFeatureFlags，两处口径由 ComplianceFeatureSwitchesTest 钉住。
 */
@Component
public class ComplianceFeatureSwitches {
    /** 用户发票：抬头管理 / 可开票订单 / 申请 / 撤回接口。 */
    public static final String USER_INVOICE = "feature.flag.userInvoice";
    /** 未成年模式总闸：监护授权接口 + 下面城市定向执行挂点的前提。 */
    public static final String MINOR_MODE = "feature.flag.minorMode";
    /** 城市定向挂点：参与前按年龄段 / 宵禁 / 同队监护人校验（需 MINOR_MODE 同时打开）。 */
    public static final String MINOR_ORIENTEERING_GATE = "feature.flag.minorOrienteeringGate";

    private final ISysConfigService configService;

    public ComplianceFeatureSwitches(ISysConfigService configService) {
        this.configService = configService;
    }

    public boolean userInvoiceOn() {
        return on(USER_INVOICE);
    }

    public boolean minorModeOn() {
        return on(MINOR_MODE);
    }

    public boolean minorOrienteeringGateOn() {
        return minorModeOn() && on(MINOR_ORIENTEERING_GATE);
    }

    public static boolean flagOn(String configValue) {
        return "true".equalsIgnoreCase(configValue) || "1".equals(configValue);
    }

    private boolean on(String key) {
        try {
            return flagOn(configService.selectConfigByKey(key));
        } catch (RuntimeException e) {
            // 读不到开关（缓存/库异常）按关闭处理：开关只用来「打开新能力」，关闭即现网既有行为。
            return false;
        }
    }
}
