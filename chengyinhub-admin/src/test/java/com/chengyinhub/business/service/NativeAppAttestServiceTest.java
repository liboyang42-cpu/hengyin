package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.NativeAppAttestMapper;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NativeAppAttestServiceTest {
    private static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private static final String APP="1234567890.com.chengyin.synthetic";
    private static final Instant NOW=Instant.parse("2026-10-02T10:00:00Z");
    private NativeAppAttestService service;
    private NativeAppAttestConfiguration config;
    private NativeAppAttestMapper mapper;
    private NativeAppAttestGuard guard;
    @BeforeEach void setup() {
        service=new NativeAppAttestService();config=mock(NativeAppAttestConfiguration.class);mapper=mock(NativeAppAttestMapper.class);
        ReflectionTestUtils.setField(service,"configuration",config);ReflectionTestUtils.setField(service,"mapper",mapper);
        ReflectionTestUtils.setField(service,"clock",Clock.fixed(NOW,ZoneOffset.UTC));
        when(config.isReady()).thenReturn(true);when(config.appId()).thenReturn(APP);
        guard=new NativeAppAttestGuard();guard.setMemberId(7L);guard.setDayKey("2026-10-02");guard.setIssuedCount(0);guard.setLastIssuedAt(0L);
        when(mapper.lockGuard(7L)).thenReturn(guard);when(mapper.insertChallenge(any())).thenReturn(1);when(mapper.updateGuard(any())).thenReturn(1);
    }
    private Map<String,Object> body() {Map<String,Object> b=new LinkedHashMap<>();b.put("provider",NativeStepProtocol.PROVIDER);b.put("deviceKeyId",KEY);return b;}
    private Map<String,Object> enrollBody() {Map<String,Object>b=body();b.put("challengeId",String.join("",Collections.nCopies(64,"a")));b.put("attestationObject","AQ==");return b;}
    private NativeAppAttestKey key(String status) {
        NativeAppAttestKey key=new NativeAppAttestKey();key.setMemberId(7L);key.setKeyId(KEY);key.setProvider(NativeStepProtocol.PROVIDER);
        key.setAppId(APP);key.setEnvironment("production");key.setStatus(status);key.setAssertionCounter(0L);return key;
    }
    private NativeAppAttestChallenge challenge() {
        NativeAppAttestChallenge c=new NativeAppAttestChallenge();c.setMemberId(7L);c.setKeyId(KEY);c.setProvider(NativeStepProtocol.PROVIDER);
        c.setAppId(APP);c.setEnvironment("production");c.setIssuedAt(NOW.minusSeconds(1).toEpochMilli());c.setExpiresAt(NOW.plusSeconds(60).toEpochMilli());
        when(mapper.lockChallenge(eq(7L),anyString())).thenReturn(c);return c;
    }
    private void reason(String expected,Runnable action) {
        ServiceException e=assertThrows(ServiceException.class,action::run);assertEquals(expected,((Map<?,?>)e.getData()).get("reasonCode"));
    }
    @Test void defaultConfigurationStaysDisabledWithoutCertificateOrRegistryTableAccess() {
        NativeAppAttestConfiguration absent=new NativeAppAttestConfiguration();absent.initialize();assertFalse(absent.isReady());
        ReflectionTestUtils.setField(service,"configuration",absent);
        assertEquals("DISABLED",service.status(7L,null).get("status"));
        reason("APP_ATTEST_NOT_CONFIGURED",()->service.challenge(7L,body()));verifyNoInteractions(mapper);
    }
    @Test void disabledStatusThroughRealSpringProxyDoesNotAcquireConnection() {
        when(config.isReady()).thenReturn(false);
        javax.sql.DataSource dataSource=mock(javax.sql.DataSource.class);
        org.springframework.jdbc.datasource.DataSourceTransactionManager manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        org.springframework.aop.framework.ProxyFactory factory=new org.springframework.aop.framework.ProxyFactory(service);factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        NativeAppAttestService proxied=(NativeAppAttestService)factory.getProxy();
        assertEquals("DISABLED",proxied.status(7L,null).get("status"));
        verifyNoInteractions(dataSource);verifyNoInteractions(mapper);
    }
    @Test void accountAndProviderCannotComeFromBody() {
        reason("APP_ATTEST_ACCOUNT_REQUIRED",()->service.challenge(0L,body()));
        Map<String,Object>b=body();b.put("memberId",8L);reason("APP_ATTEST_REQUEST_INVALID",()->service.challenge(7L,b));
        b.remove("memberId");b.put("provider","WE_RUN");reason("APP_ATTEST_PROVIDER_UNSUPPORTED",()->service.challenge(7L,b));
        verifyNoInteractions(mapper);
    }
    @Test void keyIdsMustBeCanonicalSha256Base64() {
        Map<String,Object>b=body();b.put("deviceKeyId","test-key");reason("APP_ATTEST_KEY_INVALID",()->service.challenge(7L,b));
        b.put("deviceKeyId",KEY.substring(0,43));reason("APP_ATTEST_KEY_INVALID",()->service.challenge(7L,b));verifyNoInteractions(mapper);
    }
    @Test void challengeIsOwnedRandomFreshAndBounded() {
        Map<String,Object> result=service.challenge(7L,body());
        assertEquals(7L,result.get("accountId"));assertEquals(APP,result.get("appId"));assertEquals("production",result.get("environment"));
        assertEquals(32,Base64.getDecoder().decode((String)result.get("challengeBase64")).length);
        assertEquals(64,((String)result.get("challengeId")).length());assertEquals(NOW.toEpochMilli()+300000,result.get("expiresAt"));
        assertEquals(false,result.get("rewardEnabled"));assertEquals(false,result.get("hardwareVerified"));
        verify(mapper).ensureGuard(7L);verify(mapper).lockGuard(7L);verify(mapper).updateGuard(guard);assertEquals(1,guard.getIssuedCount());
    }
    @Test void issueQuotaAndActiveKeyQuotaFailClosed() {
        guard.setLastIssuedAt(NOW.minusSeconds(10).toEpochMilli());reason("APP_ATTEST_RATE_LIMITED",()->service.challenge(7L,body()));
        guard.setLastIssuedAt(0L);guard.setIssuedCount(20);reason("APP_ATTEST_RATE_LIMITED",()->service.challenge(7L,body()));
        guard.setIssuedCount(0);when(mapper.countActiveKeys(7L)).thenReturn(5);reason("APP_ATTEST_KEY_LIMIT",()->service.challenge(7L,body()));
        verify(mapper,never()).insertChallenge(any());
    }
    @Test void mismatchedOrExpiredChallengeNeverReachesCrypto() {
        NativeAppAttestChallenge c=challenge();c.setAppId("OTHER.bundle");reason("APP_ATTEST_CHALLENGE_INVALID",()->service.enroll(7L,enrollBody()));
        c.setAppId(APP);c.setExpiresAt(NOW.toEpochMilli());reason("APP_ATTEST_CHALLENGE_EXPIRED",()->service.enroll(7L,enrollBody()));
        verify(config,never()).verifier();verify(mapper,never()).insertKey(any());
    }
    @Test void consumedReceiptReplaysOnlyExactProofWithoutNewKeyWrite() {
        NativeAppAttestChallenge c=challenge();Map<String,Object>b=enrollBody();c.setConsumedAt(NOW.minusSeconds(1).toEpochMilli());
        c.setRequestDigest(TimeWindowSchedule.sha256(NativeStepProtocol.PROVIDER+"\n"+KEY+"\n"+b.get("challengeId")+"\nAQ=="));
        when(mapper.selectKey(7L,KEY)).thenReturn(key("ACTIVE"));assertEquals("ACTIVE",service.enroll(7L,b).get("status"));
        b.put("attestationObject","Ag==");reason("APP_ATTEST_IDEMPOTENCY_MISMATCH",()->service.enroll(7L,b));
        verify(config,never()).verifier();verify(mapper,never()).insertKey(any());verify(mapper,never()).consumeChallenge(anyLong(),anyString(),anyString(),anyLong());
    }
    @Test void revocationIsOwnerScopedAndIdempotent() {
        reason("APP_ATTEST_KEY_UNAVAILABLE",()->service.revoke(7L,body()));
        when(mapper.lockKey(7L,KEY)).thenReturn(key("REVOKED"));assertEquals("REVOKED",service.revoke(7L,body()).get("status"));
        verify(mapper,never()).revokeKey(anyLong(),anyString(),anyLong());
    }
    @Test void registryDoesNotClaimHardwareOrWalkingProof() {
        when(mapper.selectKey(7L,KEY)).thenReturn(key("ACTIVE"));assertTrue(service.isEnrolledIPhoneKey(7,KEY));
        assertFalse(service.isEnrolledIPhoneKey(8,KEY));assertEquals(false,service.status(7L,KEY).get("hardwareVerified"));
        when(mapper.selectKey(7L,KEY)).thenReturn(key("REVOKED"));assertFalse(service.isEnrolledIPhoneKey(7,KEY));
    }
    @Test void assertionConsumptionCannotStartIndependentTransaction() {
        reason("APP_ATTEST_TRANSACTION_REQUIRED",()->service.verifyAndConsume(new NativeStepAssertionVerifier.Request(7,51,KEY,"payload"),"AQ=="));
        verify(mapper,never()).lockKey(anyLong(),anyString());
    }
}
