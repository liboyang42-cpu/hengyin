package com.chengyinhub.business.domain;

/** 广播名单候选:服务端按当前筛选重算出的自有客户;队伍/角色用于与前端的范围选择对齐。 */
public class MerchantCrmBroadcastCandidate {
    private Long memberId;
    private String name;
    private String teamName;
    private String roleCode;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long value) { memberId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getTeamName() { return teamName; }
    public void setTeamName(String value) { teamName = value; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String value) { roleCode = value; }
}
