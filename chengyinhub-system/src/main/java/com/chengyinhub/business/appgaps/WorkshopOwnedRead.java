package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 工坊「免费领取」只读（/api/workshop/owned/list|detail|package）。
 * 现网工坊只有付费版本（workshop_version.price_fen>0，见 v2 购买链路），没有免费领取记录这一事实源，
 * 所以这里如实回 NOT_ENABLED + 空集，不把付费许可冒充免费领取，也不开任何领取/安装写入。
 */
@Service
public class WorkshopOwnedRead {
    static final String SCOPE = "FREE_INDIVIDUAL_ONLY";

    public static boolean claimId(String value) {
        return value != null && value.matches("[0-9A-Za-z:_-]{1,128}");
    }

    static String now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(); }

    static Map<String, Object> metadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "workshop-owned-v1"); m.put("scope", SCOPE); m.put("availability", "NOT_ENABLED");
        m.put("purchasedLibraryStatus", "NOT_AVAILABLE"); m.put("contentUseStatus", "UNAVAILABLE"); m.put("checkedAt", now());
        return m;
    }

    public AjaxResult list(String body) {
        if (body != null && !body.isEmpty()) return AjaxResult.error(400, "列表不接受参数");
        Map<String, Object> m = metadata();
        m.put("items", List.of()); m.put("hasMore", false);
        return AjaxResult.success(m);
    }

    public AjaxResult detail(String claimId) {
        if (!claimId(claimId)) return AjaxResult.error(400, "领取编号无效");
        Map<String, Object> m = metadata();
        m.put("item", null);
        return AjaxResult.success(m);
    }

    public AjaxResult pkg(String claimId) {
        if (!claimId(claimId)) return AjaxResult.error(400, "领取编号无效");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", "workshop-package-v1"); m.put("scope", SCOPE); m.put("claimId", claimId); m.put("checkedAt", now());
        m.put("availability", "NOT_ENABLED"); m.put("purchasedLibraryStatus", "NOT_AVAILABLE"); m.put("contentUseStatus", "UNAVAILABLE");
        m.put("manifestStatus", "NOT_AVAILABLE"); m.put("editorStatus", "POST_INSTALL_POLICY_UNAVAILABLE"); m.put("packageInfo", null);
        return AjaxResult.success(m);
    }
}
