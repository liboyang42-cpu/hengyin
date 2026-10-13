package com.chengyinhub.business.domain;

import java.util.Collections;
import java.util.List;

/** 主理人专用的解散资金阻断明细；普通合作/财务消费面继续隐藏软删历史。 */
public class ClubDissolutionBlockers
{
    private List<ClubPendingDeposit> deposits = Collections.emptyList();
    private List<ClubPendingSettlement> settlements = Collections.emptyList();

    public List<ClubPendingDeposit> getDeposits() { return deposits; }
    public void setDeposits(List<ClubPendingDeposit> deposits) {
        this.deposits = deposits == null ? Collections.<ClubPendingDeposit>emptyList() : deposits;
    }
    public List<ClubPendingSettlement> getSettlements() { return settlements; }
    public void setSettlements(List<ClubPendingSettlement> settlements) {
        this.settlements = settlements == null ? Collections.<ClubPendingSettlement>emptyList() : settlements;
    }
}
