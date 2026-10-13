package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员角色（RBAC）
 *
 * R1 角色地基：返回当前登录会员的角色 + 配额上限 + 当前用量，供身份中心 / 动态入口使用。
 */
@RestController
@Tag(name = "会员角色")
@RequestMapping("/api/role")
public class ApiRoleController extends BaseController {

    @Autowired
    private IRoleService roleService;

    @Autowired
    private ICapabilityService capabilityService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private com.chengyinhub.business.service.ISmsCouponService smsCouponService;

    @Autowired
    private IClubLeaderService clubLeaderService;

    @Autowired
    private IClubService clubService;

    @Autowired
    private IClubMemberService clubMemberService;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    @Operation(summary = "当前会员角色+配额+用量")
    @PostMapping("/info")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AjaxResult roleInfo() {
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }

        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null) {
            return error("用户不存在");
        }

        String role = roleService.resolveRole(member);
        UserPermission perm = roleService.getPermission(member);
        if (perm == null) {
            return error("当前身份权限配置缺失，请联系管理员");
        }

        // 单一事实源：下发三端能力位全集(专家报告 §3)，前端 roleGuard 一律读这里
        //
        // X03:nodeTypes 已停止下发。它曾以 CSV → 数组的形式下发,但两端零消费:
        // 小程序的 identity-policy.can() 支持列表位判断(且有单测),却没有任何页面调用它;
        // 权益清单里也不显示这一位。要把它变成真闸,得先定死 nodeTypes 的取值
        // ('scan'/'quiz'/'gps'…)与 cms 节点 validation_method 整数之间的映射表 ——
        // 那张表不存在,恰恰是因为从来没人需要它。补一张出来就是发明产品规则,不是补强制点。
        // user_permission.node_types 列先保留(删列另开迁移),只是不再下发。
        // 键集合由 ApiRoleInfoContractTest 钉住,想加回来必须先改那条契约。
        Map<String, Object> permission = new HashMap<>();
        permission.put("maxThemes", perm.getMaxThemes());
        permission.put("maxNodesPerTheme", perm.getMaxNodesPerTheme());
        permission.put("maxEvents", perm.getMaxEvents());
        permission.put("maxCoupons", perm.getMaxCoupons());
        permission.put("canCreateTheme", perm.getCanCreateTheme());
        permission.put("canCreateEvent", perm.getCanCreateEvent());
        permission.put("canPublishCoupon", perm.getCanPublishCoupon());
        permission.put("canRedeemCoupon", perm.getCanRedeemCoupon());
        permission.put("canBranch", perm.getCanBranch());
        permission.put("canGameMechanic", perm.getCanGameMechanic());
        permission.put("canOpenAsNode", perm.getCanOpenAsNode());
        permission.put("canDesignMedal", perm.getCanDesignMedal());
        permission.put("canCreateGroup", perm.getCanCreateGroup());
        permission.put("dataScope", perm.getDataScope());
        permission.put("withdrawable", perm.getWithdrawable());

        // 当前用量：主题/活动用现有按会员计数（统计全部，状态精确过滤留 R3 配额校验时细化）；优惠券用量 R3 补
        Map<String, Object> usage = new HashMap<>();
        usage.put("themes", cmsTopicService.selectCmsTopicNumByMemberId(memberId));
        // 在架公开主题数(user_status=1,非 selfPlay):发布能力卡据此算「剩余额度=maxThemes-themesOnline」
        usage.put("themesOnline", cmsTopicService.selectOnlineThemeNumByMemberId(memberId, null));
        usage.put("events", cmsActivityService.selectCmsActivityNumByMemberId(memberId));
        usage.put("coupons", smsCouponService.selectSmsCouponNumByMemberId(memberId));

        Map<String, Object> data = new HashMap<>();
        data.put("role", role);
        data.put("permission", permission);
        data.put("usage", usage);

        // role 仅为默认视角；历史主理人档案保留，生效能力以 capability 和商家启停事实为准。
        ClubLeader leader = clubLeaderService.selectClubLeaderByMemberId(memberId);
        boolean isClubLeader = capabilityService.hasCapability(memberId, MemberCapability.CAP_CLUB_LEADER);
        MmsMerchant merchant = mmsMerchantService.selectMmsMerchantByMemberId(memberId);
        boolean isMerchant = merchant != null && Long.valueOf(1L).equals(merchant.getStatus())
                && Long.valueOf(1L).equals(merchant.getAccountStatus())
                && Integer.valueOf(0).equals(merchant.getDelFlag());
        List<Club> owned = clubService.selectClubsByMemberId(memberId);
        List<Map<String, Object>> ownedClubs = new ArrayList<>();
        if (owned != null) {
            for (Club c : owned) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", c.getId());
                m.put("name", c.getName());
                m.put("logo", c.getLogo());
                m.put("status", c.getStatus());
                ownedClubs.add(m);
            }
        }
        List<Long> joinedClubIds = clubMemberService.selectClubIdsByMember(memberId);
        data.put("effectiveRole", role);
        data.put("isClubLeader", isClubLeader);
        data.put("isMerchant", isMerchant);
        data.put("leaderLevel", leader == null ? null : leader.getLevel());
        // 发布/俱乐部能力(身份互斥 2026-07-05):商家不可创建俱乐部(商户⊥主理人);
        // 玩家创建俱乐部=迁移为主理人,可建至上限(与 ApiClubController.MAX_CLUBS_PER_LEADER=2 一致)
        int ownedClubCount = ownedClubs.size();
        int maxOwnedClubs = 2;
        permission.put("canCreateClub", !isMerchant && ownedClubCount < maxOwnedClubs);
        data.put("ownedClubCount", ownedClubCount);
        data.put("maxOwnedClubs", maxOwnedClubs);
        data.put("ownedClubs", ownedClubs);
        data.put("joinedClubIds", joinedClubIds == null ? new ArrayList<Long>() : joinedClubIds);
        return AjaxResult.success(data);
    }

}
