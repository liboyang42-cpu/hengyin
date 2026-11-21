package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUserPermissionService;


/**
 * 会员角色（RBAC）Service 业务层处理。
 */
@Service
public class RoleServiceImpl implements IRoleService
{
    @Autowired
    private IUserPermissionService userPermissionService;

    @Autowired
    private com.chengyinhub.business.mapper.UmsMemberMapper umsMemberMapper;
    @Autowired
    private com.chengyinhub.business.mapper.MemberCapabilityMapper memberCapabilityMapper;

    @Override
    public String resolveRole(UmsMember member)
    {
        if (member == null || member.getId() == null || member.getId() <= 0
                || !java.util.Arrays.asList(ROLE_PLAYER, ROLE_CLUB, ROLE_MERCHANT).contains(member.getRole())) {
            throw new com.chengyinhub.common.exception.ServiceException("会员角色无效，请联系管理员", 403);
        }
        return member.getRole();
    }

    @Override
    public UserPermission getPermission(UmsMember member)
    {
        // 调用方传入的旧会话/资料快照不决定权限，始终读持久角色。
        if (member == null || member.getId() == null) {
            throw new com.chengyinhub.common.exception.ServiceException("请先登录", 401);
        }
        UmsMember current = umsMemberMapper.selectRolePermissionSubject(member.getId());
        if (current == null || !Long.valueOf(1L).equals(current.getStatus())
                || !Integer.valueOf(0).equals(current.getDelFlag())) {
            throw new com.chengyinhub.common.exception.ServiceException("会员不可用", 403);
        }
        UserPermission perm = clonePerm(userPermissionService.getByRole(resolveRole(current)));
        // G2 / GAM-12:业主打开 creator.player.verified_unlock 后,持 verified_creator 能力的玩家创作者
        // 可用分支与游戏机制。开关默认 false = 今天的玩家默认,一字不变。
        if (perm != null && ROLE_PLAYER.equals(current.getRole()) && verifiedUnlockOn()
                && hasActiveCapability(current.getId(), CAP_VERIFIED_CREATOR)) {
            perm.setCanBranch(Boolean.TRUE);
            perm.setCanGameMechanic(Boolean.TRUE);
        }
        return perm;
    }

    public static final String CAP_VERIFIED_CREATOR = "verified_creator";
    public static final String CONFIG_VERIFIED_UNLOCK = "creator.player.verified_unlock";

    @Autowired(required = false)
    private com.chengyinhub.system.service.ISysConfigService sysConfigService;

    boolean verifiedUnlockOn() {
        try {
            return sysConfigService != null
                    && "true".equalsIgnoreCase(String.valueOf(sysConfigService.selectConfigByKey(CONFIG_VERIFIED_UNLOCK)).trim());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean hasActiveCapability(Long memberId, String capability) {
        try {
            MemberCapability cap = memberCapabilityMapper.selectByMemberAndCapability(memberId, capability);
            return cap != null && Integer.valueOf(1).equals(cap.getStatus());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void changeRole(Long memberId, String role, Long actorId)
    {
        if (memberId == null || memberId <= 0 || actorId == null || actorId < 0
                || !java.util.Arrays.asList(ROLE_PLAYER, ROLE_CLUB, ROLE_MERCHANT).contains(role)) {
            throw new com.chengyinhub.common.exception.ServiceException("会员角色修改参数无效", 400);
        }
        if (!memberId.equals(umsMemberMapper.lockActiveMemberById(memberId))) {
            throw new com.chengyinhub.common.exception.ServiceException("会员不可用", 403);
        }
        if (umsMemberMapper.updateCanonicalRole(memberId, role,
                ROLE_MERCHANT.equals(role) ? 2L : 1L) != 1) {
            throw new com.chengyinhub.common.exception.ServiceException("会员角色保存失败");
        }
        for (String cap : java.util.Arrays.asList(MemberCapability.CAP_CLUB_LEADER, MemberCapability.CAP_MERCHANT)) {
            boolean active = MemberCapability.CAP_CLUB_LEADER.equals(cap) ? ROLE_CLUB.equals(role) : ROLE_MERCHANT.equals(role);
            MemberCapability existing = memberCapabilityMapper.selectByMemberAndCapability(memberId, cap);
            if (existing == null) {
                if (!active) continue;
                MemberCapability projected = new MemberCapability();
                projected.setMemberId(memberId); projected.setCapability(cap); projected.setStatus(1);
                projected.setGrantedAt(new java.util.Date()); projected.setGrantedBy(actorId);
                if (memberCapabilityMapper.insertMemberCapability(projected) != 1) {
                    throw new com.chengyinhub.common.exception.ServiceException("会员角色投影保存失败");
                }
            } else if (!Integer.valueOf(active ? 1 : 0).equals(existing.getStatus())) {
                if (memberCapabilityMapper.updateStatus(memberId, cap, active ? 1 : 0, actorId) != 1) {
                    throw new com.chengyinhub.common.exception.ServiceException("会员角色投影保存失败");
                }
            }
        }
    }

    private static UserPermission clonePerm(UserPermission p) {
        if (p == null) return null;
        UserPermission c = new UserPermission();
        c.setRole(p.getRole());
        c.setMaxThemes(p.getMaxThemes()); c.setMaxNodesPerTheme(null); // 主题节点数量不限，兼容旧客户端的 null=不限 契约
        c.setMaxEvents(p.getMaxEvents()); c.setMaxCoupons(p.getMaxCoupons());
        c.setMaxCityNodes(p.getMaxCityNodes());
        c.setCanCreateTheme(p.getCanCreateTheme()); c.setCanCreateEvent(p.getCanCreateEvent());
        c.setCanPublishCoupon(p.getCanPublishCoupon()); c.setCanRedeemCoupon(p.getCanRedeemCoupon());
        c.setNodeTypes(p.getNodeTypes()); c.setCanBranch(p.getCanBranch());
        c.setCanGameMechanic(p.getCanGameMechanic()); c.setCanOpenAsNode(p.getCanOpenAsNode());
        c.setCanDesignMedal(p.getCanDesignMedal()); c.setCanCreateGroup(p.getCanCreateGroup());
        c.setDataScope(p.getDataScope()); c.setWithdrawable(p.getWithdrawable());
        return c;
    }
}
