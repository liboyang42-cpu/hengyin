package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiRegistrationDynamicSelectionCodeTest {
    private static final String SECRET = "unit-dynamic-code-secret";
    private ApiRegistrationController controller;
    private ICmsRegistrationService registrations;

    @BeforeEach
    void setUp() {
        controller = new ApiRegistrationController();
        registrations = mock(ICmsRegistrationService.class);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationRedemptionTestFixtures.application(controller), "dynSecret", SECRET);
    }

    @Test
    void validDynamicCodeStillResolvesOnlyItsRegistration() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(88L);
        when(registrations.selectCmsRegistrationById(88L)).thenReturn(registration);
        String code = VerifyDynCodeUtil.issue(88L, "topic", System.currentTimeMillis(), 60_000L, 9L, SECRET);
        assertSame(registration, ReflectionTestUtils.invokeMethod(RegistrationRedemptionTestFixtures.application(controller), "resolveRegistrationByScanCode", code));
        verify(registrations).selectCmsRegistrationById(88L);
        verifyNoMoreInteractions(registrations);
    }

    @Test
    void expiredOrMalformedDynamicSelectionNeverFallsBackToLegacyAndKeepsActualReason() {
        String expired = VerifyDynCodeUtil.issue(88L, "topic", System.currentTimeMillis() - 60_000L, 1_000L, 9L, SECRET);
        ServiceException timeout = assertThrows(ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(RegistrationRedemptionTestFixtures.application(controller), "resolveRegistrationByScanCode", expired));
        assertEquals("已过期", timeout.getMessage());
        ServiceException malformed = assertThrows(ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(RegistrationRedemptionTestFixtures.application(controller), "resolveRegistrationByScanCode", "v1.malformed"));
        assertEquals("格式错误", malformed.getMessage());
        verifyNoInteractions(registrations);
    }

    @Test
    void legacyStaticCodeKeepsItsExistingLookup() {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(99L);
        when(registrations.selectByverificationCode("legacy-static-code")).thenReturn(registration);
        assertSame(registration, ReflectionTestUtils.invokeMethod(RegistrationRedemptionTestFixtures.application(controller), "resolveRegistrationByScanCode", "legacy-static-code"));
        verify(registrations).selectByverificationCode("legacy-static-code");
        verifyNoMoreInteractions(registrations);
    }
}
