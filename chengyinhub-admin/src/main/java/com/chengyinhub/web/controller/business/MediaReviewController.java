package com.chengyinhub.web.controller.business;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.AuditReviewDTO;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/business/mediaReview")
public class MediaReviewController extends BaseController {
    private final AppMediaAssetService media;
    private final IAuditTaskService audits;
    public MediaReviewController(AppMediaAssetService media,IAuditTaskService audits) {this.media=media;this.audits=audits;}
    @GetMapping("/{assetId}/preview")
    @PreAuthorize("@ss.hasPermi('business:mediaReview:query')")
    public AjaxResult preview(@PathVariable Long assetId) {
        return success(java.util.Collections.singletonMap("mediaPreviewUrl",media.reviewerPreview(assetId)));
    }
    @PostMapping("/approve")
    @PreAuthorize("@ss.hasPermi('business:mediaReview:approve')")
    public AjaxResult approve(@RequestBody AuditReviewDTO request) {return decide(request,AuditTask.STATUS_APPROVED);}
    @PostMapping("/reject")
    @PreAuthorize("@ss.hasPermi('business:mediaReview:reject')")
    public AjaxResult reject(@RequestBody AuditReviewDTO request) {return decide(request,AuditTask.STATUS_REJECTED);}
    private AjaxResult decide(AuditReviewDTO request,Integer decision) {
        AuditTask task=request==null?null:audits.selectAuditTaskById(request.getTaskId());
        if(task==null || !AuditTask.BIZ_MEDIA_ASSET.equals(task.getBizType()))throw new ServiceException("媒体审核单不存在");
        request.setStatus(decision);
        java.util.List<com.chengyinhub.common.core.domain.entity.SysRole> roles=getLoginUser().getUser().getRoles();
        request.setActorRoleCode(com.chengyinhub.common.core.domain.entity.SysUser.isAdmin(getUserId()) ? "PLATFORM_ADMIN"
                : roles==null || roles.isEmpty() ? "SYS_USER" : roles.get(0).getRoleKey());
        return toAjax(audits.review(task.getId(),getUserId(),request));
    }
}
