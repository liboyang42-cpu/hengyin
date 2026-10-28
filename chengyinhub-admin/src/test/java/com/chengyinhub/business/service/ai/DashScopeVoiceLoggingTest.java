package com.chengyinhub.business.service.ai;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.common.config.AiVoiceConfig;
import com.chengyinhub.common.utils.http.HttpUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No HTTP/provider: real parser/error logging, actual admission, controlled common transport only. */
class DashScopeVoiceLoggingTest
{
    private static final String SENSITIVE="voice-private-sample-persona-and-signed-url";
    private static final String REQUEST_ID="12345678-1234-1234-1234-123456789abc";
    private static final String BASE="https://voice.invalid/api";

    @Test void providerPayloadsAndThrowableMessagesNeverEnterVoiceLogsButClassificationAndIdsRemain() {
        AiVoiceConfig config=mock(AiVoiceConfig.class);
        when(config.isUsable()).thenReturn(true);when(config.getTimeoutMs()).thenReturn(5000);
        when(config.baseUrl()).thenReturn(BASE);when(config.getApiKey()).thenReturn("synthetic");
        DashScopeVoiceClient client=new DashScopeVoiceClient();ReflectionTestUtils.setField(client,"config",config);
        ReflectionTestUtils.setField(client,"admission",new AiHttpAdmission(List.of(BASE)));
        Logger logger=(Logger)LoggerFactory.getLogger(DashScopeVoiceClient.class);
        ListAppender<ILoggingEvent> appender=new ListAppender<>();appender.start();logger.addAppender(appender);
        try(MockedStatic<HttpUtils> transport=mockStatic(HttpUtils.class)) {
            for(String[] witness:new String[][]{
                    {"{"+SENSITIVE,"INVALID_JSON"},
                    {"{\"request_id\":\""+REQUEST_ID+"\",\"code\":\"Rejected\",\"message\":\""+SENSITIVE+"\"}","PROVIDER_REJECTED"},
                    {"{\"request_id\":\""+REQUEST_ID+"\",\"message\":\""+SENSITIVE+"\"}","MISSING_OUTPUT"},
                    {"{\"request_id\":\""+REQUEST_ID+"\",\"output\":{\"private\":\""+SENSITIVE+"\"}}","MISSING_VOICE_ID"}}) {
                transport.when(()->HttpUtils.sendJsonPost(anyString(),anyString(),anyString(),anyLong(),any(HttpUtils.AttemptLease.class)))
                        .thenReturn(witness[0]);
                appender.list.clear();assertThrows(AiServiceException.class,()->client.enroll("owned-approved-recording"));
                assertSafe(appender,witness[1]);
            }
            transport.when(()->HttpUtils.sendJsonPost(anyString(),anyString(),anyString(),anyLong(),any(HttpUtils.AttemptLease.class)))
                    .thenReturn("{\"request_id\":\""+SENSITIVE+"\",\"output\":{\"private\":\""+SENSITIVE+"\"}}");
            appender.list.clear();assertThrows(AiServiceException.class,()->client.synthesize("original-id","original-words"));
            assertSafe(appender,"MISSING_AUDIO_URL");
            for(RuntimeException cause:new RuntimeException[]{new IllegalStateException(SENSITIVE),new AiProviderNotSentException()}) {
                transport.when(()->HttpUtils.sendJsonPost(anyString(),anyString(),anyString(),anyLong(),any(HttpUtils.AttemptLease.class)))
                        .thenThrow(cause);
                appender.list.clear();AiServiceException failure=assertThrows(AiServiceException.class,()->client.enroll("owned-approved-recording"));
                assertSame(cause,failure.getCause(),"logging must not destroy the original trusted proof/unknown cause boundary");
                assertEquals(AiServiceException.Reason.UNKNOWN,failure.getReason());assertSafe(appender,"TRANSPORT_FAILURE");
            }
        } finally {logger.detachAppender(appender);appender.stop();}
    }

    private static void assertSafe(ListAppender<ILoggingEvent> appender,String classification) {
        assertFalse(appender.list.isEmpty());
        assertTrue(appender.list.stream().anyMatch(event->event.getFormattedMessage().contains("category="+classification)));
        for(ILoggingEvent event:appender.list) {
            assertFalse(event.getFormattedMessage().contains(SENSITIVE));assertNull(event.getThrowableProxy());
            assertTrue(event.getFormattedMessage().matches(".*requestId=[0-9a-fA-F-]{36} .*"));
        }
    }
}
