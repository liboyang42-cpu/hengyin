package com.chengyinhub.business.service.impl;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.dto.ai.AiThemeDraftReq;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftRequestState;
import com.chengyinhub.business.domain.vo.ai.AiThemeDraftResp;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.ai.AiProviderOutcomeUnknownException;
import com.chengyinhub.business.service.ai.AiServiceException;

/**
 * AI 主题草稿的 requestId 幂等与回读(#22)。
 *
 * <p>同一会员 + 同一 requestId 只调一次模型:先在 Redis 占位 PROCESSING,再进 {@link IAiCreatorService#themeDraft};
 * 结果(SUCCEEDED 带当时那份草稿 / FAILED 带原因 / UNKNOWN 保留原请求)写回同一键。重复提交与回读都只读这条记录,不再调模型。
 *
 * <p>占位后超过租约仍无终态 = UNKNOWN:进程可能在模型返回后、写终态前退出。此时既不能说「没生成」,
 * 也不能拿额度变化去推断「已生成」—— 额度会因释放、跨日、其它请求而变化,不是这一次生成的证据。
 *
 * <p>状态存储不可用时 fail-closed:不占位就不调模型,避免同一 requestId 在重试时被重复计费。
 */
@Service
public class AiThemeDraftRequestService
{
    private static final Logger log = LoggerFactory.getLogger(AiThemeDraftRequestService.class);

    static final String KEY_PREFIX = "ai:theme:draft:request:";
    /** 长于网关单次调用上限;超过即判 UNKNOWN。 */
    static final long LEASE_MILLIS = 3 * 60 * 1000L;
    static final long RETENTION_HOURS = 24;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String GENERIC_FAILURE = "AI 起草没有成功,请稍后重试";

    @Autowired
    private IAiCreatorService aiCreatorService;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    /** 状态存储读写失败。调用方据此回 STATE_UNAVAILABLE,而不是把它当成「没有这条请求」。 */
    public static class StateUnavailableException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        StateUnavailableException(Throwable cause) { super("AI 草稿状态暂时无法确认", cause); }
    }

    public static boolean isValidRequestId(String requestId)
    {
        return requestId != null && UUID_PATTERN.matcher(requestId.trim()).matches();
    }

    /**
     * 提交(或重放)一次草稿请求。返回值的 state 只会是 SUCCEEDED / FAILED / PROCESSING / UNKNOWN。
     *
     * @throws StateUnavailableException 占位前读写状态失败(此时没有调用模型)
     */
    public AiThemeDraftRequestState submit(AiThemeDraftReq req, Long memberId)
    {
        String rid = req.getRequestId().trim();
        String key = key(memberId, rid);
        JSONObject processing = new JSONObject();
        processing.put("state", AiThemeDraftRequestState.PROCESSING);
        processing.put("startedAt", System.currentTimeMillis());
        Boolean claimed;
        try
        {
            claimed = redisTemplate.opsForValue().setIfAbsent(key, processing.toJSONString(),
                    RETENTION_HOURS, TimeUnit.HOURS);
        }
        catch (RuntimeException e)
        {
            throw new StateUnavailableException(e);
        }
        if (!Boolean.TRUE.equals(claimed))
        {
            AiThemeDraftRequestState existing = read(memberId, rid);
            if (AiThemeDraftRequestState.NOT_FOUND.equals(existing.getState()))
            {
                // 占位失败却读不到:只可能是存储抖动或恰好过期。不知道有没有另一份在途,不调模型。
                existing.setState(AiThemeDraftRequestState.UNKNOWN);
            }
            existing.setReplayed(true);
            return existing;
        }

        AiThemeDraftRequestState out = new AiThemeDraftRequestState();
        out.setRequestId(rid);
        try
        {
            AiThemeDraftResp resp = aiCreatorService.themeDraft(req, memberId);
            out.setState(AiThemeDraftRequestState.SUCCEEDED);
            out.setResult(resp);
            JSONObject done = new JSONObject();
            done.put("state", AiThemeDraftRequestState.SUCCEEDED);
            done.put("result", resp);
            writeTerminal(key, done);
            return out;
        }
        catch (AiProviderOutcomeUnknownException unknown)
        {
            // Only the provider subtype proves an uncertain original call; input failures keep their policy.
            out.setState(AiThemeDraftRequestState.UNKNOWN);
        }
        catch (AiServiceException e)
        {
            out.setState(AiThemeDraftRequestState.FAILED);
            out.setMessage(e.getMessage());
        }
        catch (RuntimeException e)
        {
            log.error("[AiThemeDraft] unexpected failure memberId={} requestId={}", memberId, rid, e);
            out.setState(AiThemeDraftRequestState.FAILED);
            out.setMessage(GENERIC_FAILURE);
        }
        JSONObject terminal = new JSONObject();
        terminal.put("state", out.getState());
        terminal.put("message", out.getMessage());
        writeTerminal(key, terminal);
        return out;
    }

    /**
     * 只读回读。键里带 memberId:别人的 requestId 在这里天然是 NOT_FOUND,不存在越权读。
     *
     * @throws StateUnavailableException 存储读失败
     */
    public AiThemeDraftRequestState read(Long memberId, String requestId)
    {
        String rid = requestId.trim();
        Object raw;
        try
        {
            raw = redisTemplate.opsForValue().get(key(memberId, rid));
        }
        catch (RuntimeException e)
        {
            throw new StateUnavailableException(e);
        }
        AiThemeDraftRequestState out = new AiThemeDraftRequestState();
        out.setRequestId(rid);
        if (raw == null)
        {
            out.setState(AiThemeDraftRequestState.NOT_FOUND);
            return out;
        }
        JSONObject stored;
        try
        {
            stored = JSON.parseObject(String.valueOf(raw));
        }
        catch (RuntimeException e)
        {
            stored = null;
        }
        String state = stored == null ? null : stored.getString("state");
        if (AiThemeDraftRequestState.SUCCEEDED.equals(state))
        {
            AiThemeDraftResp result = stored.getObject("result", AiThemeDraftResp.class);
            if (result != null && result.getDraft() != null)
            {
                out.setState(state);
                out.setResult(result);
                return out;
            }
        }
        else if (AiThemeDraftRequestState.FAILED.equals(state))
        {
            out.setState(state);
            String message = stored.getString("message");
            out.setMessage(message == null || message.trim().isEmpty() ? GENERIC_FAILURE : message);
            return out;
        }
        else if (AiThemeDraftRequestState.PROCESSING.equals(state))
        {
            long startedAt = stored.getLongValue("startedAt");
            out.setState(System.currentTimeMillis() - startedAt > LEASE_MILLIS
                    ? AiThemeDraftRequestState.UNKNOWN : AiThemeDraftRequestState.PROCESSING);
            return out;
        }
        // 记录存在但解析不出终态:结果不可证明,按 UNKNOWN,绝不当作「没有生成」。
        out.setState(AiThemeDraftRequestState.UNKNOWN);
        return out;
    }

    private void writeTerminal(String key, JSONObject value)
    {
        try
        {
            redisTemplate.opsForValue().set(key, value.toJSONString(), RETENTION_HOURS, TimeUnit.HOURS);
        }
        catch (RuntimeException e)
        {
            // 本次响应照常返回;只是之后的回读会在租约到期后看到 UNKNOWN —— 那是如实的。
            log.error("[AiThemeDraft] terminal state write failed key={}", key, e);
        }
    }

    static String key(Long memberId, String requestId)
    {
        return KEY_PREFIX + memberId + ":" + requestId.toLowerCase();
    }
}
