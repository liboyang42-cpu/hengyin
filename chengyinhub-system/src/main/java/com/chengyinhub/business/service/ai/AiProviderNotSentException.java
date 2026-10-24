package com.chengyinhub.business.service.ai;

/**
 * Positive local/transport evidence that this attempt never entered provider dispatch.
 * Catch BEFORE generic provider UNKNOWN. Refund only the original reservation through its owner.
 * This says nothing about any older request/nonce; it never authorizes a replay of an UNKNOWN.
 */
public final class AiProviderNotSentException extends IllegalStateException
{
    private static final long serialVersionUID = 1L;

    public AiProviderNotSentException()
    {
        super("AI request was not sent", null);
    }
}
