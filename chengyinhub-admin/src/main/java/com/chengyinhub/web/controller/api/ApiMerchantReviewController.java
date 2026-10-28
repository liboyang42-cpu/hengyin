package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.MerchantReviewCreateRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyDeleteRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReplyRequest;
import com.chengyinhub.business.domain.dto.MerchantReviewReportRequest;
import com.chengyinhub.business.service.IMerchantReviewService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "已核销商家评价")
@RequestMapping("/api/merchant/reviews")
public class ApiMerchantReviewController extends BaseController {
    @Autowired private IMerchantReviewService merchantReviewService;

    @Operation(summary = "公开评价列表（仅 VISIBLE，可匿名浏览）")
    @Anonymous
    @PostMapping("/public")
    public AjaxResult publicList(@RequestParam("merchantRowId") Long merchantRowId,
                                 @RequestParam(value = "pageNum", required = false) Integer pageNum,
                                 @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                 @RequestParam(value = "registrationId", required = false) Long registrationId) {
        try {
            return success(merchantReviewService.publicList(optionalPrincipal(), merchantRowId, pageNum, pageSize, registrationId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "商家评价管理列表（当前商家主体；filter=all/pending/low/photo/platform 服务端筛选 + 全量计数）")
    @PostMapping("/manage")
    public AjaxResult manageList(@RequestParam(value = "filter", required = false) String filter,
                                 @RequestParam(value = "pageNum", required = false) Integer pageNum,
                                 @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.manageList(principal, filter, pageNum, pageSize));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "凭 ACTIVE 核销提交一次不可编辑评价（先进入平台复核）")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody(required = false) MerchantReviewCreateRequest request) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.create(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "商家一次公开回复（不修改原评）")
    @PostMapping("/reply")
    public AjaxResult reply(@RequestBody(required = false) MerchantReviewReplyRequest request) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.reply(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "商家修改已有公开回复（version CAS，不改原评）")
    @PostMapping("/reply/update")
    public AjaxResult updateReply(@RequestBody(required = false) MerchantReviewReplyRequest request) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.updateReply(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "商家删除公开回复（删后该评价回到待回复，可重新回复）")
    @PostMapping("/reply/delete")
    public AjaxResult deleteReply(@RequestBody(required = false) MerchantReviewReplyDeleteRequest request) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.deleteReply(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "普通会员举报公开评价（统一进入平台审核）")
    @PostMapping("/report")
    public AjaxResult report(@RequestBody(required = false) MerchantReviewReportRequest request) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.report(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @Operation(summary = "商家举报本店评价（当前主体 + MARKETING_WRITE）")
    @PostMapping("/manage/report")
    public AjaxResult reportAsMerchant(@RequestBody(required = false) MerchantReviewReportRequest request) {
        Long principal = optionalPrincipal();
        if (principal == null) return error("请先登录");
        try {
            return success(merchantReviewService.reportAsMerchant(principal, request));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long optionalPrincipal() {
        try {
            Long id = getAppUserId();
            return id != null && id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
