package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.ClubOriginalReceiptMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClubRoleOriginalReceiptsTest {
    static final String RID = "dddddddd-1111-4111-8111-111111111111";

    Map<String, Object> assignBody() {
        Map<String, Object> b = new HashMap<>(Map.of("clubId", 7, "targetMemberId", 12, "roleCode", "CLUB_OPERATOR", "requestId", RID));
        b.put("expiresAtEpochMs", null); return b;
    }

    @Test void rejectsShapeDriftBeforeReading() {
        ClubOriginalReceiptMapper m = mock(ClubOriginalReceiptMapper.class);
        ClubRoleOriginalReceipts r = new ClubRoleOriginalReceipts(m);
        Map<String, Object> b = assignBody(); b.remove("expiresAtEpochMs");
        assertEquals(400, r.assign(9, b).get("code"));
        b = assignBody(); b.put("roleCode", "EVENT_LEAD");
        assertEquals(400, r.assign(9, b).get("code"), "无 activityId 时只允许俱乐部级角色");
        b = assignBody(); b.put("expiresAtEpochMs", 5);
        assertEquals(400, r.assign(9, b).get("code"));
        verifyNoInteractions(m);
    }

    @Test void expiringAssignmentIsNotAnAppReceipt() {
        ClubOriginalReceiptMapper m = mock(ClubOriginalReceiptMapper.class);
        when(m.selectAudit(7L, "CLUB_ROLE_ASSIGNED", RID)).thenReturn(Map.of("id", 4L, "actorMemberId", 9L, "targetId", 12L, "relationId", 3L));
        when(m.selectAssignment(3L)).thenReturn(Map.of("clubId", 7L, "targetMemberId", 12L, "roleCode", "CLUB_OPERATOR", "scopeType", "CLUB", "scopeId", 7L, "expiresAt", new java.util.Date()));
        assertEquals(409, new ClubRoleOriginalReceipts(m).assign(9, assignBody()).get("code"));
    }
}
