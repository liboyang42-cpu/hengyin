package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ClubMembershipActionRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipClubRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipPlanRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipRenewRequest;
import com.chengyinhub.business.domain.dto.ClubMembershipSettingRequest;
import com.chengyinhub.business.service.IClubMembershipService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "俱乐部付费会员与会费")
@RequestMapping("/api/club/membership")
public class ApiClubMembershipController extends BaseController {
    @Autowired private IClubMembershipService clubMembershipService;

    @PostMapping("/overview")
    public AjaxResult overview(@RequestBody(required = false) ClubMembershipClubRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.overview(principal, request.getClubId())); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/setting")
    public AjaxResult setting(@RequestBody(required = false) ClubMembershipClubRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.setting(principal, request.getClubId())); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/setting/update")
    public AjaxResult updateSetting(@RequestBody(required = false) ClubMembershipSettingRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.updateSetting(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/plans")
    public AjaxResult plans(@RequestBody(required = false) ClubMembershipClubRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.plans(principal, request.getClubId(), false)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/plans/manage")
    public AjaxResult managePlans(@RequestBody(required = false) ClubMembershipClubRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.plans(principal, request.getClubId(), true)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/plan/save")
    public AjaxResult savePlan(@RequestBody(required = false) ClubMembershipPlanRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.savePlan(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/renew")
    public AjaxResult renew(@RequestBody(required = false) ClubMembershipRenewRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.renew(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/invoice/resume")
    public AjaxResult resumeInvoice(@RequestBody(required = false) ClubMembershipActionRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.resumeInvoice(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/invoice/close")
    public AjaxResult closeInvoice(@RequestBody(required = false) ClubMembershipActionRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.closeInvoice(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/invoice")
    public AjaxResult invoice(@RequestBody(required = false) ClubMembershipActionRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.invoice(principal, request.getClubId(), request.getId())); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/finance/invoices")
    public AjaxResult financeInvoices(@RequestBody(required = false) ClubMembershipClubRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.financeInvoices(principal, request.getClubId())); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/term/cancel")
    public AjaxResult cancelTerm(@RequestBody(required = false) ClubMembershipActionRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try {
            clubMembershipService.cancelTerm(principal, request);
            return success("付费权益已取消；本操作不代表退款");
        } catch (ServiceException e) { return serviceError(e); }
    }

    @PostMapping("/refund/review")
    public AjaxResult requestRefundReview(@RequestBody(required = false) ClubMembershipActionRequest request) {
        Long principal = principal();
        if (principal == null) return error("请先登录");
        if (request == null) return bad();
        try { return success(clubMembershipService.requestRefundReview(principal, request)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception ignored) { return null; }
    }
    private AjaxResult bad() { return new AjaxResult(HttpStatus.BAD_REQUEST, "请求参数不能为空"); }
    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
    }
}
