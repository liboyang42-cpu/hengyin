package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.ClubGovernanceCase;
import com.chengyinhub.business.domain.dto.ClubGovernancePlatformBanRequest;
import com.chengyinhub.business.domain.dto.ClubGovernancePlatformReviewRequest;
import com.chengyinhub.business.domain.vo.ClubGovernanceBanVO;
import com.chengyinhub.business.domain.vo.ClubGovernancePlatformCaseVO;
import com.chengyinhub.business.service.ClubGovernanceService;
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

/** 平台治理边界；小程序俱乐部 owner/co-owner 无法调用这些裁决接口。 */
@RestController
@RequestMapping("/business/club-governance")
public class ClubGovernancePlatformController extends BaseController {
    @Autowired private ClubGovernanceService clubGovernanceService;

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/cases")
    public AjaxResult cases(@RequestParam(required = false) String caseType,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) Long clubId) {
        try {
            List<ClubGovernancePlatformCaseVO> result = new ArrayList<>();
            for (ClubGovernanceCase row : clubGovernanceService.listPlatformCases(
                    caseType, status, clubId)) {
                result.add(ClubGovernancePlatformCaseVO.from(row));
            }
            return success(result);
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "俱乐部治理工单裁决", businessType = BusinessType.UPDATE)
    @PostMapping("/cases/review")
    public AjaxResult review(@RequestBody(required = false) ClubGovernancePlatformReviewRequest request) {
        if (request == null || request.getApproved() == null) {
            return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少平台裁决参数");
        }
        try {
            clubGovernanceService.reviewPlatformCase(getUserId(), request.getCaseId(),
                    request.getVersion(), request.getApproved(), request.getReason(), request.getRequestId());
            return success("治理工单已裁决");
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "平台永久封禁俱乐部成员", businessType = BusinessType.UPDATE)
    @PostMapping("/permanent-ban")
    public AjaxResult permanentBan(@RequestBody(required = false) ClubGovernancePlatformBanRequest request) {
        if (request == null) return new AjaxResult(HttpStatus.BAD_REQUEST, "缺少平台封禁参数");
        try {
            return success(ClubGovernanceBanVO.from(clubGovernanceService.platformPermanentBan(
                    getUserId(), request.getClubId(), request.getTargetMemberId(),
                    request.getReason(), request.getRequestId())));
        } catch (ServiceException e) {
            return serviceError(e);
        }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
    }
}
