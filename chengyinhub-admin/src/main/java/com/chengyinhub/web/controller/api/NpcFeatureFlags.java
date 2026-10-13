package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.system.service.ISysConfigService;

/**
 * 漫游提示层与遗留 Chat 两轨开关的<b>唯一判定口径</b>，供 /api/config/features 与 /api/ai/npc/chat 共用。
 *
 * <p>为什么是一个组件而不是各读各的:原先「Event=false ⇒ Chat 强制 false」这条归一只写在
 * ApiConfigController 的返回 Map 上,而端点闸独立读 roamNpcChat ⇒ 直调 /api/ai/npc/chat
 * 绕开归一。Chat 轨的 No-Go 是<b>合规</b>(Phase 0 合规签字未过,签字前禁开),
 * 展示级的闸抵不了合规风险,所以判定必须只有一份、且长在端点必经之路上。
 */
@Component
public class NpcFeatureFlags {

    private static final String KEY_EVENT = "feature.flag.roamNpcEvent";
    private static final String KEY_SHOP_CHAT = "feature.flag.shopNpcChat";
    private static final String KEY_MERCHANT_CHAT = "feature.flag.merchantNpcChat";

    @Autowired
    private ISysConfigService configService;

    /** 漫游提示层总开关（沿用既有配置键）。 */
    public boolean eventOn() {
        return on(KEY_EVENT);
    }

    /**
     * 无形陪伴 V1 不提供自由追问。即使遗留 sys_config 被误开，也必须在所有消费面判关。
     * 将来若另立 Chat 合规项目，需新建开关与完整 Gate，不能复用本基座的提示层开关。
     */
    public boolean chatOn() {
        return false;
    }

    /**
     * 门店分身自由追问(自由探索 /api/ai/npc/shop-chat)的独立开关。
     *
     * <p>⚠️ <b>刻意不复用 {@link #chatOn()}</b> —— 那一条是「无形陪伴 V1 不提供自由追问」的
     * 合规闸,写死 false 且注明「将来若另立 Chat 合规项目,需新建开关与完整 Gate」。
     * 挂上去会有两个后果:①这条链路永远开不了,是死代码;②哪天有人为漫游放开那个闸,
     * 门店分身会跟着一起放开,而它俩的合规评估根本不是同一件事。
     *
     * <p>默认关:sys_config 里没有 {@code feature.flag.shopNpcChat} 时返回 false。
     * 要开必须是一次明确的运营动作,不是部署的副作用。
     */
    public boolean shopChatOn() {
        return on(KEY_SHOP_CHAT);
    }

    /** 店铺主页门店 NPC 对话开关；与按主题通行证进入的 shop-chat 分开控制。 */
    public boolean merchantChatOn() {
        return on(KEY_MERCHANT_CHAT);
    }

    /**
     * sys_config 开关值判定的<b>唯一口径</b>:"true"(忽略大小写)或 "1" 即为开。
     *
     * <p>ApiConfigController 的其余特性键也复用它 —— 两处必须同口径,否则会出现
     * 「features 下发 roamNpcChat=true 但端点仍 403」这种自相矛盾且无测试会红的状态。
     */
    public static boolean flagOn(String configValue) {
        return "true".equalsIgnoreCase(configValue) || "1".equals(configValue);
    }

    private boolean on(String key) {
        return flagOn(configService.selectConfigByKey(key));
    }
}
