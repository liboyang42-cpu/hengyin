package com.chengyinhub.business.modcommerce;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * R6c workshop-iap configuration. Safe defaults: off, China market.
 *
 * <p>{@code /api/mod-commerce/**} answers only while {@code enabled} is true AND the money switch's {@code pass} area
 * is open (see {@link ModCommerceService#open()}). App Store identity (bundle id, environment, trust roots) is not
 * repeated here: it comes from the pass agent's {@code chengyin.pass.apple-iap.*} through
 * {@code PlatformPassAppleVerifier}, so the app has one App Store configuration.
 */
@Component
@ConfigurationProperties(prefix = "chengyin.mod-commerce")
public class ModCommerceProperties {
    /** Default false. Set true on TEST / Staging only, by environment variable. */
    private boolean enabled;
    /** {@code CN} or {@code US}: which offers the app sees. Must match the app build's market. */
    private String market = "CN";
    /** Shown in the frozen terms; the App Store refund policy applies to App Store purchases. */
    private String refundPolicyReference = "APPLE_APP_STORE_REFUND_POLICY";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getMarket() { return market; }
    public void setMarket(String v) { market = v; }
    public String getRefundPolicyReference() { return refundPolicyReference; }
    public void setRefundPolicyReference(String v) { refundPolicyReference = v; }
}
