package com.chengyinhub.business.service.offline;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * C05 独占：App 内导航目的与位置分享同意分离。授予其一绝不暗示另一项。
 */
public final class NavigationDestinationConsent {

    public enum Purpose {
        IN_APP_NAVIGATION_DESTINATION, LOCATION_SHARE
    }

    public enum Failure {
        NAVIGATION_DESTINATION_CONSENT_REQUIRED,
        LOCATION_SHARE_CONSENT_REQUIRED,
        DESTINATION_IS_NOT_SHARE_PAYLOAD,
        PURPOSE_MISMATCH
    }

    private final Set<Purpose> granted = EnumSet.noneOf(Purpose.class);

    public boolean allowsNavigationDestination() {
        return granted.contains(Purpose.IN_APP_NAVIGATION_DESTINATION);
    }

    public boolean allowsLocationShare() {
        return granted.contains(Purpose.LOCATION_SHARE);
    }

    public void grant(Purpose purpose) {
        granted.add(Objects.requireNonNull(purpose));
    }

    public void revoke(Purpose purpose) {
        granted.remove(purpose);
    }

    public boolean allows(Purpose purpose) {
        return granted.contains(purpose);
    }

    public boolean impliesOtherPurpose(Purpose purpose) {
        if (purpose == Purpose.IN_APP_NAVIGATION_DESTINATION) {
            return allowsLocationShare() && !allowsNavigationDestination();
        }
        return allowsNavigationDestination() && !allowsLocationShare();
    }

    public void startInAppNavigation(String destinationReference) {
        Objects.requireNonNull(destinationReference);
        if (!allowsNavigationDestination()) {
            throw new NavigationConsentException(Failure.NAVIGATION_DESTINATION_CONSENT_REQUIRED);
        }
    }

    public void publishLocationShare(String destinationReference) {
        Objects.requireNonNull(destinationReference);
        if (!allowsLocationShare()) {
            throw new NavigationConsentException(Failure.LOCATION_SHARE_CONSENT_REQUIRED);
        }
    }

    public void sharePayloadFromDestination(String destinationReference) {
        Objects.requireNonNull(destinationReference);
        throw new NavigationConsentException(Failure.DESTINATION_IS_NOT_SHARE_PAYLOAD);
    }

    public static void split(NavigationDestinationConsent navigation, NavigationDestinationConsent share) {
        if (!navigation.allowsNavigationDestination() || navigation.allowsLocationShare()) {
            throw new NavigationConsentException(Failure.PURPOSE_MISMATCH);
        }
        if (!share.allowsLocationShare() || share.allowsNavigationDestination()) {
            throw new NavigationConsentException(Failure.PURPOSE_MISMATCH);
        }
    }

    public static final class NavigationConsentException extends IllegalStateException {
        public final Failure code;

        NavigationConsentException(Failure code) {
            super(code.name());
            this.code = code;
        }
    }
}
