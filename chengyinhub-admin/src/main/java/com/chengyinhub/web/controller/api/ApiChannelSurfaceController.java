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

import com.chengyinhub.business.service.channel.platform.ChannelSurfaceService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 频道周边只读面（docs/channels/contract.md §2.16、§6.11）：附件描述、欢迎屏、入会引导。 */
@RestController
@Tag(name = "频道周边")
public class ApiChannelSurfaceController extends BaseController
{
    @Autowired
    private ChannelSurfaceService surface;

    @Operation(summary = "单图消息附件描述（300 秒地址）")
    @GetMapping("/api/im/message/{id:[0-9]+}/attachment")
    public ResponseEntity<AjaxResult> attachment(@PathVariable("id") String id)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> surface.attachment(me, ChannelHttpResponse.id(id)));
    }

    @Operation(summary = "空间欢迎屏")
    @PostMapping("/api/welcome-screen/get")
    public ResponseEntity<AjaxResult> welcome(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> surface.welcome(me, body));
    }

    @Operation(summary = "入会引导（App JSON 原请求）")
    @PostMapping(value = "/api/onboarding/get", consumes = "application/json")
    public ResponseEntity<AjaxResult> onboardingJson(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> surface.onboarding(me, body));
    }

    @Operation(summary = "入会引导（查询参数版）")
    @GetMapping("/api/onboarding/get")
    public ResponseEntity<AjaxResult> onboarding(@RequestParam("guild_kind") int guildKind, @RequestParam("guild_id") String guildId)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> surface.onboarding(me, guildKind, ChannelHttpResponse.id(guildId)));
    }

    @Operation(summary = "提交入会引导答案（原回执族 ADMIT / ONBOARDING_SUBMIT）")
    @PostMapping("/api/onboarding/submit")
    public ResponseEntity<AjaxResult> submit(@RequestBody(required = false) Map<String, Object> body)
    {
        return ChannelHttpResponse.run(this::getAppUserId, me -> surface.submitOnboarding(me, body));
    }
}
