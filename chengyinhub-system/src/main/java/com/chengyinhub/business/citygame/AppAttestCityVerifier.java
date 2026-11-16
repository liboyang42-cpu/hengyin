package com.chengyinhub.business.citygame;

import com.chengyinhub.business.service.NativeAppAttestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * CITY-2: City Monopoly App Attest verifier. Reuses the App Attest key the app already enrolled for native steps
 * ({@link NativeAppAttestService}); no call to Apple. The assertion must sign SHA-256 of the exact
 * {@link CityAttestVerifier#canonicalPayload} bytes, and the key counter must strictly increase inside the command's
 * transaction. Any failure answers false, so the rule refuses the command with {@code APP_ATTEST_REQUIRED}.
 */
@Service
public class AppAttestCityVerifier implements CityAttestVerifier {
    private final NativeAppAttestService appAttest;

    @Autowired
    public AppAttestCityVerifier(NativeAppAttestService appAttest) {
        this.appAttest = appAttest;
    }

    @Override
    public boolean ready() {
        return appAttest != null && appAttest.isReady();
    }

    @Override
    public boolean verify(long memberId, String deviceKeyId, String canonicalPayload, String assertion) {
        if (!ready() || deviceKeyId == null || assertion == null || canonicalPayload == null) return false;
        return appAttest.verifyCityAssertion(memberId, deviceKeyId, canonicalPayload, assertion);
    }
}
