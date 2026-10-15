package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubWaitlistMaintenanceCursorTest {
    @Test void poisonClaimedPrefixCannotStarveOriginalLaterPaidOrdersAcrossRounds() {
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        ClubWaitlistServiceImpl service = new ClubWaitlistServiceImpl(mapper, mock(OmsTicketMapper.class),
                mock(IClubParticipationGuard.class), new ClubWaitlistTokenCodec("cursor-fixture"));
        Date now = new Date(); when(mapper.selectWaitlistMaintenanceMaxId()).thenReturn(600L);
        when(mapper.selectWaitlistMaintenancePage(eq(1), eq(now), anyLong(), eq(600L), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(2); int limit = call.getArgument(4);
            List<Map<String,Object>> rows = new ArrayList<>();
            for(long id=after+1;id<=Math.min(600,after+limit);id++) {
                Map<String,Object> row=new HashMap<>(); row.put("scanId",id); row.put("targetId",id+1000); rows.add(row);
            }
            return rows;
        });
        when(mapper.convertClaimedByRegistration(anyLong())).thenThrow(new IllegalStateException("one source unavailable"));
        assertEquals(0,service.maintain(now)); verify(mapper,never()).convertClaimedByRegistration(1501L);
        assertEquals(0,service.maintain(now)); verify(mapper).convertClaimedByRegistration(1600L);
        verify(mapper,times(1)).convertClaimedByRegistration(1001L);
        verify(mapper,times(2)).selectWaitlistMaintenanceMaxId();
    }
    @Test void oneDomainPageFailureDoesNotBlockLaterDomain() {
        ClubEventOpsMapper mapper = mock(ClubEventOpsMapper.class);
        ClubWaitlistServiceImpl service = new ClubWaitlistServiceImpl(mapper,mock(OmsTicketMapper.class),
                mock(IClubParticipationGuard.class),new ClubWaitlistTokenCodec("cursor-fixture"));
        Date now = new Date(); when(mapper.selectWaitlistMaintenanceMaxId()).thenReturn(1L);
        when(mapper.selectWaitlistMaintenancePage(0,now,0L,1L,100)).thenThrow(new IllegalStateException("page unavailable"));
        Map<String,Object> row = new HashMap<>();row.put("scanId",1L);row.put("targetId",1001L);
        when(mapper.selectWaitlistMaintenancePage(1,now,0L,1L,100)).thenReturn(Collections.singletonList(row));
        when(mapper.convertClaimedByRegistration(1001L)).thenReturn(1);
        assertEquals(1,service.maintain(now)); verify(mapper).convertClaimedByRegistration(1001L);
    }
}
