package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.support.CurrentPlayRegistration;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CurrentRegistrationStubsTest {
    @Test void interleavedMembersReadTheirOriginalRegistrationById() {
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(registrations);
        // 确定性复现：A 查列表、B 查列表、A 再按原 ID 锁读，无需等待随机线程调度。
        CmsRegistration first = query(registrations, 7L, 2L, 9L);
        CmsRegistration second = query(registrations, 9L, 2L, 9L);
        assertTrue(CurrentPlayRegistration.currentOwner(
                registrations.selectCmsRegistrationByIdForUpdate(first.getId()), 7L, 2L, 9L));
        assertTrue(CurrentPlayRegistration.currentOwner(
                registrations.selectCmsRegistrationByIdForUpdate(second.getId()), 9L, 2L, 9L));
        assertNotEquals(first.getId(), second.getId());
    }

    @Test void ownerTypeAndOwnerIdRemainIndependentAndRepeatedQueriesKeepTheOriginalId() {
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(registrations);
        CmsRegistration activity = query(registrations, 7L, 2L, 9L);
        CmsRegistration otherActivity = query(registrations, 7L, 2L, 10L);
        CmsRegistration topic = query(registrations, 7L, 1L, 9L);
        assertNotEquals(activity.getId(), otherActivity.getId());
        assertNotEquals(activity.getId(), topic.getId());
        assertNotEquals(otherActivity.getId(), topic.getId());
        assertSame(activity, registrations.selectCmsRegistrationByIdForUpdate(activity.getId()));
        assertSame(otherActivity, registrations.selectCmsRegistrationByIdForUpdate(otherActivity.getId()));
        assertSame(topic, registrations.selectCmsRegistrationByIdForUpdate(topic.getId()));
        assertEquals(activity.getId(), query(registrations, 7L, 2L, 9L).getId());
        assertTrue(CurrentPlayRegistration.currentOwner(otherActivity, 7L, 2L, 10L));
        assertTrue(CurrentPlayRegistration.currentOwner(topic, 7L, 1L, 9L));
    }

    @Test void unknownIdsAndNullRestubbingProbeDoNotCreateCurrentRegistrations() {
        CmsRegistrationMapper registrations = mock(CmsRegistrationMapper.class);
        CurrentRegistrationStubs.currentFromProbe(registrations);
        assertNull(registrations.selectCmsRegistrationByIdForUpdate(1L));
        assertNull(registrations.selectCmsRegistrationByIdForUpdate(null));
        assertTrue(registrations.selectCmsRegistrationList(null).isEmpty());
        CmsRegistration original = query(registrations, 7L, 2L, 9L);
        assertEquals(Long.valueOf(1L), original.getId());
        assertEquals(Integer.valueOf(2), original.getRegistrationStatus());
        assertEquals(Integer.valueOf(2), original.getPaymentStatus());
        assertNull(registrations.selectCmsRegistrationByIdForUpdate(Long.MAX_VALUE));
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertTrue(registrations.selectCmsRegistrationList(probe(7L, 2L, 9L)).isEmpty());
        assertSame(original, registrations.selectCmsRegistrationByIdForUpdate(original.getId()));
    }

    private static CmsRegistration query(CmsRegistrationMapper registrations, Long memberId, Long ownerType, Long ownerId) {
        return registrations.selectCmsRegistrationList(probe(memberId, ownerType, ownerId)).get(0);
    }

    private static CmsRegistration probe(Long memberId, Long ownerType, Long ownerId) {
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId); probe.setOwnerType(ownerType); probe.setOwnerId(ownerId);
        return probe;
    }
}
