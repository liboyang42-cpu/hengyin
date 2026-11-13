package com.chengyinhub.web.controller.api;

import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.friend.FriendService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 好友（App Core/NativeFriend*.swift，docs/channels/contract.md §8）。
 * 信封：HTTP 200 + body.code（App 好友端只把 HTTP401+code401 视为登录失效）；
 * 业务拒绝 code=4xx、msg=稳定错误码；带回执的拒绝（移除/元数据）同时给 data。
 */
@RestController
@Tag(name = "好友")
public class ApiFriendController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(ApiFriendController.class);

    @Autowired
    private FriendService friends;

    @Autowired
    private com.chengyinhub.business.service.friend.FriendInviteService invites;

    @Operation(summary = "好友列表")
    @GetMapping("/api/friend/list")
    public ResponseEntity<AjaxResult> list(@RequestParam("cursor") long cursor, @RequestParam("limit") int limit)
    {
        return run(me -> friends.page(me, false, cursor, limit, null));
    }

    @Operation(summary = "好友申请（收到/发出）")
    @GetMapping("/api/friend/requests")
    public ResponseEntity<AjaxResult> requests(@RequestParam("cursor") long cursor, @RequestParam("limit") int limit,
            @RequestParam(value = "review_cursor", required = false) Long reviewCursor)
    {
        return run(me -> friends.page(me, true, cursor, limit, reviewCursor));
    }

    @Operation(summary = "与某人的关系")
    @GetMapping("/api/friend/relationship")
    public ResponseEntity<AjaxResult> relationship(@RequestParam("peer_id") long peerId)
    {
        return run(me -> friends.relationship(me, peerId));
    }

    @Operation(summary = "好友写：request/accept/reject/cancel")
    @PostMapping("/api/friend/{action:request|accept|reject|cancel}")
    public ResponseEntity<AjaxResult> write(@PathVariable("action") String action, @RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.write(me, action, body));
    }

    @Operation(summary = "好友写原回执")
    @GetMapping("/api/friend/receipt")
    public ResponseEntity<AjaxResult> receipt(@RequestParam("peer_id") long peerId, @RequestParam("action") String action,
            @RequestParam("request_id") String requestId)
    {
        return run(me -> friends.receipt(me, peerId, action, requestId));
    }

    @Operation(summary = "删除好友")
    @PostMapping("/api/friend/remove")
    public ResponseEntity<AjaxResult> remove(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.remove(me, body));
    }

    @Operation(summary = "删除好友原回执")
    @GetMapping("/api/friend/removal/receipt")
    public ResponseEntity<AjaxResult> removalReceipt(@RequestParam("peer_id") long peerId,
            @RequestParam("expected_version") long expectedVersion, @RequestParam("request_id") String requestId)
    {
        return run(me -> friends.removalReceipt(me, peerId, expectedVersion, requestId));
    }

    @Operation(summary = "删除好友结算（只读回原回执，不产生新写）")
    @PostMapping("/api/friend/removal/settle")
    public ResponseEntity<AjaxResult> removalSettle(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.removalReceipt(me, number(body, "peer_id"), number(body, "expected_version"),
                String.valueOf(body.get("request_id"))));
    }

    @Operation(summary = "好友元数据（备注/分组/星标/进度范围）")
    @GetMapping("/api/friend/metadata")
    public ResponseEntity<AjaxResult> metadata(@RequestParam("peer_id") long peerId)
    {
        return run(me -> friends.metadata(me, peerId));
    }

    @Operation(summary = "改备注/星标/分组/进度范围")
    @PostMapping("/api/friend/remark")
    public ResponseEntity<AjaxResult> remark(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.metadataWrite(me, "remark", body));
    }

    @Operation(summary = "新建/重命名分组")
    @PostMapping("/api/friend/group/save")
    public ResponseEntity<AjaxResult> groupSave(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.metadataWrite(me, "group_save", body));
    }

    @Operation(summary = "删除分组")
    @PostMapping("/api/friend/group/delete")
    public ResponseEntity<AjaxResult> groupDelete(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.metadataWrite(me, "group_delete", body));
    }

    @Operation(summary = "分组列表")
    @RequestMapping(value = "/api/friend/group/list", method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity<AjaxResult> groups()
    {
        return run(me -> friends.groups(me));
    }

    @Operation(summary = "元数据原回执")
    @GetMapping("/api/friend/metadata/receipt")
    public ResponseEntity<AjaxResult> metadataReceipt(@RequestParam("action") String action, @RequestParam("target_id") long targetId,
            @RequestParam("request_id") String requestId)
    {
        return run(me -> friends.metadataReceipt(me, action, targetId, requestId, null));
    }

    @Operation(summary = "元数据结算（只读回原回执且指纹一致）")
    @PostMapping("/api/friend/metadata/settle")
    public ResponseEntity<AjaxResult> metadataSettle(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> friends.metadataReceipt(me, String.valueOf(body.get("action")), number(body, "target_id"),
                String.valueOf(body.get("request_id")), String.valueOf(body.get("payload_hash"))));
    }

    @Operation(summary = "通讯录")
    @GetMapping("/api/friend/directory")
    public ResponseEntity<AjaxResult> directory(@RequestParam("section") String section, @RequestParam("starred") String starred,
            @RequestParam("limit") int limit, @RequestParam(value = "group_id", required = false) Long groupId,
            @RequestParam(value = "cursor", required = false) String cursor)
    {
        return run(me -> friends.directory(me, section, "true".equals(starred), groupId, cursor, limit));
    }

    @Operation(summary = "通讯录详情")
    @GetMapping("/api/friend/directory/detail")
    public ResponseEntity<AjaxResult> directoryDetail(@RequestParam("peer_id") long peerId)
    {
        return run(me -> friends.directoryDetail(me, peerId));
    }

    @Operation(summary = "好友进度（聚合，FRIEND/SELF）")
    @GetMapping("/api/friend/progress")
    public ResponseEntity<AjaxResult> progress(@RequestParam("peer_id") long peerId,
            @RequestParam("expected_friend_version") long expectedFriendVersion)
    {
        return run(me -> friends.progress(me, peerId, expectedFriendVersion));
    }

    // ---------------------------------------------------------------- channel invite

    @Operation(summary = "频道邀请：选好友")
    @GetMapping("/api/friend/channel-invite/picker")
    public ResponseEntity<AjaxResult> channelPicker(@RequestParam("channel_id") long channelId, @RequestParam("section") String section,
            @RequestParam(value = "cursor", required = false) String cursor)
    {
        return run(me -> invites.channelPicker(me, channelId, section, cursor));
    }

    @Operation(summary = "频道邀请：发起/提交/撤回（原请求）")
    @PostMapping("/api/friend/channel-invite/command")
    public ResponseEntity<AjaxResult> channelCommand(@RequestBody(required = false) Map<String, Object> body)
    {
        return run(me -> invites.channelCommand(me, body));
    }

    @Operation(summary = "频道邀请：一批邀请的去向")
    @GetMapping("/api/friend/channel-invite/batch")
    public ResponseEntity<AjaxResult> channelBatch(@RequestParam("channel_id") long channelId, @RequestParam("request_id") String requestId)
    {
        return run(me -> invites.channelBatch(me, channelId, requestId));
    }

    @Operation(summary = "频道邀请：邀请人收到的入会通知")
    @GetMapping("/api/friend/channel-invite/notice")
    public ResponseEntity<AjaxResult> channelNotice(@RequestParam("invite_id") long inviteId)
    {
        return run(me -> invites.channelNotice(me, inviteId));
    }

    @Operation(summary = "频道邀请：被邀请人落地页")
    @GetMapping("/api/friend/channel-invite/landing")
    public ResponseEntity<AjaxResult> channelLanding(@RequestParam("invite_id") long inviteId)
    {
        return run(me -> invites.channelLanding(me, inviteId));
    }

    @Operation(summary = "频道邀请：我发出的邀请")
    @GetMapping("/api/friend/channel-invite/mine")
    public ResponseEntity<AjaxResult> channelMine(@RequestParam("before") String before)
    {
        return run(me -> invites.channelMine(me, before));
    }

    @Operation(summary = "频道邀请原回执")
    @GetMapping("/api/friend/channel-invite/receipt")
    public ResponseEntity<AjaxResult> channelReceipt(@RequestParam("request_id") String requestId)
    {
        return run(me -> invites.channelReceipt(me, requestId));
    }

    // ---------------------------------------------------------------- team invite

    @Operation(summary = "入队邀请：选好友")
    @GetMapping("/api/friend/team-invite/picker")
    public ResponseEntity<AjaxResult> teamPicker(@RequestParam("team_id") long teamId, @RequestParam("section") String section,
            @RequestParam(value = "cursor", required = false) String cursor)
    {
        return run(me -> invites.teamPicker(me, teamId, section, cursor));
    }

    @Operation(summary = "入队邀请：落地页")
    @GetMapping("/api/friend/team-invite/landing")
    public ResponseEntity<AjaxResult> teamLanding(@RequestParam("offer_id") String offerId)
    {
        return run(me -> invites.teamLanding(me, offerId));
    }

    @Operation(summary = "入队邀请：我发出/收到的")
    @GetMapping("/api/friend/team-invite/mine")
    public ResponseEntity<AjaxResult> teamMine(@RequestParam("before") String before)
    {
        return run(me -> invites.teamMine(me, before));
    }

    @Operation(summary = "入队邀请原请求（发出/确认/拒绝/撤回）")
    @PostMapping("/api/team/original-request")
    public ResponseEntity<AjaxResult> teamCommand(@RequestBody(required = false) byte[] raw)
    {
        return run(me -> invites.teamCommand(me, raw));
    }

    @Operation(summary = "入队邀请原回执")
    @GetMapping("/api/team/original-request")
    public ResponseEntity<AjaxResult> teamReceipt(@RequestParam("requestId") String requestId)
    {
        return run(me -> invites.teamReceipt(me, requestId));
    }

    static long number(Map<String, Object> body, String key)
    {
        if (body == null || !(body.get(key) instanceof Number n)) throw ChannelRejection.badField(key);
        return n.longValue();
    }

    ResponseEntity<AjaxResult> run(Function<Long, Map<String, Object>> body)
    {
        return envelope(() -> getAppUserId(), body);
    }

    /** 好友信封：拒绝走 body.code（HTTP 200），结果不确定走 HTTP 500。 */
    static ResponseEntity<AjaxResult> envelope(java.util.function.Supplier<Long> viewer, Function<Long, Map<String, Object>> body)
    {
        Long me;
        try { me = viewer.get(); }
        catch (Exception e) { me = null; }
        if (me == null || me <= 0) return ResponseEntity.status(401).body(new AjaxResult(401, "请先登录"));
        try
        {
            Map<String, Object> data = body.apply(me);
            Object status = data.remove(FriendService.STATUS_KEY);
            if (status instanceof Number n)
            {
                AjaxResult refused = new AjaxResult(n.intValue(), String.valueOf(data.getOrDefault("reason", "REJECTED")), data);
                return ResponseEntity.ok(refused);
            }
            return ResponseEntity.ok(AjaxResult.success(data));
        }
        catch (ChannelRejection e)
        {
            AjaxResult result = new AjaxResult(e.getStatus(), e.getErrorCode());
            result.put("errorCode", e.getErrorCode());
            result.put("detail", e.getMessage());
            return ResponseEntity.ok(result);
        }
        catch (Exception e)
        {
            log.error("好友接口结果待确认", e);
            AjaxResult result = new AjaxResult(500, "结果待确认，请稍后查询原请求");
            result.put("errorCode", "OUTCOME_UNKNOWN");
            return ResponseEntity.status(500).body(result);
        }
    }
}
