package com.chengyinhub.business.platformpass;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * 通行证开关。三个开关全部默认关闭：
 * <ul>
 *   <li>{@code platform-pass.enabled}：总开关。关闭时不出售、不报 ¥0、不领取，既有自玩票报价/下单结果完全不变。</li>
 *   <li>{@code platform-pass.jsapi-enabled}：安卓 / 开发者工具走微信支付 JSAPI（平台商户号）。</li>
 *   <li>{@code platform-pass.xpay.enabled}：iOS 走小程序虚拟支付（道具直购）。</li>
 * </ul>
 * AppKey 是密钥，只能由部署环境变量注入（见 application.yml 的占位），不得写进仓库。
 */
@Component
@ConfigurationProperties(prefix = "platform-pass")
public class PlatformPassProperties {
    private boolean enabled;
    private boolean jsapiEnabled;
    /** 空集合默认覆盖全部已发布主题；非空时仅覆盖列出的主题 ID。 */
    private Set<Long> coverageTopicIds = Collections.emptySet();
    private final Xpay xpay = new Xpay();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isJsapiEnabled() { return jsapiEnabled; }
    public void setJsapiEnabled(boolean v) { jsapiEnabled = v; }
    public Xpay getXpay() { return xpay; }
    public Set<Long> getCoverageTopicIds() { return coverageTopicIds; }
    public void setCoverageTopicIds(Set<Long> ids) {
        coverageTopicIds = ids == null || (ids.size() == 1 && ids.iterator().next() == null)
                ? Collections.emptySet() : Set.copyOf(ids);
    }
    public boolean coversTopic(Long topicId) {
        return topicId != null && topicId > 0 && (coverageTopicIds.isEmpty() || coverageTopicIds.contains(topicId));
    }

    public static class Xpay {
        private boolean enabled;
        /** 虚拟支付后台「基本配置」里的 OfferID。 */
        private String offerId;
        /** 现网 AppKey 或沙箱 AppKey（与 env 对应）。密钥，只经环境变量注入。 */
        private String appKey;
        /** 0 = 现网，1 = 沙箱。默认沙箱，防止误配直接收真钱。 */
        private int env = 1;
        private String apiBase = "https://api.weixin.qq.com";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public String getOfferId() { return offerId; }
        public void setOfferId(String v) { offerId = v; }
        public String getAppKey() { return appKey; }
        public void setAppKey(String v) { appKey = v; }
        public int getEnv() { return env; }
        public void setEnv(int v) { env = v; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String v) { apiBase = v; }

        public boolean isReady() {
            return enabled && offerId != null && !offerId.isBlank() && appKey != null && !appKey.isBlank()
                    && (env == 0 || env == 1);
        }
    }
}
