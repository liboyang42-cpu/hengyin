package com.chengyinhub.web.controller.api;

import com.chengyinhub.web.service.verify.CityNodeRedemptionApplication;

import com.chengyinhub.business.access.merchant.MerchantAccessServiceImpl;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 CX-32(小程序店员端):店员凭授权做一次据点核销 → 主账号撤销 → 店员旧页面再发同一请求立即被拒(403),
 * 且拒绝前不加锁、不写核销、不写审计;店员对别家店的据点也被拒且不写。
 * 用真 MerchantAccessServiceImpl:每个请求都回库读 merchant_operator 的 active 行,不靠令牌/缓存。
 */
class MerchantStaffRevocationAcceptanceTest {

    private static final String SECRET = "test-secret-cx32";
    private static final Long STAFF = 9002L;
    private static final Long OWNER = 9001L;
    private static final Long SHOP = 11L;
    private static final Long OTHER_SHOP = 12L;

    private MerchantOperatorMapper operators;
    private RoamMapper roamMapper;
    private BusinessAuditEventMapper auditMapper;
    private StaffController controller;

    private static final class StaffController extends ApiVerifyController {
        @Override
        public Long getAppUserId() {
            return STAFF;
        }
    }

    @BeforeEach
    void setUp() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        operators = mock(MerchantOperatorMapper.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MmsMerchant shop = new MmsMerchant();
        shop.setId(SHOP);
        shop.setMemberId(OWNER);
        shop.setStatus(1L);
        shop.setAccountStatus(1L);
        shop.setDelFlag(0);
        when(merchants.selectMmsMerchantById(SHOP)).thenReturn(shop);
        when(merchants.selectMmsMerchantList(any(MmsMerchant.class))).thenAnswer(inv ->
                OWNER.equals(inv.getArgument(0, MmsMerchant.class).getMemberId())
                        ? Collections.singletonList(shop) : Collections.emptyList());
        UmsMember staff = new UmsMember();
        staff.setId(STAFF);
        staff.setStatus(1L);
        staff.setDelFlag(0);
        when(members.selectUmsMemberById(STAFF)).thenReturn(staff);
        MerchantAccessServiceImpl access = new MerchantAccessServiceImpl();
        ReflectionTestUtils.setField(access, "merchantService", merchants);
        ReflectionTestUtils.setField(access, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(access, "umsMemberMapper", members);

        roamMapper = mock(RoamMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        controller = new StaffController();
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "cityNodeRedemptionApplication", new CityNodeRedemptionApplication(
                access, roamMapper, mock(ISmsCouponHistoryService.class), auditMapper, mock(com.chengyinhub.business.service.impl.QrcodeServiceImpl.class), SECRET));
    }

    private static MerchantOperator activeCheckinStaff() {
        MerchantOperator op = new MerchantOperator();
        op.setId(51L);
        op.setMerchantId(SHOP);
        op.setOperatorMemberId(STAFF);
        op.setStatus(MerchantOperator.STATUS_ACTIVE);
        op.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        return op;
    }

    private static CityNodeVO poi(long poiId, Long merchantRowId) {
        CityNodeVO poi = new CityNodeVO();
        poi.setPoiId(poiId);
        poi.setMerchantId(merchantRowId);
        poi.setStatus(1);
        return poi;
    }

    private static String code(long poiId, long playerId) {
        return VerifyDynCodeUtil.issue(poiId, "citynode_" + playerId, System.currentTimeMillis(), 60_000L, 7L, SECRET);
    }

    @Test
    void revokedStaffIsRefusedOnTheVeryNextRequestWithoutAnyWrite() {
        // 店员已接受邀请:active 行存在;主账号撤销后 active_operator_member_id 变 NULL ⇒ 查询返回 null。
        when(operators.selectActiveByMemberId(STAFF)).thenReturn(activeCheckinStaff(), (MerchantOperator) null);
        when(roamMapper.selectMerchantNodeDetail(51L)).thenReturn(poi(51L, SHOP));
        when(roamMapper.selectPendingCityNodeCouponId(51L, 7001L)).thenReturn(0L);
        when(roamMapper.redeemCityNodeReward(51L, 7001L)).thenReturn(1);

        AjaxResult authorised = controller.redeemCityNodeCode(code(51L, 7001L));
        assertEquals(200, authorised.get(AjaxResult.CODE_TAG));
        verify(roamMapper, times(1)).redeemCityNodeReward(51L, 7001L);
        verify(auditMapper, times(1)).insertBusinessAuditEvent(any());

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.redeemCityNodeCode(code(51L, 7002L)));
        assertEquals(HttpStatus.FORBIDDEN, refused.getCode());
        assertEquals("商家身份无效或权限不足", refused.getMessage());
        verify(operators, times(2)).selectActiveByMemberId(STAFF);
        verify(roamMapper, never()).lockClaimOwner(7002L);
        verify(roamMapper, never()).redeemCityNodeReward(51L, 7002L);
        verify(auditMapper, times(1)).insertBusinessAuditEvent(any());
    }

    @Test
    void activeStaffCannotRedeemAnotherShopsStationAndNothingIsWritten() {
        when(operators.selectActiveByMemberId(STAFF)).thenReturn(activeCheckinStaff());
        when(roamMapper.selectMerchantNodeDetail(61L)).thenReturn(poi(61L, OTHER_SHOP));

        AjaxResult result = controller.redeemCityNodeCode(code(61L, 7003L));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("无权核销该据点", result.get(AjaxResult.MSG_TAG));
        verify(roamMapper, never()).selectPendingCityNodeCouponId(anyLong(), anyLong());
        verify(roamMapper, never()).redeemCityNodeReward(anyLong(), anyLong());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }
}
