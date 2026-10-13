package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubPendingSettlement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 解散被未打款结算阻塞；携带待收/待付逐笔入口所需引用。 */
public class ClubDissolutionSettlementException extends IllegalStateException
{
    private static final long serialVersionUID = 1L;

    private final List<ClubPendingSettlement> settlements;

    public ClubDissolutionSettlementException(String message, List<ClubPendingSettlement> settlements)
    {
        super(message);
        this.settlements = settlements == null
                ? Collections.<ClubPendingSettlement>emptyList()
                : Collections.unmodifiableList(new ArrayList<ClubPendingSettlement>(settlements));
    }

    public List<ClubPendingSettlement> getSettlements()
    {
        return settlements;
    }
}
