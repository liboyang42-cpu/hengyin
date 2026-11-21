package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.growthloops.ShareUrlLinkService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * ONB-6:分享链接的公开落地页(chengyinhub-ui/public/link)在没装 App 或在微信里打开时,
 * 用这里拿「在微信小程序中打开」的 URL Link。
 * <pre>
 * GET /api/share/url-link?path=/pages/club/detail/index&amp;id=12 (匿名) → {available, url? | reason?}
 * </pre>
 * 只认 AASA 认领的内容页和规范正整数 id;不返回内容本身,内容读取仍走各自的接口。
 */
@RestController
@Tag(name = "分享落地页")
public class ApiShareLinkController extends BaseController
{
    @Autowired private ShareUrlLinkService shareLinks;

    @Anonymous
    @RateLimiter(time = 60, count = 30, limitType = LimitType.IP)
    @Operation(summary = "分享链接对应的小程序 URL Link")
    @GetMapping("/api/share/url-link")
    public AjaxResult urlLink(@RequestParam(value = "path", required = false) String path,
                              @RequestParam(value = "id", required = false) String id)
    {
        try {
            return success(shareLinks.link(path, id));
        } catch (IllegalArgumentException invalid) {
            return AjaxResult.error(400, "这个链接打不开，请对方重新分享");
        }
    }
}
