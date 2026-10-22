package com.chengyinhub.business.service.support;

import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 城市定向 F1d 开关的唯一判定口径。全部默认关:sys_config 里键缺失 = 关,
 * 要开必须是一次明确的运营动作,不是部署的副作用(同 NpcFeatureFlags.flagOn 口径:"true"/"1" 为开)。
 */
@Component
public class OrientSwitches {

    public static final String KEY_GEO = "orient.geo.normalize.enabled";
    public static final String KEY_ALT_TASK = "orient.alt_task.enabled";
    public static final String KEY_TEAM_BOARD = "orient.team.board.enabled";
    public static final String KEY_TEAM_RATIO = "orient.team.completion_ratio";
    public static final String KEY_SAFETY = "orient.safety.enabled";

    @Autowired
    private ISysConfigService configService;

    public boolean geoOn() { return on(KEY_GEO); }

    public boolean altTaskOn() { return on(KEY_ALT_TASK); }

    public boolean teamBoardOn() { return on(KEY_TEAM_BOARD); }

    public boolean safetyOn() { return on(KEY_SAFETY); }

    /** (0,1] 之间的完成比例;缺失或非法一律按 1(全员)—— 宁可严,不因配置坏了而放水。 */
    public double teamCompletionRatio() {
        String raw = configService.selectConfigByKey(KEY_TEAM_RATIO);
        if (raw == null || raw.trim().isEmpty()) return 1.0;
        try {
            double v = Double.parseDouble(raw.trim());
            return (v > 0.0 && v <= 1.0) ? v : 1.0;
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    public void require(boolean on) {
        if (!on) throw new OrientRejection(OrientRejection.DISABLED, "该功能暂未开放");
    }

    static boolean flagOn(String configValue) {
        return "true".equalsIgnoreCase(configValue) || "1".equals(configValue);
    }

    private boolean on(String key) {
        return flagOn(configService.selectConfigByKey(key));
    }
}
