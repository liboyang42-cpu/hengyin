package com.chengyinhub.business.service.ai;

import java.util.concurrent.atomic.AtomicInteger;
import com.chengyinhub.business.service.ConsentGuard;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhotoCheckVisionNotSentQuotaTest
{
    private static final String TOKEN = "ai:gen:limit:photo:7:20200101|" + "a".repeat(32);
    private PhotoCheckVisionService vision;
    private AiGatewayService gateway;
    private LlmClient model;

    @BeforeEach void setup()
    {
        vision = new PhotoCheckVisionService(); gateway = mock(AiGatewayService.class); model = mock(LlmClient.class);
        AppMediaReferenceGuard media = mock(AppMediaReferenceGuard.class);
        when(media.processingUrl("media-asset:42", 7L, "IMAGE")).thenReturn("owned-approved-image");
        VisionImageEncoder encoder = mock(VisionImageEncoder.class);
        when(encoder.encode("owned-approved-image")).thenReturn(VisionImageEncoder.Encoded.ok("data:image/jpeg;base64,AA=="));
        when(gateway.consumePlannedQuota(7L, "photo_check", TOKEN)).thenReturn(TOKEN);
        when(gateway.consumeQuota(7L, "photo_check")).thenReturn(TOKEN);
        when(model.name()).thenReturn("deepseek");
        ReflectionTestUtils.setField(vision, "mediaReferences", media);
        ReflectionTestUtils.setField(vision, "consentGuard", mock(ConsentGuard.class));
        ReflectionTestUtils.setField(vision, "imageEncoder", encoder);
        ReflectionTestUtils.setField(vision, "aiGatewayService", gateway);
        ReflectionTestUtils.setField(vision, "visionClient", model);
    }

    @Test void typedNotSentAfterSnapshotCompensatesOriginalDayTokenAndKeepsDegradedResult()
    {
        when(model.chatVision(anyString(), anyString(), anyString())).thenThrow(new AiProviderNotSentException());
        AtomicInteger snapshots = new AtomicInteger();
        var result = vision.reviewPrepared(7L, "media-asset:42", "original requirement", TOKEN, snapshot -> snapshots.incrementAndGet());
        assertEquals("NOT_SENT", result.providerState());
        assertTrue(result.result().isDegraded()); assertFalse(result.result().isPass());
        assertEquals(1, snapshots.get());
        var order = inOrder(gateway, model);
        order.verify(gateway).stageQuota(7L, "photo_check", TOKEN, "MODEL_STARTED");
        order.verify(model).chatVision(anyString(), anyString(), anyString());
        order.verify(gateway).refundQuota(7L, "photo_check", TOKEN);
        verify(gateway, never()).consumeQuota(any(), any());
        verify(gateway, never()).retainQuota(any(), any(), any(), any());
    }

    @Test void ordinaryFailureAndWrappedMarkerStayUnknownWithoutRefundOrRetry()
    {
        for (RuntimeException failure : java.util.List.of(new IllegalStateException("timeout"),
                new RuntimeException(new java.io.IOException("connection failed")),
                new java.util.concurrent.CancellationException("cancelled"),
                new com.chengyinhub.common.utils.http.HttpRequestException("status failure", 503, false),
                new RuntimeException(new AiProviderNotSentException())))
        {
            setup();
            when(model.chatVision(anyString(), anyString(), anyString())).thenThrow(failure);
            var result = vision.reviewPrepared(7L, "media-asset:42", "original requirement", TOKEN, snapshot -> { });
            assertEquals("UNKNOWN", result.providerState());
            verify(gateway, never()).refundQuota(any(), any(), any());
            verify(model, times(1)).chatVision(anyString(), anyString(), anyString());
        }
    }

    @Test void directReviewUsesTheSameTypedCatchWithoutCreatingAnotherReservation()
    {
        when(model.chatVision(anyString(), anyString(), anyString())).thenThrow(new AiProviderNotSentException());
        assertTrue(vision.review(7L, "media-asset:42", "requirement").isDegraded());
        verify(gateway).consumeQuota(7L, "photo_check");
        verify(gateway).refundQuota(7L, "photo_check", TOKEN);
        verify(gateway, never()).consumePlannedQuota(any(), any(), any());
    }

    @Test void notSentFromRetentionAfterActualAnswerIsNotARefundProof()
    {
        when(model.chatVision(anyString(), anyString(), anyString())).thenReturn("{\"pass\":true,\"confidence\":90,\"reason\":\"ok\"}");
        doThrow(new AiProviderNotSentException()).when(gateway).retainQuota(7L, "photo_check", TOKEN, "PHOTO_RESULT");
        var result = vision.reviewPrepared(7L, "media-asset:42", "requirement", TOKEN, snapshot -> { });
        assertEquals("UNKNOWN", result.providerState(), "preserve existing post-result persistence-failure outcome");
        verify(gateway, never()).refundQuota(any(), any(), any());
    }

    @Test void validReceivedAnswerStillRetainsItsOriginalQuota()
    {
        when(model.chatVision(anyString(), anyString(), anyString())).thenReturn("{\"pass\":true,\"confidence\":90,\"reason\":\"ok\"}");
        var result = vision.reviewPrepared(7L, "media-asset:42", "requirement", TOKEN, snapshot -> { });
        assertEquals("RECEIVED", result.providerState()); assertTrue(result.result().isPass());
        verify(gateway).retainQuota(7L, "photo_check", TOKEN, "PHOTO_RESULT");
        verify(gateway, never()).refundQuota(any(), any(), any());
    }
}
