package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.support.AuditTrailProjectionService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * DOC2 O5c-(a):统一留痕只读查询。一个内容(bizType + bizId)的全部审核/处置/举报/官方活动留痕一处读。
 * 开关 chengyin.audit.o5c.unified-trail.enabled 默认关,关时返回 404 + AUDIT_TRAIL_DISABLED。
 */
@RestController
@RequestMapping("/business/audit-trail")
public class AuditTrailController extends BaseController {

    public static final String ERR_DISABLED = "AUDIT_TRAIL_DISABLED";
    public static final String ERR_INVALID = "AUDIT_TRAIL_INVALID";

    @Autowired
    private AuditTrailProjectionService auditTrailProjectionService;

    @PreAuthorize("@ss.hasPermi('business:audit:list')")
    @GetMapping
    public AjaxResult trail(String bizType, Long bizId) {
        if (!auditTrailProjectionService.isEnabled()) {
            AjaxResult disabled = new AjaxResult(HttpStatus.NOT_FOUND, "统一留痕查询未开启");
            disabled.put("errorCode", ERR_DISABLED);
            return disabled;
        }
        try {
            return success(auditTrailProjectionService.trail(bizType, bizId));
        } catch (IllegalArgumentException e) {
            AjaxResult badRequest = new AjaxResult(HttpStatus.BAD_REQUEST, e.getMessage());
            badRequest.put("errorCode", ERR_INVALID);
            return badRequest;
        }
    }
}
