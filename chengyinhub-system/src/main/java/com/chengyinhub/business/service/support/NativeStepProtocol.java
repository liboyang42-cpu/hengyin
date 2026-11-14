package com.chengyinhub.business.service.support;

/**
 * Wire constants of the native device verification provider. The step-submission half of the original questify-be
 * packet (canonical 18-line step proof, game-session binding) is not carried by be-gap-misc; only enrollment is served.
 */
public final class NativeStepProtocol {
    public static final String PROVIDER = "IOS_CMPEDOMETER_V1";

    private NativeStepProtocol() { }
}
