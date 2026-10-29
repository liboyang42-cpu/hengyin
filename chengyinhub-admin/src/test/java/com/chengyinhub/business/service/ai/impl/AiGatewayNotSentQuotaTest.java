package com.chengyinhub.business.service.ai.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import com.chengyinhub.business.service.ai.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiGatewayNotSentQuotaTest
{
    @Test void exactNotSentCompensatesOnlyOriginalReceiptAfterModelStageAndMapsToUnavailable()
    {
        AiNotSentGatewayFixture f = new AiNotSentGatewayFixture(7L);
        when(f.model.chat(anyString(), anyString())).thenThrow(new AiProviderNotSentException());
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> f.gateway.generate(7L, "club", "merchant_plan", "system", "user"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        assertFalse(error instanceof AiProviderOutcomeUnknownException);
        String receipt = f.originalReceipt(7L, "merchant_plan", "gen");
        var order = inOrder(f.recovery, f.model);
        order.verify(f.recovery).stage(receipt, "MODEL_STARTED");
        order.verify(f.model).chat("system", "user");
        order.verify(f.recovery).requireRefund(receipt, "SERVICE_FAILURE");
        verify(f.recovery, never()).retain(anyString(), anyString());
        verify(f.model, times(1)).chat(anyString(), anyString());
    }

    @Test void ordinaryFailuresAndWrappedNotSentNeverAcquireTheTrustedClassification()
    {
        for (RuntimeException failure : List.of(new IllegalStateException("provider timeout"),
                new RuntimeException(new IOException("connection failed")), new CancellationException("cancelled"),
                new com.chengyinhub.common.utils.http.HttpRequestException("status failure", 429, false),
                new IllegalStateException("NOT_SENT"), new RuntimeException(new AiProviderNotSentException())))
        {
            AiNotSentGatewayFixture f = new AiNotSentGatewayFixture(7L);
            when(f.model.chat(anyString(), anyString())).thenThrow(failure);
            assertThrows(AiProviderOutcomeUnknownException.class,
                    () -> f.gateway.generate(7L, "club", "merchant_plan", "system", "user"));
            String receipt = f.originalReceipt(7L, "merchant_plan", "gen");
            verify(f.recovery).stage(receipt, "MODEL_STARTED");
            verify(f.recovery, never()).requireRefund(anyString(), anyString());
            verify(f.recovery, never()).retain(anyString(), anyString());
            verify(f.model, times(1)).chat(anyString(), anyString());
        }
    }

    @Test void playerDraftNotSentLeavesItsSeparateOriginalPoolToCreator()
    {
        AiNotSentGatewayFixture f = new AiNotSentGatewayFixture(7L);
        when(f.model.chat(anyString(), anyString())).thenThrow(new AiProviderNotSentException());
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> f.gateway.generate(7L, "player", "theme_draft", "system", "user"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, error.getReason());
        verifyNoInteractions(f.redis, f.recovery);
        verify(f.model, times(1)).chat(anyString(), anyString());
    }

    @Test void receivedResultRetentionFailureCannotBeTreatedAsUnsentEvenIfItThrowsThatClass()
    {
        AiNotSentGatewayFixture f = new AiNotSentGatewayFixture(7L);
        when(f.model.chat(anyString(), anyString())).thenReturn("original answer");
        doThrow(new AiProviderNotSentException()).when(f.recovery).retain(anyString(), eq("SUCCESS"));
        assertThrows(AiProviderResultReceivedException.class,
                () -> f.gateway.generate(7L, "club", "merchant_plan", "system", "user"));
        verify(f.recovery, never()).requireRefund(anyString(), anyString());
    }
}
