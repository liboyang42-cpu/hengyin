package com.chengyinhub.business.domain.vo;

/** 俱乐部成员关系变更与会费访问权收尾的同事务回执。 */
public class ClubLifecycleCommandResultVO {
    private int affectedRows;
    private ClubMembershipAccessEndResultVO membershipAccessEnd;

    public int getAffectedRows() {
        return affectedRows;
    }

    public void setAffectedRows(int affectedRows) {
        this.affectedRows = affectedRows;
    }

    public ClubMembershipAccessEndResultVO getMembershipAccessEnd() {
        return membershipAccessEnd;
    }

    public void setMembershipAccessEnd(ClubMembershipAccessEndResultVO membershipAccessEnd) {
        this.membershipAccessEnd = membershipAccessEnd;
    }
}
