package com.chengyinhub.business.service.rewardpledge;

/** Fail-closed pledge result. The code is the contract error, not a success with an empty body. */
public final class PledgeRejection extends RuntimeException {
    public final String code;

    public PledgeRejection(String code) {
        super(code);
        this.code = code;
    }
}
