package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Base for the app-gaps controllers: the logged-in member and one error envelope. A failure keeps its HTTP
 * status and the same value in the envelope code, plus the machine errorCode the app contracts branch on.
 */
public abstract class AppGapController extends BaseController {

    protected Long member() {
        try {
            Long id = getAppUserId();
            if (id == null || id <= 0) throw AppGapException.unauthorized();
            return id;
        } catch (AppGapException e) {
            throw e;
        } catch (Exception e) {
            throw AppGapException.unauthorized();
        }
    }

    /** The logged-in member when there is one; null for an anonymous caller. */
    protected Long optionalMember() {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    protected static ResponseEntity<AjaxResult> status(int status, String errorCode, Object data) {
        AjaxResult body = new AjaxResult(status, status == 200 ? "操作成功" : "请求未生效", data);
        if (errorCode != null) body.put("errorCode", errorCode);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(AppGapException.class)
    public ResponseEntity<AjaxResult> failed(AppGapException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
