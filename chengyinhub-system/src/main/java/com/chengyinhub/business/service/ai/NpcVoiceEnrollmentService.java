package com.chengyinhub.business.service.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.NpcVoiceEnrollment;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.NpcVoiceEnrollmentMapper;
import com.chengyinhub.common.utils.http.HttpRequestException;

/** One durable enrollment identity, not a lease or a generic outbound action framework. */
@Service
public class NpcVoiceEnrollmentService
{
    private final NpcVoiceEnrollmentMapper intents;
    private final NpcProfileMapper profiles;
    private final VoiceCloneClient provider;
    private final TransactionTemplate transaction;

    public NpcVoiceEnrollmentService(NpcVoiceEnrollmentMapper intents, NpcProfileMapper profiles,
            VoiceCloneClient provider, PlatformTransactionManager manager)
    {
        this.intents = intents;
        this.profiles = profiles;
        this.provider = provider;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Authorization/consent stay at the original caller; supplier is its strict owned/APPROVED AUDIO guard. */
    public NpcProfile enroll(Long actorMemberId, int scopeType, Long scopeId, Long npcId, String sample,
            Supplier<String> approvedProcessingUrl)
    {
        return enroll(actorMemberId, scopeType, scopeId, npcId, sample, null, null, false, approvedProcessingUrl);
    }

    /** A second independent attempt requires a distinct immutable request and explicit risk acknowledgement. */
    public NpcProfile enroll(Long actorMemberId, int scopeType, Long scopeId, Long npcId, String sample,
            String requestId, Long previousIntentId, boolean duplicateChargeConsent, Supplier<String> approvedProcessingUrl)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        outsideBusinessTransaction();
        validate(scopeType, scopeId, npcId);
        if (actorMemberId == null || actorMemberId <= 0 || sample == null
                || !sample.matches("media-asset:[1-9][0-9]{0,18}")) throw new Rejected("录音引用无效");
        String hash = fingerprint(sample);
        if (requestId != null && !requestId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new Rejected("声音请求身份无效");
        if ((previousIntentId != null && (previousIntentId <= 0 || requestId == null || !duplicateChargeConsent))
                || (previousIntentId == null && duplicateChargeConsent)) throw new Rejected("请确认重复音色或额度占用风险后另起请求");
        String identity = requestId == null ? "sample:" + hash : requestId;
        Decision prior = tx(() -> inspect(scopeType, scopeId, npcId, sample, hash, identity, previousIntentId, false, actorMemberId));
        if (!prior.winner() && prior.profile() != null) return prior.profile();
        if (!provider.usable()) throw new Rejected("语音服务未开通");
        // Security refusal must occur before inserting/claiming or changing the profile. Never store this signed URL.
        String processingUrl = approvedProcessingUrl.get();
        Decision claimed = tx(() -> inspect(scopeType, scopeId, npcId, sample, hash, identity, previousIntentId, true, actorMemberId));
        if (!claimed.winner()) return claimed.profile();

        String voiceId;
        try
        {
            voiceId = provider.enroll(processingUrl);
        }
        catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch (RuntimeException failure)
        {
            // Only the exact trusted transport class/disposition is positive evidence. No messages, status or timeout guesses.
            boolean notSent = provenNotSent(failure);
            return finish(scopeType, scopeId, npcId, identity, notSent ? "NOT_SENT" : "UNKNOWN", null,
                    notSent ? "VOICE_PROVIDER_NOT_SENT" : "VOICE_PROVIDER_OUTCOME_UNKNOWN");
        }
        if (voiceId == null || voiceId.isBlank() || voiceId.length() > 96)
            return finish(scopeType, scopeId, npcId, identity, "UNKNOWN", null, "VOICE_PROVIDER_OUTCOME_UNKNOWN");

        // Persist the returned original id in its own transaction BEFORE the first query; query errors cannot erase it.
        NpcProfile known = finish(scopeType, scopeId, npcId, identity, "PENDING", voiceId, null);
        return refresh(scopeType, scopeId, npcId, identity, voiceId, known);
    }

    /** Original authorized status endpoint; may query a known original id but never create an enrollment. */
    public NpcProfile status(int scopeType, Long scopeId, Long npcId)
    {
        return status(null, scopeType, scopeId, npcId, null);
    }

    public NpcProfile status(Long actorMemberId, int scopeType, Long scopeId, Long npcId, String requestId)
    {
        outsideBusinessTransaction();
        validate(scopeType, scopeId, npcId);
        if (requestId != null && !requestId.matches("(?:sample:[0-9a-f]{64}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})"))
            throw new Rejected("声音请求身份无效");
        Snapshot snapshot = snapshot(scopeType, scopeId, npcId, requestId);
        if (snapshot.intent() != null && actorMemberId != null
                && !actorMemberId.equals(snapshot.intent().getActorMemberId())) throw new Rejected("声音任务归属不符");
        if (snapshot.intent() != null)
        {
            NpcVoiceEnrollment row = snapshot.intent();
            if ("PENDING".equals(row.getState()) && row.getVoiceId() != null)
                return refresh(scopeType, scopeId, npcId, row.getRequestId(), row.getVoiceId(), snapshot.profile());
            return snapshot.profile();
        }
        // Preserve legacy known-id polling. No absent/new intent is ever inserted by status.
        NpcProfile current = snapshot.profile();
        if (Integer.valueOf(1).equals(current.getVoiceStatus()) && current.getVoiceId() != null && provider.usable())
        {
            boolean ready;
            try { ready = provider.isReady(current.getVoiceId()); } catch (RuntimeException unavailable) { return snapshot(scopeType, scopeId, npcId, null).profile(); }
            if (ready) return tx(() -> {
                NpcProfile fresh = profile(scopeType, scopeId, npcId);
                if (Objects.equals(fresh.getVoiceVersion(), current.getVoiceVersion())
                        && Objects.equals(fresh.getVoiceId(), current.getVoiceId())
                        && Integer.valueOf(1).equals(fresh.getVoiceStatus()))
                {
                    fresh.setVoiceStatus(2);
                    requireOne(update(fresh));
                    fresh.setVoiceVersion(fresh.getVoiceVersion() + 1);
                }
                return legacy(fresh);
            });
            return snapshot(scopeType, scopeId, npcId, null).profile();
        }
        return current;
    }

    private Snapshot snapshot(int scopeType, Long scopeId, Long npcId, String originalHash)
    {
        return tx(() -> {
            NpcProfile current = profile(scopeType, scopeId, npcId);
            NpcVoiceEnrollment row = originalHash == null ? intents.findLatest(npcId) : find(npcId, originalHash);
            if (originalHash != null && row == null) throw new Rejected("原声音请求不存在，请保留原记录核对");
            if (row != null) { requireScope(row, scopeType, scopeId); return new Snapshot(project(current, row), row); }
            return new Snapshot(legacy(current), null);
        });
    }

    private Decision inspect(int scopeType, Long scopeId, Long npcId, String sample, String hash, String identity,
            Long previousIntentId, boolean create, Long actorMemberId)
    {
        NpcProfile current = profile(scopeType, scopeId, npcId);
        NpcVoiceEnrollment existing = find(npcId, identity);
        if (existing != null)
        {
            requireScope(existing, scopeType, scopeId);
            if (!sample.equals(existing.getSampleReference()) || !hash.equals(existing.getSampleSha256())
                    || !actorMemberId.equals(existing.getActorMemberId())
                    || !Objects.equals(previousIntentId, existing.getPreviousIntentId())) throw new Rejected("录音身份冲突");
            return new Decision(false, project(current, existing));
        }
        NpcVoiceEnrollment latest = intents.findLatest(npcId);
        if (previousIntentId != null)
        {
            NpcVoiceEnrollment previous = intents.findById(npcId, previousIntentId);
            if (previous == null || latest == null || !previousIntentId.equals(latest.getId()))
                throw new Rejected("原声音任务已改变，请查询原请求");
            requireScope(previous, scopeType, scopeId);
            if (!actorMemberId.equals(previous.getActorMemberId())
                    || !("IN_FLIGHT".equals(previous.getState()) || "UNKNOWN".equals(previous.getState())))
                throw new Rejected("原声音任务不是当前账号的未知请求");
        }
        else
        {
            // Superseded UNKNOWN rows remain durable; they never reopen or block a newer decided identity.
            if (latest != null && ("IN_FLIGHT".equals(latest.getState()) || "UNKNOWN".equals(latest.getState())))
                throw new Rejected("声音结果未知，请先查询原任务");
            if (identity.startsWith("sample:") && sample.equals(current.getVoiceSample())) return new Decision(false, legacy(current));
            if (Integer.valueOf(1).equals(current.getVoiceStatus())) throw new Rejected("声音仍在生成，请稍后再试");
            if (current.getVoiceSample() != null && current.getVoiceId() == null && latest == null)
                throw new Rejected("历史声音结果待确认，不能重新复刻");
        }
        if (!create) return new Decision(false, null);

        NpcVoiceEnrollment row = new NpcVoiceEnrollment();
        row.setRequestId(identity); row.setPreviousIntentId(previousIntentId);
        row.setNpcId(npcId); row.setScopeType(scopeType); row.setScopeId(scopeId); row.setActorMemberId(actorMemberId);
        row.setSampleSha256(hash); row.setSampleReference(sample); row.setState("PREPARED");
        row.setProfileVersion(current.getVoiceVersion() + 1);
        requireOne(intents.insert(row));
        if (row.getId() == null) throw new StateUnavailableException();
        current.setVoiceSample(sample); current.setVoiceStatus(1);
        // Retain any known original voice id while this new sample is unresolved. PENDING is never usable for TTS.
        requireOne(update(current));
        current.setVoiceVersion(row.getProfileVersion());
        requireOne(intents.claim(row.getId()));
        row.setState("IN_FLIGHT");
        return new Decision(true, project(current, row));
    }

    private NpcProfile refresh(int scopeType, Long scopeId, Long npcId, String identity, String voiceId, NpcProfile known)
    {
        if (!provider.usable()) return known;
        boolean ready;
        try { ready = provider.isReady(voiceId); }
        catch (RuntimeException unavailable) { return snapshot(scopeType, scopeId, npcId, identity).profile(); }
        if (!ready) return snapshot(scopeType, scopeId, npcId, identity).profile();
        return finish(scopeType, scopeId, npcId, identity, "READY", voiceId, null);
    }

    private NpcProfile finish(int scopeType, Long scopeId, Long npcId, String identity, String state, String voiceId, String reason)
    {
        return tx(() -> {
            NpcProfile current = profile(scopeType, scopeId, npcId);
            NpcVoiceEnrollment row = find(npcId, identity);
            if (row == null) throw new StateUnavailableException();
            requireScope(row, scopeType, scopeId);
            // A concurrent query must not downgrade READY. No path transitions a terminal fact back to a claim.
            if ("READY".equals(row.getState())) return project(current, row);
            if ("UNKNOWN".equals(state) || "NOT_SENT".equals(state))
            {
                if (!"IN_FLIGHT".equals(row.getState())) return project(current, row);
            }
            else if (!("PENDING".equals(state) && "IN_FLIGHT".equals(row.getState()))
                    && !("READY".equals(state) && "PENDING".equals(row.getState()))) throw new StateUnavailableException();
            if (row.getVoiceId() != null && !row.getVoiceId().equals(voiceId)) throw new StateUnavailableException();
            row.setState(state); row.setVoiceId(voiceId); row.setReasonCode(reason);
            if (!"UNKNOWN".equals(state) && Objects.equals(current.getVoiceVersion(), row.getProfileVersion())
                    && Objects.equals(current.getVoiceSample(), row.getSampleReference()))
            {
                if (voiceId != null) current.setVoiceId(voiceId);
                current.setVoiceStatus("READY".equals(state) ? 2 : "NOT_SENT".equals(state) ? 3 : 1);
                requireOne(update(current));
                current.setVoiceVersion(current.getVoiceVersion() + 1);
                row.setProfileVersion(current.getVoiceVersion());
            }
            requireOne(intents.complete(row));
            return project(current, row);
        });
    }

    private NpcProfile profile(int scopeType, Long scopeId, Long npcId)
    {
        NpcProfile profile = intents.lockProfile(npcId, scopeType, scopeId);
        if (profile == null) throw new Rejected("无权修改该形象");
        return profile;
    }

    private int update(NpcProfile profile)
    {
        return profile.getScopeType() == 2 ? profiles.updateMerchantNpcVoice(profile) : profiles.updateNodeNpcVoice(profile);
    }

    private NpcProfile project(NpcProfile profile, NpcVoiceEnrollment row)
    {
        profile.setVoiceRequestId(row.getRequestId());
        profile.setVoicePreviousIntentId(row.getPreviousIntentId());
        profile.setVoiceSelected(Objects.equals(profile.getVoiceVersion(), row.getProfileVersion())
                && Objects.equals(profile.getVoiceSample(), row.getSampleReference()));
        if (!Boolean.TRUE.equals(profile.getVoiceSelected())) profile.setVoiceSample(null);
        profile.setVoiceIntentId(row.getId()); profile.setVoiceIntentState(row.getState()); profile.setVoiceReasonCode(row.getReasonCode());
        if (("PENDING".equals(row.getState()) || "READY".equals(row.getState()))
                && !Boolean.TRUE.equals(profile.getVoiceSelected()))
            profile.setVoiceReasonCode("VOICE_PROFILE_CHANGED");
        return profile;
    }

    private NpcProfile legacy(NpcProfile profile)
    {
        if (profile.getVoiceSample() == null) return profile;
        boolean ready = Integer.valueOf(2).equals(profile.getVoiceStatus()) && profile.getVoiceId() != null;
        profile.setVoiceIntentState(ready ? "READY" : profile.getVoiceId() != null ? "PENDING" : "UNKNOWN");
        profile.setVoiceReasonCode("VOICE_LEGACY_UNBOUND");
        return profile;
    }

    private void requireScope(NpcVoiceEnrollment row, int scopeType, Long scopeId)
    {
        if (!Integer.valueOf(scopeType).equals(row.getScopeType()) || !scopeId.equals(row.getScopeId()))
            throw new Rejected("声音任务归属不符");
    }

    /** Metadata only; samples, provider ids and signed URLs never enter the recovery list. */
    public java.util.List<java.util.Map<String, Object>> history(Long actorMemberId, int scopeType, Long scopeId, Long npcId)
    {
        outsideBusinessTransaction(); validate(scopeType, scopeId, npcId);
        if (actorMemberId == null || actorMemberId <= 0) throw new Rejected("请先登录");
        return tx(() -> {
            NpcProfile selected = profile(scopeType, scopeId, npcId);
            return intents.history(npcId, actorMemberId).stream().map(row -> {
                requireScope(row, scopeType, scopeId);
                java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
                value.put("requestId", row.getRequestId()); value.put("intentId", row.getId());
                value.put("state", row.getState()); value.put("reasonCode", row.getReasonCode());
                value.put("previousIntentId", row.getPreviousIntentId());
                value.put("selected", Objects.equals(selected.getVoiceVersion(), row.getProfileVersion())
                        && Objects.equals(selected.getVoiceSample(), row.getSampleReference()));
                return value;
            }).toList();
        });
    }

    private NpcVoiceEnrollment find(Long npcId, String identity)
    {
        return identity.startsWith("sample:") ? intents.find(npcId, identity.substring(7)) : intents.findRequest(npcId, identity);
    }

    private static boolean provenNotSent(RuntimeException failure)
    {
        Throwable proof = failure instanceof AiServiceException ? failure.getCause() : failure;
        return proof instanceof AiProviderNotSentException || proof != null && proof.getClass() == HttpRequestException.class && proof instanceof HttpRequestException http
                && http.getSendDisposition() == HttpRequestException.SendDisposition.NOT_SENT;
    }

    static String fingerprint(String canonicalSample)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalSample.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void validate(int scopeType, Long scopeId, Long npcId)
    {
        if ((scopeType != 2 && scopeType != 4) || scopeId == null || scopeId <= 0 || npcId == null || npcId <= 0)
            throw new Rejected("声音任务参数无效");
    }

    private static void outsideBusinessTransaction()
    {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new Rejected("声音外呼不能处于业务事务内");
    }

    private <T> T tx(Supplier<T> work)
    {
        try { return transaction.execute(status -> work.get()); }
        catch (Rejected | StateUnavailableException known) { throw known; }
        catch (RuntimeException unavailable) { throw new StateUnavailableException(unavailable); }
    }

    private static void requireOne(int rows) { if (rows != 1) throw new StateUnavailableException(); }
    private record Decision(boolean winner, NpcProfile profile) {}
    private record Snapshot(NpcProfile profile, NpcVoiceEnrollment intent) {}
    public static final class Rejected extends IllegalStateException { public Rejected(String message) { super(message); } }
    public static final class StateUnavailableException extends IllegalStateException
    {
        public StateUnavailableException() { super("声音状态暂不可用，请查询原任务"); }
        public StateUnavailableException(Throwable cause) { super("声音状态暂不可用，请查询原任务", cause); }
    }
}
