package com.chengyinhub.business.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 拍照审核视觉模型配置(对应 application.yml 的 ai.vision.*)。
 *
 * <p>与 AiModelConfig(ai.model.*,文本创作)分开:视觉与文本是两条独立的模型、超时口径,
 * baseUrl / model / apiKey 全部配置化,一个都不写死在调用代码里。
 * 默认走 DeepSeek(复用现成的 CHENGYIN_DEEPSEEK_API_KEY,模型 deepseek-flash);
 * 要换阿里云百炼(compatible-mode + qwen-vl-max)或别家,只改配置不改代码。
 * key 只放后端,经环境变量注入,切勿下发到小程序前端。
 *
 * @author chengyinhub
 */
@Component
public class VisionModelConfig
{
    /** 上线开关;真调用前提=apiKey 已注入。关闭/未注入时调用方拿到「拿不到结论」并走降级。 */
    @Value("${ai.vision.enabled:true}")
    private boolean enabled;

    /** 视觉模型 API Key(默认 DeepSeek,环境变量 CHENGYIN_DEEPSEEK_API_KEY 注入) */
    @Value("${ai.vision.apiKey:}")
    private String apiKey;

    /** OpenAI 兼容 baseUrl;默认 DeepSeek,换百炼只改配置 */
    @Value("${ai.vision.baseUrl:}")
    private String baseUrl;

    /** 视觉模型名,默认 deepseek-flash(旧名 deepseek-v4-flash-vision-exp 已退役) */
    @Value("${ai.vision.model:deepseek-flash}")
    private String model;

    /** 模型调用硬超时(毫秒) */
    @Value("${ai.vision.timeoutMs:8000}")
    private int timeoutMs;

    public boolean isEnabled()
    {
        return enabled;
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

    public int getTimeoutMs()
    {
        return timeoutMs;
    }
}
