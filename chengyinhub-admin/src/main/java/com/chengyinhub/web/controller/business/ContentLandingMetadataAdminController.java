package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ContentLandingTranslationReviewRequest;
import com.chengyinhub.business.service.IContentLandingMetadataService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M7 落地元信息后台审校（WEB-05 审校面板）。
 *
 * <p><b>写面边界</b>：审校只翻转译文的 {@code translation_state}（PENDING ⇄ APPROVED），
 * <b>不覆盖作者文字</b>。落地元信息是作者对「能不能接团体/学校、适合几岁」的声明，
 * 运营改写会让公开页与作者声明不一致，因此本控制器不提供元信息正文的写入口。
 *
 * <p><b>权限</b>：列表沿用既有 {@code business:topic:query}；审校写面用
 * {@code business:contentlanding:review}。该权限串**本轮不 seed、不授角色**，
 * 未授权角色一律 403（fail-closed），超管按既有机制可用。
 * 这与 {@code business:marketing:channel:*} 的处理方式一致：宁可接口暂时只有超管能用，
 * 也不擅自往角色里塞权限。
 */
@RestController
@Tag(name = "M7 落地元信息审校")
@RequestMapping("/business/content-landing")
public class ContentLandingMetadataAdminController extends BaseController {

    /** 审校写面权限串（未 seed；fail-closed）。 */
    static final String PERM_REVIEW = "@ss.hasPermi('business:contentlanding:review')";

    @Autowired
    private IContentLandingMetadataService landingService;

    @PreAuthorize("@ss.hasPermi('business:topic:query')")
    @Operation(summary = "落地元信息审校列表(只含已公开主题)")
    @GetMapping("/list")
    public AjaxResult list(String keyword) {
        startPage();
        try {
            return success(getDataTable(landingService.reviewList(keyword)));
        } finally {
            clearPage();
        }
    }

    @PreAuthorize(PERM_REVIEW)
    @Operation(summary = "审校台读取译文正文与版本(只读)")
    @GetMapping("/translation/detail")
    public AjaxResult translationDetail(Long topicId) {
        return success(landingService.reviewDetail(topicId));
    }

    @PreAuthorize(PERM_REVIEW)
    @Operation(summary = "取待审译文的内容摘要(审校台渲染前调用)")
    @GetMapping("/translation/digest")
    public AjaxResult translationDigest(Long topicId, String locale) {
        return success(landingService.reviewDigest(topicId, locale));
    }

    @PreAuthorize(PERM_REVIEW)
    @Operation(summary = "审校译文(只翻转状态,不覆盖作者文字;需 expectedVersion + 内容摘要 + 理由)")
    @PostMapping("/translation/review")
    public AjaxResult reviewTranslation(@RequestBody ContentLandingTranslationReviewRequest request) {
        return success(landingService.reviewTranslation(request, getUserId()));
    }
}
