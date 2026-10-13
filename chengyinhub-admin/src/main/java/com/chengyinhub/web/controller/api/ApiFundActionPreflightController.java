package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.domain.dto.FundPreflightDecisionRequest;
import com.chengyinhub.business.exception.FundActionPreflightException;
import com.chengyinhub.business.service.IFundActionPreflightService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/fund/preflight")
public class ApiFundActionPreflightController extends BaseController {
    private final IFundActionPreflightService service;

    public ApiFundActionPreflightController(IFundActionPreflightService service) {
        this.service = service;
    }

    @PostMapping("/bank-withdrawal")
    public AjaxResult prepareBankWithdrawal(@Validated @RequestBody BankWithdrawalPreflightRequest body,
                                            HttpServletRequest request) {
        try {
            return success(service.prepareBankWithdrawal(currentMemberId(), body, null,
                    deviceLabel(request.getHeader("User-Agent"))));
        } catch (FundActionPreflightException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", e.getBusinessCode());
        }
    }

    @PostMapping("/{challengeId}/confirm")
    public AjaxResult confirm(@PathVariable Long challengeId,
                              @Validated @RequestBody FundPreflightDecisionRequest body) {
        try {
            return success(service.confirm(currentMemberId(), challengeId, body.getChallengeToken()));
        } catch (FundActionPreflightException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", e.getBusinessCode());
        }
    }

    @PostMapping("/{challengeId}/reject")
    public AjaxResult reject(@PathVariable Long challengeId,
                             @Validated @RequestBody FundPreflightDecisionRequest body) {
        try {
            return success(service.reject(currentMemberId(), challengeId, body.getChallengeToken()));
        } catch (FundActionPreflightException e) {
            return AjaxResult.error(409, e.getMessage()).put("businessCode", e.getBusinessCode());
        }
    }

    private String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.trim().isEmpty()) return null;
        if (userAgent.contains("MicroMessenger")) return "微信小程序";
        return userAgent.length() <= 64 ? userAgent : userAgent.substring(0, 64);
    }

    private Long currentMemberId() {
        try {
            LoginAppUser login = getLoginAppUser();
            if (login == null || login.getAppUser() == null || login.getAppUser().getUserId() == null) {
                throw new FundActionPreflightException("LOGIN_REQUIRED", "请先登录");
            }
            return login.getAppUser().getUserId();
        } catch (ServiceException e) {
            throw new FundActionPreflightException("LOGIN_REQUIRED", "请先登录");
        }
    }
}
