package com.chengyinhub.business.service.ai;

/** The provider call started, but its completion and usage cannot be established. */
public class AiProviderOutcomeUnknownException extends AiServiceException
{
    public AiProviderOutcomeUnknownException(String message, Throwable cause)
    {
        super(Reason.UNKNOWN, message, cause);
    }
}
