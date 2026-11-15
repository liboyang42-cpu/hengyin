package com.chengyinhub.web.controller.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.channel.messaging.MessageInteractionService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.web.realtime.ImRealtimeBus;
import com.chengyinhub.web.realtime.ImRealtimeHub;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * W5 C2 频道/会话消息：实时流（SSE）、表情回应、消息附加信息（回应 + 引用 + 话题计数）、话题列表、正在输入。
 * 业务拒绝走 body.code（400/403/404/409/429）+ errorCode，HTTP 200，与 /api/im 其余接口同一信封。
 */
@RestController
@Tag(name = "站内消息-互动与实时")
public class ApiImMessagingController extends BaseController
{
    @Autowired
    private MessageInteractionService interaction;
    @Autowired
    private IImService im;
    @Autowired
    private ImRealtimeHub hub;
    @Autowired
    private ImRealtimeBus bus;

    @Value("${chengyin.im.realtime.enabled:true}")
    private boolean realtimeEnabled;

    /**
     * 打开中的会话实时流（text/event-stream）。事件：ready、message.created、message.updated、reaction.updated、
     * pin.updated、typing，以及拒绝时的 error（随后关闭，客户端退回轮询）。事件只带 id，不带正文。
     */
    @Operation(summary = "会话实时流(SSE)")
    @GetMapping(value = "/api/im/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "conversation_id", required = false) String conversationId,
            HttpServletResponse response)
    {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        Long me = viewer();
        if (me == null) return refused(401, "UNAUTHORIZED", "请先登录");
        if (!realtimeEnabled) return refused(503, "REALTIME_OFF", "实时连接暂未开放，已改为定时刷新");
        long conv;
        try { conv = Long.parseLong(conversationId == null ? "" : conversationId); }
        catch (NumberFormatException e) { return refused(400, "BAD_FIELD", "conversation_id 不合法"); }
        if (conv <= 0) return refused(400, "BAD_FIELD", "conversation_id 不合法");
        try { im.requireReadable(me, conv); }
        catch (ServiceException e) { return refused(e.getCode() == null ? 403 : e.getCode(), errorCode(e, "FORBIDDEN"), e.getMessage()); }
        catch (RuntimeException e)
        {
            if (e.getClass() != RuntimeException.class) { logger.error("IM 实时流鉴权异常", e); return refused(500, "SERVER_ERROR", "操作失败，请稍后重试"); }
            return refused(403, "FORBIDDEN", e.getMessage());
        }
        SseEmitter emitter = hub.open(me, conv);
        if (emitter == null) return refused(429, "TOO_MANY_STREAMS", "连接过多，已改为定时刷新");
        bus.ensureListening();
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "ready");
        ready.put("conversation_id", String.valueOf(conv));
        ready.put("heartbeat_sec", 25);
        ready.put("reconnect_after_sec", 300);
        try { emitter.send(SseEmitter.event().name("ready").reconnectTime(3000).data(JSON.toJSONString(ready), MediaType.APPLICATION_JSON)); }
        catch (IOException gone) { emitter.complete(); }
        return emitter;
    }

    @Operation(summary = "表情回应：加上或取消")
    @PostMapping("/api/im/reaction")
    public AjaxResult react(@RequestBody(required = false) Map<String, Object> body)
    {
        return envelope(me -> interaction.react(me, body));
    }

    @Operation(summary = "可用表情")
    @GetMapping("/api/im/reaction/emoji")
    public AjaxResult emoji()
    {
        return envelope(me -> interaction.emojiSet());
    }

    @Operation(summary = "消息附加信息：回应、引用摘要、话题计数（一页最多 50 条）")
    @GetMapping("/api/im/message/extras")
    public AjaxResult extras(@RequestParam("conversation_id") String conversationId, @RequestParam(value = "ids", required = false) String ids)
    {
        return envelope(me -> interaction.extras(me, positive(conversationId, "conversation_id"), ids));
    }

    @Operation(summary = "话题内回复（正序，after 之后）")
    @GetMapping("/api/im/thread")
    public AjaxResult thread(@RequestParam("conversation_id") String conversationId, @RequestParam("root_id") String rootId,
            @RequestParam(value = "after", required = false) String after, @RequestParam(value = "limit", defaultValue = "30") int limit)
    {
        return envelope(me -> interaction.thread(me, positive(conversationId, "conversation_id"), positive(rootId, "root_id"), after, limit));
    }

    @Operation(summary = "正在输入（3 秒内重复调用只发一次）")
    @PostMapping("/api/im/typing")
    public AjaxResult typing(@RequestBody(required = false) Map<String, Object> body)
    {
        return envelope(me -> {
            if (body == null || body.size() != 1 || !body.containsKey("conversation_id")) throw new ServiceException("只接受 conversation_id", 400);
            return interaction.typing(me, positive(String.valueOf(body.get("conversation_id")), "conversation_id"));
        });
    }

    // ---------------------------------------------------------------- helpers

    private Long viewer()
    {
        try
        {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        }
        catch (Exception e) { return null; }
    }

    private AjaxResult envelope(Function<Long, Map<String, Object>> body)
    {
        Long me = viewer();
        if (me == null) return new AjaxResult(401, "请先登录");
        try { return AjaxResult.success(body.apply(me)); }
        catch (ServiceException e)
        {
            AjaxResult r = new AjaxResult(e.getCode() == null ? 400 : e.getCode(), e.getMessage());
            r.put("errorCode", errorCode(e, "REJECTED"));
            return r;
        }
        catch (RuntimeException e)
        {
            if (e.getClass() == RuntimeException.class)
            {
                // IImService 的读闸对“已退出群/队伍”抛普通 RuntimeException（中文提示）。
                AjaxResult r = new AjaxResult(403, e.getMessage());
                r.put("errorCode", "FORBIDDEN");
                return r;
            }
            logger.error("IM 互动接口异常", e);
            return AjaxResult.error("操作失败，请稍后重试");
        }
    }

    private static String errorCode(ServiceException e, String fallback)
    {
        String detail = e.getDetailMessage();
        return detail != null && detail.matches("[A-Z_]{3,40}") ? detail : fallback;
    }

    private static long positive(String raw, String key)
    {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) throw new ServiceException(key + " 不合法", 400);
        return Long.parseLong(raw);
    }

    private static SseEmitter refused(int code, String errorCode, String message)
    {
        SseEmitter emitter = new SseEmitter(1000L);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("code", code);
        body.put("errorCode", errorCode);
        body.put("msg", message);
        body.put("fallback", "POLL");
        try
        {
            emitter.send(SseEmitter.event().name("error").data(JSON.toJSONString(body), MediaType.APPLICATION_JSON));
            emitter.complete();
        }
        catch (IOException | IllegalStateException e) { emitter.completeWithError(e); }
        return emitter;
    }
}
