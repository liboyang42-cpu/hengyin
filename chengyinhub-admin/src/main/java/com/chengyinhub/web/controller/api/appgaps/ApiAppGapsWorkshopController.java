package com.chengyinhub.web.controller.api.appgaps;

import com.chengyinhub.business.appgaps.WorkshopOwnedRead;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * App 工坊/模组缺口路由。
 * <ul>
 *   <li>/api/workshop/owned/*：免费领取只读，现网无免费领取事实源，如实 NOT_ENABLED。</li>
 *   <li>/api/mod-commerce/**：购买/支付/退款族，按发布策略关闭——任何方法一律拒绝，不读请求体、不触达支付。
 *       App（Core/WorkshopCommerceService）只认 code：未登录回 401（走会话失效），已登录回 503 + errorCode
 *       WORKSHOP_COMMERCE_DISABLED（落到 unavailable）。</li>
 *   <li>模组安装/升级/举报/创作者收益、创作者待审包/公开主题授权：后端尚无对应领域，显式 503「未开放」，
 *       让 App 落到 disabled 而不是 404 猜测（交付文档里记为 DEFERRED）。</li>
 * </ul>
 * 付费已购库与安装请走 /api/workshop/v2/purchased/*（见 docs/delivery/app-gaps-b/MAPPING.md）。
 */
@RestController
@Tag(name = "App 工坊缺口")
public class ApiAppGapsWorkshopController extends BaseController {
    static final int DISABLED = NotBuiltFeature.CODE;
    private final WorkshopOwnedRead owned;

    public ApiAppGapsWorkshopController(WorkshopOwnedRead owned) { this.owned = owned; }

    boolean signedIn() {
        try { return getAppUserId() != null; } catch (Exception e) { return false; }
    }

    @PostMapping("/api/workshop/owned/list")
    public AjaxResult ownedList(@RequestBody(required = false) String body) {
        if (!signedIn()) return AjaxResult.error(401, "请先登录");
        return owned.list(body);
    }

    @PostMapping("/api/workshop/owned/detail")
    public AjaxResult ownedDetail(@RequestParam(value = "claim_id", required = false) String claimId) {
        if (!signedIn()) return AjaxResult.error(401, "请先登录");
        return owned.detail(claimId);
    }

    @PostMapping("/api/workshop/owned/package")
    public AjaxResult ownedPackage(@RequestParam(value = "claim_id", required = false) String claimId) {
        if (!signedIn()) return AjaxResult.error(401, "请先登录");
        return owned.pkg(claimId);
    }

    /**
     * 购买/支付/退款恒关：不读请求体、不建单、不调支付。R9 BE-06：族名、文案与「未建成」标记见 {@link NotBuiltFeature}。
     */
    @RequestMapping({"/api/mod-commerce", "/api/mod-commerce/**"})
    public AjaxResult commerceOff(jakarta.servlet.http.HttpServletRequest request,
                                  jakarta.servlet.http.HttpServletResponse response) {
        if (!signedIn()) return AjaxResult.error(401, "请先登录");
        return notBuilt(NotBuiltFeature.MOD_COMMERCE, response);
    }

    /**
     * 后端尚无对应领域的族：模组安装/升级/已购举报/创作者收益、创作者待审包/公开主题授权。
     * 每个前缀对应 {@link NotBuiltFeature} 的一个具名条目，不再是匿名通配 503。
     */
    @RequestMapping({"/api/mod-installation", "/api/mod-installation/**", "/api/mod-upgrade", "/api/mod-upgrade/**",
            "/api/mod-owned-report", "/api/mod-owned-report/**", "/api/mod-settlement", "/api/mod-settlement/**",
            "/api/workshop/creator/pending-packages", "/api/workshop/creator/pending-packages/**",
            "/api/workshop/creator/public-theme-use", "/api/workshop/creator/public-theme-use/**"})
    public AjaxResult notEnabled(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response) {
        if (!signedIn()) return AjaxResult.error(401, "请先登录");
        NotBuiltFeature feature = NotBuiltFeature.forPath(request == null ? null : request.getRequestURI());
        return notBuilt(feature == null ? NotBuiltFeature.MOD_INSTALLATION : feature, response);
    }

    /** 机器可读清单：哪些 App 路由族尚未建成或按发布策略关闭。App 与冒烟脚本据此如实显示「未上线」，不计入已服务。 */
    @GetMapping("/api/meta/not-built")
    public AjaxResult notBuiltList() {
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        for (NotBuiltFeature f : NotBuiltFeature.values()) rows.add(f.describe());
        return AjaxResult.success(rows);
    }

    static AjaxResult notBuilt(NotBuiltFeature feature, jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) response.setHeader(NotBuiltFeature.HEADER, feature.name());
        AjaxResult off = AjaxResult.error(DISABLED, feature.messageZh());
        off.put("errorCode", feature.errorCode());
        off.put("feature", feature.name());
        off.put("notBuilt", Boolean.TRUE);
        off.put("errorKind", feature.status() == NotBuiltFeature.Status.POLICY_CLOSED ? "POLICY_CLOSED" : "NOT_BUILT");
        return off;
    }
}
