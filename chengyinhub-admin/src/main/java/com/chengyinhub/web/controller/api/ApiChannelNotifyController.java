package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ChannelNotifyService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 频道/会话通知设置（docs/channels/contract.md §6.8）。JSON 版 /api/im/mute、/api/im/read 与旧表单版并存：
 * 按 Content-Type 分派，表单旧版仍由 ApiImController 处理（带 request_id 时也落原回执）。
 */
@RestController
@Tag(name = "频道通知")
public class ApiChannelNotifyController extends BaseController
{
    @Autowired
    private ChannelNotifyService notify;

    @Operation(summary = "通知偏好目录/单项")
    @GetMapping("/api/im/notify-pref")
    public ResponseEntity<AjaxResult> preferences(@RequestParam(value = "scope_type", required = false) Integer scopeType,
            @RequestParam(value = "scope_id", required = false) String scopeId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.preferences(me, scopeType, scopeId));
    }

    @Operation(summary = "保存通知偏好")
    @PostMapping("/api/im/notify-pref")
    public ResponseEntity<AjaxResult> save(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.savePreference(me, body));
    }

    @Operation(summary = "会话/频道静音（JSON 原请求）")
    @PostMapping(value = "/api/im/mute", consumes = "application/json")
    public ResponseEntity<AjaxResult> mute(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.mute(me, body));
    }

    @Operation(summary = "频道已读确认（JSON 原请求）")
    @PostMapping(value = "/api/im/read", consumes = "application/json")
    public ResponseEntity<AjaxResult> ack(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.ack(me, body));
    }

    @Operation(summary = "俱乐部共享通知默认（成员可读）")
    @GetMapping("/api/club/settings/notify-level")
    public ResponseEntity<AjaxResult> clubDefault(@RequestParam("clubId") String clubId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.clubDefault(me, clubId));
    }

    @Operation(summary = "保存俱乐部共享通知默认（管理员，按版本 CAS，回执 CLUB_DEFAULT）")
    @PostMapping(value = "/api/club/settings/notify-level", consumes = "application/json")
    public ResponseEntity<AjaxResult> saveClubDefault(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.saveClubDefault(me, body));
    }

    @Operation(summary = "通知写原回执")
    @RequestMapping(value = "/api/im/notification-requests/{requestId}", method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity<AjaxResult> receipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.notificationReceipt(me, requestId));
    }

    @Operation(summary = "未读汇总（含频道读状态）")
    @GetMapping("/api/im/unread-summary")
    public ResponseEntity<AjaxResult> unreadSummary()
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.unreadSummary(me));
    }

    @Operation(summary = "通知投递结果日志")
    @GetMapping("/api/im/notify-log")
    public ResponseEntity<AjaxResult> deliveryLog(@RequestParam("limit") int limit, @RequestParam(value = "event", required = false) String event,
            @RequestParam(value = "cursor", required = false) String cursor)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.deliveryLog(me, limit, event, cursor));
    }

    @Operation(summary = "本机推送尝试（无 APNs 发送端：如实空页）")
    @GetMapping("/api/im/device/attempts")
    public ResponseEntity<AjaxResult> pushAttempts(@RequestParam("installation_id") String installationId,
            @RequestParam(value = "before", defaultValue = "0") String before,
            @RequestParam(value = "read_attempts", required = false) String readAttempts)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.pushAttempts(me, installationId, before, readAttempts));
    }

    @Operation(summary = "普通会话已读状态")
    @GetMapping("/api/im/read-state")
    public ResponseEntity<AjaxResult> readState(@RequestParam("conversation_id") String conversationId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.ordinaryState(me, "read", ChannelHttpResponse.id(conversationId)));
    }

    @Operation(summary = "普通会话已读原回执")
    @GetMapping("/api/im/read-receipt")
    public ResponseEntity<AjaxResult> readReceipt(@RequestParam("conversation_id") String conversationId, @RequestParam("read_to") String readTo,
            @RequestParam("request_id") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> notify.ordinaryReceipt(me, "read", ChannelHttpResponse.id(conversationId), requestId, readTo));
    }

    @Operation(summary = "普通会话免打扰状态")
    @GetMapping("/api/im/mute-state")
    public ResponseEntity<AjaxResult> muteState(@RequestParam("conversation_id") String conversationId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> notify.ordinaryState(me, "mute", ChannelHttpResponse.id(conversationId)));
    }

    @Operation(summary = "普通会话免打扰原回执")
    @GetMapping("/api/im/mute-receipt")
    public ResponseEntity<AjaxResult> muteReceipt(@RequestParam("conversation_id") String conversationId, @RequestParam("muted") String muted,
            @RequestParam("request_id") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId,
                me -> notify.ordinaryReceipt(me, "mute", ChannelHttpResponse.id(conversationId), requestId, "1".equals(muted)));
    }
}
