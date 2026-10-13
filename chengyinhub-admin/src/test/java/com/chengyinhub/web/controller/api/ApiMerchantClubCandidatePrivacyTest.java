package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiMerchantClubCandidatePrivacyTest {

    @Test
    void inviteCandidatesExposeCapabilityWhitelistWithoutApplicationOrContactFields() throws Exception {
        IClubService clubService = mock(IClubService.class);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        ApiMerchantController controller = new ApiMerchantController() {
            @Override
            public Long getAppUserId() {
                return 7001L;
            }
        };
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        com.chengyinhub.business.service.IClubMemberService memberService =
                mock(com.chengyinhub.business.service.IClubMemberService.class);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        when(accessService.require(7001L, MerchantPermission.COOP_MANAGE))
                .thenReturn(new MerchantAccessContext(7001L, 90L, 7001L, "owner"));

        Club club = new Club();
        club.setId(42L);
        club.setMemberId(998877L);
        club.setName("城市徒步社");
        club.setCity("上海");
        club.setMaxEventSize(80);
        club.setCanDesignRoute(1);
        club.setPhone("13800138000");
        club.setCertImages("private-cert.png");
        club.setExperience("内部申请经验");
        club.setJoinReason("内部加入理由");
        club.setCaseImages("private-case.png");
        club.setAuditBy("internal-auditor");
        club.setRejectReason("内部驳回原因");
        club.setMemberDiscountPrice(new BigDecimal("19.90"));
        club.setMemberReservedQuota(12);
        when(clubService.selectClubList(any())).thenReturn(Collections.singletonList(club));
        when(memberService.countEffectiveMembers(club)).thenReturn(1);

        Club attackerQuery = new Club();
        attackerQuery.setMemberId(998877L);
        attackerQuery.setName("城市");
        attackerQuery.setCategoryId(12L);
        AjaxResult result = controller.clubs(attackerQuery);

        org.mockito.ArgumentCaptor<Club> queryCaptor = org.mockito.ArgumentCaptor.forClass(Club.class);
        verify(clubService).selectClubList(queryCaptor.capture());
        Club effectiveQuery = queryCaptor.getValue();
        assertEquals(null, effectiveQuery.getMemberId(), "客户端 memberId 不得进入候选 SQL 形成 owner 枚举器");
        assertEquals("城市", effectiveQuery.getName());
        assertEquals(12L, effectiveQuery.getCategoryId());
        assertEquals(1L, effectiveQuery.getStatus());

        Object first = ((java.util.List<?>) result.get("data")).get(0);
        String json = new ObjectMapper().writeValueAsString(first);
        assertEquals("ClubInviteCandidateVO", first.getClass().getSimpleName());
        assertTrue(json.contains("城市徒步社"));
        // CU-M-02:主理人没有成员行 ⇒ 计 1,与俱乐部主页同口径(原来直接读 member_count=null/0)
        assertTrue(json.contains("\"memberCount\":1"), json);
        verify(memberService).countEffectiveMembers(club);
        assertTrue(json.contains("maxEventSize"));
        assertFalse(json.contains("13800138000"));
        assertFalse(json.contains("private-cert"));
        assertFalse(json.contains("内部申请经验"));
        assertFalse(json.contains("内部加入理由"));
        assertFalse(json.contains("private-case"));
        assertFalse(json.contains("internal-auditor"));
        assertFalse(json.contains("内部驳回原因"));
        assertFalse(json.contains("memberDiscountPrice"));
        assertFalse(json.contains("memberReservedQuota"));
        assertFalse(json.contains("998877"), "邀请目标只需 club.id，不得暴露 owner memberId");
    }
}
