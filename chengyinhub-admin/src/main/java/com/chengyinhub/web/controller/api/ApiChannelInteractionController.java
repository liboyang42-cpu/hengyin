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

import com.chengyinhub.business.service.channel.platform.ChannelInteractionService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 频道接龙与每日打卡（docs/channels/contract.md §6.7）。App 这两族只把 HTTP 2xx + body.code 当作服务端答复，
 * 业务拒绝走 body.code(400/403/404/409) + errorCode，与好友接口同一信封。
 */
@RestController
@Tag(name = "频道互动")
public class ApiChannelInteractionController extends BaseController
{
    @Autowired
    private ChannelInteractionService interaction;

    @Operation(summary = "接龙：create/join/withdraw/close")
    @PostMapping("/api/im/chain/{operation:create|join|withdraw|close}")
    public ResponseEntity<AjaxResult> chain(@PathVariable("operation") String operation, @RequestBody(required = false) Map<String, Object> body)
    {
        return ApiFriendController.envelope(this::getAppUserId, me -> interaction.chain(me, operation, body));
    }

    @Operation(summary = "接龙详情与参与列表")
    @GetMapping("/api/im/chain/info")
    public ResponseEntity<AjaxResult> info(@RequestParam("conversation_id") String conversationId, @RequestParam(value = "chain_id", required = false) String chainId,
            @RequestParam(value = "cursor", defaultValue = "0") long cursor, @RequestParam(value = "limit", defaultValue = "50") int limit)
    {
        return ApiFriendController.envelope(this::getAppUserId,
                me -> interaction.chainInfo(me, ChannelHttpResponse.id(conversationId), chainId == null ? null : ChannelHttpResponse.id(chainId),
                        cursor, limit));
    }

    @Operation(summary = "接龙原回执")
    @GetMapping("/api/im/interaction/request")
    public ResponseEntity<AjaxResult> chainReceipt(@RequestParam("conversation_id") String conversationId,
            @RequestParam("request_id") String requestId)
    {
        return ApiFriendController.envelope(this::getAppUserId,
                me -> interaction.interactionReceipt(me, ChannelHttpResponse.id(conversationId), requestId));
    }

    @Operation(summary = "频道打卡")
    @PostMapping("/api/im/channel/checkin")
    public ResponseEntity<AjaxResult> checkin(@RequestBody(required = false) Map<String, Object> body)
    {
        return ApiFriendController.envelope(this::getAppUserId, me -> interaction.checkin(me, body));
    }

    @Operation(summary = "打卡状态")
    @GetMapping("/api/im/channel/checkin-status")
    public ResponseEntity<AjaxResult> status(@RequestParam("channel_id") String channelId)
    {
        return ApiFriendController.envelope(this::getAppUserId, me -> interaction.checkinStatus(me, ChannelHttpResponse.id(channelId)));
    }

    @Operation(summary = "30 天打卡榜")
    @GetMapping("/api/im/channel/checkin-rank")
    public ResponseEntity<AjaxResult> rank(@RequestParam("channel_id") String channelId, @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestParam(value = "limit", defaultValue = "10") int limit)
    {
        return ApiFriendController.envelope(this::getAppUserId, me -> interaction.checkinRank(me, ChannelHttpResponse.id(channelId), days, limit));
    }

    @Operation(summary = "打卡原回执")
    @GetMapping("/api/im/channel/checkin-request")
    public ResponseEntity<AjaxResult> checkinReceipt(@RequestParam("channel_id") String channelId, @RequestParam("request_id") String requestId,
            @RequestParam("checkin_date") String checkinDate)
    {
        return ApiFriendController.envelope(this::getAppUserId,
                me -> interaction.checkinReceipt(me, ChannelHttpResponse.id(channelId), requestId, checkinDate));
    }
}
