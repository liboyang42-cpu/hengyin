package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiClubMerchantsAndBadgeAuthzTest {

    private static final Long CALLER_ID = 9004L;
    private static final Long OWNER_ID = 9003L;

    private IMmsMerchantService merchantService;
    private IClubService clubService;
    private IClubMemberService clubMemberService;
    private GrowthBadgeMapper growthBadgeMapper;
    private Long activePrincipal;
    private ApiClubController controller;

    @BeforeEach
    void setUp() {
        merchantService = mock(IMmsMerchantService.class);
        clubService = mock(IClubService.class);
        clubMemberService = mock(IClubMemberService.class);
        growthBadgeMapper = mock(GrowthBadgeMapper.class);
        activePrincipal = CALLER_ID;
        controller = new ApiClubController() {
            @Override
            public Long getAppUserId() {
                if (activePrincipal == null) {
                    throw new IllegalStateException("unauthenticated");
                }
                return activePrincipal;
            }
        };
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubMemberService", clubMemberService);
        ReflectionTestUtils.setField(controller, "growthBadgeMapper", growthBadgeMapper);
    }

    @Test
    void merchantsRequiresAuthentication() {
        activePrincipal = null;
        MmsMerchant query = new MmsMerchant();
        query.setMemberId(9002L);

        AjaxResult result = controller.merchants(query);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
        verify(merchantService, never()).selectMmsMerchantList(any());
    }

    @Test
    void merchantsStripsPrivateQueryCriteriaAndSensitiveFields() throws Exception {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(8001L);
        merchant.setMemberId(9002L);
        merchant.setName("探店咖啡");
        merchant.setAddress("愚园路100号");
        merchant.setSuitActivityTypes("城市漫步");
        merchant.setCoopOpen(1);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setPhone("13800138000");
        merchant.setWechat("secret_wx_id");
        merchant.setWechatQrcode("secret-qr.png");
        merchant.setBusinessLicense("secret-license.png");
        merchant.setReson("内部审核意见");
        merchant.setDisableReason("内部停用原因");
        when(merchantService.selectMmsMerchantList(any())).thenReturn(Collections.singletonList(merchant));

        MmsMerchant attackerQuery = new MmsMerchant();
        attackerQuery.setMemberId(9002L);
        attackerQuery.setPhone("13800138000");
        attackerQuery.setWechat("secret_wx_id");
        attackerQuery.setAddress("愚园路");
        attackerQuery.setReson("内部");
        attackerQuery.setName("探店");
        attackerQuery.setCategoryId(4001);
        attackerQuery.setTags("咖啡");
        attackerQuery.setCityRole("接头点");

        AjaxResult result = controller.merchants(attackerQuery);

        ArgumentCaptor<MmsMerchant> captor = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(merchantService).selectMmsMerchantList(captor.capture());
        MmsMerchant effective = captor.getValue();
        assertNull(effective.getMemberId(), "client memberId must not enter SQL WHERE oracle");
        assertNull(effective.getPhone(), "client phone must not enter SQL WHERE oracle");
        assertNull(effective.getWechat(), "client wechat must not enter SQL WHERE oracle");
        assertNull(effective.getAddress(), "client address must not enter SQL WHERE oracle");
        assertNull(effective.getReson(), "client audit reson must not enter SQL WHERE oracle");
        assertEquals("探店", effective.getName());
        assertEquals(4001, effective.getCategoryId());
        assertEquals("咖啡", effective.getTags());
        assertEquals("接头点", effective.getCityRole());
        assertEquals(1, effective.getCoopOpen());
        assertEquals(1L, effective.getStatus());
        assertEquals(1L, effective.getAccountStatus());
        assertEquals(0, effective.getDelFlag());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        List<?> rows = (List<?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0) instanceof PublicMerchantDiscoveryVO);
        String json = new ObjectMapper().writeValueAsString(rows.get(0));
        assertTrue(json.contains("\"memberId\":9002"));
        assertTrue(json.contains("探店咖啡"));
        assertTrue(json.contains("愚园路100号"));
        assertTrue(json.contains("城市漫步"));
        assertFalse(json.contains("13800138000"));
        assertFalse(json.contains("secret_wx_id"));
        assertFalse(json.contains("secret-qr.png"));
        assertFalse(json.contains("secret-license.png"));
        assertFalse(json.contains("内部审核意见"));
        assertFalse(json.contains("内部停用原因"));
    }

    @Test
    void badgeListRejectsUnauthenticatedAndNonMemberOfPrivateClub() {
        Club privateClub = club(7002L, OWNER_ID, 1L, 0);
        when(clubService.selectClubById(7002L)).thenReturn(privateClub);
        when(clubMemberService.selectMembershipRecord(7002L, CALLER_ID)).thenReturn(null);

        Club req = new Club();
        req.setId(7002L);

        activePrincipal = null;
        assertEquals("请先登录", controller.badgeList(req).get(AjaxResult.MSG_TAG));

        activePrincipal = CALLER_ID;
        AjaxResult denied = controller.badgeList(req);
        assertEquals(500, denied.get(AjaxResult.CODE_TAG));
        assertEquals("这个俱乐部只对成员开放", denied.get(AjaxResult.MSG_TAG));
        verify(growthBadgeMapper, never()).selectByClub(any());
    }

    @Test
    void badgeListRejectsUnapprovedClubForNonOwner() {
        Club pendingClub = club(7003L, OWNER_ID, 0L, 1);
        when(clubService.selectClubById(7003L)).thenReturn(pendingClub);

        Club req = new Club();
        req.setId(7003L);
        AjaxResult denied = controller.badgeList(req);

        assertEquals(500, denied.get(AjaxResult.CODE_TAG));
        assertEquals("俱乐部不存在", denied.get(AjaxResult.MSG_TAG));
        verify(growthBadgeMapper, never()).selectByClub(any());
    }

    @Test
    void badgeListAllowsActiveMemberOfPrivateClub() {
        Club privateClub = club(7002L, OWNER_ID, 1L, 0);
        ClubMember membership = new ClubMember();
        membership.setClubId(7002L);
        membership.setMemberId(CALLER_ID);
        membership.setStatus(1);
        GrowthBadge badge = new GrowthBadge();
        badge.setBadgeCode("CLUB_7002_STAR");
        badge.setBadgeName("私密星徽");

        when(clubService.selectClubById(7002L)).thenReturn(privateClub);
        when(clubMemberService.selectMembershipRecord(7002L, CALLER_ID)).thenReturn(membership);
        when(growthBadgeMapper.selectByClub(7002L)).thenReturn(Collections.singletonList(badge));

        Club req = new Club();
        req.setId(7002L);
        AjaxResult result = controller.badgeList(req);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(1, ((List<?>) result.get(AjaxResult.DATA_TAG)).size());
        verify(growthBadgeMapper).selectByClub(7002L);
    }

    private static Club club(Long id, Long ownerId, Long status, Integer publicVisible) {
        Club c = new Club();
        c.setId(id);
        c.setMemberId(ownerId);
        c.setStatus(status);
        c.setPublicVisible(publicVisible);
        return c;
    }
}
