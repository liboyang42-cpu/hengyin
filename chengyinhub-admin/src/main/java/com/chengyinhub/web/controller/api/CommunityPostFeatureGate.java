package com.chengyinhub.web.controller.api;

import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.stereotype.Component;

/** 专业广场的运行时灰度闸；读写可独立关闭，账号白名单支持最终全量切换。 */
@Component
public class CommunityPostFeatureGate {
    static final String READ_KEY = "feature.flag.communityPostRead";
    static final String WRITE_KEY = "feature.flag.communityPostWrite";
    static final String PILOT_MEMBERS_KEY = "feature.flag.communityPostPilotMembers";

    private final ISysConfigService configService;

    public CommunityPostFeatureGate(ISysConfigService configService) {
        this.configService = configService;
    }

    public boolean canRead(Long memberId) {
        return NpcFeatureFlags.flagOn(configService.selectConfigByKey(READ_KEY))
                && allowed(memberId, true);
    }

    public boolean canWrite(Long memberId) {
        return memberId != null && memberId > 0
                && NpcFeatureFlags.flagOn(configService.selectConfigByKey(WRITE_KEY))
                && allowed(memberId, false);
    }

    private boolean allowed(Long memberId, boolean allowGuestAfterGlobalCutover) {
        String raw = configService.selectConfigByKey(PILOT_MEMBERS_KEY);
        if (raw == null || raw.trim().isEmpty()) return false;
        if ("*".equals(raw.trim())) return allowGuestAfterGlobalCutover || (memberId != null && memberId > 0);
        if (memberId == null || memberId <= 0) return false;
        String expected = String.valueOf(memberId);
        for (String item : raw.split(",")) {
            if (expected.equals(item.trim())) return true;
        }
        return false;
    }
}
