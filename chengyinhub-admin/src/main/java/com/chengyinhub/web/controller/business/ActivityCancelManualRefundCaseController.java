package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundAssessmentRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundHandleRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReopenRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundReworkRequest;
import com.chengyinhub.business.domain.dto.ActivityCancelManualRefundVerifyRequest;
import com.chengyinhub.business.service.IActivityCancelManualRefundCaseService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.web.controller.api.AppUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 部分核销父订单的人工退款评估；这里只判额和转交，不执行退款。 */
@RestController
@RequestMapping("/business/refund/activity-cancel-cases")
public class ActivityCancelManualRefundCaseController extends BaseController {
    @Autowired
    private IActivityCancelManualRefundCaseService manualRefundCaseService;

    @Autowired
    private AppUploadService appUploadService;

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @GetMapping("/list")
    public TableDataInfo list(@RequestParam(required = false) Long clubId,
                              @RequestParam(required = false) String status) {
        startPage();
        return getDataTable(manualRefundCaseService.list(clubId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @GetMapping("/{caseId}")
    public AjaxResult detail(@PathVariable Long caseId) {
        return AjaxResult.success(manualRefundCaseService.detail(caseId));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "活动取消人工退款评估", businessType = BusinessType.UPDATE)
    @PostMapping("/assess")
    public AjaxResult assess(@RequestBody ActivityCancelManualRefundAssessmentRequest request) {
        return AjaxResult.success(manualRefundCaseService.assess(getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "活动取消人工退款凭证登记", businessType = BusinessType.UPDATE)
    @PostMapping("/handle")
    public AjaxResult handle(@RequestBody ActivityCancelManualRefundHandleRequest request) {
        return AjaxResult.success(manualRefundCaseService.handle(getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "活动取消人工退款凭证复核", businessType = BusinessType.UPDATE)
    @PostMapping("/verify")
    public AjaxResult verify(@RequestBody ActivityCancelManualRefundVerifyRequest request) {
        return AjaxResult.success(manualRefundCaseService.verify(getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "活动取消人工退款凭证退回重做", businessType = BusinessType.UPDATE)
    @PostMapping("/rework")
    public AjaxResult rework(@RequestBody ActivityCancelManualRefundReworkRequest request) {
        return AjaxResult.success(manualRefundCaseService.rework(getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "活动取消人工退款重新评估", businessType = BusinessType.UPDATE)
    @PostMapping("/reopen-assessment")
    public AjaxResult reopenAssessment(@RequestBody ActivityCancelManualRefundReopenRequest request) {
        return AjaxResult.success(manualRefundCaseService.reopenAssessment(getUserId(), request));
    }

    @PreAuthorize("@ss.hasPermi('business:refund:review')")
    @Log(title = "活动取消人工退款凭证上传", businessType = BusinessType.INSERT)
    @PostMapping("/evidence/upload")
    public AjaxResult uploadEvidence(@RequestParam("file") MultipartFile file,
                                     @RequestParam("caseId") Long caseId,
                                     @RequestParam("expectedVersion") Long expectedVersion,
                                     @RequestParam("requestId") String requestId) {
        Long uploaderUserId = getUserId();
        String objectKey = manualRefundCaseService.prepareEvidenceUpload(
                uploaderUserId, caseId, expectedVersion, requestId);
        if (objectKey == null) {
            AppUploadService.ManualRefundEvidenceUpload uploaded =
                    appUploadService.uploadManualRefundEvidence(file);
            objectKey = manualRefundCaseService.recordEvidenceUpload(
                    uploaderUserId, caseId, expectedVersion, requestId,
                    uploaded.getObjectKey(), uploaded.getVersionId());
        }
        AjaxResult result = AjaxResult.success();
        result.put("objectKey", objectKey);
        result.put("requestId", requestId);
        return result;
    }
}
