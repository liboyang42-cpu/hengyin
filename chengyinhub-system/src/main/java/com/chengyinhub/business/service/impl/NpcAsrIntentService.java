package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcAsrIntent;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.NpcAsrIntentMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.common.utils.http.HttpRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** NPC ASR only. Original member fence, committed claim, at most one provider invocation; no retries/leases. */
@Service
public class NpcAsrIntentService {
    private final NpcAsrIntentMapper intents;
    private final NpcChatRequestMapper requests;
    private final SpeechToTextService speech;
    private final TransactionTemplate transaction;
    private record Admission(NpcAsrIntent receipt,boolean winner) { }

    public NpcAsrIntentService(NpcAsrIntentMapper intents,NpcChatRequestMapper requests,
                               PlatformTransactionManager transactions,SpeechToTextService speech) {
        this.intents=intents;this.requests=requests;this.speech=speech;
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private <T> T locked(Long userId,Supplier<T> work) {
        // Never suspend a caller already owning the same member row.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("NPC ASR boundary must be outside the original business transaction");
        return transaction.execute(status -> {
            if (userId==null || userId<=0 || requests.lockChatUser(userId)==null)
                throw new IllegalStateException("NPC user persistence unavailable");
            intents.redactExpiredForUser(userId);
            return work.get();
        });
    }
    private static void key(Long user,String rid,Long node) {
        if (user==null || user<=0 || rid==null || rid.isBlank() || rid.length()>64 || !rid.equals(rid.trim())
                || node==null || node<=0 || node>Long.MAX_VALUE/2)
            throw new IllegalArgumentException("Invalid NPC ASR identity");
    }
    public NpcAsrIntent read(Long user,String rawRid,Long node) {
        String rid=rawRid==null?null:rawRid.trim();
        key(user,rid,node);
        return locked(user,() -> {
            NpcAsrIntent existing=intents.selectForUpdate(user,rid);
            if (existing!=null && !node.equals(existing.getNodeId())) throw new IllegalArgumentException("NPC ASR scope conflict");
            return existing;
        });
    }
    /** Caller has already checked the original node/registration gate and detected the actual audio format. */
    public NpcAsrIntent recognize(Long user,String rawRid,Long node,Long merchant,byte[] audio,String format) {
        String rid=rawRid==null?null:rawRid.trim();
        key(user,rid,node);
        if (merchant==null || merchant<=0 || audio==null || audio.length==0 || audio.length>2*1024*1024
                || !java.util.Set.of("wav","mp3","aac","m4a").contains(format))
            throw new IllegalArgumentException("Invalid NPC ASR input");
        final String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Admission admitted=locked(user,() -> {
            NpcAsrIntent old=intents.selectForUpdate(user,rid);
            if (old!=null) {
                if (!node.equals(old.getNodeId()) || !merchant.equals(old.getMerchantMemberId())
                        || !digest.equals(old.getAudioSha256()) || !format.equals(old.getAudioFormat()))
                    throw new IllegalArgumentException("NPC ASR original input conflict");
                return new Admission(old,false);
            }
            NpcAsrIntent row=new NpcAsrIntent();row.setUserId(user);row.setRequestId(rid);
            row.setNodeId(node);row.setMerchantMemberId(merchant);row.setAudioSha256(digest);row.setAudioFormat(format);
            row.setClaimToken(UUID.randomUUID().toString());row.setState("IN_FLIGHT");
            if (intents.insert(row)!=1) throw new IllegalStateException("NPC ASR admission failed");
            NpcAsrIntent stored=intents.selectForUpdate(user,rid);
            if (stored==null || !row.getClaimToken().equals(stored.getClaimToken()) || !"IN_FLIGHT".equals(stored.getState()))
                throw new IllegalStateException("NPC ASR claim has no exact durable readback");
            return new Admission(stored,true);
        }); // A commit failure/unknown never reaches the provider; a later read is never a send grant.
        if (admitted==null) throw new IllegalStateException("NPC ASR admission commit unavailable");
        if (!admitted.winner()) return admitted.receipt();
        String text;
        try { text=speech.transcribe(audio,format); }
        catch(RuntimeException failure) {
            return finish(admitted.receipt(),notSent(failure)?"NOT_SENT":"UNKNOWN",null);
        }
        // Save separately BEFORE eligibility recheck/model admission. An empty transcription is also terminal.
        return finish(admitted.receipt(),"RECEIVED",text==null?"":text.trim());
    }
    private NpcAsrIntent finish(NpcAsrIntent original,String state,String text) {
        return locked(original.getUserId(),() -> {
            NpcAsrIntent update=new NpcAsrIntent();update.setUserId(original.getUserId());
            update.setRequestId(original.getRequestId());update.setClaimToken(original.getClaimToken());
            update.setState(state);update.setTranscript(text);
            intents.finish(update); // Deletion revokes this exact owner; never return its in-memory text.
            NpcAsrIntent stored=intents.selectForUpdate(original.getUserId(),original.getRequestId());
            if (stored==null) throw new IllegalStateException("NPC ASR result readback unavailable");
            return stored;
        });
    }
    /** Commit this one-way handoff before calling the original text service. Unknown commit means stop. */
    public boolean beginHandoff(Long user,String rawRid,Long node,Long merchant) {
        String rid=rawRid==null?null:rawRid.trim();
        key(user,rid,node);
        return Boolean.TRUE.equals(locked(user,() -> {
            NpcAsrIntent row=intents.selectForUpdate(user,rid);
            if (row==null || !node.equals(row.getNodeId()) || !Objects.equals(merchant,row.getMerchantMemberId()))
                throw new IllegalArgumentException("NPC ASR handoff scope conflict");
            return intents.beginHandoff(user,rid)==1;
        }));
    }
    /** Read-only recovery; no provider, text admission, entitlement check, quota or handoff. */
    public NpcChatResp readOutcome(String scope,Long node,String rid,Long user) {
        try {
            NpcAsrIntent stored=read(user,rid,node);
            return stored==null?null:!"shop".equals(scope)?unavailable(rid,"INVALID_REQUEST"):outcome(stored);
        } catch(IllegalArgumentException conflict) { return unavailable(rid,"INVALID_REQUEST"); }
        catch(RuntimeException unavailable) { return unavailable(rid,"ASR_STATE_UNAVAILABLE"); }
    }
    public static NpcChatResp outcome(NpcAsrIntent row) {
        String code=row.isBodyErased()?"ASR_TEXT_UNAVAILABLE":row.isHandoffStarted()?"ASR_TEXT_HANDOFF_STARTED"
                :"RECEIVED".equals(row.getState())?(row.getTranscript()==null?"ASR_TEXT_UNAVAILABLE"
                    :row.getTranscript().isEmpty()?"ASR_EMPTY_TEXT":"ASR_RECEIVED")
                :"NOT_SENT".equals(row.getState())?"ASR_NOT_SENT"
                :"IN_FLIGHT".equals(row.getState())?"ASR_IN_FLIGHT":"ASR_OUTCOME_UNKNOWN";
        return unavailable(row.getRequestId(),code);
    }
    public static NpcChatResp unavailable(String rid,String code) {
        NpcChatResp response=new NpcChatResp();response.setRequestId(rid);
        response.setOutcomeStatus("ASR_IN_FLIGHT".equals(code)?"PROCESSING":"FAILED");
        response.setSafetyDecision("NOT_RUN");response.setErrorCode(code);response.setRetryable(false);response.setAi(false);
        response.setSafeText("原语音请求已保留；请回读原请求状态，勿重复发送。正文不可恢复或结果未知时须人工核实。");
        return response;
    }
    private static boolean notSent(Throwable failure) {
        Throwable current=failure;
        for(int i=0;current!=null && i<16;i++,current=current.getCause()) {
            if (current instanceof AiProviderNotSentException) return true;
            if (current instanceof HttpRequestException transport)
                return transport.getSendDisposition()==HttpRequestException.SendDisposition.NOT_SENT;
        }
        return false;
    }
}
