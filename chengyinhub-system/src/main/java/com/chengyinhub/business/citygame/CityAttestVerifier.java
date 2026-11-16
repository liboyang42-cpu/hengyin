package com.chengyinhub.business.citygame;

/**
 * App Attest verification hook for City Monopoly claim / upgrade / challenge commands (spec §2.2 "设备证明").
 *
 * <p>{@link AppAttestCityVerifier} verifies the assertion over the exact canonical payload below against the member's
 * enrolled, non-revoked native-steps key and CASes the key counter in the claim transaction. While
 * {@code city.monopoly.claim.require_app_attest=true} and the verifier is not ready (App Attest not configured), every
 * claim is refused with {@code APP_ATTEST_REQUIRED} (fail closed) and {@code /territory/capabilities} says so first.
 */
public interface CityAttestVerifier {
    /**
     * @param canonicalPayload {@code CHENGYIN_CITY_MONOPOLY_V1\n<kind>\n<memberId>\n<requestId>\n<quoteHash>\n<deviceKeyId>}
     * @return true only when the assertion is valid for this member, device key and payload
     */
    boolean verify(long memberId, String deviceKeyId, String canonicalPayload, String assertion);

    /** False while the verifier cannot accept any assertion (for example App Attest is not configured). */
    default boolean ready() { return true; }

    static String canonicalPayload(String kind, long memberId, String requestId, String quoteHash, String deviceKeyId) {
        return "CHENGYIN_CITY_MONOPOLY_V1\n" + kind + "\n" + memberId + "\n" + requestId + "\n" + quoteHash + "\n"
                + (deviceKeyId == null ? "" : deviceKeyId);
    }
}
