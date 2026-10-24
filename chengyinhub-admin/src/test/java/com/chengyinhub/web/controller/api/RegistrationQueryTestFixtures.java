package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.registration.RegistrationQueryApplication;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only assembly: each old explicit leaf is injected directly at the real query owner.
 * Never copies controller fields or invents successful mocks/default dependencies.
 * Mid-test leaf replacement updates that same application, without a stale snapshot. */
public final class RegistrationQueryTestFixtures {
    private RegistrationQueryTestFixtures() { }
    public static RegistrationQueryApplication application(ApiRegistrationController controller) {
        RegistrationQueryApplication application = (RegistrationQueryApplication)
                ReflectionTestUtils.getField(controller, "registrationQueryApplication");
        if (application == null) {
            application = new RegistrationQueryApplication();
            ReflectionTestUtils.setField(controller, "registrationQueryApplication", application);
        }
        return application;
    }
    public static <T extends ApiRegistrationController> T wire(T controller) {
        application(controller);
        return controller;
    }
}
