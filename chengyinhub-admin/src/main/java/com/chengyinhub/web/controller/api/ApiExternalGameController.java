package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.appgaps.AppGapException;
import com.chengyinhub.business.externalgame.ExternalGamePackageValidator;
import com.chengyinhub.business.externalgame.ExternalGameReviewService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * External game (H5 package) submission, author reads, public catalog and player reports. Review is admin-only in
 * {@code ExternalGameReviewController}. Upload is multipart: either {@code archive} (the app's ExternalGamePackageArchive
 * JSON) or {@code manifest} + {@code entry} (single-HTML package). No money: monetization must be NONE.
 */
@RestController
@Tag(name = "外部游戏提交与审核")
public class ApiExternalGameController extends AppGapController {
    private final ExternalGameReviewService reviews;

    public ApiExternalGameController(ExternalGameReviewService reviews) { this.reviews = reviews; }

    @Operation(summary = "提交外部游戏包（服务端校验 + 服务端签名，状态 PENDING）")
    @PostMapping(value = "/api/external-game/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult submit(@RequestPart(value = "archive", required = false) MultipartFile archive,
                             @RequestPart(value = "manifest", required = false) MultipartFile manifest,
                             @RequestPart(value = "entry", required = false) MultipartFile entry,
                             @RequestParam(value = "requestId", required = false) String requestId,
                             @RequestParam(value = "monetization", required = false) String monetization) {
        Long me = member();
        return success(reviews.submit(me, requestId, read(archive, manifest, entry, monetization), null));
    }

    @Operation(summary = "被驳回或撤回后重新提交（须为更高版本）")
    @PostMapping(value = "/api/external-game/submissions/{submissionId}/resubmit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult resubmit(@PathVariable String submissionId,
                               @RequestPart(value = "archive", required = false) MultipartFile archive,
                               @RequestPart(value = "manifest", required = false) MultipartFile manifest,
                               @RequestPart(value = "entry", required = false) MultipartFile entry,
                               @RequestParam(value = "requestId", required = false) String requestId,
                               @RequestParam(value = "monetization", required = false) String monetization) {
        Long me = member();
        if (!submissionId.matches("[1-9][0-9]{0,18}")) throw AppGapException.badRequest("submissionId");
        return success(reviews.submit(me, requestId, read(archive, manifest, entry, monetization), Long.parseLong(submissionId)));
    }

    @Operation(summary = "我的提交（状态与驳回原因）")
    @GetMapping("/api/external-game/submissions/mine")
    public AjaxResult mine(@RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
                           @RequestParam(required = false) String limit) {
        return success(reviews.mine(member(), status, cursor, limit));
    }

    @Operation(summary = "我的提交详情与审核时间线")
    @GetMapping("/api/external-game/submissions/{submissionId}")
    public AjaxResult mineDetail(@PathVariable String submissionId) {
        return success(reviews.mineDetail(member(), submissionId));
    }

    @Operation(summary = "撤回待审核的提交")
    @PostMapping("/api/external-game/submissions/{submissionId}/cancel")
    public AjaxResult cancel(@PathVariable String submissionId, @RequestBody(required = false) Map<String, Object> body) {
        return success(reviews.cancel(member(), submissionId, body));
    }

    @Anonymous
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @Operation(summary = "已上架外部游戏目录")
    @GetMapping("/api/external-game/catalog")
    public AjaxResult catalog(@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit) {
        return success(reviews.catalog(cursor, limit));
    }

    @Anonymous
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @Operation(summary = "已上架外部游戏详情（含限时签名下载地址）")
    @GetMapping("/api/external-game/catalog/{gameId}")
    public AjaxResult catalogDetail(@PathVariable String gameId) {
        return success(reviews.catalogDetail(gameId));
    }

    @Anonymous
    @RateLimiter(time = 60, count = 60, limitType = LimitType.IP)
    @Operation(summary = "限时签名下载（返回服务端签名的游戏包 JSON）")
    @GetMapping("/api/external-game/catalog/{gameId}/package")
    public ResponseEntity<byte[]> download(@PathVariable String gameId, @RequestParam(required = false) String submissionId,
                                           @RequestParam(required = false) String exp, @RequestParam(required = false) String sig) {
        byte[] body = reviews.catalogPackage(gameId, submissionId, exp, sig);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + gameId + ".questify-game.json\"")
                .body(body);
    }

    @Anonymous
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @Operation(summary = "外部游戏签名公钥（Ed25519）")
    @GetMapping("/api/external-game/signing-key")
    public AjaxResult signingKey() {
        return success(reviews.signingKey());
    }

    @Operation(summary = "举报已安装的外部游戏")
    @PostMapping(value = "/api/external-game/reports", consumes = "application/json")
    public AjaxResult report(@RequestBody(required = false) Map<String, Object> body) {
        return success(reviews.report(member(), body));
    }

    private static ExternalGamePackageValidator.Validated read(MultipartFile archive, MultipartFile manifest, MultipartFile entry, String monetization) {
        try {
            if (archive != null && !archive.isEmpty()) {
                if (manifest != null || entry != null) throw new AppGapException(400, "MALFORMED_ARCHIVE", "archive 与 manifest/entry 只能二选一");
                if (archive.getSize() > ExternalGamePackageValidator.MAX_ARCHIVE_JSON_BYTES) {
                    throw new AppGapException(413, "PACKAGE_OVERSIZED", "游戏包体积超限", Map.of("size", archive.getSize(), "maxAllowed", ExternalGamePackageValidator.MAX_ARCHIVE_JSON_BYTES));
                }
                return ExternalGamePackageValidator.fromArchiveJson(archive.getBytes(), monetization);
            }
            if (manifest == null || entry == null) throw new AppGapException(400, "MALFORMED_ARCHIVE", "需要 archive，或 manifest + entry");
            if (manifest.getSize() > ExternalGamePackageValidator.MAX_MANIFEST_JSON_BYTES) throw new AppGapException(400, "MALFORMED_ARCHIVE", "清单过大");
            if (entry.getSize() > ExternalGamePackageValidator.MAX_FILE_BYTES) {
                throw new AppGapException(413, "FILE_OVERSIZED", "单文件体积超限", Map.of("size", entry.getSize(), "maxAllowed", ExternalGamePackageValidator.MAX_FILE_BYTES));
            }
            return ExternalGamePackageValidator.fromManifestAndEntry(manifest.getBytes(), entry.getBytes(), monetization);
        } catch (IOException e) {
            throw new AppGapException(400, "MALFORMED_ARCHIVE", "上传读取失败");
        }
    }
}
