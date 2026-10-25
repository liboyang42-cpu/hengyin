package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NpcChatLog;
import com.chengyinhub.business.domain.NpcChatRequest;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

/** Short per-user database transactions. Never hold this lock across provider calls. */
@Service
public class NpcChatWriteService
{
    /** Original ASR won the member fence; the caller must project that receipt instead of inserting another terminal. */
    public static final class AsrIdentityPresent extends RuntimeException {
        private final com.chengyinhub.business.domain.NpcAsrIntent receipt;
        private AsrIdentityPresent(com.chengyinhub.business.domain.NpcAsrIntent receipt) {
            super("Original NPC ASR identity already exists");this.receipt=receipt;
        }
        public com.chengyinhub.business.domain.NpcAsrIntent receipt() { return receipt; }
    }
    public record ShopAdmission(NpcChatRequest request,
            com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff quota) { }
    /** Existing NPC lease duration, shared with the heartbeat; only its admission start moves. */
    static final long LEASE_MILLIS = 20_000L;
    @org.springframework.beans.factory.annotation.Autowired
    private com.chengyinhub.business.mapper.NpcAsrIntentMapper asrIntents;
    private final NpcChatRequestMapper requests;
    private final NpcChatLogMapper logs;
    private final TransactionTemplate transaction;

    public NpcChatWriteService(NpcChatRequestMapper requests, NpcChatLogMapper logs,
                               PlatformTransactionManager transactionManager)
    {
        this.requests = requests;
        this.logs = logs;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private <T> T locked(Long userId, Supplier<T> operation)
    {
        return transaction.execute(status -> {
            if (userId == null || requests.lockChatUser(userId) == null)
                throw new IllegalStateException("NPC chat user persistence unavailable");
            return operation.get();
        });
    }

    public NpcChatRequest read(Long userId, String requestId)
    {
        return locked(userId, () -> requests.selectByUserAndRequestId(userId, requestId));
    }

    public NpcChatRequest admit(NpcChatRequest request, String timeoutText)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return locked(request.getUserId(), () -> admitLocked(request,timeoutText,null,null).request());
    }

    public ShopAdmission admitShop(NpcChatRequest request,String timeoutText,Long merchantId,String message)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        return locked(request.getUserId(), () -> admitLocked(request,timeoutText,merchantId,message));
    }

    private ShopAdmission admitLocked(NpcChatRequest request,String timeoutText,Long merchantId,String message)
    {
            NpcChatRequest existing = requests.selectByUserAndRequestId(request.getUserId(), request.getRequestId());
            if (existing != null) return new ShopAdmission(existing,null);
            com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff quota=null;
            var original=asrIntents.selectForUpdate(request.getUserId(),request.getRequestId());
            if (original!=null) {
                if (merchantId==null || !merchantId.equals(original.getMerchantMemberId())
                        || !request.getUserId().equals(original.getUserId())
                        || original.getNodeId()==null || original.getNodeId()<=0
                        || !Long.valueOf(-original.getNodeId()).equals(request.getSessionId())
                        || original.getQuotaReservation()==null || !"RECEIVED".equals(original.getState())
                        || !original.isHandoffStarted() || original.isBodyErased()
                        || original.getTranscript()==null || original.getTranscript().isEmpty()
                        || !original.getTranscript().equals(message)
                        || asrIntents.claimTextAdmission(request.getUserId(),request.getRequestId(),request.getSessionId())!=1)
                    throw new IllegalStateException("Original ASR text admission is unavailable; read the original receipt");
                quota=new com.chengyinhub.business.service.ai.NpcAsrQuotaHandoff(original.getUserId(),original.getRequestId(),
                        original.getNodeId(),original.getMerchantMemberId(),original.getQuotaReservation());
            }
            requests.expireProcessingByUserAndSession(request.getUserId(), request.getSessionId(), timeoutText);
            // Waiting for the per-user lock must not consume a new request's entire initial lease.
            // Existing rows returned above are never renewed here, including expired/deleted tombstones.
            request.setLeaseUntil(new java.util.Date(System.currentTimeMillis() + LEASE_MILLIS));
            if (requests.insertNpcChatRequest(request) != 1) throw new IllegalStateException("NPC request admission failed");
            return new ShopAdmission(requests.selectByUserAndRequestId(request.getUserId(), request.getRequestId()),quota);
    }

    public NpcChatRequest insertTerminal(NpcChatRequest request)
    {
        return locked(request.getUserId(), () -> {
            NpcChatRequest existing = requests.selectByUserAndRequestId(request.getUserId(), request.getRequestId());
            if (existing != null) return existing;
            var original=asrIntents.selectForUpdate(request.getUserId(),request.getRequestId());
            if (original!=null) throw new AsrIdentityPresent(original);
            if (requests.insertNpcChatRequest(request) != 1) throw new IllegalStateException("NPC request persistence failed");
            return requests.selectByUserAndRequestId(request.getUserId(), request.getRequestId());
        });
    }

    public NpcChatRequest finish(NpcChatRequest request, NpcChatLog entry)
    {
        return locked(request.getUserId(), () -> {
            if (requests.finishIfLeaseOwned(request) == 1 && entry != null)
            {
                if (!request.getUserId().equals(entry.getUserId())
                        || !request.getSessionId().equals(entry.getActivityId()))
                    throw new IllegalArgumentException("NPC log scope mismatch");
                if (logs.insertNpcChatLog(entry) != 1) throw new IllegalStateException("NPC log persistence failed");
            }
            // Never return the worker's in-memory answer after its lease was revoked by deletion.
            return requests.selectByUserAndRequestId(request.getUserId(), request.getRequestId());
        });
    }

    public int deleteForUser(Long userId)
    {
        return locked(userId, () -> {
            asrIntents.redactForUser(userId);
            requests.redactChatRequestsByUserId(userId);
            // This count retains the existing HTTP contract: log rows only, not receipt tombstones.
            return logs.deleteNpcChatLogsByUserId(userId);
        });
    }
}
