package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IWxContentSecurityService.CallbackResult;

class ApiContentSecurityControllerTest
{
    private IWxContentSecurityService service;
    private ApiContentSecurityController controller;

    @BeforeEach
    void setUp()
    {
        service = mock(IWxContentSecurityService.class);
        controller = new ApiContentSecurityController();
        ReflectionTestUtils.setField(controller, "wxContentSecurityService", service);
    }

    @Test
    void getOnlyMapsServiceVerificationAndNeverOwnsCryptoOrConfiguration()
    {
        when(service.verifyMediaCheckCallback("sig", "ts", "nonce")).thenReturn(CallbackResult.SUCCESS);

        ResponseEntity<String> response = controller.verify("sig", "ts", "nonce", "challenge");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("challenge", response.getBody());
        verify(service).verifyMediaCheckCallback("sig", "ts", "nonce");
    }

    @Test
    void getMissingTokenFailsClosedWithoutEchoingChallenge()
    {
        when(service.verifyMediaCheckCallback("sig", "ts", "nonce"))
                .thenReturn(CallbackResult.TOKEN_UNAVAILABLE);

        ResponseEntity<String> response = controller.verify("sig", "ts", "nonce", "challenge");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("unavailable", response.getBody());
    }

    @Test
    void postDelegatesRawHttpInputsToTheSingleServiceEntry()
    {
        String body = "{\"Event\":\"wxa_media_check\"}";
        when(service.processMediaCheckCallback("sig", "ts", "nonce", body)).thenReturn(CallbackResult.SUCCESS);

        ResponseEntity<String> response = controller.onResult("sig", "ts", "nonce", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody());
        verify(service).processMediaCheckCallback("sig", "ts", "nonce", body);
    }

    @Test
    void postDoesNotTurnProtocolOrReplayRejectionIntoBusinessSuccess()
    {
        when(service.processMediaCheckCallback("bad", "ts", "nonce", "body"))
                .thenReturn(CallbackResult.FORBIDDEN);
        when(service.processMediaCheckCallback("sig", "old", "nonce", "body"))
                .thenReturn(CallbackResult.INVALID_TIMESTAMP);
        when(service.processMediaCheckCallback("sig", "ts", "replay", "body"))
                .thenReturn(CallbackResult.REPLAYED);

        assertEquals(HttpStatus.FORBIDDEN,
                controller.onResult("bad", "ts", "nonce", "body").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.onResult("sig", "old", "nonce", "body").getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                controller.onResult("sig", "ts", "replay", "body").getStatusCode());
    }

    @Test
    void processingFailureIsNotAcknowledgedAsSuccess()
    {
        when(service.processMediaCheckCallback("sig", "ts", "nonce", "body"))
                .thenReturn(CallbackResult.PROCESSING_FAILED);

        ResponseEntity<String> response = controller.onResult("sig", "ts", "nonce", "body");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("processing failed", response.getBody());
    }
}
