package com.chengyinhub.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.clubinvite.ClubInviteService;
import com.chengyinhub.business.clubinvite.ClubInviteService.Ticket;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * W7 SOC-15: club invite links (expiry, use limit, revoke, target channel). Additive routes; the share path stays
 * the mini-program club page ({@code /pages/club/detail/index?id=<club>&invite=<code>}). Same HTTP shell as the
 * channel routes: a refusal's HTTP status equals {@code body.code} and carries a stable {@code errorCode}.
 * Accepting runs the existing {@code /api/club/join} path, so the club's join policy still applies.
 */
@RestController
@Tag(name = "俱乐部邀请")
public class ApiClubInviteController extends BaseController
{
    @Autowired
    private ClubInviteService invites;

    @Autowired
    private ApiClubController clubs;

    @Operation(summary = "生成邀请链接(有效期、次数、可选目标频道)")
    @PostMapping("/api/club/invites")
    public ResponseEntity<AjaxResult> create(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> invites.create(me, body == null ? Map.of() : body));
    }

    @Operation(summary = "俱乐部的邀请链接(管理员看全部，成员看自己的)")
    @GetMapping("/api/club/invites")
    public ResponseEntity<AjaxResult> list(@RequestParam("clubId") String clubId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> invites.list(me, ChannelHttpResponse.id(clubId)));
    }

    @Operation(summary = "邀请落地页(俱乐部卡片、目标频道、是否有效)")
    @GetMapping("/api/club/invites/{code}")
    public ResponseEntity<AjaxResult> landing(@PathVariable("code") String code)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> invites.landing(me, code));
    }

    @Operation(summary = "接受邀请(走原有加入俱乐部流程)")
    @PostMapping("/api/club/invites/{code}/accept")
    public ResponseEntity<AjaxResult> accept(@PathVariable("code") String code,
            @RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> {
            Ticket ticket = invites.take(me, code);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("club_id", String.valueOf(ticket.clubId()));
            out.put("channel_id", ticket.channelId() == null ? null : String.valueOf(ticket.channelId()));
            if (ticket.alreadyMember())
            {
                out.put("state", "MEMBER");
                return out;
            }
            AjaxResult joined;
            try
            {
                Club request = new Club();
                request.setId(ticket.clubId());
                Object message = body == null ? null : body.get("message");
                request.setJoinMessage(message == null ? null : message.toString());
                joined = clubs.join(request);
            }
            catch (RuntimeException e)
            {
                invites.giveBack(ticket);
                throw e;
            }
            String state = joinState(joined);
            if (state == null)
            {
                invites.giveBack(ticket);
                Object msg = joined == null ? null : joined.get(AjaxResult.MSG_TAG);
                throw new ChannelRejection(409, "INVITE_JOIN_REFUSED",
                        msg == null || msg.toString().isBlank() ? "暂时加入不了，请稍后再试" : msg.toString());
            }
            out.put("state", state);
            out.put("message", joined.get(AjaxResult.MSG_TAG));
            return out;
        });
    }

    @Operation(summary = "撤销邀请")
    @PostMapping("/api/club/invites/{code}/revoke")
    public ResponseEntity<AjaxResult> revoke(@PathVariable("code") String code)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> invites.revoke(me, code));
    }

    /** The join path's state ("joined" or "pending") on success; null when it refused. */
    static String joinState(AjaxResult joined)
    {
        Object code = joined == null ? null : joined.get(AjaxResult.CODE_TAG);
        if (!(code instanceof Number n) || n.intValue() != 200) return null;
        Object data = joined.get(AjaxResult.DATA_TAG);
        if (data instanceof Map<?, ?> map && map.get("state") != null) return map.get("state").toString();
        return "joined";
    }
}
