package com.chengyinhub.business.begapmisc.home;

/** A refused home / home-return call: {@code code} is the envelope code, the message is the fixed reason code. */
public class HomeRejection extends RuntimeException {
    public final int code;

    public HomeRejection(int code, String reason) {
        super(reason, null, false, false);
        this.code = code;
    }
}
