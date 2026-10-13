package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxContentSecurityService.CallbackResult;
import com.chengyinhub.common.annotation.Anonymous;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 内容安全·图片异步检测回调（微信「消息推送」）
 *
 * 用途：接收微信对 media_check_async 送检图片的异步检测结果（事件 wxa_media_check），
 * 命中违规则自动下架对应内容。需在微信公众平台-开发管理-消息推送 配置本接口 URL
 * （建议数据格式选 JSON），并把 Token 配到 sys_config 的 wx.msgpush.token。
 *
 * 边界说明：
 *  - GET：仅接收 HTTP 参数并把 service 验签结果映射为 HTTP；成功时返回 echostr。
 *  - POST：仅接收 HTTP 参数/body 并映射结果；验签、协议校验、防重放和业务处理均在 service。
 *  - 均为 @Anonymous（微信服务器调用，不带业务 token）。
 */
@RestController
@Tag(name = "内容安全回调")
@RequestMapping("/api/content-sec")
public class ApiContentSecurityController
{
    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    /**
     * 微信「消息推送」URL 接入验证：service 校验通过后原样返回 echostr。
     */
    @Operation(summary = "图片检测回调-接入验证")
    @GetMapping("/callback")
    @Anonymous
    public ResponseEntity<String> verify(@RequestParam(required = false) String signature,
                                         @RequestParam(required = false) String timestamp,
                                         @RequestParam(required = false) String nonce,
                                         @RequestParam(required = false) String echostr)
    {
        CallbackResult result = wxContentSecurityService.verifyMediaCheckCallback(signature, timestamp, nonce);
        if (result == CallbackResult.SUCCESS)
        {
            return ResponseEntity.ok(echostr);
        }
        if (result == CallbackResult.TOKEN_UNAVAILABLE)
        {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("unavailable");
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
    }

    /**
     * 接收图片异步检测结果推送。
     * 仅支持微信消息推送的 JSON 明文模式。媒体检测结果含：
     * trace_id、version、result.suggest(risky/pass)、result.label、errcode 等。
     * 安全模式的 msg_signature / AES 密文不在本接口能力范围内，必须在微信平台选择明文 JSON。
     */
    @Operation(summary = "图片检测回调-结果推送")
    @PostMapping("/callback")
    @Anonymous
    public ResponseEntity<String> onResult(@RequestParam(required = false) String signature,
                                           @RequestParam(required = false) String timestamp,
                                           @RequestParam(required = false) String nonce,
                                           @RequestBody(required = false) String body)
    {
        CallbackResult result = wxContentSecurityService.processMediaCheckCallback(
                signature, timestamp, nonce, body);
        switch (result)
        {
            case SUCCESS:
                return ResponseEntity.ok("success");
            case TOKEN_UNAVAILABLE:
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("unavailable");
            case FORBIDDEN:
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
            case INVALID_TIMESTAMP:
                return ResponseEntity.badRequest().body("invalid timestamp");
            case INVALID_PAYLOAD:
                return ResponseEntity.badRequest().body("invalid payload");
            case REPLAYED:
                return ResponseEntity.status(HttpStatus.CONFLICT).body("rejected");
            case PROCESSING_FAILED:
            default:
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("processing failed");
        }
    }
}
