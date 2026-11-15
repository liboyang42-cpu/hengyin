package com.chengyinhub.business.platformpass;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R6a:通行证的 iOS 内购(StoreKit 2)配置。全部来自服务端配置或环境变量,默认安全:关闭、无 bundle、无根证书、无商品。
 *
 * <p>任何一项缺失都让 Apple 渠道显示不可用,不会退回到别的渠道,也不会信任客户端传来的任何东西。
 * 私钥只按文件路径读取(App Store Server API 回查用),本类不保存密钥内容。
 */
@Component
@ConfigurationProperties(prefix = "chengyin.pass.apple-iap")
public class PlatformPassAppleProperties {
    /** 总开关。默认 false;只在 TEST/Staging 用环境变量打开,且还要资金开关的 pass 区域打开。 */
    private boolean enabled;
    /** App 的 bundle id,验签后逐笔比对。 */
    private String bundleId;
    /** App Store 里的 App Apple ID;Production 环境必填(Apple SDK 校验通知用)。 */
    private Long appAppleId;
    /** {@code Sandbox} 或 {@code Production}。其它值(含 Xcode / LocalTesting)一律不可用。 */
    private String environment = "Sandbox";
    /** 运营从 Apple PKI 下载到本机的根证书文件(DER 或 PEM)。请求永远不能提供根证书。 */
    private List<String> rootCertificatePaths = new ArrayList<>();
    /** 证书链在线吊销检查(OCSP)。生产与 TEST 保持 true;只有单元测试的合成证书链用 false。 */
    private boolean onlineChecks = true;
    /** 套餐代码 → App Store Connect 商品 ID(非续期订阅)。只有这里列出的商品能开通通行证。 */
    private Map<String, String> productIds = new LinkedHashMap<>();
    /** App Store Server API 回查(丢失签名交易时按交易号找回)。默认关。 */
    private boolean serverApiEnabled;
    /** .p8 私钥文件路径(只读路径,不读内容进配置)。 */
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
    public void setRootCertificatePaths(List<String> v) { rootCertificatePaths = v == null ? new ArrayList<>() : v; }
    public boolean isOnlineChecks() { return onlineChecks; }
    public void setOnlineChecks(boolean v) { onlineChecks = v; }
    public Map<String, String> getProductIds() { return productIds; }
    public void setProductIds(Map<String, String> v) { productIds = v == null ? new LinkedHashMap<>() : v; }
    public boolean isServerApiEnabled() { return serverApiEnabled; }
    public void setServerApiEnabled(boolean v) { serverApiEnabled = v; }
    public String getSigningKeyPath() { return signingKeyPath; }
    public void setSigningKeyPath(String v) { signingKeyPath = v; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String v) { keyId = v; }
    public String getIssuerId() { return issuerId; }
    public void setIssuerId(String v) { issuerId = v; }

    /** 商品 ID → 套餐代码(大写)。空白项忽略。 */
    public String planForProduct(String productId) {
        if (productId == null || productId.isBlank()) return null;
        for (Map.Entry<String, String> e : productIds.entrySet()) {
            if (e.getKey() != null && productId.equals(e.getValue())) return e.getKey().trim().toUpperCase();
        }
        return null;
    }

    /** 套餐代码 → 商品 ID;未配置返回 null。 */
    public String productForPlan(String planCode) {
        if (planCode == null) return null;
        for (Map.Entry<String, String> e : productIds.entrySet()) {
            if (e.getKey() != null && e.getKey().trim().equalsIgnoreCase(planCode.trim())
                    && e.getValue() != null && !e.getValue().isBlank()) return e.getValue().trim();
        }
        return null;
    }
}
