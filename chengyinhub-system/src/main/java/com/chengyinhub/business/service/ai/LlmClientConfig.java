package com.chengyinhub.business.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.config.AiProviderProperties;

/**
 * LlmClient Bean 装配:始终注册 mock;额外注册 qwen/deepseek/minimax 等 OpenAI 兼容实例。
 * AiGatewayService 按 AiSceneRouter 选取对应 name() 的实现。
 *
 * @author chengyinhub
 */
@Configuration
public class LlmClientConfig
{
    private static final Logger log = LoggerFactory.getLogger(LlmClientConfig.class);

    @Bean
    public AiHttpAdmission aiHttpAdmission(AiModelConfig text, VisionModelConfig vision, AiProviderProperties props,
            AiVoiceConfig voice)
    {
        java.util.List<String> endpoints = new java.util.ArrayList<>();
        addEndpoint(endpoints, text.getBaseUrl());
        addEndpoint(endpoints, vision.getBaseUrl());
        endpoints.add(voice.baseUrl()); // Fixed startup voice origin; the same registry/permits as text and vision.
        if (voice.isAsrUsable()) endpoints.add(voice.asrUrl()); // Fixed ASR origin; no runtime registry.
        AiProviderProperties.ProviderConfig minimax = props.getProviders() == null
                ? null : props.getProviders().get("minimax");
        if (minimax != null) addEndpoint(endpoints, minimax.getBaseUrl());
        return new AiHttpAdmission(endpoints);
    }

    private static void addEndpoint(java.util.List<String> endpoints, String baseUrl)
    {
        try { endpoints.add(OpenAiCompatibleLlmClient.completionUrl(baseUrl)); }
        catch (AiProviderNotSentException invalid) { /* Invalid config cannot acquire admission. */ }
    }

    @Bean("qwenLlmClient")
    public LlmClient qwenLlmClient(AiModelConfig config, AiHttpAdmission admission)
    {
        return new OpenAiCompatibleLlmClient("qwen", config.getBaseUrl(), config.getApiKey(),
                config.getModel(), config.isEnabled(), config.getTimeoutMs(), admission);
    }

    @Bean("deepseekLlmClient")
    public LlmClient deepseekLlmClient(AiModelConfig config, AiHttpAdmission admission)
    {
        return new OpenAiCompatibleLlmClient("deepseek", config.getBaseUrl(), config.getApiKey(),
                config.getModel(), config.isEnabled(), config.getTimeoutMs(), admission);
    }

    /**
     * 拍照审核的视觉客户端(qwen-vl,OpenAI vision 协议)。
     * 与文本实例分开:模型、baseUrl、超时、额度口径都独立,配置见 ai.vision.*。
     */
    @Bean("visionLlmClient")
    public LlmClient visionLlmClient(VisionModelConfig config, AiHttpAdmission admission)
    {
        return new OpenAiCompatibleLlmClient("vision", config.getBaseUrl(), config.getApiKey(),
                config.getModel(), config.isEnabled(), config.getTimeoutMs(), admission);
    }

    @Bean("minimaxLlmClient")
    public LlmClient minimaxLlmClient(AiProviderProperties props, AiHttpAdmission admission)
    {
        AiProviderProperties.ProviderConfig cfg = props.getProviders() != null
            ? props.getProviders().get("minimax") : null;
        if (cfg == null || !cfg.isEnabled())
        {
            log.info("[LlmClientConfig] minimax disabled, registering placeholder");
            LlmClient disabled = new LlmClient() {
                @Override public String name() { return "minimax"; }
                @Override public String chat(String systemPrompt, String userPrompt) {
                    throw new AiProviderNotSentException();
                }
            };
            return disabled;
        }
        return new OpenAiCompatibleLlmClient("minimax", cfg.getBaseUrl(), cfg.getApiKey(),
            cfg.getModel() != null ? cfg.getModel() : "MiniMax-M2.7", true, props.getTimeoutMs(), admission);
    }
}
