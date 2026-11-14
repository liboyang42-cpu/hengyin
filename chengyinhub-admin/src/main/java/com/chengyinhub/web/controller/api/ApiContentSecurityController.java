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

    /** 同一个消息推送 URL 也会收到虚拟支付事件；只在识别为 xpay_* 事件时转交通行证，其余照旧。 */
    @Autowired(required = false)
    private com.chengyinhub.business.platformpass.PlatformPassService platformPassService;

    @Autowired(required = false)
    private com.chengyinhub.business.platformpass.SelfPlayXpayService selfPlayXpayService;

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
        String xpayEvent = xpayEvent(body);
        if (xpayEvent != null)
        {
            return onXpayEvent(signature, timestamp, nonce, body);
        }
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

    /** 只认 JSON 明文里 Event 以 xpay_ 开头的推送；解析失败返回 null 交回原内容安全分支。 */
    private static String xpayEvent(String body)
    {
        if (body == null || body.isEmpty() || body.indexOf("xpay_") < 0)
        {
            return null;
        }
        try
        {
            com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(body);
            String event = obj == null ? null : obj.getString("Event");
            return event != null && event.startsWith("xpay_") ? event : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * 虚拟支付推送：先用同一个消息推送 token 验签；推送内容只是「去回查」的信号，
     * 是否开通/退款由通行证服务向微信回查后决定。按官方约定成功返回 {"ErrCode":0,"ErrMsg":"success"}。
     */
    private ResponseEntity<String> onXpayEvent(String signature, String timestamp, String nonce, String body)
    {
        CallbackResult verification = wxContentSecurityService.verifyMediaCheckCallback(signature, timestamp, nonce);
        if (verification != CallbackResult.SUCCESS)
        {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
        }
        boolean handled = false;
        try
        {
            com.alibaba.fastjson2.JSONObject event = com.alibaba.fastjson2.JSON.parseObject(body);
            // 通行证单号(PPO)归通行证;其余按自玩票虚拟支付冻结行认领。互不重叠。
            handled = (platformPassService != null && platformPassService.handleXpayPush(event))
                    || (selfPlayXpayService != null && selfPlayXpayService.handleXpayPush(event));
        }
        catch (Exception e)
        {
            handled = false;
        }
        return handled
                ? ResponseEntity.ok("{\"ErrCode\":0,\"ErrMsg\":\"success\"}")
                : ResponseEntity.ok("{\"ErrCode\":1,\"ErrMsg\":\"retry\"}");
    }
}
