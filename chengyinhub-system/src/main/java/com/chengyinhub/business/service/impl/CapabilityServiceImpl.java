package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.mapper.MemberCapabilityMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 兼容能力视图。权限读取持久角色，历史能力表只保留展示投影。
 */
@Service
public class CapabilityServiceImpl implements ICapabilityService {

    public static final String CAP_PLAYER = "player";

    @Autowired
    private MemberCapabilityMapper memberCapabilityMapper;

    @Autowired
    private com.chengyinhub.business.mapper.UmsMemberMapper umsMemberMapper;
    @Autowired
    private com.chengyinhub.business.service.IRoleService roleService;

    @Override
    public void grant(Long memberId, String capability, Long grantedBy) {
        roleService.changeRole(memberId, roleForCapability(capability), grantedBy == null ? 0L : grantedBy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long memberId, String capability) {
        String role = roleForCapability(capability);
        if (memberId == null || !memberId.equals(umsMemberMapper.lockActiveMemberById(memberId))) {
            throw new ServiceException("会员不可用", 403);
        }
        if (role.equals(roleService.resolveRole(umsMemberMapper.selectRolePermissionSubject(memberId)))) {
            roleService.changeRole(memberId, com.chengyinhub.business.service.IRoleService.ROLE_PLAYER, 0L);
        }
    }

    private String roleForCapability(String capability) {
        if (CAP_PLAYER.equals(capability)) return com.chengyinhub.business.service.IRoleService.ROLE_PLAYER;
        if (MemberCapability.CAP_CLUB_LEADER.equals(capability)) return com.chengyinhub.business.service.IRoleService.ROLE_CLUB;
        if (MemberCapability.CAP_MERCHANT.equals(capability)) return com.chengyinhub.business.service.IRoleService.ROLE_MERCHANT;
        throw new ServiceException("会员角色无效", 400);
    }

    @Override
    public boolean hasCapability(Long memberId, String capability) {
        return getCapabilities(memberId).contains(capability);
    }

    @Override
    public List<String> getCapabilities(Long memberId) {
        List<String> result = new ArrayList<>();
        if (memberId == null || memberId <= 0) return result;
        com.chengyinhub.business.domain.UmsMember member = umsMemberMapper.selectRolePermissionSubject(memberId);
        if (member == null || !Long.valueOf(1L).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getDelFlag())) return result;
        String role = roleService.resolveRole(member);
        result.add(CAP_PLAYER);
        if (com.chengyinhub.business.service.IRoleService.ROLE_CLUB.equals(role)) result.add(MemberCapability.CAP_CLUB_LEADER);
        if (com.chengyinhub.business.service.IRoleService.ROLE_MERCHANT.equals(role)) result.add(MemberCapability.CAP_MERCHANT);
        return result;
    }

    @Override
    public List<MemberCapability> listValid(Long memberId) {
        if (memberId == null) return new ArrayList<>();
        List<MemberCapability> caps = memberCapabilityMapper.selectByMember(memberId);
        return caps == null ? new ArrayList<>() : caps;
    }
}
