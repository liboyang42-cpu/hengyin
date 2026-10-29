package com.chengyinhub.business.service.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.utils.http.HttpUtils;
import java.util.concurrent.TimeUnit;

/**
 * 阿里云百炼(DashScope)的声音复刻与语音合成。
 *
 * 两个端点:
 *   复刻  POST {base}/customization        model=voice-enrollment
 *   合成  POST {base}/SpeechSynthesizer     model=cosyvoice-v2
 * base 形如 https://{workspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts
 *
 * ⚠️ 这里刻意不做任何「失败就返回空」的兜底。语音失败时上层要能区分是没开通、
 * 还是上游抖了 —— 吞掉异常返回 null,调用方只能猜,而这两种情况给用户的话完全不同。
 *
 * @author chengyinhub
 */
@Component
public class DashScopeVoiceClient implements VoiceCloneClient
{
    private static final Logger log = LoggerFactory.getLogger(DashScopeVoiceClient.class);

    /** 音色部署完成的状态字面量。其余状态一律当作「还不能用」。 */
    private static final String STATUS_OK = "OK";

    @Autowired
    private AiVoiceConfig config;

    @Autowired
    private AiHttpAdmission admission = AiHttpAdmission.closed();

    @Override
    public boolean usable()
    {
        return config.isUsable();
    }

    @Override
    public String enroll(String audioUrl) throws AiServiceException
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireUsable();
        if (audioUrl == null || audioUrl.trim().isEmpty())
        {
            throw new AiServiceException("没有可用于复刻的录音");
        }

        JSONObject input = new JSONObject();
        input.put("action", "create_voice");
        input.put("target_model", config.getTargetModel());
        input.put("prefix", config.getPrefix());
        input.put("url", audioUrl);

        JSONObject body = new JSONObject();
        body.put("model", config.getEnrollModel());
        body.put("input", input);

        ProviderReply reply = post(config.baseUrl() + "/customization", body, "复刻");
        JSONObject out = reply.output();
        // 官方在不同模型系列下字段名不同(voice_id / voice),两个都认 ——
        // 只认一个的话换模型系列会得到「成功但没有音色」,而那是不会报错的
        String voiceId = out.getString("voice_id");
        if (voiceId == null || voiceId.trim().isEmpty())
        {
            voiceId = out.getString("voice");
        }
        if (voiceId == null || voiceId.trim().isEmpty())
        {
            log.error("[VoiceClient] requestId={} action={} category={}", reply.requestId(), "复刻", "MISSING_VOICE_ID");
            throw new AiServiceException("复刻没有返回音色");
        }
        return voiceId;
    }

    @Override
    public boolean isReady(String voiceId) throws AiServiceException
    {
        requireUsable();
        if (voiceId == null || voiceId.trim().isEmpty()) return false;

        JSONObject input = new JSONObject();
        input.put("action", "query_voice");
        input.put("voice_id", voiceId);

        JSONObject body = new JSONObject();
        body.put("model", config.getEnrollModel());
        body.put("input", input);

        JSONObject out = post(config.baseUrl() + "/customization", body, "查询音色").output();
        return STATUS_OK.equalsIgnoreCase(out.getString("status"));
    }

    @Override
    public String synthesize(String voiceId, String text) throws AiServiceException
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        requireUsable();
        if (voiceId == null || voiceId.trim().isEmpty())
        {
            throw new AiServiceException("这个形象还没有配声音");
        }
        if (text == null || text.trim().isEmpty())
        {
            throw new AiServiceException("没有要合成的内容");
        }

        JSONObject input = new JSONObject();
        input.put("text", text);
        input.put("voice", voiceId);
        input.put("format", "mp3");

        JSONObject body = new JSONObject();
        body.put("model", config.getSynthModel());
        body.put("input", input);

        ProviderReply reply = post(config.baseUrl() + "/SpeechSynthesizer", body, "合成");
        JSONObject out = reply.output();
        String url = out.getString("audio_url");
        if (url == null || url.trim().isEmpty())
        {
            JSONObject audio = out.getJSONObject("audio");
            if (audio != null) url = audio.getString("url");
        }
        if (url == null || url.trim().isEmpty())
        {
            log.error("[VoiceClient] requestId={} action={} category={}", reply.requestId(), "合成", "MISSING_AUDIO_URL");
            throw new AiServiceException("合成没有返回音频");
        }
        return url;
    }

    /** 开关开着但 key 没注入,是最常见的「配了一半」—— 这时必须明确报未开通,不能去打空 key 的请求。 */
    private void requireUsable() throws AiServiceException
    {
        if (!config.isUsable())
        {
            throw new AiServiceException("语音服务未开通");
        }
    }

    private ProviderReply post(String url, JSONObject body, String what) throws AiServiceException
    {
        // Original-id status queries remain available; enrollment/synthesis do not.
        JSONObject input = body.getJSONObject("input");
        if (input == null || !"query_voice".equals(input.getString("action")))
            com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String raw;
        String correlationId = java.util.UUID.randomUUID().toString(); // log correlation only, never an external business identity
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(config.getTimeoutMs(), 10_000));
        // Reuse the existing admitted one-attempt transport; its physical worker owns the transferred lease.
        try (HttpUtils.AttemptLease lease = admission.acquire(url))
        {
            raw = HttpUtils.sendJsonPost(url, body.toJSONString(), config.getApiKey(), deadline, lease);
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (AiServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[VoiceClient] requestId={} action={} category={} failureType={}",
                    correlationId, what, "TRANSPORT_FAILURE", e.getClass().getSimpleName());
            // Keep VoiceCloneClient's existing exception contract. Do not turn a dispatched failure into NOT_SENT.
            throw new AiServiceException(what + "失败,请稍后再试", e);
        }

        JSONObject json;
        try
        {
            json = JSON.parseObject(raw);
        }
        catch (Exception e)
        {
            log.error("[VoiceClient] requestId={} action={} category={}", correlationId, what, "INVALID_JSON");
            throw new AiServiceException(what + "失败,请稍后再试");
        }
        String requestId = safeRequestId(json, correlationId);
        // DashScope 的错误是 HTTP 200 带 code 字段 —— 只看状态码会把失败当成功,
        // 然后在下一步「取不到 voice_id」的地方以一个无关的错误暴露出来
        String code = json.getString("code");
        if (code != null && !code.trim().isEmpty())
        {
            log.error("[VoiceClient] requestId={} action={} category={}", requestId, what, "PROVIDER_REJECTED");
            throw new AiServiceException(what + "失败:" + code);
        }
        JSONObject out = json.getJSONObject("output");
        if (out == null)
        {
            log.error("[VoiceClient] requestId={} action={} category={}", requestId, what, "MISSING_OUTPUT");
            throw new AiServiceException(what + "失败,请稍后再试");
        }
        return new ProviderReply(out, requestId);
    }

    private record ProviderReply(JSONObject output, String requestId) {}

    private static String safeRequestId(JSONObject response, String fallback)
    {
        String value = response == null ? null : response.getString("request_id");
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                ? value : fallback;
    }

    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
