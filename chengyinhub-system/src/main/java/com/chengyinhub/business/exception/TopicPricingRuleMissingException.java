package com.chengyinhub.business.exception;

/** A required topic pricing rule or rate field is absent; pricing must remain fail-closed. */
public final class TopicPricingRuleMissingException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public TopicPricingRuleMissingException(String message) {
        super(message);
    }
}
