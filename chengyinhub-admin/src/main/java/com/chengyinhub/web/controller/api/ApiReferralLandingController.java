package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ReferralLandingRequest;
import com.chengyinhub.business.service.IReferralLandingService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推广凭据 / 礼品领取码落地页（匿名只读）。
 *
 * <p><b>看链接不产生任何结果</b>：不登录也能看状态，但这里没有领取、下单、发券或
 * 发免费局的入口；真正的领取/注册仍在原来的登录接口里完成。
 *
 * <p>客户端裸传 {@code ?inviter=<id>} 或改签名都会得到 {@code INVALID_TOKEN}：
 * 只有服务端签发、库里摘要对得上的凭据才算来源事实。
 */
@Tag(name = "推广/礼品落地页")
@RestController
@RequestMapping("/api/referral/landing")
public class ApiReferralLandingController extends BaseController {

    private final IReferralLandingService landingService;

    public ApiReferralLandingController(IReferralLandingService landingService) {
        this.landingService = landingService;
    }

    @Anonymous
    @Operation(summary = "解析推广凭据(只读，不发放权益)")
    @PostMapping("/referral")
    public AjaxResult referral(@RequestBody(required = false) ReferralLandingRequest request) {
        try {
            return success(landingService.describeReferral(request == null ? null : request.getToken()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Anonymous
    @Operation(summary = "解析礼品领取码(只读，不领取)")
    @PostMapping("/gift")
    public AjaxResult gift(@RequestBody(required = false) ReferralLandingRequest request) {
        try {
            return success(landingService.describeGift(request == null ? null : request.getToken()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }
}
