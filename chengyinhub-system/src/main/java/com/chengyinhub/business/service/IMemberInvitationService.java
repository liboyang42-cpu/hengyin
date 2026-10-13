package com.chengyinhub.business.service;

public interface IMemberInvitationService {

    boolean bindInviter(Long memberId, String inviterId);
}
