package com.chengyinhub.web.controller.api.appgaps;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R9 BE-06: the explicit list of app route families the backend has NOT built (or keeps closed by release policy).
 *
 * <p>Before R9 these were anonymous {@code /**} wildcards answering 503, so a capability scan counted every path
 * under them as "served". Each family is now a named entry with plain player copy in both languages; the stub
 * answer carries {@code notBuilt=true}, {@code feature=<key>} and the {@code X-Chengyin-Not-Built} header, and
 * {@code GET /api/meta/not-built} lists them all. A smoke harness or the app must treat these as "not built yet",
 * never as served. Remove an entry only when a real controller replaces it.
 */
public enum NotBuiltFeature
{
    /** Buying, paying and refunding workshop mods. Closed by release policy (real money stays off). */
    MOD_COMMERCE(Status.POLICY_CLOSED, "WORKSHOP_COMMERCE_DISABLED",
            "工坊购买暂未开放", "Buying from the workshop isn't open yet",
            "/api/mod-commerce", "/api/mod-commerce/**"),
    MOD_INSTALLATION(Status.NOT_BUILT, "WORKSHOP_FEATURE_NOT_ENABLED",
            "模组安装还没上线", "Installing mods isn't available yet",
            "/api/mod-installation", "/api/mod-installation/**"),
    MOD_UPGRADE(Status.NOT_BUILT, "WORKSHOP_FEATURE_NOT_ENABLED",
            "模组升级还没上线", "Upgrading mods isn't available yet",
            "/api/mod-upgrade", "/api/mod-upgrade/**"),
    MOD_OWNED_REPORT(Status.NOT_BUILT, "WORKSHOP_FEATURE_NOT_ENABLED",
            "已购模组举报还没上线", "Reporting a mod you own isn't available yet",
            "/api/mod-owned-report", "/api/mod-owned-report/**"),
    MOD_SETTLEMENT(Status.NOT_BUILT, "WORKSHOP_FEATURE_NOT_ENABLED",
            "创作者模组收益还没上线", "Creator mod earnings aren't available yet",
            "/api/mod-settlement", "/api/mod-settlement/**"),
    CREATOR_PENDING_PACKAGES(Status.NOT_BUILT, "WORKSHOP_FEATURE_NOT_ENABLED",
            "创作者待审作品包还没上线", "Creator packages waiting for review aren't available yet",
            "/api/workshop/creator/pending-packages", "/api/workshop/creator/pending-packages/**"),
    CREATOR_PUBLIC_THEME_USE(Status.NOT_BUILT, "WORKSHOP_FEATURE_NOT_ENABLED",
            "公开主题授权还没上线", "Licensing public themes isn't available yet",
            "/api/workshop/creator/public-theme-use", "/api/workshop/creator/public-theme-use/**");

    public enum Status { NOT_BUILT, POLICY_CLOSED }

    /** Header on every stub answer; its value is the feature key. */
    public static final String HEADER = "X-Chengyin-Not-Built";
    /** HTTP-200 envelope code kept from the original stubs: the app maps it to "unavailable". */
    public static final int CODE = 503;

    private final Status status;
    private final String errorCode;
    private final String messageZh;
    private final String messageEn;
    private final List<String> patterns;

    NotBuiltFeature(Status status, String errorCode, String messageZh, String messageEn, String... patterns)
    {
        this.status = status;
        this.errorCode = errorCode;
        this.messageZh = messageZh;
        this.messageEn = messageEn;
        this.patterns = Arrays.asList(patterns);
    }

    public Status status() { return status; }
    public String errorCode() { return errorCode; }
    public String messageZh() { return messageZh; }
    public String messageEn() { return messageEn; }
    public List<String> patterns() { return patterns; }

    /** Longest-prefix match of a request path against the families; null when the path is not a stub. */
    public static NotBuiltFeature forPath(String path)
    {
        if (path == null) return null;
        NotBuiltFeature best = null;
        int bestLength = -1;
        for (NotBuiltFeature f : values())
        {
            String root = f.patterns.get(0);
            if ((path.equals(root) || path.startsWith(root + "/")) && root.length() > bestLength)
            {
                best = f;
                bestLength = root.length();
            }
        }
        return best;
    }

    public Map<String, Object> describe()
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("feature", name());
        row.put("status", status.name());
        row.put("errorCode", errorCode);
        row.put("messageZh", messageZh);
        row.put("messageEn", messageEn);
        row.put("routes", patterns);
        return row;
    }
}
