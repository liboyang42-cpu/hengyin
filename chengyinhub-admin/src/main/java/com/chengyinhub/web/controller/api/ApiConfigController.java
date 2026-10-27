package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.system.service.ISysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M10 灰度功能开关
 *
 * <p>读取 sys_config 中的 feature.flag.* 配置(默认 false)，供小程序启动时拉取入 globalData.features。
 * 缓存复用 RuoYi 的 sys_config Redis 缓存(改配置即清缓存生效)。新功能一律默认关闭。
 */
@RestController
@Tag(name = "灰度功能开关")
@RequestMapping("/api/config")
public class ApiConfigController extends BaseController {

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private NpcFeatureFlags npcFeatureFlags;

    @Autowired
    private CommunityPostFeatureGate communityPostFeatureGate;

    @Autowired
    private SpeechToTextService speechToTextService;

    /**
     * 通用特性键(短名)；实际 sys_config key = "feature.flag." + 短名。
     *
     * <p>漫游提示层与遗留 Chat 键不在此列：前者由 NpcFeatureFlags 读取，后者在无形陪伴 V1 固定下发 false。
     */
    private static final String[] FLAG_KEYS = {
            // X09:只保留真正有读取方的开关。曾经下发但两端都没人读的
            // selfPlay / citymap / eggs / trackRecord / xpBudget 已移除 ——
            // 运营在后台改它们不会有任何效果,留着是个会骗人的按钮。
            // 要重新启用某个开关,先接上判断点再加回来。
            "drops",              // 服务端 RewardDropServiceImpl 读
            "roamDemoPois",       // 小程序 utils/roam-demo-gate.js 读(开发版演示种子门)
            "finishNearbyRoute",  // 小程序 play/index.js 读
            "clubMembership",     // 两端都读(ClubMembershipFeatureGate + club 页)
            "merchantDeskProducts" // 两端都读(MerchantDeskSwitch 写入口 + 商家工作台商品与履约段),D2 默认关
    };

    @Operation(summary = "M10 灰度功能开关:返回各特性 on/off")
    @Anonymous
    @PostMapping("/features")
    public AjaxResult features() {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : FLAG_KEYS) {
            data.put(k, readFlag("feature.flag." + k));
        }
        // 漫游提示层与 /api/ai/npc/chat 的端点闸同源；Chat 在 V1 固定为 false。
        data.put("roamNpcEvent", npcFeatureFlags.eventOn());
        data.put("roamNpcChat", npcFeatureFlags.chatOn());
        boolean shopNpcChat = npcFeatureFlags.shopChatOn();
        boolean shopNpcVoice = shopNpcChat && speechToTextService.available();
        data.put("shopNpcChat", shopNpcChat);
        // 两个键同源：shopNpcVoice 是功能键，shopNpcVoiceAvailable 是 App 的能力就绪语义。
        data.put("shopNpcVoice", shopNpcVoice);
        data.put("shopNpcVoiceAvailable", shopNpcVoice);
        data.put("merchantNpcChat", npcFeatureFlags.merchantChatOn());
        Long memberId = viewer();
        data.put("communityPostRead", communityPostFeatureGate.canRead(memberId));
        data.put("communityPostWrite", communityPostFeatureGate.canWrite(memberId));
        return success(data);
    }

    private Long viewer() {
        try {
            Long id = getAppUserId();
            return id == null ? 0L : id;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private boolean readFlag(String key) {
        return NpcFeatureFlags.flagOn(configService.selectConfigByKey(key));
    }
}
