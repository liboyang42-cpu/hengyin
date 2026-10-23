package com.chengyinhub.business.service.offline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NavigationDestinationConsentTest {

    @Test
    void navigationGrantDoesNotImplyShare() {
        NavigationDestinationConsent consent = new NavigationDestinationConsent();
        consent.grant(NavigationDestinationConsent.Purpose.IN_APP_NAVIGATION_DESTINATION);
        assertTrue(consent.allowsNavigationDestination());
        assertFalse(consent.allowsLocationShare());
        assertFalse(consent.impliesOtherPurpose(NavigationDestinationConsent.Purpose.IN_APP_NAVIGATION_DESTINATION));
        assertEquals(NavigationDestinationConsent.Failure.LOCATION_SHARE_CONSENT_REQUIRED,
                assertThrows(NavigationDestinationConsent.NavigationConsentException.class,
                        () -> consent.publishLocationShare("gate")).code);
        NavigationDestinationConsent share = new NavigationDestinationConsent();
        share.grant(NavigationDestinationConsent.Purpose.LOCATION_SHARE);
        share.grant(NavigationDestinationConsent.Purpose.IN_APP_NAVIGATION_DESTINATION);
        assertEquals(NavigationDestinationConsent.Failure.PURPOSE_MISMATCH,
                assertThrows(NavigationDestinationConsent.NavigationConsentException.class,
                        () -> NavigationDestinationConsent.split(consent, share)).code);
    }
}
