package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.business.service.impl.NpcProfileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantNpcIdentityDirectoryTest {
    private final MmsMerchantMapper mapper = mock(MmsMerchantMapper.class);
    private final MerchantNpcIdentityDirectory directory = new MerchantNpcIdentityDirectory(mapper);

    @Test void exactUnfilteredMemberDirectoryLookupAndBusinessClosedIsDisplayOnly() {
        MmsMerchant row = row(); row.setBusinessStatus(0);
        when(mapper.selectMmsMerchantList(any())).thenReturn(Collections.singletonList(row));
        assertEquals(700L, directory.availableMerchantIdForMember(70L));
        ArgumentCaptor<MmsMerchant> probe = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(mapper).selectMmsMerchantList(probe.capture());
        assertEquals(70L, probe.getValue().getMemberId()); assertNull(probe.getValue().getId());
        assertNull(probe.getValue().getStatus()); assertNull(probe.getValue().getAccountStatus()); assertNull(probe.getValue().getDelFlag());
    }

    @Test void foreignNullAndMalformedRowsNeverFallBackToEqualNumericId() {
        for (int kind = 0; kind < 7; kind++) {
            MmsMerchant row = row();
            if (kind == 0) row.setMemberId(71L);
            if (kind == 1) row.setMemberId(null);
            if (kind == 2) row.setId(null);
            if (kind == 3) row.setId(0L);
            if (kind == 4) row.setId(-1L);
            if (kind == 5) row = null;
            when(mapper.selectMmsMerchantList(any())).thenReturn(kind == 6 ? null : Collections.singletonList(row));
            assertNull(directory.availableMerchantIdForMember(70L));
        }
    }

    @Test void invalidInputsNeverBecomeUnboundedDirectoryQueries() {
        assertNull(directory.availableMerchantIdForMember(null)); assertNull(directory.availableMerchantIdForMember(0L));
        assertNull(directory.availableMerchantIdForMember(-1L)); verifyNoInteractions(mapper);
    }

    @Test void directoryStorageFailurePropagatesForReadsButNodePriorityDoesNotNeedDirectory() {
        IllegalStateException unavailable = new IllegalStateException("directory unavailable");
        when(mapper.selectMmsMerchantList(any())).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> directory.availableMerchantIdForMember(70L)));
        NpcProfileService service = new NpcProfileService(); NpcProfileMapper npcs = mock(NpcProfileMapper.class);
        ReflectionTestUtils.setField(service, "npcProfileMapper", npcs);
        ReflectionTestUtils.setField(service, "merchantNpcIdentityDirectory", directory);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> service.resolveForNodeOrMerchant(31L,70L)));
        clearInvocations(mapper);
        NpcProfile npc = new NpcProfile(); npc.setId(902L);
        when(npcs.selectEnabledByScope(4,31L)).thenReturn(Collections.singletonList(npc));
        assertEquals(902L, service.resolveForNodeOrMerchant(31L,70L).getProfileId()); verifyNoInteractions(mapper);
    }

    private static MmsMerchant row() {
        MmsMerchant row = new MmsMerchant(); row.setId(700L); row.setMemberId(70L);
        row.setStatus(1L); row.setAccountStatus(1L); row.setDelFlag(0); return row;
    }
}
