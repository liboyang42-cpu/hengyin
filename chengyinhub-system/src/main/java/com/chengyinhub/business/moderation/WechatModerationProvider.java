package com.chengyinhub.business.moderation;

import com.chengyinhub.business.service.IWxContentSecurityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The existing WeChat check (msgSecCheck v2 / mediaCheckAsync) as a provider. Only for authors with a mini-program
 * openid; images stay on the asynchronous WeChat callback path, so {@link #checkImage} is not synchronous.
 */
@Component
public class WechatModerationProvider implements ContentModerationProvider {
    public static final String NAME = "WECHAT";
    private final ObjectProvider<IWxContentSecurityService> wx;

    public WechatModerationProvider(ObjectProvider<IWxContentSecurityService> wx) {
        this.wx = wx;
    }

    @Override public String name() { return NAME; }

    @Override
    public boolean canJudge(String openid) {
        IWxContentSecurityService service = wx.getIfAvailable();
        return service != null && service.isEnabled() && openid != null && !openid.trim().isEmpty();
    }

    @Override public boolean judgesImagesSynchronously() { return false; }

    @Override
    public ModerationVerdict checkText(String text, String openid) {
        IWxContentSecurityService service = wx.getIfAvailable();
        if (service == null) return ModerationVerdict.UNAVAILABLE;
        return fromWechat(service.checkText(text, openid));
    }

    @Override
    public ModerationVerdict checkImage(String imageUrl, String openid) {
        return ModerationVerdict.UNAVAILABLE;
    }

    /** Maps the legacy {@code checkText} string contract onto a verdict. */
    public static ModerationVerdict fromWechat(String message) {
        if (message == null) return ModerationVerdict.PASS;
        if (IWxContentSecurityService.REVIEW_REQUIRED.equals(message)) return ModerationVerdict.REVIEW;
        if (IWxContentSecurityService.isDetectionUnavailable(message)) return ModerationVerdict.UNAVAILABLE;
        return ModerationVerdict.RISK;
    }
}
