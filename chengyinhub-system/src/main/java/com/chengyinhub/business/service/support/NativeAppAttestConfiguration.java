package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.support.appattest.AppAttestPolicy;
import com.chengyinhub.business.service.support.appattest.AppAttestVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.util.*;

/** No root, policy, environment or enablement is guessed. Disabled initialization performs no file IO. */
@Component
public class NativeAppAttestConfiguration {
    @Value("${chengyin.native-steps.app-attest.enabled:false}") private boolean enabled;
    @Value("${chengyin.native-steps.app-attest.app-id:}") private String appId = "";
    @Value("${chengyin.native-steps.app-attest.distribution-categories:}") private String categories = "";
    @Value("${chengyin.native-steps.app-attest.bundle-versions:}") private String bundles = "";
    @Value("${chengyin.native-steps.app-attest.attestation-root-der-path:}") private String attestationRoot = "";
    @Value("${chengyin.native-steps.app-attest.receipt-root-der-path:}") private String receiptRoot = "";
    private volatile AppAttestVerifier verifier;

    @PostConstruct
    public void initialize() {
        verifier = null;
        if (!enabled) return;
        try {
            if (appId.isEmpty() || categories.isEmpty() || bundles.isEmpty()) return;
            Set<Integer> allowedCategories = new LinkedHashSet<>();
            for (String value : categories.split(",", -1)) allowedCategories.add(Integer.valueOf(value.trim()));
            Set<String> allowedBundles = new LinkedHashSet<>();
            for (String value : bundles.split(",", -1)) {
                if (value.trim().isEmpty()) return;
                allowedBundles.add(value.trim());
            }
            verifier = new AppAttestVerifier(new AppAttestPolicy(appId, allowedCategories, allowedBundles),
                    readRoot(attestationRoot), readRoot(receiptRoot));
        } catch (Exception unavailable) {
            // A bad/absent trust policy cannot break legacy startup or silently activate a permissive verifier.
            verifier = null;
        }
    }
    private byte[] readRoot(String path) throws Exception {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Missing trust anchor");
        Path file = Paths.get(path);
        if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > 8192) {
            throw new IllegalArgumentException("Invalid trust anchor resource");
        }
        return Files.readAllBytes(file);
    }
    public boolean isReady() { return enabled && verifier != null; }
    public AppAttestVerifier verifier() {
        if (!isReady()) NativeAppAttestSupport.fail("APP_ATTEST_NOT_CONFIGURED");
        return verifier;
    }
    public String appId() { return appId; }
}
