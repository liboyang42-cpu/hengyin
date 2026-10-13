package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUserPermissionService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 会员角色（RBAC）Service 业务层处理。
 */
@Service
public class RoleServiceImpl implements IRoleService
{
    @Autowired
    private IUserPermissionService userPermissionService;

    @Autowired
    private ICapabilityService capabilityService;

    /** capability → user_permission.role 映射(player 隐式同时是 M.CAP_PLAYER 兜底) */
    private static final java.util.Map<String, String> CAP_TO_PERM_ROLE = new java.util.HashMap<>();
    static {
        CAP_TO_PERM_ROLE.put(MemberCapability.CAP_CLUB_LEADER, "club");
        CAP_TO_PERM_ROLE.put(MemberCapability.CAP_MERCHANT, "merchant");
    }

    @Override
    public String resolveRole(UmsMember member)
    {
        if (member == null)
        {
            return ROLE_PLAYER;
        }
        if (StringUtils.isNotEmpty(member.getRole()))
        {
            return member.getRole();
        }
        // 过渡期兜底：role 未回填时按 userType 推断（2=商家，其余=玩家）
        Long userType = member.getUserType();
        if (userType != null && userType == 2L)
        {
            return ROLE_MERCHANT;
        }
        return ROLE_PLAYER;
    }

    /**
     * P2 配额按能力并集:取该会员所有能力的 user_permission 行合并(quota MAX/canX OR/nodeTypes 并集/dataScope 最宽)。
     * player 行为基数始终包含;club_leader/merchant 按能力记录叠加。对外仍返单对象,QuotaServiceImpl 透明。
     */
    @Override
    public UserPermission getPermission(UmsMember member)
    {
        Long memberId = member == null ? null : member.getId();
        // 基数:player 行始终包含
        UserPermission base = userPermissionService.getByRole(ROLE_PLAYER);
        UserPermission merged = clonePerm(base);
        if (memberId == null) return merged;

        List<String> caps;
        try {
            caps = capabilityService.getCapabilities(memberId);
        } catch (Exception e) {
            // member_capability 表尚未部署 → 退回单 role 模式;迁移到位后自动走能力并集
            return clonePerm(userPermissionService.getByRole(resolveRole(member)));
        }
        if (caps == null) return merged;

        for (String cap : caps) {
            String permRole = CAP_TO_PERM_ROLE.get(cap);
            if (permRole == null) continue;
            UserPermission row = userPermissionService.getByRole(permRole);
            if (row == null) continue;
            // quota:MAX
            merged.setMaxThemes(maxInt(merged.getMaxThemes(), row.getMaxThemes()));
            merged.setMaxEvents(maxInt(merged.getMaxEvents(), row.getMaxEvents()));
            merged.setMaxCoupons(maxInt(merged.getMaxCoupons(), row.getMaxCoupons()));
            merged.setMaxCityNodes(maxInt(merged.getMaxCityNodes(), row.getMaxCityNodes()));
            // canX:OR
            merged.setCanCreateTheme(or(merged.getCanCreateTheme(), row.getCanCreateTheme()));
            merged.setCanCreateEvent(or(merged.getCanCreateEvent(), row.getCanCreateEvent()));
            merged.setCanPublishCoupon(or(merged.getCanPublishCoupon(), row.getCanPublishCoupon()));
            merged.setCanRedeemCoupon(or(merged.getCanRedeemCoupon(), row.getCanRedeemCoupon()));
            merged.setCanBranch(or(merged.getCanBranch(), row.getCanBranch()));
            merged.setCanGameMechanic(or(merged.getCanGameMechanic(), row.getCanGameMechanic()));
            merged.setCanOpenAsNode(or(merged.getCanOpenAsNode(), row.getCanOpenAsNode()));
            merged.setCanDesignMedal(or(merged.getCanDesignMedal(), row.getCanDesignMedal()));
            merged.setCanCreateGroup(or(merged.getCanCreateGroup(), row.getCanCreateGroup()));
            merged.setWithdrawable(or(merged.getWithdrawable(), row.getWithdrawable()));
            // nodeTypes:并集(逗号分隔去重)
            merged.setNodeTypes(unionCsv(merged.getNodeTypes(), row.getNodeTypes()));
            // dataScope:取最宽 privilegeOrder
            merged.setDataScope(widerScope(merged.getDataScope(), row.getDataScope()));
        }
        return merged;
    }

    private static UserPermission clonePerm(UserPermission p) {
        if (p == null) return new UserPermission();
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
    private static Integer maxInt(Integer a, Integer b) {
        if (a == null) return b; if (b == null) return a;
        return a >= b ? a : b;
    }
    private static Boolean or(Boolean a, Boolean b) {
        if (a == null) return b; if (b == null) return a;
        return a || b;
    }
    private static String unionCsv(String a, String b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (a != null) set.addAll(Arrays.asList(a.split(",")));
        if (b != null) set.addAll(Arrays.asList(b.split(",")));
        if (set.isEmpty()) return null;
        List<String> parts = new ArrayList<>(set);
        parts.replaceAll(String::trim);
        parts.removeIf(String::isEmpty);
        return String.join(",", parts);
    }
    private static String widerScope(String a, String b) {
        // privilegeOrder: theme+city(3) > store(2) > self(1)
        int oa = scopeOrder(a), ob = scopeOrder(b);
        return oa >= ob ? a : b;
    }
    private static int scopeOrder(String s) {
        if ("theme+city".equals(s)) return 3;
        if ("store".equals(s)) return 2;
        return 1; // "self" or anything else
    }
}