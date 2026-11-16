package com.chengyinhub.business.growthloops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.chengyinhub.common.core.redis.RedisCache;

/**
 * ONB-6:公开分享落地页(public/link)用的小程序 URL Link。匿名可调,所以:
 * <ul>
 *   <li>只认 App 分享出去、AASA 认领的内容页,id 必须是规范正整数;其它一律 400;</li>
 *   <li>同一 (页面, id) 复用 {@link MiniProgramUrlLinkService} 的 7 天缓存;</li>
 *   <li>新组合每小时有全局预算(默认 600,可配),超了如实返回 available=false / BUSY,不打微信接口。</li>
 * </ul>
 * 微信不可用时同样 available=false,落地页据此只给「用城瘾 App 打开」。
 */
@Service
public class ShareUrlLinkService
{
    /** AASA 路径 → 小程序页面与它读 id 的参数名。poi-detail 在小程序里读 poiId。 */
    static final Map<String, String[]> PAGES = Map.of(
            "/pages/activity/detail/index", new String[] { "pages/activity/detail/index", "id" },
            "/pages/club/detail/index", new String[] { "pages/club/detail/index", "id" },
            "/pages/topic/index/index", new String[] { "pages/topic/index/index", "id" },
            "/pages/templatedetail/templatedetail", new String[] { "pages/templatedetail/templatedetail", "id" },
            "/pages/square/detail/index", new String[] { "pages/square/detail/index", "id" },
            "/subpackageRoam/poi-detail/index", new String[] { "subpackageRoam/poi-detail/index", "poiId" });

    static final String SEEN_PREFIX = "share:urllink:seen:v1:";
    static final String BUDGET_KEY = "share:urllink:budget:v1";

    @Autowired private MiniProgramUrlLinkService urlLinks;
    @Autowired(required = false) private RedisCache redis;

    @Value("${share.urllink.hourly-budget:600}")
    private int hourlyBudget = 600;

    public ShareUrlLinkService() { }

    ShareUrlLinkService(MiniProgramUrlLinkService urlLinks, RedisCache redis, int hourlyBudget)
    {
        this.urlLinks = urlLinks;
        this.redis = redis;
        this.hourlyBudget = hourlyBudget;
    }

    /** 路径不在白名单或 id 不是规范正整数时抛 IllegalArgumentException。返回 {available, url? | reason?}。 */
    public Map<String, Object> link(String path, String id)
    {
        String[] page = path == null ? null : PAGES.get(path.trim());
        if (page == null) throw new IllegalArgumentException("path");
        if (!canonicalPositive(id)) throw new IllegalArgumentException("id");
        if (!withinBudget(page[0] + "?" + id)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", "BUSY");
            return out;
        }
        return urlLinks.link(page[0], page[1] + "=" + id);
    }

    static boolean canonicalPositive(String raw)
    {
        if (raw == null || raw.isEmpty() || raw.length() > 18) return false;
        if (raw.charAt(0) == '0') return false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** 已见过的组合(走缓存)不计数;新组合计入每小时全局预算。Redis 不可用时放行给下游(它自己会降级)。 */
    private boolean withinBudget(String key)
    {
        if (redis == null) return true;
        try {
            Boolean fresh = redis.setIfAbsent(SEEN_PREFIX + key, "1", 7, TimeUnit.DAYS);
            if (!Boolean.TRUE.equals(fresh)) return true;
            Long used = redis.incrementWithinWindow(BUDGET_KEY, 1, TimeUnit.HOURS);
            if (used != null && used > Math.max(0, hourlyBudget)) {
                redis.deleteObject(SEEN_PREFIX + key);
                return false;
            }
            return true;
        } catch (RuntimeException down) {
            return true;
        }
    }
}
