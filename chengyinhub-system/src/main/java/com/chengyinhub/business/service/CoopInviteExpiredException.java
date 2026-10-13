package com.chengyinhub.business.service;

/** 待确认合作邀约已超过服务端处理时限。 */
public class CoopInviteExpiredException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public CoopInviteExpiredException(String message) {
        super(message);
    }
}
