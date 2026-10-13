package com.chengyinhub.business.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.chengyinhub.common.config.AiModelConfig;
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

    @Bean("qwenLlmClient")
    public LlmClient qwenLlmClient(AiModelConfig config)
    {
        return new OpenAiCompatibleLlmClient("qwen", config);
    }

    @Bean("deepseekLlmClient")
    public LlmClient deepseekLlmClient(AiModelConfig config)
    {
        return new OpenAiCompatibleLlmClient("deepseek", config);
    }

    /**
     * 拍照审核的视觉客户端(qwen-vl,OpenAI vision 协议)。
     * 与文本实例分开:模型、baseUrl、超时、额度口径都独立,配置见 ai.vision.*。
     */
    @Bean("visionLlmClient")
    public LlmClient visionLlmClient(VisionModelConfig config)
    {
        return new OpenAiCompatibleLlmClient("vision", config.getBaseUrl(), config.getApiKey(),
                config.getModel(), config.isEnabled(), config.getTimeoutMs());
    }

    @Bean("minimaxLlmClient")
    public LlmClient minimaxLlmClient(AiProviderProperties props)
    {
        AiProviderProperties.ProviderConfig cfg = props.getProviders() != null
            ? props.getProviders().get("minimax") : null;
        if (cfg == null || !cfg.isEnabled())
        {
            log.info("[LlmClientConfig] minimax disabled, registering placeholder");
            LlmClient disabled = new LlmClient() {
                @Override public String name() { return "minimax"; }
                @Override public String chat(String systemPrompt, String userPrompt) {
                    throw new IllegalStateException("MiniMax provider is not enabled. Set ai.providers.minimax.enabled=true");
                }
            };
            return disabled;
        }
        return new OpenAiCompatibleLlmClient("minimax", cfg.getBaseUrl(), cfg.getApiKey(),
            cfg.getModel() != null ? cfg.getModel() : "MiniMax-M2.7", true, props.getTimeoutMs());
    }
}
