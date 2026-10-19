package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** A包当前资格改为「快照列表 + 原ID锁读当前行」；玩法单测按查询条件回一张当前有效的原报名。 */
public final class CurrentRegistrationStubs {
    private CurrentRegistrationStubs() { }

    public static void currentFromProbe(CmsRegistrationMapper registrations) {
        AtomicReference<CmsRegistration> last = new AtomicReference<>();
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> {
            CmsRegistration probe = call.getArgument(0);
            if (probe == null) return new ArrayList<CmsRegistration>(); // when(...) 重设 stub 时的空参调用
            CmsRegistration row = new CmsRegistration();
            row.setId(1L); row.setMemberId(probe.getMemberId()); row.setOwnerType(probe.getOwnerType());
            row.setOwnerId(probe.getOwnerId()); row.setRegistrationStatus(2); row.setPaymentStatus(2);
            last.set(row);
            List<CmsRegistration> rows = new ArrayList<>();
            rows.add(row);
            return rows;
        });
        when(registrations.selectCmsRegistrationByIdForUpdate(1L)).thenAnswer(call -> last.get());
    }
}
