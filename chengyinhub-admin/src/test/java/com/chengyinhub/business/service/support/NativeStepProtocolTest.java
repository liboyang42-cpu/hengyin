package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.GameRuntimeSession;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeStepProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-10-02T10:00:00Z");
    private NativeStepProtocol protocol;
    private NativeStepAssertionVerifier verifier;
    private ObjectNode state;
    private GameRuntimeSession session;
    private long counter;
    @BeforeEach void setup() {
        protocol = new NativeStepProtocol(); verifier = mock(NativeStepAssertionVerifier.class);
        ReflectionTestUtils.setField(protocol, "enabled", true); ReflectionTestUtils.setField(protocol, "verifier", verifier);
        when(verifier.isReady()).thenReturn(true); when(verifier.isEnrolledIPhoneKey(7,"device-key")).thenReturn(true);
        when(verifier.verifyAndConsume(any(), anyString())).thenAnswer(i -> ++counter);
        state = json.createObjectNode(); session = new GameRuntimeSession();
        session.setId(51L); session.setOwnerType(1); session.setOwnerId(7L); session.setVersion(0);
        session.setStartedAt(Date.from(now.minusSeconds(60))); session.setScore(19);
    }
    private Map<String,Object> issuePayload() {
        Map<String,Object> p = new LinkedHashMap<>(); p.put("provider",NativeStepProtocol.PROVIDER);p.put("deviceKeyId","device-key");return p;
    }
    private JsonNode issue(Instant time) {
        protocol.act(NativeStepProtocol.ISSUE,issuePayload(),state,session,7,"issue-"+time.toEpochMilli(),time);
        session.setVersion(session.getVersion()+1);return state.path("nativeSteps").path("challenge");
    }
    private Map<String,Object> sample(long steps,Instant time) {
        JsonNode c = state.path("nativeSteps").path("challenge");Map<String,Object> p=issuePayload();
        p.put("challengeId",c.path("challengeId").asText());p.put("dayKey",c.path("dayKey").asText());
        p.put("sampleStartAt",c.path("dayStartAt").asLong());p.put("sampleEndAt",time.toEpochMilli());
        p.put("cumulativeSteps",steps);p.put("assertion","cHJvb2Y=");return p;
    }
    private void submit(Map<String,Object> p,Instant time) {
        protocol.act(NativeStepProtocol.SUBMIT,p,state,session,7,"submit-"+time.toEpochMilli(),time);
        session.setVersion(session.getVersion()+1);
    }
    private void failure(String code,Runnable action) {
        ServiceException e=assertThrows(ServiceException.class,action::run);
        assertEquals(code,((Map<?,?>)e.getData()).get("reasonCode"));
    }
    @Test void defaultOffAndVerifierMissingBothFailClosed() {
        NativeStepProtocol disabled=new NativeStepProtocol();
        failure("NATIVE_STEPS_DISABLED",()->disabled.act(NativeStepProtocol.ISSUE,issuePayload(),state,session,7,"x",now));
        ReflectionTestUtils.setField(protocol,"verifier",null);
        failure("NATIVE_STEPS_VERIFIER_UNAVAILABLE",()->issue(now));
        assertEquals("DISABLED",protocol.view(state,session,7).get("status"));
    }
    @Test void challengeBindsActorDeviceSessionAttemptVersionProviderAndDay() {
        JsonNode c=issue(now);assertEquals(7,c.path("accountId").asLong());assertEquals(51,c.path("sessionId").asLong());
        assertEquals(1,c.path("sessionVersion").asInt());assertEquals(session.getStartedAt().getTime(),c.path("attemptStartedAt").asLong());
        assertEquals("Asia/Shanghai",c.path("timeZone").asText());assertEquals("2026-10-02",c.path("dayKey").asText());
        assertEquals(120000,c.path("expiresAt").asLong()-c.path("issuedAt").asLong());
        assertEquals(64,c.path("challengeId").asText().length());
    }
    @Test void baselineThenDeltaNeverAwardsOrSetsLegacyCompletion() {
        issue(now);submit(sample(4000,now.plusSeconds(1)),now.plusSeconds(1));
        assertEquals(4000,state.path("nativeSteps").path("baselineSteps").asLong());
        assertEquals(0,state.path("nativeSteps").path("acceptedDelta").asLong());
        issue(now.plusSeconds(31));submit(sample(4060,now.plusSeconds(32)),now.plusSeconds(32));
        assertEquals(60,state.path("nativeSteps").path("acceptedDelta").asLong());
        assertFalse(state.has("steps"));assertEquals(19,session.getScore());
        assertEquals(false,protocol.view(state.path("nativeSteps"),session,7).get("rewardEnabled"));
    }
    @Test void nextServerDayResetsBaselineNotAssertionCounter() {
        issue(now);submit(sample(4000,now.plusSeconds(1)),now.plusSeconds(1));
        Instant tomorrow=now.plusSeconds(86400);issue(tomorrow);submit(sample(9,tomorrow.plusSeconds(1)),tomorrow.plusSeconds(1));
        assertEquals(9,state.path("nativeSteps").path("baselineSteps").asLong());
        assertEquals(0,state.path("nativeSteps").path("acceptedDelta").asLong());assertEquals(2,counter);
    }
    @Test void deviceSwapAndTeamSessionsRefused() {
        issue(now);when(verifier.isEnrolledIPhoneKey(7,"other-key")).thenReturn(true);
        Map<String,Object> p=issuePayload();p.put("deviceKeyId","other-key");
        failure("NATIVE_STEPS_DEVICE_CHANGED",()->protocol.act(NativeStepProtocol.ISSUE,p,state,session,7,"x",now.plusSeconds(31)));
        session.setOwnerType(2);failure("NATIVE_STEPS_UNSUPPORTED_OWNER",()->issue(now.plusSeconds(31)));
    }
    @Test void unsupportedOwnershipNeverAdvertisesAnEnabledCapability() {
        session.setOwnerType(2);
        Map<String,Object> view=protocol.view(state,session,7);
        assertEquals(false,view.get("enabled"));assertEquals("DISABLED",view.get("status"));
        assertEquals("NATIVE_STEPS_UNSUPPORTED_OWNER",view.get("reasonCode"));
        session.setOwnerType(1);session.setOwnerId(8L);
        assertEquals(false,protocol.view(state,session,7).get("enabled"));
    }
    @Test void revokedOrUnavailableRegistryDoesNotAdvertiseReadyNativeCapability() {
        issue(now);JsonNode nativeState=state.path("nativeSteps");
        when(verifier.isEnrolledIPhoneKey(7,"device-key")).thenReturn(false);
        assertEquals(false,protocol.view(nativeState,session,7).get("enabled"));
        assertEquals("NATIVE_STEPS_DEVICE_NOT_ENROLLED",protocol.view(nativeState,session,7).get("reasonCode"));
        when(verifier.isEnrolledIPhoneKey(7,"device-key")).thenThrow(new IllegalStateException("registry offline"));
        assertEquals("NATIVE_STEPS_VERIFIER_UNAVAILABLE",protocol.view(nativeState,session,7).get("reasonCode"));
        assertFalse(protocol.view(nativeState,session,7).containsKey("challenge"));
    }
    @Test void unregisteredDeviceProviderAndExtraWeRunFieldsRefused() {
        when(verifier.isEnrolledIPhoneKey(anyLong(),anyString())).thenReturn(false);
        failure("NATIVE_STEPS_DEVICE_NOT_ENROLLED",()->issue(now));
        Map<String,Object> p=issuePayload();p.put("provider","WE_RUN");
        failure("NATIVE_STEPS_PROVIDER_UNSUPPORTED",()->protocol.act(NativeStepProtocol.ISSUE,p,state,session,7,"x",now));
        p.put("provider",NativeStepProtocol.PROVIDER);p.put("encryptedData","cipher");
        failure("NATIVE_STEPS_PROVIDER_UNSUPPORTED",()->protocol.act(NativeStepProtocol.ISSUE,p,state,session,7,"x",now));
    }
    @Test void expiredFutureAndReplayedChallengesRefused() {
        issue(now);Map<String,Object> p=sample(10,now.plusSeconds(1));
        failure("NATIVE_STEPS_CHALLENGE_EXPIRED",()->submit(p,now.minusSeconds(1)));
        failure("NATIVE_STEPS_CHALLENGE_EXPIRED",()->submit(p,now.plusSeconds(120)));
        submit(p,now.plusSeconds(1));failure("NATIVE_STEPS_CHALLENGE_REPLAY",()->submit(p,now.plusSeconds(2)));
    }
    @Test void staleVersionAccountAndAttemptBindingRefused() {
        issue(now);Map<String,Object> p=sample(10,now.plusSeconds(1));session.setVersion(99);
        failure("NATIVE_STEPS_BINDING_MISMATCH",()->submit(p,now.plusSeconds(1)));session.setVersion(1);
        session.setStartedAt(Date.from(now));failure("NATIVE_STEPS_BINDING_MISMATCH",()->submit(p,now.plusSeconds(1)));
        session.setStartedAt(Date.from(now.minusSeconds(60)));
        ((ObjectNode)state.path("nativeSteps").path("challenge")).put("accountId",8);
        failure("NATIVE_STEPS_BINDING_MISMATCH",()->submit(p,now.plusSeconds(1)));
    }
    @Test void counterRollbackAndImplausibleVelocityRefusedBeforeVerifier() {
        issue(now);submit(sample(4000,now.plusSeconds(1)),now.plusSeconds(1));issue(now.plusSeconds(31));
        failure("NATIVE_STEPS_COUNTER_INVALID",()->submit(sample(3999,now.plusSeconds(32)),now.plusSeconds(32)));
        failure("NATIVE_STEPS_COUNTER_INVALID",()->submit(sample(5000,now.plusSeconds(32)),now.plusSeconds(32)));
        verify(verifier,times(1)).verifyAndConsume(any(),anyString());
    }
    @Test void fractionalNegativeOversizeOrWrongIntervalRefused() {
        issue(now);
        for(Object value:Arrays.asList(-1L,100001L,1.5,"40")) {
            Map<String,Object> p=sample(1,now.plusSeconds(1));p.put("cumulativeSteps",value);
            failure("NATIVE_STEPS_SAMPLE_INVALID",()->submit(p,now.plusSeconds(1)));
        }
        Map<String,Object> p=sample(1,now.plusSeconds(1));p.put("sampleStartAt",now.toEpochMilli());
        failure("NATIVE_STEPS_SAMPLE_INVALID",()->submit(p,now.plusSeconds(1)));
        failure("NATIVE_STEPS_SAMPLE_INVALID",()->submit(sample(1,now.plusSeconds(7)),now.plusSeconds(1)));
        verify(verifier,never()).verifyAndConsume(any(),anyString());
    }
    @Test void challengeRateAndDailyQuotaAreBounded() {
        issue(now);failure("NATIVE_STEPS_RATE_LIMITED",()->issue(now.plusSeconds(29)));
        for(int i=1;i<240;i++)issue(now.plusSeconds(i*30L));
        failure("NATIVE_STEPS_RATE_LIMITED",()->issue(now.plusSeconds(240*30L)));
    }
    @Test void noClientDigestOrCallerAssertionCounterTrusted() {
        issue(now);Map<String,Object> p=sample(1,now.plusSeconds(1));p.put("clientDataHash","claimed");
        failure("NATIVE_STEPS_PROVIDER_UNSUPPORTED",()->submit(p,now.plusSeconds(1)));
        when(verifier.verifyAndConsume(any(),anyString())).thenReturn(0L);
        failure("NATIVE_STEPS_ASSERTION_REPLAY",()->submit(sample(1,now.plusSeconds(1)),now.plusSeconds(1)));
    }
    @Test void idempotencyDigestIncludesActionVersionAndExactSample() {
        issue(now);Map<String,Object> p=sample(1,now.plusSeconds(1));
        String a=NativeStepProtocol.requestDigest(NativeStepProtocol.SUBMIT,p,1,"key");
        assertEquals(a,NativeStepProtocol.requestDigest(NativeStepProtocol.SUBMIT,new TreeMap<>(p),1,"key"));
        assertNotEquals(a,NativeStepProtocol.requestDigest(NativeStepProtocol.SUBMIT,p,2,"key"));
        p.put("cumulativeSteps",2L);assertNotEquals(a,NativeStepProtocol.requestDigest(NativeStepProtocol.SUBMIT,p,1,"key"));
    }
    @Test void goldenFixtureMatchesNativeHash() throws Exception {
        JsonNode f=json.readTree(getClass().getResourceAsStream("/native-platform-v1.json"));
        JsonNode p=f.path("request").path("payload");
        String actual=NativeStepProtocol.canonical(f.path("challenge"),p.path("sampleStartAt").asLong(),
                p.path("sampleEndAt").asLong(),p.path("cumulativeSteps").asLong(),1,"submit-1");
        assertEquals(f.path("canonicalPayload").asText(),actual);
        assertEquals(f.path("clientDataHashHex").asText(),TimeWindowSchedule.sha256(actual));
    }
    @Test void deterministicCanonicalBytesAreUnambiguous() throws Exception {
        JsonNode c=json.readTree("{\"provider\":\"IOS_CMPEDOMETER_V1\",\"accountId\":7,\"sessionId\":51,\"attemptStartedAt\":1000,\"deviceKeyId\":\"device-key\",\"challengeId\":\"abc\",\"issuedAt\":2000,\"expiresAt\":122000,\"timeZone\":\"Asia/Shanghai\",\"dayKey\":\"2026-10-02\",\"dayStartAt\":0,\"dayEndAt\":86400000}");
        String value=NativeStepProtocol.canonical(c,0,2100,4000,1,"submit-1");
        assertEquals("CHENGYIN_NATIVE_STEPS_V1\nIOS_CMPEDOMETER_V1\n7\n51\n1000\ndevice-key\nabc\n2000\n122000\nAsia/Shanghai\n2026-10-02\n0\n86400000\n0\n2100\n4000\n1\nsubmit-1",value);
        assertFalse(value.endsWith("\n"));assertEquals(18,value.split("\n").length);
    }
}
