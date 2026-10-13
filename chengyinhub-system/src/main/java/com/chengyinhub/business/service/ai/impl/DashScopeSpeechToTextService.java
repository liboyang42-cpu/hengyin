package com.chengyinhub.business.service.ai.impl;

import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.common.config.AiVoiceConfig;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

/** 阿里云百炼短录音识别 adapter。 */
@Service
public class DashScopeSpeechToTextService implements SpeechToTextService
{
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
        if (!available())
        {
            throw new AiServiceException("语音识别未开通");
        }
        if (audio == null || audio.length == 0)
        {
            throw new IllegalArgumentException("audio must not be empty");
        }
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase();
        String mimeType = mimeTypeOf(normalizedFormat);
        if (mimeType == null)
        {
            throw new IllegalArgumentException("unsupported audio format");
        }

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

        String raw;
        try (HttpResponse response = HttpRequest.post(endpoint())
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .header("X-DashScope-SSE", "disable")
                .body(body.toJSONString())
                .timeout(config.getTimeoutMs())
                .execute())
        {
            raw = response.body();
            if (!response.isOk())
            {
                throw new AiServiceException("语音识别失败,请稍后再试");
            }
        }
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
