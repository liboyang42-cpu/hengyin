package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.AttributionClaimRequest;
import com.chengyinhub.business.domain.dto.ReferralTokenIssueRequest;
import com.chengyinhub.business.exception.MarketingAttributionException;
import com.chengyinhub.business.service.IMarketingAttributionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销推广归因(CO-09 归因半边)。
 *
 * <p>只读订单做归属校验,只写归因事实表;不创建订单、不改价、不改支付状态、不发放权益或佣金。
 * 客户端拿到分享 token 后:分享链接携带 token → 买家下单成功 → 调 {@code /attribution/claim} 落快照。
 *
 * <p><b>身份入口</b>:走 {@link #getAppUserId()}(SecurityUtils 的真实认证入口)。
 * 未登录时它抛 {@code ServiceException(401)},由全局处理器回 401;
 * <b>不</b>把鉴权异常 catch 成 {@code 0L} —— 那会把"未登录"伪装成一个合法的假身份。
 */
@Tag(name = "营销推广归因")
@RestController
@RequestMapping("/api/marketing")
public class ApiMarketingAttributionController extends BaseController {

    private final IMarketingAttributionService attributionService;

    public ApiMarketingAttributionController(IMarketingAttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @Operation(summary = "签发我的推广来源凭据(分享链接用,只归因不发钱)")
    @PostMapping("/referral/token")
    public AjaxResult issueToken(@RequestBody(required = false) ReferralTokenIssueRequest request) {
        return success(attributionService.issueToken(requireAppMemberId(), request));
    }

    @Operation(summary = "下单来源归因(幂等;同单换来源一律拒绝)")
    @PostMapping("/attribution/claim")
    public AjaxResult claim(@RequestBody(required = false) AttributionClaimRequest request) {
        return success(attributionService.claim(requireAppMemberId(), request));
    }

    /** 理由码随响应返回,客户端按码分支,不解析人工文案。 */
    @ExceptionHandler(MarketingAttributionException.class)
    public AjaxResult handleAttributionRejection(MarketingAttributionException rejection) {
        AjaxResult result = AjaxResult.error(rejection.getHttpStatus(), rejection.getMessage());
        result.put("reasonCode", rejection.getReasonCode());
        return result;
    }

    /**
     * 取真实登录身份。{@link #getAppUserId()} 未登录时抛 ServiceException(401),原样向上传播;
     * 这里只兜"认证通过但 principal 无 userId"这一种真实异常,仍按 401 处理,绝不返回 0L 伪身份。
     */
    private long requireAppMemberId() {
        Long memberId = getAppUserId();
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", HttpStatus.UNAUTHORIZED);
        }
        return memberId;
    }
}
