package com.chengyinhub.business.service.ai.npc;

import java.util.UUID;

/** Internal locator only. Principal is supplied separately by the authenticated server caller. */
public final class ScopedNpcRequest
{
    public enum Mode { PUBLIC_STORE, THEME }
    public final Mode mode;
    public final long merchantId;
    public final Long runId;
    public final Long bindingId;
    public final String conversationId;
    public final String requestId;
    public final String message;

    private ScopedNpcRequest(Mode mode, long merchantId, Long runId, Long bindingId,
                             String conversationId, String requestId, String message)
    {
        if (merchantId <= 0 || (mode == Mode.THEME && (runId == null || runId <= 0 || bindingId == null || bindingId <= 0)))
            throw new IllegalArgumentException("invalid NPC locator");
        this.mode = mode;
        this.merchantId = merchantId;
        this.runId = runId;
        this.bindingId = bindingId;
        this.conversationId = canonicalUuid(conversationId);
        this.requestId = canonicalUuid(requestId);
        if (message == null || message.trim().isEmpty() || message.codePointCount(0, message.length()) > 300)
            throw new IllegalArgumentException("invalid NPC message");
        NpcDigest.hash(message); // Reject malformed Unicode rather than aliasing digest bytes.
        this.message = message;
    }

    public static ScopedNpcRequest publicStore(long merchantId, String conversationId, String requestId, String message)
    {
        return new ScopedNpcRequest(Mode.PUBLIC_STORE, merchantId, null, null, conversationId, requestId, message);
    }

    public static ScopedNpcRequest theme(long merchantId, long runId, long bindingId,
                                          String conversationId, String requestId, String message)
    {
        return new ScopedNpcRequest(Mode.THEME, merchantId, runId, bindingId, conversationId, requestId, message);
    }

    private static String canonicalUuid(String value)
    {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("canonical UUID required");
        return value;
    }
}
