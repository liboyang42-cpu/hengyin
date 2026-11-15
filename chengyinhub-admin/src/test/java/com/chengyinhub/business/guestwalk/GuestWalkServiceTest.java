package com.chengyinhub.business.guestwalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.guestwalk.mapper.GuestWalkMapper;
import com.chengyinhub.business.locationtrust.LocationTrustService;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.impl.RoamFogCellService;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.exception.ServiceException;

class GuestWalkServiceTest {
    static final Long MEMBER = 7L;
    static final long NOW = GuestWalkPolicyTest.NOW;

    GuestWalkMapper mapper;
    RoamMapper roam;
    RoamLocationConsentService consent;
    IGrowthService growth;
    RoamFogCellService fog;
    GuestWalkService service;

    @BeforeEach
    void setUp() {
        mapper = mock(GuestWalkMapper.class);
        roam = mock(RoamMapper.class);
        consent = mock(RoamLocationConsentService.class);
        growth = mock(IGrowthService.class);
        fog = mock(RoamFogCellService.class);
        when(roam.lockClaimOwner(MEMBER)).thenReturn(MEMBER);
        doAnswer(inv -> { ((RoamSession) inv.getArgument(0)).setId(55L); return 1; }).when(roam).insertSession(any());
        when(roam.batchInsertTile(eq(MEMBER), eq(55L), anyList(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(2)).size());
        when(roam.updateSessionFinish(any())).thenReturn(1);
        when(roam.saveFinishResult(eq(55L), eq(MEMBER), anyString())).thenReturn(1);
        service = new GuestWalkService(mapper, roam, consent, growth, fog, new LocationTrustService(), true);
    }

    @Test
    void creditsCellsAsAFinishedWalkAndTheFirstWalkXpOnce() {
        String body = GuestWalkPolicyTest.walk(121, 1.4, 60_000L);
        int sent = GuestWalkPolicy.parse(body).cells().size();
        Map<String, Object> data = service.save(MEMBER, body, NOW);
        assertEquals("55", data.get("sessionId"));
        assertEquals(sent, data.get("newlyRevealed"));
        assertEquals(Math.min(200, sent), data.get("xp"));
        assertEquals(false, data.get("duplicate"));
        assertFalse(data.containsKey("uncredited"));
        verify(consent).requireCurrentAgreement(MEMBER);
        verify(growth).addExp(MEMBER, Math.min(200, sent), GuestWalkService.XP_REASON);
        verify(fog).record(eq(MEMBER), anyList(), any());
        verify(roam).saveSessionTrack(eq(55L), eq(MEMBER), anyString());
        ArgumentCaptor<RoamSession> finished = ArgumentCaptor.forClass(RoamSession.class);
        verify(roam).updateSessionFinish(finished.capture());
        assertEquals(0, finished.getValue().getPoisFound());
        verify(mapper).insert(eq(GuestWalkPolicyTest.INSTALL), eq(GuestWalkPolicyTest.WALK), eq(MEMBER), eq(55L), any(), any(),
                eq(sent), eq(sent), eq(Math.min(200, sent)), eq(0), any(), anyString());
    }

    @Test
    void retryOfTheSameWalkReplaysTheFirstResult() {
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", MEMBER);
        row.put("walkId", GuestWalkPolicyTest.WALK);
        row.put("resultJson", "{\"sessionId\":\"55\",\"newlyRevealed\":12,\"xp\":12}");
        when(mapper.selectByInstall(GuestWalkPolicyTest.INSTALL)).thenReturn(row);
        Map<String, Object> data = service.save(MEMBER, GuestWalkPolicyTest.walk(30, 1.4, 60_000L), NOW);
        assertEquals(true, data.get("duplicate"));
        assertEquals(12, data.get("xp"));
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
        verify(roam, never()).insertSession(any());
        verify(mapper, never()).insert(anyString(), anyString(), anyLong(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), anyString());
    }

    @Test
    void anInstallSavesOneWalkOnly() {
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", 99L);
        row.put("walkId", GuestWalkPolicyTest.WALK);
        when(mapper.selectByInstall(GuestWalkPolicyTest.INSTALL)).thenReturn(row);
        GuestWalkService.Refused refused = assertThrows(GuestWalkService.Refused.class,
                () -> service.save(MEMBER, GuestWalkPolicyTest.walk(30, 1.4, 60_000L), NOW));
        assertEquals(GuestWalkService.CODE_INSTALL_USED, refused.code());
        assertFalse(refused.retryable());
    }

    @Test
    void aMemberReceivesOneGuestWalkOnly() {
        when(mapper.selectByMember(MEMBER)).thenReturn(Map.of("memberId", MEMBER));
        GuestWalkService.Refused refused = assertThrows(GuestWalkService.Refused.class,
                () -> service.save(MEMBER, GuestWalkPolicyTest.walk(30, 1.4, 60_000L), NOW));
        assertEquals(GuestWalkService.CODE_MEMBER_USED, refused.code());
        verify(roam, never()).insertSession(any());
    }

    @Test
    void missingRoamLocationConsentIsARetryableRefusal() {
        doThrow(new ServiceException("请先明确同意漫游定位")).when(consent).requireCurrentAgreement(MEMBER);
        GuestWalkService.Refused refused = assertThrows(GuestWalkService.Refused.class,
                () -> service.save(MEMBER, GuestWalkPolicyTest.walk(30, 1.4, 60_000L), NOW));
        assertEquals(GuestWalkService.CODE_CONSENT_REQUIRED, refused.code());
        assertTrue(refused.retryable());
        verify(roam, never()).insertSession(any());
    }

    @Test
    void switchedOffRefusesAndStoresNothing() {
        GuestWalkService off = new GuestWalkService(mapper, roam, consent, growth, fog, new LocationTrustService(), false);
        GuestWalkService.Refused refused = assertThrows(GuestWalkService.Refused.class,
                () -> off.save(MEMBER, GuestWalkPolicyTest.walk(30, 1.4, 60_000L), NOW));
        assertEquals(GuestWalkService.CODE_DISABLED, refused.code());
        verify(roam, never()).lockClaimOwner(anyLong());
    }

    @Test
    void simulatedWalkIsRecordedButCreditsNothing() {
        Map<String, Object> data = service.save(MEMBER,
                GuestWalkPolicyTest.walk(30, 1.4, 60_000L, i -> ",\"simulated\":true"), NOW);
        assertEquals(true, data.get("uncredited"));
        assertEquals("SIMULATED", data.get("trustCode"));
        assertNull(data.get("sessionId"));
        assertEquals(0, data.get("xp"));
        verify(roam, never()).insertSession(any());
        verify(growth, never()).addExp(anyLong(), anyInt(), anyString());
        verify(mapper).insert(anyString(), anyString(), eq(MEMBER), isNull(), any(), any(), anyInt(), eq(0), eq(0), eq(1),
                eq("SIMULATED"), anyString());
    }
}
