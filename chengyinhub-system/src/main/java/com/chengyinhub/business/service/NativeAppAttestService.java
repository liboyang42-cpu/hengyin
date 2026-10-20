package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.NativeAppAttestMapper;
import com.chengyinhub.business.service.support.*;
import com.chengyinhub.business.service.support.appattest.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static com.chengyinhub.business.service.support.NativeAppAttestSupport.*;

/** Default-off account-bound enrollment and transactional assertion registry. No fraud-metric network calls. */
@Service
public class NativeAppAttestService implements NativeStepAssertionVerifier {
    private static final long TTL=300000L, INTERVAL=30000L, RECEIPT_RETENTION=86400000L;
    private static final int MAX_DAILY_CHALLENGES=20, MAX_ACTIVE_KEYS=5;
    @Autowired private NativeAppAttestMapper mapper;
    @Autowired private NativeAppAttestConfiguration configuration;
    private final SecureRandom random=new SecureRandom();
    private Clock clock=Clock.systemUTC();
    @Override public boolean isReady() { return configuration!=null && configuration.isReady(); }
    private void ready() { if(!isReady())fail("APP_ATTEST_NOT_CONFIGURED"); }

    // A disabled status read is connection-free; enabled status uses one owner-scoped mapper read.
    public Map<String,Object> status(Long memberId,String deviceKeyId) {
        account(memberId);
        if(!isReady())return view(memberId,deviceKeyId,"DISABLED","APP_ATTEST_NOT_CONFIGURED");
        if(deviceKeyId==null || deviceKeyId.isEmpty())return view(memberId,null,"UNENROLLED","APP_ATTEST_KEY_REQUIRED");
        NativeAppAttestKey key=mapper.selectKey(memberId,keyId(deviceKeyId));
        if(key==null)return view(memberId,deviceKeyId,"UNENROLLED","APP_ATTEST_KEY_REQUIRED");
        if(!binding(key))return view(memberId,deviceKeyId,"DISABLED","APP_ATTEST_BINDING_MISMATCH");
        return view(memberId,deviceKeyId,key.getStatus(),"ACTIVE".equals(key.getStatus())?"APP_ATTEST_ENROLLED":"APP_ATTEST_REVOKED");
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> challenge(Long memberId,Map<String,Object> body) {
        account(memberId);ready();shape(body,"provider","deviceKeyId");
        String key=keyId(string(body,"deviceKeyId",44));long now=clock.millis();
        NativeAppAttestGuard guard=guard(memberId);
        if(mapper.countActiveKeys(memberId)>=MAX_ACTIVE_KEYS)fail("APP_ATTEST_KEY_LIMIT");
        if(mapper.selectKey(memberId,key)!=null)fail("APP_ATTEST_KEY_ALREADY_ENROLLED");
        String day=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        int count=day.equals(guard.getDayKey())?guard.getIssuedCount():0;
        if(now-guard.getLastIssuedAt()<INTERVAL || count>=MAX_DAILY_CHALLENGES)fail("APP_ATTEST_RATE_LIMITED");
        byte[] nonce=new byte[32],id=new byte[32];random.nextBytes(nonce);random.nextBytes(id);
        NativeAppAttestChallenge c=new NativeAppAttestChallenge();c.setChallengeId(hex(id));c.setMemberId(memberId);
        c.setKeyId(key);c.setProvider(NativeStepProtocol.PROVIDER);c.setAppId(configuration.appId());c.setEnvironment("production");
        c.setNonce(nonce);c.setIssuedAt(now);c.setExpiresAt(now+TTL);
        if(mapper.insertChallenge(c)!=1)fail("APP_ATTEST_STORAGE_UNAVAILABLE");
        guard.setDayKey(day);guard.setIssuedCount(count+1);guard.setLastIssuedAt(now);
        if(mapper.updateGuard(guard)!=1)fail("APP_ATTEST_STORAGE_UNAVAILABLE");
        mapper.pruneChallenges(memberId,now-RECEIPT_RETENTION);
        Map<String,Object> result=view(memberId,key,"CHALLENGE","APP_ATTEST_CHALLENGE_ISSUED");
        result.put("challengeId",c.getChallengeId());result.put("challengeBase64",Base64.getEncoder().encodeToString(nonce));
        result.put("issuedAt",now);result.put("expiresAt",c.getExpiresAt());return result;
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> enroll(Long memberId,Map<String,Object> body) {
        account(memberId);ready();shape(body,"provider","deviceKeyId","challengeId","attestationObject");
        String keyId=keyId(string(body,"deviceKeyId",44)),id=string(body,"challengeId",64);
        if(!id.matches("[a-f0-9]{64}"))fail("APP_ATTEST_CHALLENGE_INVALID");
        String encoded=string(body,"attestationObject",87384);
        byte[] attestation=decode(encoded,1,65536,"APP_ATTEST_ATTESTATION_INVALID");
        String digest=TimeWindowSchedule.sha256(NativeStepProtocol.PROVIDER+"\n"+keyId+"\n"+id+"\n"+encoded);
        long now=clock.millis();guard(memberId);
        NativeAppAttestChallenge c=mapper.lockChallenge(memberId,id);
        if(c==null || !keyId.equals(c.getKeyId()) || !configuration.appId().equals(c.getAppId())
                || !"production".equals(c.getEnvironment()) || !NativeStepProtocol.PROVIDER.equals(c.getProvider())) fail("APP_ATTEST_CHALLENGE_INVALID");
        if(c.getConsumedAt()!=null) {
            if(!digest.equals(c.getRequestDigest()))fail("APP_ATTEST_IDEMPOTENCY_MISMATCH");
            NativeAppAttestKey stored=mapper.selectKey(memberId,keyId);
            if(stored==null || !binding(stored))fail("APP_ATTEST_BINDING_MISMATCH");
            return view(memberId,keyId,stored.getStatus(),"ACTIVE".equals(stored.getStatus())?"APP_ATTEST_ENROLLED":"APP_ATTEST_REVOKED");
        }
        if(now<c.getIssuedAt() || now>=c.getExpiresAt())fail("APP_ATTEST_CHALLENGE_EXPIRED");
        if(mapper.countActiveKeys(memberId)>=MAX_ACTIVE_KEYS)fail("APP_ATTEST_KEY_LIMIT");
        if(mapper.selectKey(memberId,keyId)!=null)fail("APP_ATTEST_KEY_ALREADY_ENROLLED");
        EnrollmentResult verified;
        try { verified=configuration.verifier().verifyEnrollment(attestation,keyId,c.getNonce(),Instant.ofEpochMilli(now)); }
        catch(AppAttestVerificationException invalid) { fail(invalid.getReasonCode());return null; }
        NativeAppAttestKey key=new NativeAppAttestKey();key.setKeyId(keyId);key.setMemberId(memberId);
        key.setProvider(NativeStepProtocol.PROVIDER);key.setAppId(configuration.appId());key.setEnvironment("production");
        key.setPublicKeySpki(verified.getPublicKeySpki());key.setReceipt(verified.getReceipt());key.setAssertionCounter(0L);
        key.setStatus("ACTIVE");key.setBundleVersion(verified.getBundleVersion());key.setDistributionCategory(Math.toIntExact(verified.getDistributionCategory()));
        key.setCreatedAt(now);key.setUpdatedAt(now);
        try { if(mapper.insertKey(key)!=1)fail("APP_ATTEST_STORAGE_UNAVAILABLE"); }
        catch(DuplicateKeyException duplicate) { fail("APP_ATTEST_KEY_UNAVAILABLE"); }
        if(mapper.consumeChallenge(memberId,id,digest,now)!=1)fail("APP_ATTEST_CHALLENGE_REPLAY");
        return view(memberId,keyId,"ACTIVE","APP_ATTEST_ENROLLED");
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> revoke(Long memberId,Map<String,Object> body) {
        account(memberId);ready();shape(body,"provider","deviceKeyId");String keyId=keyId(string(body,"deviceKeyId",44));
        NativeAppAttestKey key=mapper.lockKey(memberId,keyId);
        if(key==null || !binding(key))fail("APP_ATTEST_KEY_UNAVAILABLE");
        if("ACTIVE".equals(key.getStatus()) && mapper.revokeKey(memberId,keyId,clock.millis())!=1)fail("APP_ATTEST_STORAGE_UNAVAILABLE");
        return view(memberId,keyId,"REVOKED","APP_ATTEST_REVOKED");
    }

    @Override public boolean isEnrolledIPhoneKey(long accountId,String deviceKeyId) {
        if(!isReady() || accountId<=0)return false;
        keyId(deviceKeyId);NativeAppAttestKey key=mapper.selectKey(accountId,deviceKeyId);
        return key!=null && binding(key) && "ACTIVE".equals(key.getStatus());
    }
    /** MANDATORY joins the game action transaction; global key counter cannot commit independently. */
    @Override @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public long verifyAndConsume(Request request,String assertion) {
        ready();
        if(!TransactionSynchronizationManager.isActualTransactionActive())fail("APP_ATTEST_TRANSACTION_REQUIRED");
        if(request==null || request.accountId<=0 || request.sessionId<=0)fail("APP_ATTEST_BINDING_MISMATCH");
        keyId(request.deviceKeyId);
        String[] fields=request.canonicalPayload==null?new String[0]:request.canonicalPayload.split("\n",-1);
        if(fields.length!=18 || !"CHENGYIN_NATIVE_STEPS_V1".equals(fields[0]) || !NativeStepProtocol.PROVIDER.equals(fields[1])
                || !Long.toString(request.accountId).equals(fields[2]) || !Long.toString(request.sessionId).equals(fields[3])
                || !request.deviceKeyId.equals(fields[5]))fail("APP_ATTEST_BINDING_MISMATCH");
        NativeAppAttestKey key=mapper.lockKey(request.accountId,request.deviceKeyId);
        if(key==null || !binding(key) || !"ACTIVE".equals(key.getStatus()))fail("APP_ATTEST_KEY_UNAVAILABLE");
        long counter;
        try { counter=configuration.verifier().verifyAssertion(decode(assertion,1,4096,"APP_ATTEST_ASSERTION_INVALID"),
                    key.getPublicKeySpki(),request.canonicalPayload.getBytes(StandardCharsets.UTF_8)).getCounter(); }
        catch(AppAttestVerificationException invalid) { fail(invalid.getReasonCode());return 0; }
        if(counter<=key.getAssertionCounter() || counter>4294967295L)fail("APP_ATTEST_COUNTER_REPLAY");
        if(mapper.advanceCounter(request.accountId,request.deviceKeyId,key.getAssertionCounter(),counter,clock.millis())!=1)fail("APP_ATTEST_COUNTER_REPLAY");
        return counter;
    }
    private NativeAppAttestGuard guard(Long memberId) {
        mapper.ensureGuard(memberId);NativeAppAttestGuard guard=mapper.lockGuard(memberId);
        if(guard==null)fail("APP_ATTEST_STORAGE_UNAVAILABLE");return guard;
    }
    private boolean binding(NativeAppAttestKey key) {
        return NativeStepProtocol.PROVIDER.equals(key.getProvider()) && "production".equals(key.getEnvironment())
                && configuration.appId().equals(key.getAppId());
    }
    private Map<String,Object> view(Long member,String key,String status,String reason) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("protocolVersion",1);out.put("provider",NativeStepProtocol.PROVIDER);
        out.put("enabled",isReady());out.put("rewardEnabled",false);out.put("hardwareVerified",false);
        out.put("accountId",member);out.put("deviceKeyId",key);out.put("appId",isReady()?configuration.appId():null);
        out.put("environment","production");out.put("status",status);out.put("reasonCode",reason);return out;
    }
}
