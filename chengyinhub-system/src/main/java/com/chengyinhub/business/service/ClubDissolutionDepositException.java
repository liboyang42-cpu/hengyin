package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubPendingDeposit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 解散被未了结合作保证金阻塞；携带可供前端直达具体合作的邀约 ID。 */
public class ClubDissolutionDepositException extends IllegalStateException
{
    private static final long serialVersionUID = 1L;

    private final List<ClubPendingDeposit> deposits;

    public ClubDissolutionDepositException(String message, List<ClubPendingDeposit> deposits)
    {
        super(message);
        this.deposits = deposits == null
                ? Collections.<ClubPendingDeposit>emptyList()
                : Collections.unmodifiableList(new ArrayList<ClubPendingDeposit>(deposits));
    }

    public List<ClubPendingDeposit> getDeposits()
    {
        return deposits;
    }
}
