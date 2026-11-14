package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.ContentLandingMetadataSaveRequest;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationSaveRequest;
import com.chengyinhub.business.service.IContentLandingMetadataService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M7 落地元信息 / 公开落地译文(作者本人写面)。
 *
 * <p>必须登录；服务层再校验「主题发布者 = 当前用户」，非本人 403。
 * 写入只影响「对外怎么介绍」，不改发布状态、售卖状态、价格、退款与结算事实。
 *
 * <p>幂等：新建 expectedVersion=0；更新回传 {@code rowVersion}。版本已推进返回 409，
 * 客户端重读后重试；同一 payload 重放不会产生第二行，也不会覆盖他人写入。
 */
@RestController
@Tag(name = "M7 落地元信息")
@RequestMapping("/api/content-landing")
public class ApiContentLandingMetadataController extends BaseController {

    @Autowired
    private IContentLandingMetadataService landingService;

    @Operation(summary = "读取本人主题的落地元信息")
    @Parameters({@Parameter(name = "topic_id", description = "主题 id")})
    @PostMapping("/info")
    public AjaxResult info(String topic_id) {
        try {
            return success(landingService.readOwn(parseId(topic_id), currentMemberId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "保存落地元信息(团体/学校/年龄/无障碍/公开摘要)")
    @PostMapping("/save")
    public AjaxResult save(@RequestBody ContentLandingMetadataSaveRequest request) {
        try {
            return success(landingService.saveMetadata(request, currentMemberId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    @Operation(summary = "保存公开落地译文(只含 title/subtitle/summary)")
    @PostMapping("/translation/save")
    public AjaxResult saveTranslation(@RequestBody ContentLandingTranslationSaveRequest request) {
        try {
            return success(landingService.saveTranslation(request, currentMemberId()));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    /** 未登录直接 401，不把匿名当 memberId=0 交给服务层。 */
    private Long currentMemberId() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            throw new ServiceException("请先登录", 401);
        }
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("请先登录", 401);
        }
        return memberId;
    }

    private static Long parseId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new ServiceException("topic_id 参数不合规", 400);
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("topic_id 参数不合规", 400);
        }
    }
}
