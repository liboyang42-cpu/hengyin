package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.PhotoCheckIntentRow;
import com.chengyinhub.business.mapper.PhotoCheckIntentMapper;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** New read overload only; the frozen 16 intent units remain unchanged. */
class PhotoCheckIntentRecoveryReadTest {
    private static final String HASH = "a".repeat(64);
    private static final String KEY = "original-key";
    private PhotoCheckIntentMapper mapper;
    private AiGatewayService gateway;
    private PhotoCheckVisionService vision;
    private ConsentGuard consent;
    private AppMediaReferenceGuard media;
    private PlatformTransactionManager transactions;
    private PhotoCheckIntentService service;
    private PhotoCheckIntentRow row;

    @BeforeEach void setup() throws Exception {
        mapper=mock(PhotoCheckIntentMapper.class);gateway=mock(AiGatewayService.class);
        vision=mock(PhotoCheckVisionService.class);consent=mock(ConsentGuard.class);
        media=mock(AppMediaReferenceGuard.class);transactions=mock(PlatformTransactionManager.class);
        service=new PhotoCheckIntentService(mapper,gateway,vision,consent,media,transactions);
        row=new PhotoCheckIntentRow();row.setId(77L);row.setMemberId(7L);row.setSessionId(8L);
        row.setActionKey(KEY);row.setRequestFingerprint(HASH);row.setImageReference("media-asset:42");
        row.setContextJson(new ObjectMapper().writeValueAsString(
                PhotoCheckIntentServiceTest.command(KEY,"media-asset:42",HASH)));
        row.setNonce("c".repeat(32));row.setOriginalDay("20261023");
        row.setReservation("ai:gen:limit:photo:7:20261023|"+row.getNonce());row.setState("PREPARED");
    }
    private void noExternalWork() { verifyNoInteractions(gateway,vision,consent,media,transactions); }

    @Test void exactAbsenceReturnsNullAndOnlyPerformsCurrentKeyRead() {
        assertNull(service.read(7L,8L,KEY,HASH));
        verify(mapper).selectByKeyForUpdate(7L,8L,KEY);verifyNoMoreInteractions(mapper);noExternalWork();
    }
    @Test void lostPrepareResponseRecoversOriginalIdentityAndStoredResultWithoutChangingNonce() {
        when(mapper.selectByKeyForUpdate(7L,8L,KEY)).thenReturn(row);
        PhotoCheckIntentView prepared=service.read(7L,8L,KEY,HASH);
        assertEquals(77L,prepared.intentId());assertEquals(PhotoCheckIntentState.PREPARED,prepared.state());assertNull(prepared.result());
        row.setState("RESULT_READY");row.setProviderState("UNKNOWN");row.setReasonCode("PROVIDER_RESULT_UNKNOWN");
        row.setResultPass(false);row.setResultConfidence(0);row.setResultReason("stored-first-result");row.setResultDegraded(true);
        PhotoCheckIntentView ready=service.read(7L,8L,KEY,HASH);
        assertEquals(prepared.intentId(),ready.intentId());assertEquals(PhotoCheckIntentState.RESULT_READY,ready.state());
        assertTrue(ready.result().isDegraded());assertEquals("stored-first-result",ready.result().getReason());assertEquals("UNKNOWN",ready.providerState());
        assertEquals("c".repeat(32),row.getNonce());assertEquals("ai:gen:limit:photo:7:20261023|"+"c".repeat(32),row.getReservation());
        verify(mapper,times(2)).selectByKeyForUpdate(7L,8L,KEY);verifyNoMoreInteractions(mapper);noExternalWork();
    }
    @Test void conflictingHashIs409AndDifferentOwnerDoesNotDiscoverOriginalIntent() {
        when(mapper.selectByKeyForUpdate(7L,8L,KEY)).thenReturn(row);
        assertEquals(409,assertThrows(ServiceException.class,()->service.read(7L,8L,KEY,"b".repeat(64))).getCode());
        assertNull(service.read(99L,8L,KEY,HASH));
        // A misrouted mapper result must also fail closed instead of exposing another owner.
        when(mapper.selectByKeyForUpdate(99L,8L,KEY)).thenReturn(row);
        assertEquals(409,assertThrows(ServiceException.class,()->service.read(99L,8L,KEY,HASH)).getCode());
        verify(mapper).selectByKeyForUpdate(7L,8L,KEY);verify(mapper,times(2)).selectByKeyForUpdate(99L,8L,KEY);
        verifyNoMoreInteractions(mapper);noExternalWork();
    }
    @Test void databaseFailurePropagatesWithoutPretendingAbsenceOrPreparingReplacement() {
        IllegalStateException failure=new IllegalStateException("injected database unavailable");
        when(mapper.selectByKeyForUpdate(7L,8L,KEY)).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->service.read(7L,8L,KEY,HASH)));
        verify(mapper).selectByKeyForUpdate(7L,8L,KEY);verifyNoMoreInteractions(mapper);noExternalWork();
    }
    @Test void invalidOriginalScopeIs409BeforeDatabaseOrAnyOtherWork() {
        assertEquals(409,assertThrows(ServiceException.class,()->service.read(0L,8L,KEY,HASH)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->service.read(7L,null,KEY,HASH)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->service.read(7L,8L," ",HASH)).getCode());
        assertEquals(409,assertThrows(ServiceException.class,()->service.read(7L,8L,KEY,"invalid")).getCode());
        verifyNoInteractions(mapper);noExternalWork();
    }
}
