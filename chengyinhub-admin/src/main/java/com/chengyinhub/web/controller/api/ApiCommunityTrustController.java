package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.MerchantSafetyRating;
import com.chengyinhub.business.domain.dto.CommunityReportAppealRequest;
import com.chengyinhub.business.domain.dto.CommunityReportSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSubmitRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionSupplementRequest;
import com.chengyinhub.business.domain.dto.MerchantSafetyRatingRequest;
import com.chengyinhub.business.domain.vo.CommunityReportVO;
import com.chengyinhub.business.domain.vo.CommunityReportAppealReceiptVO;
import com.chengyinhub.business.domain.vo.MerchantContributionVO;
import com.chengyinhub.business.domain.vo.MerchantSafetyRatingReceiptVO;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "社区信任与安全")
@RequestMapping("/api/community-trust")
public class ApiCommunityTrustController extends BaseController {
    @Autowired private CommunityTrustService communityTrustService;

    @PostMapping("/reports/submit")
    public AjaxResult submitReport(@RequestBody(required = false) CommunityReportSubmitRequest request) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少举报参数");
        try {
            CommunityReportCase row = communityTrustService.submitReport(principal, request);
            return success(CommunityReportVO.from(row));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @GetMapping("/reports/taxonomy")
    public AjaxResult reportTaxonomy() {
        return success(communityTrustService.getReportTaxonomy());
    }

    @GetMapping("/reports/mine")
    public AjaxResult myReports(@RequestParam(required = false) String stage) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        try {
            return success(communityTrustService.listMyReports(principal, stage));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @GetMapping("/reports/{reportId}")
    public AjaxResult reportDetail(@PathVariable Long reportId) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        try {
            return success(communityTrustService.getMyReport(principal, reportId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @GetMapping("/reports/{reportId}/subject")
    public AjaxResult subjectReport(@PathVariable Long reportId) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        try {
            return success(communityTrustService.getSubjectReport(principal, reportId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/reports/appeal")
    public AjaxResult appealReport(@RequestBody(required = false) CommunityReportAppealRequest request) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少申诉参数");
        try {
            CommunityReportCase row = communityTrustService.appealReport(principal, request);
            return success(CommunityReportAppealReceiptVO.from(row));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/contributions/submit")
    public AjaxResult submitContribution(
            @RequestBody(required = false) MerchantContributionSubmitRequest request) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少贡献参数");
        try {
            MerchantContribution row = communityTrustService.submitContribution(principal, request);
            return success(MerchantContributionVO.from(row, null));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @GetMapping("/contributions/taxonomy")
    public AjaxResult contributionTaxonomy() {
        return success(communityTrustService.getContributionTaxonomy());
    }

    @GetMapping("/contributions/mine")
    public AjaxResult myContributions() {
        Long principal = principal();
        if (principal == null) return unauthorized();
        try {
            return success(communityTrustService.listMyContributions(principal));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/contributions/supplement")
    public AjaxResult supplementContribution(
            @RequestBody(required = false) MerchantContributionSupplementRequest request) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少补证参数");
        try {
            MerchantContribution row = communityTrustService.supplementContribution(principal, request);
            return success(MerchantContributionVO.from(row, null));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @GetMapping("/contributions/{contributionId}")
    public AjaxResult contributionDetail(@PathVariable Long contributionId) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        try {
            return success(communityTrustService.getMyContribution(principal, contributionId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PostMapping("/safety/ratings")
    public AjaxResult submitSafetyRating(
            @RequestBody(required = false) MerchantSafetyRatingRequest request) {
        Long principal = principal();
        if (principal == null) return unauthorized();
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少安全评分参数");
        try {
            MerchantSafetyRating row = communityTrustService.submitSafetyRating(principal, request);
            return success(MerchantSafetyRatingReceiptVO.from(row));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @GetMapping("/safety/taxonomy")
    public AjaxResult safetyTaxonomy() {
        return success(communityTrustService.getSafetyTaxonomy());
    }

    @GetMapping("/safety/merchants/{merchantId}")
    public AjaxResult safetySummary(@PathVariable Long merchantId) {
        try {
            return success(communityTrustService.getSafetySummary(merchantId));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private Long principal() {
        try { return getAppUserId(); } catch (Exception e) { return null; }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }

    private AjaxResult unauthorized() {
        return new AjaxResult(HttpStatus.UNAUTHORIZED, "请先登录");
    }
}
