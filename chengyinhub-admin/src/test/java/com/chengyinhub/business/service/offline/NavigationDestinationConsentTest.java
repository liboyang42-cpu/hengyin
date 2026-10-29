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

    @Test
    void unusedConsentDoesNotOccupyShareThenUniqueShareGrantApplies() {
        NavigationDestinationConsent consent = new NavigationDestinationConsent();
        assertFalse(consent.allowsNavigationDestination());
        assertFalse(consent.allowsLocationShare());
        assertEquals(NavigationDestinationConsent.Failure.NAVIGATION_DESTINATION_CONSENT_REQUIRED,
                assertThrows(NavigationDestinationConsent.NavigationConsentException.class,
                        () -> consent.startInAppNavigation("shop-door")).code);
        assertFalse(consent.allowsNavigationDestination(), "未授权 start 不得占导航同意");
        assertFalse(consent.allowsLocationShare());

        consent.grant(NavigationDestinationConsent.Purpose.IN_APP_NAVIGATION_DESTINATION);
        assertTrue(consent.allowsNavigationDestination());
        assertFalse(consent.allowsLocationShare(), "导航同意不得占位置分享");
        consent.startInAppNavigation("shop-door");
        assertEquals(NavigationDestinationConsent.Failure.LOCATION_SHARE_CONSENT_REQUIRED,
                assertThrows(NavigationDestinationConsent.NavigationConsentException.class,
                        () -> consent.publishLocationShare("shop-door")).code);
        assertFalse(consent.allowsLocationShare(), "分享失败不得占位置分享");

        consent.grant(NavigationDestinationConsent.Purpose.LOCATION_SHARE);
        assertTrue(consent.allowsLocationShare());
        consent.publishLocationShare("shop-door");
        NavigationDestinationConsent restarted = new NavigationDestinationConsent();
        assertFalse(restarted.allowsNavigationDestination());
        assertFalse(restarted.allowsLocationShare(), "新实例不得继承同意");
    }
}
