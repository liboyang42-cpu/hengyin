package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.support.release.ContentCapturePreviewService;
import com.chengyinhub.business.service.support.release.ContentCaptureReviewService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.HashSet;

/** Read-only reviewer endpoint. Identity, fresh permission and scope are checked inside the transactional service. */
@RestController
@RequestMapping("/business/audit")
public class ContentCapturePreviewController {
    private final ContentCapturePreviewService previews;
    public ContentCapturePreviewController(ContentCapturePreviewService previews) { this.previews=previews; }

    @PreAuthorize("@ss.hasPermi('business:audit:review')")
    @GetMapping("/{id}/candidate-preview")
    public ResponseEntity<AjaxResult> preview(@PathVariable("id") String id,
            @RequestParam MultiValueMap<String,String> query) {
        final long task, version, snapshot, sourceVersion;
        final String hash;
        try {
            if (!query.keySet().equals(new HashSet<>(Arrays.asList("expectedTaskVersion","snapshotId","sourceConfigVersion","contentHash"))))
                throw new IllegalArgumentException();
            for (String key:query.keySet()) if (query.get(key)==null || query.get(key).size()!=1) throw new IllegalArgumentException();
            task=parse(id); version=parse(query.getFirst("expectedTaskVersion"));
            snapshot=parse(query.getFirst("snapshotId")); sourceVersion=parse(query.getFirst("sourceConfigVersion"));
            hash=query.getFirst("contentHash");
            if (task<=0 || snapshot<=0 || version>Integer.MAX_VALUE || hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            return failure(400,"CONTENT_PREVIEW_INVALID");
        }
        try {
            return response(200,AjaxResult.success(previews.preview(task,(int)version,snapshot,sourceVersion,hash)));
        } catch (AccessDeniedException denied) {
            return failure(403,"CONTENT_PREVIEW_FORBIDDEN");
        } catch (ContentCaptureReviewService.UnavailableException unavailable) {
            return failure(503,"CONTENT_PREVIEW_UNAVAILABLE");
        } catch (ContentCapturePreviewService.ConflictException conflict) {
            return failure(409,"CONTENT_PREVIEW_CONFLICT");
        } catch (Exception unavailable) {
            return failure(503,"CONTENT_PREVIEW_UNAVAILABLE");
        }
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<AjaxResult> methodAccessDenied(AccessDeniedException denied) {
        return failure(403,"CONTENT_PREVIEW_FORBIDDEN");
    }
    private long parse(String value) {
        if (value==null || !value.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    private ResponseEntity<AjaxResult> failure(int status,String code) {
        AjaxResult body=new AjaxResult(status,"Candidate preview unavailable; refresh the bound review task");
        body.put("errorCode",code); return response(status,body);
    }
    private ResponseEntity<AjaxResult> response(int status,AjaxResult body) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(body);
    }
}
