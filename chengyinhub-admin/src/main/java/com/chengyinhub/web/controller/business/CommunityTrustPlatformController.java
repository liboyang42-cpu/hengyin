package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CommunityReportCase;
import com.chengyinhub.business.domain.MerchantContribution;
import com.chengyinhub.business.domain.dto.CommunityReportStageRequest;
import com.chengyinhub.business.domain.dto.MerchantContributionReviewRequest;
import com.chengyinhub.business.domain.vo.CommunityReportPlatformVO;
import com.chengyinhub.business.domain.vo.MerchantContributionVO;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/business/community-trust")
public class CommunityTrustPlatformController extends BaseController {
    @Autowired private CommunityTrustService communityTrustService;

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/reports")
    public AjaxResult reports(@RequestParam(required = false) String stage,
                              @RequestParam(required = false) String targetType) {
        try {
            return success(communityTrustService.listPlatformReports(stage, targetType));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "社区举报阶段更新", businessType = BusinessType.UPDATE)
    @PostMapping("/reports/stage")
    public AjaxResult transitionReport(@RequestBody(required = false) CommunityReportStageRequest request) {
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少阶段参数");
        try {
            return success(CommunityReportPlatformVO.from(
                    communityTrustService.transitionReport(getUserId(), request)));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/contributions")
    public AjaxResult contributions(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Long merchantId) {
        try {
            List<MerchantContributionVO> result = new ArrayList<>();
            for (MerchantContribution row : communityTrustService.listPlatformContributions(status, merchantId)) {
                result.add(MerchantContributionVO.from(row, null));
            }
            return success(result);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "商家众包审核与合并", businessType = BusinessType.UPDATE)
    @PostMapping("/contributions/review")
    public AjaxResult reviewContribution(
            @RequestBody(required = false) MerchantContributionReviewRequest request) {
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少审核参数");
        try {
            return success(MerchantContributionVO.from(
                    communityTrustService.reviewContribution(getUserId(), request), null));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
