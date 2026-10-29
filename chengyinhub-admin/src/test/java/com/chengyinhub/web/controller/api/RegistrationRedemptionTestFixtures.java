package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.registration.RegistrationRedemptionApplication;
import org.springframework.test.util.ReflectionTestUtils;

/** Explicit fixture ownership only: callers still supply every original leaf/mock/real service.
 * Never copies controller fields or mocks the application admission/result. */
public final class RegistrationRedemptionTestFixtures {
    private RegistrationRedemptionTestFixtures() { }

    public static RegistrationRedemptionApplication application(ApiRegistrationController controller) {
        RegistrationRedemptionApplication application = (RegistrationRedemptionApplication)
                ReflectionTestUtils.getField(controller, "registrationRedemptionApplication");
        if (application == null) {
            application = new RegistrationRedemptionApplication();
            ReflectionTestUtils.setField(controller, "registrationRedemptionApplication", application);
        }
        return application;
    }
}
