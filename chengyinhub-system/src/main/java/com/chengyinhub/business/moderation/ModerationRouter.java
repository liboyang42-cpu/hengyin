package com.chengyinhub.business.moderation;

import org.springframework.stereotype.Component;

/**
 * SEC-12 routing: which machine provider judges an author's content.
 * <ul>
 *   <li>author has a mini-program openid → WeChat (unchanged behaviour);</li>
 *   <li>no openid (phone and Apple accounts) → Tencent Cloud TMS/IMS when its keys are configured;</li>
 *   <li>otherwise none: callers hold the content for its author and queue it for human review.</li>
 * </ul>
 */
@Component
public class ModerationRouter {
    private final WechatModerationProvider wechat;
    private final TencentCloudModerationProvider tencent;

    public ModerationRouter(WechatModerationProvider wechat, TencentCloudModerationProvider tencent) {
        this.wechat = wechat;
        this.tencent = tencent;
    }

    /** The provider for this author, or null when no machine provider can judge. */
    public ContentModerationProvider route(String openid) {
        if (!blank(openid)) return wechat.canJudge(openid) ? wechat : null;
        return fallbackFor(openid);
    }

    /** The provider for authors without an openid, or null. Never WeChat. */
    public ContentModerationProvider fallbackFor(String openid) {
        if (!blank(openid)) return null;
        return tencent != null && tencent.canJudge(openid) ? tencent : null;
    }

    static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
