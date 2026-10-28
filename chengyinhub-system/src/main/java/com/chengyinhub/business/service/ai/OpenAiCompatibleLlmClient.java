package com.chengyinhub.business.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.AiModelConfig;

import java.util.concurrent.TimeUnit;
import com.chengyinhub.common.utils.http.HttpUtils;
import com.chengyinhub.common.utils.http.HttpRequestException;

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
    private final AiHttpAdmission admission;

    /**
     * 保留旧签名供本地 readiness/parser 调用；未显式注入 admission 时禁止网络发送。
     */
    public OpenAiCompatibleLlmClient(String name, AiModelConfig config)
    {
        this(name, config.getBaseUrl(), config.getApiKey(), config.getModel(), config.isEnabled(), config.getTimeoutMs());
    }

    /**
     * 保留旧签名；网络发送必须使用带启动 admission 的构造器。
     */
    public OpenAiCompatibleLlmClient(String name, String baseUrl, String apiKey, String model, boolean enabled, int timeoutMs)
    {
        this(name, baseUrl, apiKey, model, enabled, timeoutMs, AiHttpAdmission.closed());
    }

    /** Production callers must supply the single startup admission bean. */
    public OpenAiCompatibleLlmClient(String name, String baseUrl, String apiKey, String model,
            boolean enabled, int timeoutMs, AiHttpAdmission admission)
    {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
        this.admission = admission == null ? AiHttpAdmission.closed() : admission;
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
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
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
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
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
        if (!enabled || apiKey == null || apiKey.isBlank()
                || apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)
                || model == null || model.isBlank() || timeoutMs <= 0)
        {
            throw new AiProviderNotSentException();
        }
        AiHttpAdmission.origin(completionUrl(baseUrl));
    }

    static String completionUrl(String baseUrl)
    {
        if (baseUrl == null || baseUrl.isBlank()) throw new AiProviderNotSentException();
        return baseUrl.replaceAll("/+$", "") + "/chat/completions";
    }

    /** Text/vision share one bounded authenticated POST; no provider retry is allowed. */
    private String post(JSONObject body)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireReady();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMs, 10_000));
        String url = completionUrl(baseUrl);
        int status = -1;
        String phase = "local";
        try (HttpUtils.AttemptLease lease = admission.acquire(url))
        {
            byte[] json;
            try
            {
                json = JSON.toJSONBytes(body);
            }
            catch (RuntimeException invalid)
            {
                throw new AiProviderNotSentException();
            }
            phase = "transport";
            String result = HttpUtils.sendJsonPostBytes(url, json, apiKey, deadline, lease);
            // The String-only common API does not expose a success status; do not invent one.
            phase = "parse";
            return responseText(result);
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (AiProviderNotSentException notSent)
        {
            logFailure("not-sent", 0, "NOT_SENT");
            throw notSent;
        }
        catch (HttpRequestException transport)
        {
            status = transport.getStatusCode();
            if (transport.getSendDisposition() == HttpRequestException.SendDisposition.NOT_SENT)
            {
                logFailure("not-sent", status, "NOT_SENT");
                throw new AiProviderNotSentException();
            }
            logFailure(phase, status, "PROVIDER_OUTCOME_UNKNOWN");
            throw failure(phase, status);
        }
        catch (Exception unknown)
        {
            logFailure(phase, status, "PROVIDER_OUTCOME_UNKNOWN");
            throw failure(phase, status);
        }
    }

    private RuntimeException failure(String phase, int status)
    {
        // No raw throwable, URL, auth, prompt, body, parser message or suppressed exception.
        return new RuntimeException("AI 模型调用失败 (phase=" + phase + ", HTTP=" + status + ")");
    }

    private void logFailure(String phase, int status, String code)
    {
        String diagnosticName = name != null && name.matches("[A-Za-z0-9_-]{1,32}") ? name : "provider";
        log.error("[LlmClient:{}] request-failed phase={} httpStatus={} code={}", diagnosticName, phase, status, code);
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
