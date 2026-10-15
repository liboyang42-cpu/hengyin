package com.chengyinhub.business.service.ai;

/** Internal fact: model text was received but cannot be returned after output review. */
public class AiProviderResultReceivedException extends AiServiceException
{
    public AiProviderResultReceivedException(Reason reason, String message)
    {
        super(reason, message);
    }

    public AiProviderResultReceivedException(Reason reason, String message, Throwable cause)
    {
        super(reason, message, cause);
    }
}
