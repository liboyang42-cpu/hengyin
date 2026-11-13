package com.chengyinhub.web.controller.api;

import java.util.Map;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.friend.NativeBlacklistService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 黑名单（App Core/NativeBlacklist*.swift，docs/channels/contract.md §8.1）。
 * 信封同好友端：HTTP 200 + body.code；回执（含 REJECTED）一律 code 200；结果不确定 HTTP 500。
 * 拉黑本身仍是 POST /api/im/block（同一张 im_block）。
 */
@RestController
@Tag(name = "黑名单")
public class ApiNativeBlacklistController extends BaseController
{
    @Autowired
    private NativeBlacklistService blacklist;

    @Operation(summary = "我拉黑的人（block_id 倒序，每页 50）")
    @GetMapping("/api/native/blacklist/list")
    public ResponseEntity<AjaxResult> list(@RequestParam("cursor") long cursor)
    {
        return run(me -> blacklist.page(me, cursor));
    }

    @Operation(summary = "我是否拉黑了对方（当前 block_id）")
    @GetMapping("/api/native/blacklist/current")
    public ResponseEntity<AjaxResult> current(@RequestParam("peer_id") long peerId)
    {
        return run(me -> blacklist.current(me, peerId));
    }

    @Operation(summary = "取消拉黑（只删 expected_block_id 这一行）")
    @PostMapping("/api/native/blacklist/unblock")
    public ResponseEntity<AjaxResult> unblock(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> blacklist.unblock(me, body));
    }

    @Operation(summary = "取消拉黑原回执（只读；未知为 UNKNOWN）")
    @GetMapping("/api/native/blacklist/receipt")
    public ResponseEntity<AjaxResult> receipt(@RequestParam("peer_id") long peerId,
            @RequestParam("expected_block_id") long expectedBlockId, @RequestParam("request_id") String requestId)
    {
        return run(me -> blacklist.receipt(me, peerId, expectedBlockId, requestId));
    }

    @Operation(summary = "取消拉黑结算（已有回执原样返回，否则终局为未改变）")
    @PostMapping("/api/native/blacklist/settle")
    public ResponseEntity<AjaxResult> settle(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> blacklist.settle(me, body));
    }

    ResponseEntity<AjaxResult> run(Function<Long, Map<String, Object>> body)
    {
        return ApiFriendController.envelope(() -> getAppUserId(), body);
    }
}
