package com.chengyinhub.business.service.ai.npc;

import com.chengyinhub.business.domain.vo.ai.NpcChatResp;

/** Immutable internal result; provider text appears only after gateway safety and final authority CAS. */
public final class ScopedNpcReply
{
    public final String requestId, text, reason, traceId, model, promptVersion, policyVersion, scopeHash;
    public final Long releaseId;
    public final boolean generated;

    ScopedNpcReply(String requestId, String text, String reason, String traceId, String model,
                   String promptVersion, String policyVersion, String scopeHash, Long releaseId, boolean generated)
    {
        this.requestId = requestId; this.text = text; this.reason = reason; this.traceId = traceId;
        this.model = model; this.promptVersion = promptVersion; this.policyVersion = policyVersion;
        this.scopeHash = scopeHash; this.releaseId = releaseId; this.generated = generated;
    }

    public NpcChatResp toChatResponse()
    {
        NpcChatResp result = new NpcChatResp();
        result.setRequestId(requestId); result.setSafeText(text); result.setAi(generated);
        result.setOutcomeStatus(generated ? "SUCCEEDED" : "FAILED");
        result.setSafetyDecision(generated ? "PASS" : "NOT_RUN");
        result.setErrorCode(reason); result.setRetryable(false);
        return result;
    }

    /** No NPC response, including a failure, is a runtime completion/action authorization. */
    public boolean requiresAiForMainline() { return false; }
}
