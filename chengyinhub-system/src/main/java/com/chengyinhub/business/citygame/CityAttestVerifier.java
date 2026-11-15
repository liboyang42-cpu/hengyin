package com.chengyinhub.business.citygame;

/**
 * App Attest verification hook for City Monopoly claim / upgrade / challenge commands (spec §2.2 "设备证明").
 *
 * <p>Deliberately has no production implementation in this change: the existing {@code NativeAppAttestService} only
 * verifies the native-steps protocol payload. A reviewed adapter must verify the assertion over the exact canonical
 * payload below against an enrolled, non-revoked key and CAS the key counter in the same transaction. While
 * {@code city.monopoly.claim.require_app_attest=true} and no bean exists, every claim is refused with
 * {@code APP_ATTEST_REQUIRED} (fail closed).
 */
public interface CityAttestVerifier {
    /**
     * @param canonicalPayload {@code CHENGYIN_CITY_MONOPOLY_V1\n<kind>\n<memberId>\n<requestId>\n<quoteHash>\n<deviceKeyId>}
     * @return true only when the assertion is valid for this member, device key and payload
     */
    boolean verify(long memberId, String deviceKeyId, String canonicalPayload, String assertion);

    static String canonicalPayload(String kind, long memberId, String requestId, String quoteHash, String deviceKeyId) {
        return "CHENGYIN_CITY_MONOPOLY_V1\n" + kind + "\n" + memberId + "\n" + requestId + "\n" + quoteHash + "\n"
                + (deviceKeyId == null ? "" : deviceKeyId);
    }
}
