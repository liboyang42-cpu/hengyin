package com.chengyinhub.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 创作助手模型配置（对应 application.yml 的 ai.model.*）。
 *
 * 设计约定（重要）：
 *  - provider 决定走哪个 LlmClient：mock(本期默认,无需 key) / qwen / deepseek，切真模型只改这一项。
 *  - apiKey 只放后端，经环境变量 CHENGYIN_AI_API_KEY 注入，切勿下发到小程序前端。
 *  - mock 实现不依赖 apiKey/enabled，便于未配置 key 时链路、频控、内容安全全部可测。
 *
 * @author chengyinhub
 */
@Component
public class AiModelConfig
{
    /** 模型供应商：mock / qwen / deepseek */
    @Value("${ai.model.provider:mock}")
    private String provider;

    /** 模型 API Key（环境变量注入，勿入库勿下发前端） */
    @Value("${ai.model.apiKey:}")
    private String apiKey;

    /** OpenAI 兼容 baseUrl（qwen DashScope / deepseek） */
    @Value("${ai.model.baseUrl:}")
    private String baseUrl;

    /** 模型名，如 qwen-plus / deepseek-chat */
    @Value("${ai.model.model:qwen-plus}")
    private String model;

    /** 是否启用真实模型调用（mock 不依赖此开关） */
    @Value("${ai.model.enabled:false}")
    private boolean enabled;

    /** 单用户每日 AI 调用上限(创作类共用一个池:merchant_plan/theme_draft/node_generate/...) */
    @Value("${ai.model.dailyLimit:20}")
    private int dailyLimit;

    /**
     * 单用户每日 NPC 聊天上限(npc_chat 独立池)。
     * NPC 是「一次对话来回好几句」的交互,与创作类「一次生成一份稿」的频次量级不同,
     * 共用一个池会让聊两句就把创作额度耗光,故分池分额度。
     */
    @Value("${ai.model.npcDailyLimit:100}")
    private int npcDailyLimit;

    /** 模型调用硬超时（毫秒） */
    @Value("${ai.model.timeoutMs:8000}")
    private int timeoutMs;

    public String getProvider()
    {
        return provider;
    }

    public String getApiKey()
    {
        return apiKey;
    }

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public String getModel()
    {
        return model;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public int getDailyLimit()
    {
        return dailyLimit;
    }

    public int getNpcDailyLimit()
    {
        return npcDailyLimit;
    }

    public int getTimeoutMs()
    {
        return timeoutMs;
    }
}
