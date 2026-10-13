package com.chengyinhub.web.controller.api;

import java.util.Map;
import java.util.TreeSet;
import java.util.Arrays;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubLeaderService;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * /api/role/info 下发的能力位键集合契约。
 *
 * <p>存在的理由：这个接口是 {@code utils/roleGuard.js} 口中的「三端权限单一事实源」，
 * 少一个键前端会静默按「未解锁」处理，多一个键则可能是又下发了一位两端都没人读的死开关
 * ——{@code nodeTypes} 就是后者，已在 X03 里停止下发。
 * 键集合写死在这里，想加回来必须先改这条契约，改不动就说明该先想清楚谁读它。
 */
class ApiRoleInfoContractTest
{
    private static final long MEMBER = 77L;

    private ApiRoleController controller;
    private UserPermission perm;
    private ICapabilityService capabilities;
    private IClubLeaderService leaders;
    private IMmsMerchantService merchants;
    private IClubService clubs;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        controller = spy(new ApiRoleController());
        doReturn(MEMBER).when(controller).getAppUserId();

        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        when(memberService.selectUmsMemberById(MEMBER)).thenReturn(member);

        perm = new UserPermission();
        perm.setNodeTypes("scan,quiz");   // 库里有值也不许下发
        IRoleService roleService = mock(IRoleService.class);
        when(roleService.getPermission(any(UmsMember.class))).thenReturn(perm);
        when(roleService.resolveRole(any(UmsMember.class))).thenReturn("player");

        ICmsTopicService topicService = mock(ICmsTopicService.class);
        when(topicService.selectCmsTopicNumByMemberId(anyLong())).thenReturn(0);
        IClubService clubService = mock(IClubService.class);
        clubs = clubService;
        IClubMemberService clubMemberService = mock(IClubMemberService.class);

        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", clubMemberService);
        merchants = mock(IMmsMerchantService.class);
        capabilities = mock(ICapabilityService.class);
        leaders = mock(IClubLeaderService.class);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilities);
        ReflectionTestUtils.setField(controller, "smsCouponService",
                mock(com.chengyinhub.business.service.ISmsCouponService.class));
        ReflectionTestUtils.setField(controller, "clubLeaderService", leaders);
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalLeaderProfileDoesNotRegrantIdentityAndOwnedClubStateRemainsVisible() {
        ClubLeader history = new ClubLeader();
        history.setMemberId(MEMBER); history.setStatus(1); history.setLevel(3);
        when(leaders.selectClubLeaderByMemberId(MEMBER)).thenReturn(history);
        Club pending = new Club();
        pending.setId(42L); pending.setName("待处置俱乐部"); pending.setStatus(0L);
        when(clubs.selectClubsByMemberId(MEMBER)).thenReturn(Arrays.asList(pending));

        Map<String, Object> data = (Map<String, Object>) controller.roleInfo().get(AjaxResult.DATA_TAG);

        assertEquals(false, data.get("isClubLeader"));
        assertEquals(3, data.get("leaderLevel"), "历史档案仍保留");
        java.util.List<Map<String, Object>> owned = (java.util.List<Map<String, Object>>) data.get("ownedClubs");
        assertEquals(42L, owned.get(0).get("id"));
        assertEquals(0L, owned.get(0).get("status"));
        when(capabilities.hasCapability(MEMBER, MemberCapability.CAP_CLUB_LEADER)).thenReturn(true);
        assertEquals(true, ((Map<?, ?>) controller.roleInfo().get(AjaxResult.DATA_TAG)).get("isClubLeader"));
    }

    @Test
    void approvedButDisabledOrDeletedMerchantDoesNotReceiveMerchantIdentity() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(8L); merchant.setMemberId(MEMBER); merchant.setStatus(1L);
        merchant.setAccountStatus(2L); merchant.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(MEMBER)).thenReturn(merchant);
        assertEquals(false, ((Map<?, ?>) controller.roleInfo().get(AjaxResult.DATA_TAG)).get("isMerchant"));
        merchant.setAccountStatus(1L); merchant.setDelFlag(2);
        assertEquals(false, ((Map<?, ?>) controller.roleInfo().get(AjaxResult.DATA_TAG)).get("isMerchant"));
        merchant.setDelFlag(0);
        assertEquals(true, ((Map<?, ?>) controller.roleInfo().get(AjaxResult.DATA_TAG)).get("isMerchant"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> permissionMap()
    {
        AjaxResult r = controller.roleInfo();
        Map<String, Object> data = (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
        return (Map<String, Object>) data.get("permission");
    }

    @Test
    void 下发的能力位键集合必须与契约逐键相等()
    {
        assertEquals(
                new TreeSet<>(Arrays.asList(
                        "maxThemes", "maxNodesPerTheme", "maxEvents", "maxCoupons",
                        "canCreateTheme", "canCreateEvent", "canPublishCoupon", "canRedeemCoupon",
                        "canBranch", "canGameMechanic", "canOpenAsNode", "canDesignMedal",
                        "canCreateGroup", "dataScope", "withdrawable",
                        // canCreateClub 是**派生位**,不是 user_permission 的列:
                        // !isMerchant && 已建俱乐部数 < 2,在下发时现算。
                        // 服务端强制点在 ApiClubController(MAX_CLUBS_PER_LEADER=2 + 商户⊥主理人),
                        // 与能力位 canCreateGroup 是两件事:后者判「这个身份解锁建群没有」,
                        // 前者判「此刻还能不能再建一个」。
                        "canCreateClub")),
                new TreeSet<>(permissionMap().keySet()),
                "少一个键前端会静默按未解锁处理；多一个键要先说清楚谁读它");
    }

    @Test
    void nodeTypes_即使库里有值也不再下发()
    {
        // ★ 负控：X03 停止下发的那一位。库里 node_types='scan,quiz' 仍在，
        // 但两端零消费，下发它只会让人误以为它是个闸。
        assertFalse(permissionMap().containsKey("nodeTypes"),
                "nodeTypes 两端零消费，已停止下发；要加回来先定死它与 validation_method 的映射表");
    }
}
