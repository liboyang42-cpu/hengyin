package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import com.chengyinhub.business.externalgame.ExternalGameMonetizationService;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.utils.ip.IpUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * External game monetization (pricing, TEST-gated purchase/entitlement), node binding,
 * and signed result callback endpoints.
 */
@RestController
@Tag(name = "外部游戏变现与结果回执")
@RequestMapping("/api/external-game")
public class ApiExternalGameMonetizationController extends BaseController {
    private final ExternalGameMonetizationService monetization;

    public ApiExternalGameMonetizationController(ExternalGameMonetizationService monetization) {
        this.monetization = monetization;
    }

    @Operation(summary = "读取外部游戏变现与购买资格配置")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/catalog/{gameId}/monetization")
    public AjaxResult getMonetization(@PathVariable String gameId) {
        return success(monetization.publicMonetizationView(optionalMember(), gameId));
    }

    @Operation(summary = "创作者保存外部游戏上架定价、分成与流量激励配置（送审）")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PutMapping("/catalog/{gameId}/monetization")
    public AjaxResult saveCreatorMonetization(@PathVariable String gameId, @RequestBody(required = false) Map<String, Object> body) {
        return success(monetization.saveCreatorConfig(requireMember(), gameId, body));
    }

    @Operation(summary = "创作者外部游戏变现与流量激励看板")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @GetMapping("/creator/summary")
    public AjaxResult creatorSummary() {
        return success(monetization.creatorSummary(requireMember()));
    }

    @Operation(summary = "购买付费外部游戏（TEST-only，受 MiscMoneySwitch 门控）")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/orders")
    public AjaxResult purchase(@RequestBody(required = false) Map<String, Object> body) {
        if (!monetization.paymentsOpen()) {
            return MiscMoneySwitch.closed();
        }
        String gameId = body == null || body.get("gameId") == null ? null : String.valueOf(body.get("gameId"));
        return success(monetization.purchaseGame(requireMember(), gameId, body));
    }

    @Operation(summary = "TEST 环境确认待支付订单（受 MiscMoneySwitch 门控）")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/orders/{orderNo}/confirm-test")
    public AjaxResult confirmTest(@PathVariable String orderNo) {
        if (!monetization.paymentsOpen()) {
            return MiscMoneySwitch.closed();
        }
        return success(monetization.confirmOrderForTest(requireMember(), orderNo));
    }

    @Operation(summary = "查询外部游戏订单")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/orders/{orderNo}")
    public AjaxResult getOrder(@PathVariable String orderNo) {
        return success(monetization.getOrder(requireMember(), orderNo));
    }

    @Operation(summary = "我的已购外部游戏权益")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/entitlements/mine")
    public AjaxResult myEntitlements() {
        return success(monetization.myEntitlements(requireMember()));
    }

    @Operation(summary = "创作者绑定外部游戏到主题节点")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PutMapping("/nodes/{nodeId}/binding")
    public AjaxResult bindNode(@PathVariable Long nodeId, @RequestBody(required = false) Map<String, Object> body) {
        return success(monetization.bindNode(requireMember(), nodeId, body));
    }

    @Operation(summary = "创作者解除主题节点与外部游戏绑定")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @DeleteMapping("/nodes/{nodeId}/binding")
    public AjaxResult unbindNode(@PathVariable Long nodeId) {
        return success(monetization.unbindNode(requireMember(), nodeId));
    }

    @Operation(summary = "读取节点绑定的外部游戏信息")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @GetMapping("/nodes/{nodeId}/binding")
    public AjaxResult getNodeBinding(@PathVariable Long nodeId) {
        requireMember();
        return success(monetization.nodeBindingView(nodeId));
    }

    @Operation(summary = "签发外部游戏游玩会话票据与随机串")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/sessions/start")
    public AjaxResult startSession(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String ip = req == null ? null : IpUtils.getIpAddr(req);
        return success(monetization.startSession(requireMember(), body, ip));
    }

    @Operation(summary = "提交签名游玩结果回执（XP/勋章/节点推进/流量记账）")
    @RateLimiter(time = 60, count = 60, limitType = LimitType.MEMBER)
    @PostMapping("/sessions/{sessionId}/result")
    public AjaxResult submitResult(@PathVariable String sessionId, @RequestBody(required = false) Map<String, Object> body) {
        return success(monetization.submitResult(requireMember(), sessionId, body));
    }

    /** W7 GAME-9: the creator's own result key for their game server. Shown once; calling again rotates it. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.externalgame.ExternalGameResultKeyService resultKeys;

    @Operation(summary = "创作者领取或更换自己游戏的成绩密钥（只显示一次）")
    @RateLimiter(time = 60, count = 5, limitType = LimitType.MEMBER)
    @PostMapping("/creator/games/{gameId}/result-key")
    public AjaxResult issueResultKey(@PathVariable String gameId) {
        long member = requireMember();
        if (resultKeys == null) throw AppGapException.conflict("RESULT_KEY_NOT_CONFIGURED", "平台还没有开通成绩密钥，请稍后再试");
        return success(resultKeys.issue(member, gameId));
    }

    private Long optionalMember() {
        try {
            return getAppUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private long requireMember() {
        Long m = optionalMember();
        if (m == null || m <= 0) {
            throw AppGapException.unauthorized();
        }
        return m;
    }

    @ExceptionHandler(AppGapException.class)
    public ResponseEntity<AjaxResult> failed(AppGapException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
