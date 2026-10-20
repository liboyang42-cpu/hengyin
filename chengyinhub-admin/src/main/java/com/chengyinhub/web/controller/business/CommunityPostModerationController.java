package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.CommunityPostRequests;
import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/community/posts")
public class CommunityPostModerationController extends BaseController {
    private final ICommunityPostService service;

    public CommunityPostModerationController(ICommunityPostService service) { this.service = service; }

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/{id}/enforcements")
    public AjaxResult enforcements(@PathVariable Long id) {
        try { return success(service.enforcements(id)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping("/appeals")
    public AjaxResult appeals(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long cursor,
                              @RequestParam(required = false) Integer limit) {
        try { return success(service.appeals(status, cursor, limit)); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "广场帖文处置", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/enforcements")
    public AjaxResult enforce(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.Enforcement request) {
        try { service.enforce(getUserId(), id, request); return success(); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "广场帖文精选", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/featured")
    public AjaxResult feature(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.Featured request) {
        try { service.feature(getUserId(), id, request); return success(); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "广场帖文版权移除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}/copyright")
    public AjaxResult copyrightRemove(@PathVariable Long id,
                                      @RequestBody(required = false) CommunityPostRequests.State request) {
        try { service.copyrightRemove(getUserId(), id, request); return success(); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "广场帖文申诉认领", businessType = BusinessType.UPDATE)
    @PostMapping("/appeals/{id}/claim")
    public AjaxResult claimAppeal(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.AppealReview request) {
        try { return success(service.claimAppeal(getUserId(), id, request == null ? null : request.getExpectedVersion())); }
        catch (ServiceException e) { return serviceError(e); }
    }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @Log(title = "广场帖文申诉复核", businessType = BusinessType.UPDATE)
    @PostMapping("/enforcements/{id}/appeals/review")
    public AjaxResult reviewAppeal(@PathVariable Long id, @RequestBody(required = false) CommunityPostRequests.AppealReview request) {
        try { service.reviewAppeal(getUserId(), id, request); return success(); }
        catch (ServiceException e) { return serviceError(e); }
    }

    private AjaxResult serviceError(ServiceException e) {
        return new AjaxResult(e.getCode() == null ? HttpStatus.ERROR : e.getCode(), e.getMessage());
    }
}
