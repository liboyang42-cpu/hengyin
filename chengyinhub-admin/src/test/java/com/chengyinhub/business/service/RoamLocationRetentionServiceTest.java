package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.service.impl.RoamLocationRetentionService;
import com.chengyinhub.business.support.RoamLocationRetention;
import java.util.Date;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoamLocationRetentionServiceTest {
    @Test
    void exactBoundaryClearsOnlyGeometryAndRequiresOneRow() {
        RoamMapper mapper = mock(RoamMapper.class);
        RoamShareSnapshotMapper shares = mock(RoamShareSnapshotMapper.class);
        RoamSession row = new RoamSession(); row.setId(1L); row.setMemberId(9L);
        row.setStatus(1); row.setFinishedAt(new Date(1000));
        row.setFinishResultJson("{\"totalXp\":7,\"distanceM\":30,\"track\":[{\"lat\":1}],\"nested\":{\"lng\":2,\"shops\":3}}");
        when(mapper.selectOwnedSessionForUpdate(1L,9L)).thenReturn(row);
        Date cutoff = new Date(1000);
        String expected = "{\"totalXp\":7,\"distanceM\":30,\"nested\":{\"shops\":3}}";
        when(mapper.clearEndedSessionLocation(1L,9L,cutoff,expected)).thenReturn(1);
        RoamLocationRetentionService service = new RoamLocationRetentionService(mapper,shares);
        assertEquals(1,service.clear(1L,9L,cutoff));
        org.mockito.InOrder locks=inOrder(mapper);
        locks.verify(mapper).lockClaimOwner(9L);
        locks.verify(mapper).selectOwnedSessionForUpdate(1L,9L);
        locks.verify(mapper).requireClaimKey();
        verify(shares).deleteBySession(1L,9L);
        verify(mapper).clearEndedSessionLocation(1L,9L,cutoff,expected);
        when(mapper.clearEndedSessionLocation(1L,9L,cutoff,expected)).thenReturn(0);
        assertThrows(IllegalStateException.class,() -> service.clear(1L,9L,cutoff));
    }
    @Test
    void confirmedEndedUnknownTimeClearsGeometryAndKeepsStatistics() {
        RoamMapper mapper=mock(RoamMapper.class);RoamShareSnapshotMapper shares=mock(RoamShareSnapshotMapper.class);
        RoamSession row=new RoamSession();row.setStatus(1);row.setFinishedAt(null);
        row.setFinishResultJson("{\"totalXp\":7,\"track\":[{\"lat\":1}]}");
        when(mapper.selectOwnedSessionForUpdate(1L,9L)).thenReturn(row);
        Date cutoff=new Date(1000);
        when(mapper.clearEndedSessionLocation(1L,9L,cutoff,"{\"totalXp\":7}")).thenReturn(1);
        assertEquals(1,new RoamLocationRetentionService(mapper,shares).clear(1L,9L,cutoff));
        verify(mapper).requireClaimKey();verify(shares).deleteBySession(1L,9L);
        assertNull(row.getFinishedAt());
    }
    @Test
    void fingerprintsAreKeyedScopedAndCannotBeIssuedWithoutKey() {
        new com.chengyinhub.business.security.PiiCryptoService(java.util.Base64.getEncoder().encodeToString(new byte[32]),"test-roam-claim-hmac").register();
        String tile=RoamLocationRetention.claimFingerprint("tile",9L,"wtw3sjq");
        assertEquals(tile,RoamLocationRetention.claimFingerprint("tile",9L,"WTW3SJQ"));
        assertNotEquals(tile,RoamLocationRetention.claimFingerprint("tile",10L,"wtw3sjq"));
        assertNotEquals(tile,RoamLocationRetention.claimFingerprint("poi",9L,"wtw3sjq"));
        assertTrue(tile.matches("[0-9a-f]{64}"));
        new com.chengyinhub.business.security.PiiCryptoService("","").register();
        assertThrows(IllegalStateException.class,()->RoamLocationRetention.claimFingerprint("tile",9L,"wtw3sjq"));
    }
    @Test
    void activeRecentUnknownStatusOrAlreadyClearedDoNotMutate() {
        RoamMapper mapper = mock(RoamMapper.class); RoamShareSnapshotMapper shares = mock(RoamShareSnapshotMapper.class);
        RoamLocationRetentionService service = new RoamLocationRetentionService(mapper,shares);
        RoamSession row = new RoamSession(); when(mapper.selectOwnedSessionForUpdate(1L,9L)).thenReturn(row);
        Date cutoff = new Date(1000);
        row.setStatus(0); row.setFinishedAt(new Date(1)); assertEquals(0,service.clear(1L,9L,cutoff));
        row.setStatus(null); row.setFinishedAt(null); assertEquals(0,service.clear(1L,9L,cutoff));
        row.setStatus(1); row.setFinishedAt(new Date(1001)); assertEquals(0,service.clear(1L,9L,cutoff));
        row.setFinishedAt(new Date(1000)); row.setLocationClearedAt(new Date()); assertEquals(0,service.clear(1L,9L,cutoff));
        verifyNoInteractions(shares);
        verify(mapper, never()).clearEndedSessionLocation(any(),any(),any(),any());
        assertTrue(RoamLocationRetention.expired(new Date(1000),new Date(1000+RoamLocationRetention.THIRTY_DAYS_MILLIS)));
        assertFalse(RoamLocationRetention.expired(new Date(1001),new Date(1000+RoamLocationRetention.THIRTY_DAYS_MILLIS)));
    }
}
