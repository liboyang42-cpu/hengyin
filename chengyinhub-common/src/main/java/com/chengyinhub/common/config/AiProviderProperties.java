package com.chengyinhub.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 多 Provider 配置(DeepSeek/MiniMax/qwen/mock)。
 * application.yml 格式:
 * ai.providers.deepseek.enabled=true
 * ai.providers.deepseek.apiKey=xxx
 * ai.providers.deepseek.baseUrl=https://api.deepseek.com
 * ai.providers.deepseek.model=deepseek-v4-flash
 *
 * @author chengyinhub
 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProviderProperties
{
    /** 默认 provider */
    private String defaultProvider = "mock";

    /** 单用户每日调用上限 */
    private int dailyLimit = 20;

    /** 超时(ms) */
    private int timeoutMs = 8000;

    /** 多 provider 配置: key=provider name, value=config */
    private Map<String, ProviderConfig> providers;

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public static class ProviderConfig
    {
        private boolean enabled;
        private String apiKey;
        private String baseUrl;
        private String model;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
