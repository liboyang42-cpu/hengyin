package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MemberDataCopyRequest;
import com.chengyinhub.business.domain.vo.DataCopyRequestVO;

import java.util.List;

/** ONB-10 (PIPL Art. 45): a member asks for a copy of their personal information; admins work the ticket. */
public interface IMemberDataCopyService {

    /** Returns the member's open request (PENDING/PROCESSING) if there is one, otherwise creates a PENDING one. */
    DataCopyRequestVO request(Long memberId);

    /** The member's newest request, or null when there is none. */
    DataCopyRequestVO latest(Long memberId);

    /** Admin list (paged by the caller), optionally filtered by status; each row carries its {@code dueBy}. */
    List<MemberDataCopyRequest> list(String status);

    /**
     * Admin status change. Allowed: PENDING→PROCESSING, PROCESSING→DELIVERED, PENDING/PROCESSING→REJECTED
     * (a rejection needs a note giving the reason). Anything else throws ServiceException.
     */
    MemberDataCopyRequest changeStatus(Long id, String status, String note, Long adminUserId);
}
