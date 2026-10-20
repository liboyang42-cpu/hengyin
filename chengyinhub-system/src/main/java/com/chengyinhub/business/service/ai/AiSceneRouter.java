package com.chengyinhub.business.service.ai;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 场景->模型路由器。
 * 创作类 bizType -> deepseek; NPC 追问 -> deepseek(minimax 未启用,见下); NPC 事件 -> cache only。
 *
 * @author chengyinhub
 */
@Component
public class AiSceneRouter
{
    private static final Logger log = LoggerFactory.getLogger(AiSceneRouter.class);

    /** bizType -> provider name 映射(Java 8 兼容:静态块构建不可变 Map) */
    private static final Map<String, String> ROUTES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("merchant_plan",         "deepseek");
        m.put("theme_draft",           "deepseek");
        m.put("node_generate",         "deepseek");
        m.put("chapter_overview",      "deepseek");
        m.put("safety_precheck",       "deepseek");
        m.put("merchant_task_suggest", "deepseek");
        m.put("merchant_insight",      "deepseek");
        m.put("template_fill",         "deepseek");
        m.put("club_design",           "deepseek");
        // npc_chat 走 deepseek 而非 minimax:
        //  1) ai.providers.minimax.enabled=false ⇒ LlmClientConfig 注册的是 chat() 直接
        //     throw IllegalStateException 的 placeholder ⇒ 路由到 minimax = NPC 100% 落兜底话术。
        //  2) deepseek 正是 NPC 今天实际在用的(收编前 NpcChatService 读
        //     AiModelConfig.provider,application.yml 里 ai.model.provider=deepseek)。
        //     ⇒ 指向 deepseek 是零新增成本、零行为变化。
        //  MiniMax 真启用(providers.minimax.enabled=true + key 注入)后再改回 "minimax"。
        m.put("npc_chat",              "deepseek");
        // 门店 NPC 对话。与 npc_chat 分开注册而不是复用同一个 bizType:
        //  ① 两轨的合规范围不同(漫游自由追问 vs 只谈本店),成本与配额要能分别看;
        //  ② 漏注册的代价见下面 FALLBACK 那段注释 —— 生产静默返回假文案、全链零信号。
        m.put("merchant_npc_chat",     "deepseek");
        m.put("npc_shop_chat",         "deepseek");
        m.put("scoped_store_npc",      "deepseek");
        m.put("scoped_theme_npc",      "deepseek");
        m.put("npc_event",             "cache");
        ROUTES = Collections.unmodifiableMap(m);
    }

    /**
     * 根据 bizType 解析 provider 名称。
     */
    public String resolve(String bizType)
    {
        String provider = ROUTES.get(bizType);
        if (provider == null)
        {
            log.warn("[AiSceneRouter] unregistered bizType={}", bizType);
            throw new AiServiceException(AiServiceException.Reason.UPSTREAM_UNAVAILABLE,
                    "AI 场景未配置，请联系管理员");
        }
        return provider;
    }

    /**
     * npc_event 特殊:永远走缓存,不调模型。
     */
    public boolean isCacheOnly(String bizType)
    {
        return "npc_event".equals(bizType);
    }
}
