package com.chengyinhub.business.platformpass;

import com.apple.itunes.storekit.client.AppStoreServerAPIClient;
import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R6a:通行证内购的验签。证书链(x5c → 运营配置的 Apple 根证书)、签名、bundle id、环境全部交给 Apple 官方
 * {@code app-store-server-library}(版本见 chengyinhub-system/pom.xml)。本类只决定「用哪些根、哪个 bundle、哪个环境」,
 * 这些只来自服务端配置;请求体里的任何字段都不能改变它们。
 */
@Component
public class PlatformPassAppleVerifier {
    static final int MAX_SIGNED_LENGTH = 131_072;

    private final PlatformPassAppleProperties config;
    private final List<byte[]> fixedRoots;
    private volatile SignedDataVerifier verifier;

    @org.springframework.beans.factory.annotation.Autowired
    public PlatformPassAppleVerifier(PlatformPassAppleProperties config) {
        this(config, null);
    }

    /** 单元测试:用合成的测试根证书,绝不连 Apple。 */
    PlatformPassAppleVerifier(PlatformPassAppleProperties config, List<byte[]> fixedRoots) {
        this.config = config;
        this.fixedRoots = fixedRoots;
    }

    /** 不可用原因;null = 配置齐全(根证书是否可读在首次验签时确定)。 */
    public String unavailableReason() {
        if (!config.isEnabled()) return "APPLE_PASS_DISABLED";
        if (blank(config.getBundleId())) return "APPLE_BUNDLE_NOT_CONFIGURED";
        if (environment() == null) return "APPLE_ENVIRONMENT_INVALID";
        if (environment() == Environment.PRODUCTION && (config.getAppAppleId() == null || config.getAppAppleId() <= 0)) {
            return "APPLE_APP_ID_NOT_CONFIGURED";
        }
        if (fixedRoots == null && (config.getRootCertificatePaths() == null || config.getRootCertificatePaths().isEmpty())) {
            return "APPLE_TRUST_ROOTS_NOT_CONFIGURED";
        }
        return null;
    }

    public JWSTransactionDecodedPayload transaction(String signed) {
        bounded(signed);
        try {
            return verifier().verifyAndDecodeTransaction(signed);
        } catch (PlatformPassException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformPassException("APPLE_TRANSACTION_VERIFICATION_FAILED", 422, "购买凭证校验未通过");
        }
    }

    public ResponseBodyV2DecodedPayload notification(String signed) {
        bounded(signed);
        try {
            return verifier().verifyAndDecodeNotification(signed);
        } catch (PlatformPassException e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformPassException("APPLE_NOTIFICATION_VERIFICATION_FAILED", 422, "通知校验未通过");
        }
    }

    /**
     * App Store Server API「Get Transaction Info」:按交易号取回 Apple 签名的交易(随后仍走 {@link #transaction} 验签)。
     * 私钥只从配置的文件路径读取;任何一项缺失都不可用。
     */
    public String lookup(String transactionId) {
        String reason = unavailableReason();
        if (reason != null) throw PlatformPassException.unavailable(reason, "App Store 购买暂不可用");
        if (!config.isServerApiEnabled() || blank(config.getSigningKeyPath()) || blank(config.getKeyId())
                || blank(config.getIssuerId())) {
            throw PlatformPassException.unavailable("APPLE_SERVER_API_UNAVAILABLE", "暂时无法向 App Store 查询");
        }
        if (transactionId == null || !transactionId.matches("[0-9]{1,64}")) {
            throw PlatformPassException.badRequest("APPLE_TRANSACTION_ID_INVALID", "交易号无效");
        }
        try {
            var client = new AppStoreServerAPIClient(Files.readString(Path.of(config.getSigningKeyPath())),
                    config.getKeyId(), config.getIssuerId(), config.getBundleId(), environment());
            return client.getTransactionInfo(transactionId).getSignedTransactionInfo();
        } catch (Exception e) {
            throw PlatformPassException.unavailable("APPLE_SERVER_API_FAILED", "暂时无法向 App Store 查询");
        }
    }

    public String configuredEnvironment() {
        Environment e = environment();
        return e == null ? null : e.getValue();
    }

    private Environment environment() {
        if ("Sandbox".equals(config.getEnvironment())) return Environment.SANDBOX;
        if ("Production".equals(config.getEnvironment())) return Environment.PRODUCTION;
        return null;
    }

    private SignedDataVerifier verifier() {
        String reason = unavailableReason();
        if (reason != null) throw PlatformPassException.unavailable(reason, "App Store 购买暂不可用");
        SignedDataVerifier current = verifier;
        if (current != null) return current;
        synchronized (this) {
            if (verifier != null) return verifier;
            Set<InputStream> roots = new HashSet<>();
            try {
                for (byte[] root : rootBytes()) roots.add(new ByteArrayInputStream(root));
                if (roots.isEmpty()) throw new IllegalStateException("no roots");
                verifier = new SignedDataVerifier(roots, config.getBundleId(), config.getAppAppleId(), environment(),
                        config.isOnlineChecks());
                return verifier;
            } catch (Exception e) {
                throw PlatformPassException.unavailable("APPLE_TRUST_ROOTS_UNAVAILABLE", "App Store 购买暂不可用");
            }
        }
    }

    private List<byte[]> rootBytes() throws Exception {
        if (fixedRoots != null) return fixedRoots;
        List<byte[]> out = new ArrayList<>();
        // 只读运营放在本机的 Apple PKI 根证书文件;请求永远不能提供根证书或 URL。
        for (String file : config.getRootCertificatePaths()) {
            if (!blank(file)) out.add(Files.readAllBytes(Path.of(file.trim())));
        }
        return out;
    }

    private static void bounded(String signed) {
        if (blank(signed) || signed.length() > MAX_SIGNED_LENGTH) {
            throw PlatformPassException.badRequest("APPLE_SIGNED_DATA_INVALID", "购买凭证无效");
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
