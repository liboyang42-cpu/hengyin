package com.chengyinhub.business.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 角色配额配置对象 user_permission
 *
 * RBAC 角色级配额（非每用户），key 为 role(player/club/merchant)。
 */
public class UserPermission
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 角色 player/club/merchant */
    private String role;

    /** 主题同时存在上限 */
    private Integer maxThemes;

    /** 单主题节点上限，null=不限 */
    private Integer maxNodesPerTheme;

    /** 可常驻漫游据点数（仅商家） */
    private Integer maxCityNodes;

    /** 活动同时存在上限 */
    private Integer maxEvents;

    /** 优惠券同时存在上限 */
    private Integer maxCoupons;

    /** 可创建主题 */
    private Boolean canCreateTheme;

    /** 可创建活动 */
    private Boolean canCreateEvent;

    /** 可发布优惠券 */
    private Boolean canPublishCoupon;

    /** 到店核销权（仅商家） */
    private Boolean canRedeemCoupon;

    /** 可用节点类型，逗号分隔 checkin,scan,quiz,... */
    private String nodeTypes;

    /** 分支/条件逻辑 */
    private Boolean canBranch;

    /** 博弈机制 竞速/对抗/协作 */
    private Boolean canGameMechanic;

    /** 开放为城市节点（仅商家） */
    private Boolean canOpenAsNode;

    /** 勋章设计权（仅俱乐部） */
    private Boolean canDesignMedal;

    /** 建群权 */
    private Boolean canCreateGroup;

    /** 数据范围 self/store/theme+city */
    private String dataScope;

    /** 可提现（否=积分制） */
    private Boolean withdrawable;

    /** 预留：会员等级 free/pro/premium，本期不用 */
    private String membershipLevel;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Integer getMaxThemes() { return maxThemes; }
    public void setMaxThemes(Integer maxThemes) { this.maxThemes = maxThemes; }

    public Integer getMaxNodesPerTheme() { return maxNodesPerTheme; }
    public void setMaxNodesPerTheme(Integer maxNodesPerTheme) { this.maxNodesPerTheme = maxNodesPerTheme; }

    public Integer getMaxCityNodes() { return maxCityNodes; }
    public void setMaxCityNodes(Integer maxCityNodes) { this.maxCityNodes = maxCityNodes; }

    public Integer getMaxEvents() { return maxEvents; }
    public void setMaxEvents(Integer maxEvents) { this.maxEvents = maxEvents; }

    public Integer getMaxCoupons() { return maxCoupons; }
    public void setMaxCoupons(Integer maxCoupons) { this.maxCoupons = maxCoupons; }

    public Boolean getCanCreateTheme() { return canCreateTheme; }
    public void setCanCreateTheme(Boolean canCreateTheme) { this.canCreateTheme = canCreateTheme; }

    public Boolean getCanCreateEvent() { return canCreateEvent; }
    public void setCanCreateEvent(Boolean canCreateEvent) { this.canCreateEvent = canCreateEvent; }

    public Boolean getCanPublishCoupon() { return canPublishCoupon; }
    public void setCanPublishCoupon(Boolean canPublishCoupon) { this.canPublishCoupon = canPublishCoupon; }

    public Boolean getCanRedeemCoupon() { return canRedeemCoupon; }
    public void setCanRedeemCoupon(Boolean canRedeemCoupon) { this.canRedeemCoupon = canRedeemCoupon; }

    public String getNodeTypes() { return nodeTypes; }
    public void setNodeTypes(String nodeTypes) { this.nodeTypes = nodeTypes; }

    public Boolean getCanBranch() { return canBranch; }
    public void setCanBranch(Boolean canBranch) { this.canBranch = canBranch; }

    public Boolean getCanGameMechanic() { return canGameMechanic; }
    public void setCanGameMechanic(Boolean canGameMechanic) { this.canGameMechanic = canGameMechanic; }

    public Boolean getCanOpenAsNode() { return canOpenAsNode; }
    public void setCanOpenAsNode(Boolean canOpenAsNode) { this.canOpenAsNode = canOpenAsNode; }

    public Boolean getCanDesignMedal() { return canDesignMedal; }
    public void setCanDesignMedal(Boolean canDesignMedal) { this.canDesignMedal = canDesignMedal; }

    public Boolean getCanCreateGroup() { return canCreateGroup; }
    public void setCanCreateGroup(Boolean canCreateGroup) { this.canCreateGroup = canCreateGroup; }

    public String getDataScope() { return dataScope; }
    public void setDataScope(String dataScope) { this.dataScope = dataScope; }

    public Boolean getWithdrawable() { return withdrawable; }
    public void setWithdrawable(Boolean withdrawable) { this.withdrawable = withdrawable; }

    public String getMembershipLevel() { return membershipLevel; }
    public void setMembershipLevel(String membershipLevel) { this.membershipLevel = membershipLevel; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("role", getRole())
            .append("maxThemes", getMaxThemes())
            .append("maxEvents", getMaxEvents())
            .append("maxCoupons", getMaxCoupons())
            .append("canCreateTheme", getCanCreateTheme())
            .append("canCreateEvent", getCanCreateEvent())
            .append("canPublishCoupon", getCanPublishCoupon())
            .append("membershipLevel", getMembershipLevel())
            .toString();
    }
}
