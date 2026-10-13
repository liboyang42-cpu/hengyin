package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.MemberInvitationMapper;
import com.chengyinhub.business.service.IMemberInvitationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemberInvitationServiceImpl implements IMemberInvitationService {

    @Autowired
    private MemberInvitationMapper memberInvitationMapper;

    @Override
    public boolean bindInviter(Long memberId, String inviterId) {
        if (memberId == null || memberId <= 0 || inviterId == null) {
            return false;
        }

        final Long parsedInviterId;
        try {
            parsedInviterId = Long.valueOf(inviterId.trim());
        } catch (NumberFormatException e) {
            return false;
        }

        if (parsedInviterId <= 0 || parsedInviterId.equals(memberId)) {
            return false;
        }
        return memberInvitationMapper.bindInviterIfAbsent(memberId, parsedInviterId) == 1;
    }
}
