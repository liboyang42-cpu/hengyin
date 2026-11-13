package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.appgaps.AttributeAuthorService;
import com.chengyinhub.business.appgaps.TopicCoverService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.appgaps.TopicEditRequestService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * App gaps A pass 2, topic authoring routes: the author-only cover pipeline, the durable edit-request wrapper,
 * the versioned attribute-author contract with its original-request receipt, and the module preflight read.
 * Attribute-author replies keep topicId/configVersion at the envelope root, as that contract reads them.
 */
@RestController
@RequestMapping("/api/topic")
public class ApiAppGapsTopicController extends AppGapController {
    private final TopicCoverService covers;
    private final TopicEditRequestService edits;
    private final AttributeAuthorService attributes;

    public ApiAppGapsTopicController(TopicCoverService covers, TopicEditRequestService edits, AttributeAuthorService attributes) {
        this.covers = covers;
        this.edits = edits;
        this.attributes = attributes;
    }

    // ---- cover ----

    @GetMapping("/cover/selection/current")
    public AjaxResult coverCurrent(@RequestParam String topicId) { return success(covers.current(member(), topicId)); }

    @PostMapping("/cover/upload")
    public AjaxResult coverUpload(@RequestParam("file") MultipartFile file) {
        Long member = member();
        byte[] bytes;
        try {
            if (file == null || file.getSize() > 10L * 1024 * 1024) throw new AppGapException(400, "COVER_IMAGE_INVALID", "图片无效");
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new AppGapException(400, "COVER_IMAGE_INVALID", "图片无效");
        }
        return success(covers.upload(member, bytes));
    }

    @PostMapping("/cover/selection")
    public AjaxResult coverSelect(@RequestBody Map<String, Object> body) { return success(covers.select(member(), body)); }

    @PostMapping("/cover/selection/status")
    public AjaxResult coverStatus(@RequestBody Map<String, Object> body) { return success(covers.status(member(), body)); }

    @GetMapping("/cover/{assetId}/content")
    public ResponseEntity<byte[]> coverContent(@PathVariable String assetId, @RequestParam String sourceVersion, @RequestParam String contentHash) {
        byte[] bytes = covers.content(member(), assetId, sourceVersion, contentHash);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    // ---- edit requests ----

    @PostMapping("/edit-requests/submit")
    public AjaxResult editSubmit(@RequestBody Map<String, Object> body) { return success(edits.submit(member(), body)); }

    @PostMapping("/edit-requests/read")
    public AjaxResult editRead(@RequestBody Map<String, Object> body) { return success(edits.read(member(), body)); }

    /** No post-processing is tracked by this wrapper; resuming answers the same durable receipt. */
    @PostMapping("/edit-requests/resume-post-processing")
    public AjaxResult editResume(@RequestBody Map<String, Object> body) { return success(edits.read(member(), body)); }

    // ---- versioned attribute author ----

    @GetMapping("/attribute-author")
    public Map<String, Object> attributeList(@RequestParam String topicId, @RequestParam String scope,
                                             @RequestParam(required = false) String expectedConfigVersion) {
        return attributes.list(member(), topicId, scope, expectedConfigVersion);
    }

    @GetMapping("/attribute-author/references")
    public Map<String, Object> attributeReferences(@RequestParam String topicId, @RequestParam String scope,
                                                   @RequestParam String expectedConfigVersion, @RequestParam String key) {
        return attributes.references(member(), topicId, scope, expectedConfigVersion, key);
    }

    @PostMapping(value = "/attribute-author/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> attributeValidate(@RequestBody String body) { return attributes.validate(member(), body); }

    @PostMapping(value = "/attribute-author", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> attributeSave(@RequestBody String body,
                                             @RequestHeader(value = "X-Attribute-Request-Protocol", required = false) String protocol,
                                             @RequestHeader(value = "X-Attribute-Request-Id", required = false) String requestId,
                                             @RequestHeader(value = "X-Attribute-Request-SHA256", required = false) String sha,
                                             @RequestHeader(value = "X-Attribute-Session-Epoch", required = false) String epoch) {
        Long member = member();
        return attributes.save(member, body, new AttributeAuthorService.Original(protocol, requestId, sha, epoch));
    }

    @DeleteMapping("/attribute-author")
    public Map<String, Object> attributeDelete(@RequestParam String topicId, @RequestParam String scope,
                                               @RequestParam String expectedConfigVersion, @RequestParam String key,
                                               @RequestHeader(value = "X-Attribute-Request-Protocol", required = false) String protocol,
                                               @RequestHeader(value = "X-Attribute-Request-Id", required = false) String requestId,
                                               @RequestHeader(value = "X-Attribute-Request-SHA256", required = false) String sha,
                                               @RequestHeader(value = "X-Attribute-Session-Epoch", required = false) String epoch) {
        Long member = member();
        return attributes.delete(member, topicId, scope, expectedConfigVersion, key, new AttributeAuthorService.Original(protocol, requestId, sha, epoch));
    }

    @GetMapping("/attribute-author/request")
    public Map<String, Object> attributeRequest(@RequestParam Map<String, String> query) { return attributes.request(member(), query); }

    @GetMapping("/module-preflight")
    public Map<String, Object> modulePreflight(@RequestParam String topicId, @RequestParam String scope, @RequestParam String expectedConfigVersion) {
        return attributes.modulePreflight(member(), topicId, scope, expectedConfigVersion);
    }
}
