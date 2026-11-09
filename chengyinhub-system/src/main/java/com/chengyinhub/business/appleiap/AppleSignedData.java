package com.chengyinhub.business.appleiap;

import com.apple.itunes.storekit.client.AppStoreServerAPIClient;
import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** All cryptographic parsing and certificate-chain verification belongs to Apple's SDK. */
@Component
public class AppleSignedData {
    private final AppleIapProperties config;
    private volatile SignedDataVerifier verifier;
    public AppleSignedData(AppleIapProperties config) { this.config = config; }

    public JWSTransactionDecodedPayload transaction(String signed) {
        bounded(signed);
        try { return verifier().verifyAndDecodeTransaction(signed); }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw error("APPLE_TRANSACTION_VERIFICATION_FAILED", 422); }
    }

    public ResponseBodyV2DecodedPayload notification(String signed) {
        bounded(signed);
        try { return verifier().verifyAndDecodeNotification(signed); }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw error("APPLE_NOTIFICATION_VERIFICATION_FAILED", 422); }
    }

    public String query(String transactionId) {
        requireEnabled();
        if (!config.isServerApiEnabled()
                || blank(config.getSigningKeyPath()) || blank(config.getKeyId()) || blank(config.getIssuerId()))
            throw error("APPLE_SERVER_QUERY_UNAVAILABLE", 503);
        if (transactionId == null || !transactionId.matches("[0-9]{1,128}")) throw error("APPLE_TRANSACTION_ID_INVALID", 400);
        try {
            var client = new AppStoreServerAPIClient(Files.readString(Path.of(config.getSigningKeyPath())),
                    config.getKeyId(), config.getIssuerId(), config.getBundleId(),
                    "Sandbox".equals(config.getEnvironment()) ? Environment.SANDBOX : Environment.PRODUCTION);
            return client.getTransactionInfo(transactionId).getSignedTransactionInfo();
        } catch (Exception e) { throw error("APPLE_SERVER_QUERY_FAILED", 503); }
    }

    public boolean isReady() {
        try { verifier(); return true; } catch (RuntimeException e) { return false; }
    }

    public void requireEnabled() {
        if (!config.isEnabled() || blank(config.getBundleId())
                || !("Sandbox".equals(config.getEnvironment()) || "Production".equals(config.getEnvironment()))
                || ("Production".equals(config.getEnvironment()) && (config.getAppAppleId() == null || config.getAppAppleId() <= 0)))
            throw error("APPLE_IAP_UNAVAILABLE", 503);
    }

    private SignedDataVerifier verifier() {
        requireEnabled();
        if (verifier != null) return verifier;
        synchronized (this) {
            if (verifier != null) return verifier;
            var roots = new HashSet<InputStream>();
            try {
                if (config.getRootCertificatePaths() == null || config.getRootCertificatePaths().isEmpty())
                    throw error("APPLE_TRUST_ROOTS_UNAVAILABLE", 503);
                // Only operator-controlled local files downloaded from Apple's PKI; requests never supply roots/URLs.
                for (String file : config.getRootCertificatePaths()) roots.add(Files.newInputStream(Path.of(file)));
                verifier = new SignedDataVerifier(roots, config.getBundleId(), config.getAppAppleId(),
                        "Sandbox".equals(config.getEnvironment()) ? Environment.SANDBOX : Environment.PRODUCTION, true);
                return verifier;
            } catch (ServiceException e) { throw e; }
            catch (Exception e) { throw error("APPLE_TRUST_ROOTS_UNAVAILABLE", 503); }
            finally { for (InputStream root : roots) try { root.close(); } catch (Exception ignored) { } }
        }
    }

    private static void bounded(String signed) {
        if (blank(signed) || signed.length() > 131072) throw error("APPLE_SIGNED_DATA_INVALID", 400);
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    static ServiceException error(String code, int status) { return new ServiceException(code, status); }
}
