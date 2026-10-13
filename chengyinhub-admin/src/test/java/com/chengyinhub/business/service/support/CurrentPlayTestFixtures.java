package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 非资格测试的明确已付原对象夹具；资格负控须覆盖这些默认事实，不改生产门禁。 */
public final class CurrentPlayTestFixtures {
    private CurrentPlayTestFixtures() { }
    public static void install(CmsActivityMapper activities,CmsRegistrationMapper registrations) {
        AtomicReference<CmsRegistration> selected=new AtomicReference<>();
        when(activities.selectCmsActivityByIdForUpdate(anyLong())).thenAnswer(call -> activities.selectCmsActivityById(call.getArgument(0)));
        when(registrations.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(call -> {
            CmsRegistration probe=call.getArgument(0),row=new CmsRegistration();row.setId(9000001L);
            row.setMemberId(probe.getMemberId());row.setOwnerType(probe.getOwnerType());row.setOwnerId(probe.getOwnerId());
            row.setPaymentStatus(2);row.setRegistrationStatus(2);row.setPurchaseKind(1);
            selected.set(row);return Collections.singletonList(row);
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(9000001L)).thenAnswer(call -> selected.get());
    }
}
