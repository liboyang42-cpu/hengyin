package com.chengyinhub.business.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.AiModelConfig;
import com.chengyinhub.common.utils.StringUtils;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/**
 * OpenAI 兼容协议的大模型客户端(qwen DashScope / deepseek 共用)。
 *
 * - qwen:  baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1, model=qwen-plus
 * - deepseek: baseUrl=https://api.deepseek.com, model=deepseek-chat
 * 两者都走 POST {baseUrl}/chat/completions,Authorization: Bearer {apiKey},body 为 OpenAI 标准。
 *
 * 两个实例(qwen/deepseek)由 LlmClientConfig 注册,AiGatewayService 按 provider 选取。
 * 调用前校验 enabled + apiKey,未配置时抛友好异常由上层降级。
 *
 * @author chengyinhub
 */
public class OpenAiCompatibleLlmClient implements LlmClient
{
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean enabled;
    private final int timeoutMs;

    /**
     * 从 AiModelConfig 构造(qwen/deepseek 旧路径)。
     */
    public OpenAiCompatibleLlmClient(String name, AiModelConfig config)
    {
        this(name, config.getBaseUrl(), config.getApiKey(), config.getModel(), config.isEnabled(), config.getTimeoutMs());
    }

    /**
     * 多 Provider 构造(MiniMax 等,从 AiProviderProperties.ProviderConfig 取值)。
     */
    public OpenAiCompatibleLlmClient(String name, String baseUrl, String apiKey, String model, boolean enabled, int timeoutMs)
    {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String name()
    {
        return name;
    }

    public String configuredModel()
    {
        return model;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt)
    {
        requireReady();

        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt == null ? "" : systemPrompt);
        messages.add(sys);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userPrompt == null ? "" : userPrompt);
        messages.add(user);
        body.put("messages", messages);
        body.put("temperature", 0.7);

        return post(body);
    }

    /**
     * 视觉对话:OpenAI vision 请求体,content 是数组 —— 图片在前、文字在后。
     * 图片支持公网 URL 或 data:image/jpeg;base64,...(qwen-vl 两者都收)。
     */
    @Override
    public String chatVision(String systemPrompt, String imageUrl, String userPrompt)
    {
        requireReady();

        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty())
        {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }
        JSONObject user = new JSONObject();
        user.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject image = new JSONObject();
        image.put("type", "image_url");
        JSONObject imageUrlPart = new JSONObject();
        imageUrlPart.put("url", imageUrl == null ? "" : imageUrl);
        image.put("image_url", imageUrlPart);
        content.add(image);
        JSONObject text = new JSONObject();
        text.put("type", "text");
        text.put("text", userPrompt == null ? "" : userPrompt);
        content.add(text);
        user.put("content", content);
        messages.add(user);
        body.put("messages", messages);

        return post(body);
    }

    /** Local configuration check only; never sends a provider request. */
    public void requireReady()
    {
        if (!enabled)
        {
            throw new IllegalStateException("AI 模型未启用(" + name + ".enabled=false),请改用 mock 或配置后开启");
        }
        if (apiKey == null || apiKey.trim().isEmpty())
        {
            throw new IllegalStateException("AI 模型 " + name + " 未配置 apiKey");
        }
    }

    /** 文本/视觉两条路共用的 HTTP 出口:POST {baseUrl}/chat/completions,解析 choices[0].message.content。 */
    private String post(JSONObject body)
    {
        String url = StringUtils.isEmpty(baseUrl)
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
                : baseUrl.replaceAll("/+$", "") + "/chat/completions";

        int status = -1;
        String phase = "transport";
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body.toJSONString())
                .timeout(timeoutMs)
                .execute())
        {
            status = resp.getStatus();
            phase = "response";
            String result = resp.body();
            if (!resp.isOk())
            {
                // Provider bodies can echo prompts, retrieved facts or credentials. Never log them.
                throw new RuntimeException("AI 模型调用失败(" + resp.getStatus() + ")");
            }
            phase = "parse";
            return responseText(result);
        }
        catch (Exception e)
        {
            // Parser/transport exception messages and causes can contain response bytes or URLs.
            // Keep bounded operational diagnostics only, including no raw throwable in the log.
            String diagnosticName = name != null && name.matches("[A-Za-z0-9_-]{1,32}") ? name : "provider";
            log.error("[LlmClient:{}] request-failed phase={} httpStatus={} errorType={}",
                    diagnosticName, phase, status, e.getClass().getSimpleName());
            throw new RuntimeException("AI 模型调用失败 (phase=" + phase + ", HTTP=" + status + ")");
        }
    }

    /** The existing reply contract is plain text, never a coerced JSON value. */
    private String responseText(String result)
    {
        JSONObject json = JSON.parseObject(result);
        Object choicesValue = json == null ? null : json.get("choices");
        if (!(choicesValue instanceof JSONArray) || ((JSONArray) choicesValue).isEmpty())
        {
            throw new IllegalStateException("AI 模型返回为空");
        }
        Object choice = ((JSONArray) choicesValue).get(0);
        Object message = choice instanceof JSONObject ? ((JSONObject) choice).get("message") : null;
        Object content = message instanceof JSONObject ? ((JSONObject) message).get("content") : null;
        if (!(content instanceof String) || ((String) content).trim().isEmpty())
        {
            throw new IllegalStateException("AI 模型返回文本无效");
        }
        return (String) content;
    }

    private String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))
                && Character.isLowSurrogate(s.charAt(end))) end--;
        return s.substring(0, end);
    }
}
