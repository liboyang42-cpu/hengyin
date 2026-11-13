package com.chengyinhub.web.controller.api;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.channel.platform.ProgressPrivacyService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 进度隐私（docs/channels/contract.md §3.9–3.10、§6.9）。/api/privacy/progress 同时服务两套 App 客户端：
 * 带 channel_id 的 GET 与带四个范围的 POST 属于频道进度页；其余属于隐私设置页。
 */
@RestController
@Tag(name = "进度隐私")
public class ApiProgressPrivacyController extends BaseController
{
    @Autowired
    private ProgressPrivacyService privacy;

    @Operation(summary = "本人进度隐私（设置页快照 / 带 channel_id 时为频道进度页视图）")
    @GetMapping("/api/privacy/progress")
    public ResponseEntity<AjaxResult> read(@RequestParam(value = "channel_id", required = false) String channelId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> channelId == null ? privacy.userSnapshot(me)
                : privacy.progressPrivacy(me, ChannelHttpResponse.id(channelId)));
    }

    @Operation(summary = "保存本人进度隐私")
    @PostMapping("/api/privacy/progress")
    public ResponseEntity<AjaxResult> save(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> body != null && body.containsKey("detail_scope")
                ? privacy.saveProgress(me, body) : privacy.saveAggregate(me, null, body));
    }

    @Operation(summary = "频道进度隐私快照")
    @GetMapping("/api/channel/{channelId}/progress/privacy")
    public ResponseEntity<AjaxResult> channel(@PathVariable("channelId") String channelId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> privacy.channelSnapshot(me, ChannelHttpResponse.id(channelId)));
    }

    @Operation(summary = "保存频道进度隐私（MANAGE_PROGRESS）")
    @PostMapping("/api/channel/{channelId}/progress/privacy")
    public ResponseEntity<AjaxResult> saveChannel(@PathVariable("channelId") String channelId, @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> privacy.saveAggregate(me, ChannelHttpResponse.id(channelId), body));
    }

    @Operation(summary = "隐私设置原回执（?request_id=）")
    @GetMapping("/api/progress/requests")
    public ResponseEntity<AjaxResult> settingsReceipt(@RequestParam("request_id") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> privacy.settingsReceipt(me, requestId));
    }

    @Operation(summary = "频道进度页隐私原回执")
    @GetMapping("/api/progress/requests/{requestId}")
    public ResponseEntity<AjaxResult> progressReceipt(@PathVariable("requestId") String requestId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> privacy.progressReceipt(me, requestId));
    }
}
