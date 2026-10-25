package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcAsrIntent;
import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.mapper.NpcAsrIntentMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.service.ai.AiProviderNotSentException;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.ai.AiGatewayService;
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
    private static final String QUOTA_BIZ="npc_shop_chat";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NpcAsrIntentService.class);
    private final NpcAsrIntentMapper intents;
    private final NpcChatRequestMapper requests;
    private final SpeechToTextService speech;
    private final AiGatewayService quota;
    private final TransactionTemplate transaction;
    private record Admission(NpcAsrIntent receipt,boolean winner) { }

    public NpcAsrIntentService(NpcAsrIntentMapper intents,NpcChatRequestMapper requests,
                               PlatformTransactionManager transactions,SpeechToTextService speech,AiGatewayService quota) {
        this.intents=intents;this.requests=requests;this.speech=speech;this.quota=quota;
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
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String rid=rawRid==null?null:rawRid.trim();
        key(user,rid,node);
        if (merchant==null || merchant<=0 || audio==null || audio.length==0 || audio.length>2*1024*1024
                || !java.util.Set.of("wav","mp3","aac","m4a").contains(format))
            throw new IllegalArgumentException("Invalid NPC ASR input");
        final String digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(audio)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Admission admitted=locked(user,() -> {
            intents.redactExpiredForUser(user);
            NpcAsrIntent old=intents.selectForUpdate(user,rid);
            if (old!=null) {
                if (!node.equals(old.getNodeId()) || !merchant.equals(old.getMerchantMemberId())
                        || !digest.equals(old.getAudioSha256()) || !format.equals(old.getAudioFormat()))
                    throw new IllegalArgumentException("NPC ASR original input conflict");
                return new Admission(old,false);
            }
            // Text and ASR admission share this member fence. A text winner must not acquire a second ASR quota.
            if (requests.selectByUserAndRequestId(user,rid)!=null)
                throw new IllegalStateException("Original NPC text request already exists; read its receipt");
            NpcAsrIntent row=new NpcAsrIntent();row.setUserId(user);row.setRequestId(rid);
            row.setNodeId(node);row.setMerchantMemberId(merchant);row.setAudioSha256(digest);row.setAudioFormat(format);
            row.setClaimToken(UUID.randomUUID().toString());row.setState("IN_FLIGHT");
            row.setQuotaReservation(quota.planQuotaReservation(user,QUOTA_BIZ,row.getClaimToken().replace("-","")));
            if (row.getQuotaReservation()==null || row.getQuotaReservation().isBlank())
                throw new IllegalStateException("NPC ASR quota identity unavailable");
            if (intents.insert(row)!=1) throw new IllegalStateException("NPC ASR admission failed");
            NpcAsrIntent stored=intents.selectForUpdate(user,rid);
            if (stored==null || !row.getClaimToken().equals(stored.getClaimToken()) || !"IN_FLIGHT".equals(stored.getState()))
                throw new IllegalStateException("NPC ASR claim has no exact durable readback");
            return new Admission(stored,true);
        }); // A commit failure/unknown never reaches the provider; a later read is never a send grant.
        if (admitted==null) throw new IllegalStateException("NPC ASR admission commit unavailable");
        if (!admitted.winner()) return admitted.receipt();
        try {
            String reservation=admitted.receipt().getQuotaReservation();
            if (!reservation.equals(quota.consumePlannedQuota(user,QUOTA_BIZ,reservation)))
                throw new IllegalStateException("NPC ASR original reservation unavailable");
            // Persist the cost boundary before the provider. Expiry cannot prove an ASR was not sent.
            quota.stageQuota(user,QUOTA_BIZ,reservation,"ASR_STARTED");
        } catch(com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
            throw paused;
        } catch(RuntimeException notDispatched) {
            // No speech call has occurred on this path; persist this local NOT_SENT proof before compensation.
            return finish(admitted.receipt(),"NOT_SENT",null);
        }
        String text;
        try { text=speech.transcribe(audio,format); }
        catch(com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) { throw paused; }
        catch(RuntimeException failure) {
            return finish(admitted.receipt(),notSent(failure)?"NOT_SENT":"UNKNOWN",null);
        }
        // Save separately BEFORE eligibility recheck/model admission. An empty transcription is also terminal.
        return finish(admitted.receipt(),"RECEIVED",text==null?"":text.trim());
    }
    private NpcAsrIntent finish(NpcAsrIntent original,String state,String text) {
        NpcAsrIntent stored=locked(original.getUserId(),() -> {
            NpcAsrIntent update=new NpcAsrIntent();update.setUserId(original.getUserId());
            update.setRequestId(original.getRequestId());update.setClaimToken(original.getClaimToken());
            update.setState(state);update.setTranscript(text);
            update.setQuotaReservation(original.getQuotaReservation());
            if ("NOT_SENT".equals(state)) intents.finishNotSent(update);
            else intents.finish(update); // Deletion revokes body ownership; never return in-memory text.
            NpcAsrIntent current=intents.selectForUpdate(original.getUserId(),original.getRequestId());
            if (current==null) throw new IllegalStateException("NPC ASR result readback unavailable");
            return current;
        });
        try {
            if ("NOT_SENT".equals(stored.getState()) && stored.isQuotaRefundPending())
                quota.refundNpcAsrNotSent(stored.getUserId(),stored.getRequestId());
            else if ("RECEIVED".equals(state))
                quota.retainQuota(original.getUserId(),QUOTA_BIZ,original.getQuotaReservation(),"ASR_RECEIVED");
            // UNKNOWN stays at ASR_STARTED. The original recovery owner records UNCERTAIN, never a completed debit/refund.
        } catch(RuntimeException pending) {
            // NOT_SENT is queued in the ASR row; otherwise ASR_STARTED remains a durable do-not-refund boundary.
            log.warn("NPC ASR quota outcome pending code=QUOTA_PERSISTENCE_UNAVAILABLE");
        }
        return stored;
    }
    /** Commit this one-way handoff before calling the original text service. Unknown commit means stop. */
    public boolean beginHandoff(Long user,String rawRid,Long node,Long merchant) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        String rid=rawRid==null?null:rawRid.trim();
        key(user,rid,node);
        return Boolean.TRUE.equals(locked(user,() -> {
            intents.redactExpiredForUser(user);
            NpcAsrIntent row=intents.selectForUpdate(user,rid);
            if (row==null || !node.equals(row.getNodeId()) || !Objects.equals(merchant,row.getMerchantMemberId()))
                throw new IllegalArgumentException("NPC ASR handoff scope conflict");
            if (row.getQuotaReservation()==null) throw new IllegalStateException("Original ASR quota identity unavailable");
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
        String code=row.isBodyErased()?"ASR_TEXT_UNAVAILABLE"
                :row.getQuotaReservation()==null && !"NOT_SENT".equals(row.getState())?"ASR_QUOTA_UNAVAILABLE"
                :row.isHandoffStarted()?"ASR_TEXT_HANDOFF_STARTED"
                :"RECEIVED".equals(row.getState())?(row.getTranscript()==null?"ASR_TEXT_UNAVAILABLE"
                    :row.getTranscript().isEmpty()?"ASR_EMPTY_TEXT":"ASR_RECEIVED")
                :"NOT_SENT".equals(row.getState())?"ASR_NOT_SENT"
                :"IN_FLIGHT".equals(row.getState())?"ASR_IN_FLIGHT":"ASR_OUTCOME_UNKNOWN";
        NpcChatResp response=unavailable(row.getRequestId(),code);
        response.setAsrState(row.getState());
        response.setQuotaStatus(row.getQuotaReservation()==null?"PROCESSING"
                :"NOT_SENT".equals(row.getState())?(row.isQuotaRefundPending()?"RELEASE_PENDING":"RELEASED")
                :"RECEIVED".equals(row.getState())?"HELD":"PROCESSING");
        return response;
    }
    public static NpcChatResp unavailable(String rid,String reason) {
        boolean unknown="ASR_OUTCOME_UNKNOWN".equals(reason) || "ASR_STATE_UNAVAILABLE".equals(reason)
                || "ASR_TEXT_HANDOFF_STARTED".equals(reason) || "ASR_QUOTA_UNAVAILABLE".equals(reason);
        boolean waiting=unknown || "ASR_IN_FLIGHT".equals(reason) || "ASR_RECEIVED".equals(reason);
        NpcChatResp response=new NpcChatResp();response.setRequestId(rid);
        // Keep the original client recovery contract; ASR detail must not masquerade as a new terminal error.
        response.setOutcomeStatus(waiting?"PROCESSING":"FAILED");
        response.setSafetyDecision(unknown?"UNKNOWN":"NOT_RUN");
        response.setErrorCode(unknown?"REQUEST_OUTCOME_UNKNOWN":waiting?"REQUEST_IN_PROGRESS":reason);
        response.setAsrReason(reason);response.setAsrResumable("ASR_RECEIVED".equals(reason));
        if (waiting) response.setQuotaStatus("PROCESSING");
        response.setRetryable(false);response.setAi(false);
        response.setSafeText(unknown?"原请求结果待确认，额度状态处理中，请稍后查看原请求。"
                :"ASR_RECEIVED".equals(reason)?"原语音已转写，可继续原请求；不会重新识别。"
                :"ASR_IN_FLIGHT".equals(reason)?"原语音仍在处理中，请查看原请求；不会重复识别。"
                :"ASR_TEXT_UNAVAILABLE".equals(reason)?"原语音正文不可恢复；原请求身份仍保留，不会重新识别。"
                :"ASR_EMPTY_TEXT".equals(reason)?"原语音未识别出文字；原请求已保留，不会重新识别。"
                :"原语音请求已保留，不会自动重发。");
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
