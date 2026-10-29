package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** Unit doubles only: current reads use the fixture's explicit original rows.
 * This neither creates scope/financial evidence nor models SQL locking. Install before
 * a case's deliberate stale/current-row overrides; real MyBatis mappers are prohibited. */
public final class RegistrationScopeFixture {
    private RegistrationScopeFixture() { }

    public static void currentReads(CmsRegistrationMapper registrations,
                                    CmsActivityMapper activities, CmsTopicMapper topics) {
        if (registrations != null) {
            requireMock(registrations);
            doAnswer(call -> registrations.selectCmsRegistrationById(call.getArgument(0)))
                    .when(registrations).selectCmsRegistrationByIdForUpdate(anyLong());
        }
        if (activities != null) {
            requireMock(activities);
            doAnswer(call -> activities.selectCmsActivityById(call.getArgument(0)))
                    .when(activities).selectCmsActivityByIdForUpdate(anyLong());
        }
        if (topics != null) {
            requireMock(topics);
            doAnswer(call -> topics.selectCmsTopicById(call.getArgument(0)))
                    .when(topics).selectCmsTopicByIdForUpdate(anyLong());
        }
    }

    private static void requireMock(Object mapper) {
        if (!mockingDetails(mapper).isMock()) {
            throw new IllegalArgumentException("current-read adapter requires an explicit unit Mapper mock");
        }
    }
}
