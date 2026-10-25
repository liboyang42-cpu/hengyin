package com.chengyinhub.business.service.ai.impl;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.AiHttpAdmission;
import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.common.config.AiVoiceConfig;

import com.chengyinhub.common.utils.http.HttpUtils;

/** 阿里云百炼短录音识别 adapter。 */
@Service
public class DashScopeSpeechToTextService implements SpeechToTextService
{
    /** Same 2 MiB ceiling as the existing NPC multipart entry, before Base64/JSON allocation. */
    static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;

    @Autowired
    private AiHttpAdmission admission;
    private final AiVoiceConfig config;
    private final String endpointOverride;

    @Autowired
    public DashScopeSpeechToTextService(AiVoiceConfig config)
    {
        this(config, null);
    }

    DashScopeSpeechToTextService(AiVoiceConfig config, String endpointOverride)
    {
        this.config = config;
        this.endpointOverride = endpointOverride;
    }

    @Override
    public boolean available()
    {
        return config != null && config.isAsrUsable();
    }

    @Override
    public String transcribe(byte[] audio, String format)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (!available())
        {
            throw new AiServiceException("语音识别未开通");
        }
        if (audio == null || audio.length == 0)
        {
            throw new IllegalArgumentException("audio must not be empty");
        }
        if (audio.length > MAX_AUDIO_BYTES)
        {
            throw new IllegalArgumentException("audio exceeds existing 2 MiB limit");
        }
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase();
        String mimeType = mimeTypeOf(normalizedFormat);
        if (mimeType == null)
        {
            throw new IllegalArgumentException("unsupported audio format");
        }

        String raw;
        try
        {
            String target = endpoint();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(config.getTimeoutMs(), 10_000));
            try (HttpUtils.AttemptLease lease = acquireAdmission(target))
            {
                JSONObject audioInput = new JSONObject();
                audioInput.put("data", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(audio));
                JSONObject content = new JSONObject();
                content.put("type", "input_audio");
                content.put("input_audio", audioInput);
                JSONArray contents = new JSONArray();
                contents.add(content);
                JSONObject message = new JSONObject();
                message.put("role", "user");
                message.put("content", contents);
                JSONArray messages = new JSONArray();
                messages.add(message);
                JSONObject input = new JSONObject();
                input.put("messages", messages);
                JSONObject parameters = new JSONObject();
                parameters.put("format", normalizedFormat);
                JSONObject body = new JSONObject();
                body.put("model", config.getAsrModel());
                body.put("input", input);
                body.put("parameters", parameters);
                raw = HttpUtils.sendJsonPost(target, body.toJSONString(), config.getApiKey(), deadline, lease,
                        Map.of("X-DashScope-SSE", "disable"));
            }
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (AiServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new AiServiceException("语音识别失败,请稍后再试", e);
        }

        JSONObject result;
        try
        {
            result = JSON.parseObject(raw);
        }
        catch (Exception e)
        {
            throw new AiServiceException("语音识别返回异常", e);
        }
        if (result == null || nonBlank(result.getString("code")))
        {
            throw new AiServiceException("语音识别失败,请稍后再试");
        }
        JSONObject output = result.getJSONObject("output");
        if (output == null)
        {
            throw new AiServiceException("语音识别返回异常");
        }
        String text = output.getString("text");
        return nonBlank(text) ? text.trim() : null;
    }

    private HttpUtils.AttemptLease acquireAdmission(String target)
    {
        if (admission == null) throw new AiProviderNotSentException();
        return admission.acquire(target);
    }

    String endpoint()
    {
        return endpointOverride == null ? config.asrUrl() : endpointOverride;
    }

    private String mimeTypeOf(String format)
    {
        if ("mp3".equals(format)) return "audio/mpeg";
        if ("wav".equals(format)) return "audio/wav";
        if ("m4a".equals(format)) return "audio/mp4";
        if ("aac".equals(format)) return "audio/aac";
        return null;
    }

    private boolean nonBlank(String value)
    {
        return value != null && !value.trim().isEmpty();
    }
}
