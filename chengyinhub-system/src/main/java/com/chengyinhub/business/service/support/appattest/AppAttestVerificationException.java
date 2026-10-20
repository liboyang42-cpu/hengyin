package com.chengyinhub.business.service.support.appattest;

/** A closed verification failure. Callers must not continue enrollment or accept an assertion. */
public final class AppAttestVerificationException extends IllegalArgumentException
{
    private final String reason;

    AppAttestVerificationException(String reason)
    {
        super(reason);
        this.reason = reason;
    }

    AppAttestVerificationException(String reason, Throwable cause)
    {
        super(reason, cause);
        this.reason = reason;
    }

    public String getReasonCode()
    {
        return reason;
    }
}
