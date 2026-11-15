package com.chengyinhub.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.growthloops.GrowthLoopsSettings;
import com.chengyinhub.business.growthloops.InviteRewardService;
import com.chengyinhub.business.growthloops.MiniProgramUrlLinkService;
import com.chengyinhub.business.growthloops.WinbackService;
import com.chengyinhub.common.annotation.Anonymous;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.LimitType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * R11 growth-loops 接口（App 与小程序共用）。
 * <pre>
 * GET  /api/growth-loops/winback/pref                → {enabled, serviceMode, pushConfigured, maxPerWeek, lastSentAt}
 * POST /api/growth-loops/winback/pref {"enabled":b}  → 同上（关掉 = 不再收到“回来看看”的提醒）
 * GET  /api/growth-loops/invite/summary              → {enabled, rewardKind:"BADGE", cityCoin, granted, monthlyCap, invites[]}
 * POST /api/growth-loops/miniprogram-link {"page":"pages/x/index","query":"id=1"} → {available, url? | reason?}
 * GET  /api/growth-loops/app-handoff  (匿名)          → {universalLink, appStoreUrl|null}
 * </pre>
 */
@RestController
@Tag(name = "增长循环")
@RequestMapping("/api/growth-loops")
public class ApiGrowthLoopsController extends BaseController
{
    private static final java.util.Set<String> PREF_KEYS = java.util.Set.of("enabled");
    private static final java.util.Set<String> LINK_KEYS = java.util.Set.of("page", "query");

    @Autowired private WinbackService winback;
    @Autowired private InviteRewardService invites;
    @Autowired private MiniProgramUrlLinkService urlLinks;
    @Autowired private GrowthLoopsSettings settings;

    @Operation(summary = "我的召回提醒设置")
    @GetMapping("/winback/pref")
    public AjaxResult winbackPref()
    {
        Long me = principal();
        return me == null ? error("请先登录") : success(winback.preference(me));
    }

    @Operation(summary = "开关召回提醒")
    @PostMapping("/winback/pref")
    public AjaxResult setWinbackPref(@RequestBody(required = false) Map<String, Object> body)
    {
        Long me = principal();
        if (me == null) return error("请先登录");
        if (body == null || !PREF_KEYS.containsAll(body.keySet()) || !(body.get("enabled") instanceof Boolean enabled))
            return AjaxResult.error(400, "参数不对");
        return success(winback.setEnabled(me, enabled));
    }

    @Operation(summary = "我的邀请奖励")
    @GetMapping("/invite/summary")
    public AjaxResult inviteSummary()
    {
        Long me = principal();
        return me == null ? error("请先登录") : success(invites.summary(me));
    }

    @Operation(summary = "生成小程序 URL Link（App 打开还没有原生页面的小程序页）")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/miniprogram-link")
    public AjaxResult miniProgramLink(@RequestBody(required = false) Map<String, Object> body)
    {
        Long me = principal();
        if (me == null) return error("请先登录");
        if (body == null || !LINK_KEYS.containsAll(body.keySet()) || !(body.get("page") instanceof String page)
                || (body.get("query") != null && !(body.get("query") instanceof String)))
            return AjaxResult.error(400, "参数不对");
        try {
            return success(urlLinks.link(page, (String) body.get("query")));
        } catch (IllegalArgumentException invalid) {
            return AjaxResult.error(400, "页面地址不对");
        }
    }

    @Anonymous
    @Operation(summary = "小程序 → App：通用链接页与 App Store 地址")
    @GetMapping("/app-handoff")
    public AjaxResult appHandoff()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("universalLink", settings.universalLinkBase());
        out.put("appStoreUrl", settings.appStoreUrl());
        return success(out);
    }

    private Long principal()
    {
        try {
            Long id = getAppUserId();
            return id == null || id <= 0 ? null : id;
        } catch (Exception ignored) {
            return null;
        }
    }
}
