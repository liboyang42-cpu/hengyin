package com.chengyinhub.business.appleiap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "workshop.apple-iap")
public class AppleIapProperties {
    private boolean enabled;
    private String bundleId;
    private Long appAppleId;
    private String environment = "Sandbox";
    private List<String> rootCertificatePaths = new ArrayList<>();
    // Explicit SKU -> ISO currency -> Apple's price in milliunits; no exchange-rate conversion.
    private Map<String, Map<String, Long>> skuPrices = new HashMap<>();
    private boolean serverApiEnabled;
    private String signingKeyPath;
    private String keyId;
    private String issuerId;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getBundleId() { return bundleId; }
    public void setBundleId(String v) { bundleId = v; }
    public Long getAppAppleId() { return appAppleId; }
    public void setAppAppleId(Long v) { appAppleId = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { environment = v; }
    public List<String> getRootCertificatePaths() { return rootCertificatePaths; }
    public void setRootCertificatePaths(List<String> v) { rootCertificatePaths = v; }
    public Map<String, Map<String, Long>> getSkuPrices() { return skuPrices; }
    public void setSkuPrices(Map<String, Map<String, Long>> v) { skuPrices = v; }
    public boolean isServerApiEnabled() { return serverApiEnabled; }
    public void setServerApiEnabled(boolean v) { serverApiEnabled = v; }
    public String getSigningKeyPath() { return signingKeyPath; }
    public void setSigningKeyPath(String v) { signingKeyPath = v; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String v) { keyId = v; }
    public String getIssuerId() { return issuerId; }
    public void setIssuerId(String v) { issuerId = v; }
}
