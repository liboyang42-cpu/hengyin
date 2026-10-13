package com.chengyinhub.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 门店 AI 形象的声音配置(对应 application.yml 的 ai.voice.*)。
 *
 * 选型:阿里云百炼(DashScope)。合成 0.8 元/万字符,声音复刻免费 ——
 * 一店一音色的形态下,复刻按只数收费的方案(某家 150 元/音色/年)会随商家数线性爆炸,
 * 而这里音色数量就等于商家数量,所以「复刻免费」是决定性的,不是省小钱。
 *
 * 两个模型名要成对:target_model(复刻时声明音色给谁用)必须与 synth_model(合成时用哪个)
 * 属于同一系列,否则复刻出来的音色合成时会认不出来 —— 那个报错长得像鉴权失败,很难查。
 *
 * apiKey 与 workspaceId 都只放后端,经环境变量注入,绝不下发小程序。
 *
 * @author chengyinhub
 */
@Component
public class AiVoiceConfig
{
    /** 总开关。未开通时所有语音接口返回明确的「未开通」,不假装成功、也不静默返回空音频。 */
    @Value("${ai.voice.enabled:false}")
    private boolean enabled;

    /** DashScope API Key(环境变量 CHENGYIN_DASHSCOPE_API_KEY 注入)。 */
    @Value("${ai.voice.apiKey:${CHENGYIN_DASHSCOPE_API_KEY:}}")
    private String apiKey;

    /** 百炼业务空间 id。TTS 与非北京区 ASR 必填；北京 ASR 可回退共享域名。 */
    @Value("${ai.voice.workspaceId:}")
    private String workspaceId;

    /** 语音识别独立开关。已有 TTS 凭据不能自动把 ASR 暴露给用户。 */
    @Value("${ai.voice.asrEnabled:${CHENGYIN_ASR_ENABLED:false}}")
    private boolean asrEnabled;

    /** 短录音识别模型；固定快照，升级时先重跑真实中文录音。 */
    @Value("${ai.voice.asrModel:${CHENGYIN_ASR_MODEL:fun-asr-flash-2026-06-15}}")
    private String asrModel;

    /** 地域域名片段,华北2=cn-beijing。key 与地域必须同区,跨区会当作无效 key。 */
    @Value("${ai.voice.region:cn-beijing}")
    private String region;

    /** 复刻用的模型名。CosyVoice 系列走 voice-enrollment。 */
    @Value("${ai.voice.enrollModel:voice-enrollment}")
    private String enrollModel;

    /** 音色的目标模型,声明这个音色将来给谁用。必须与 synthModel 同系列。 */
    @Value("${ai.voice.targetModel:cosyvoice-v2}")
    private String targetModel;

    /** 合成用的模型名。 */
    @Value("${ai.voice.synthModel:cosyvoice-v2}")
    private String synthModel;

    /** 音色名前缀,便于在百炼控制台里认出这批音色是谁建的。 */
    @Value("${ai.voice.prefix:chengyin}")
    private String prefix;

    /** 单次调用硬超时(毫秒)。复刻比合成慢,取两者中较慢的那个。 */
    @Value("${ai.voice.timeoutMs:15000}")
    private int timeoutMs;

    /** 单个商家每日合成上限。防的是「NPC 台词被循环触发」这类把账单打穿的场景。 */
    @Value("${ai.voice.dailyLimit:200}")
    private int dailyLimit;

    /** 配齐了才算真的能用 —— 开关开着但 key 或空间 id 空着,等于开了个会骗人的开关。 */
    public boolean isUsable()
    {
        return enabled
                && apiKey != null && !apiKey.trim().isEmpty()
                && workspaceId != null && !workspaceId.trim().isEmpty();
    }

    public boolean isAsrUsable()
    {
        return asrEnabled
                && apiKey != null && !apiKey.trim().isEmpty()
                && (workspaceId != null && !workspaceId.trim().isEmpty()
                    || "cn-beijing".equals(region))
                && asrModel != null && !asrModel.trim().isEmpty();
    }

    /** 复刻与合成共用的请求前缀。 */
    public String baseUrl()
    {
        return "https://" + workspaceId + "." + region + ".maas.aliyuncs.com/api/v1/services/audio/tts";
    }

    public String asrUrl()
    {
        if (workspaceId == null || workspaceId.trim().isEmpty())
        {
            if (!"cn-beijing".equals(region))
            {
                throw new IllegalStateException("workspaceId is required outside cn-beijing");
            }
            return "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
        }
        return "https://" + workspaceId + "." + region
                + ".maas.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public boolean isAsrEnabled() { return asrEnabled; }
    public void setAsrEnabled(boolean asrEnabled) { this.asrEnabled = asrEnabled; }
    public String getAsrModel() { return asrModel; }
    public void setAsrModel(String asrModel) { this.asrModel = asrModel; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getEnrollModel() { return enrollModel; }
    public void setEnrollModel(String enrollModel) { this.enrollModel = enrollModel; }
    public String getTargetModel() { return targetModel; }
    public void setTargetModel(String targetModel) { this.targetModel = targetModel; }
    public String getSynthModel() { return synthModel; }
    public void setSynthModel(String synthModel) { this.synthModel = synthModel; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int dailyLimit) { this.dailyLimit = dailyLimit; }
}
