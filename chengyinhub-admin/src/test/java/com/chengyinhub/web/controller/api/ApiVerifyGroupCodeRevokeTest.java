package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.CoopSettlementMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.service.impl.GroupCodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X034:商家「已接待团」回读与撤销。
 *
 * <p>撤销时限按当前默认口径:只在主题合作结算开始前可撤(cms_topic.is_settlement=0 且本合作单没有结算行)。
 * 撤销 = 删除接待行(结算基数随之不计该团) + 追加 GROUP_CODE_REVOKE 审计(含撤销前快照与原因)。
 */
class ApiVerifyGroupCodeRevokeTest {

    private static final Long MERCHANT = 77L;
    private static final Long OPERATOR = 7002L;
    private static final Long OTHER_MERCHANT = 78L;
    private static final Long VERIFY_ID = 9001L;
    private static final Long TOPIC = 300L;
    private static final Long ACTIVITY = 301L;
    private static final Long COOP_ORDER = 8001L;
    private static final Long LEADER = 42L;

    private CoopGroupVerifyMapper groupVerifyMapper;
    private CoopSettlementMapper coopSettlementMapper;
    private BusinessAuditEventMapper auditMapper;
    private IMerchantAccessService merchantAccessService;
    private ApiVerifyController controller;

    @BeforeEach
    void setUp() {
        groupVerifyMapper = mock(CoopGroupVerifyMapper.class);
        coopSettlementMapper = mock(CoopSettlementMapper.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);

        GroupCodeServiceImpl service = new GroupCodeServiceImpl(mock(ICmsActivityService.class),
                mock(IClubAccessService.class), mock(CoopOrderMapper.class), groupVerifyMapper,
                mock(IQrcodeService.class), "test-secret");
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "coopSettlementMapper", coopSettlementMapper);

        controller = spy(new ApiVerifyController());
        ReflectionTestUtils.setField(controller, "groupCodeService", service);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        doReturn(OPERATOR).when(controller).getAppUserId();
        when(merchantAccessService.require(OPERATOR, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(OPERATOR, 11L, MERCHANT, MerchantRoleCode.MERCHANT_CHECKIN));

        CoopGroupVerify row = new CoopGroupVerify();
        row.setId(VERIFY_ID);
        row.setTopicId(TOPIC);
        row.setActivityId(ACTIVITY);
        row.setCoopOrderId(COOP_ORDER);
        row.setMerchantMemberId(MERCHANT);
        row.setLeaderMemberId(LEADER);
        row.setVerifyTime(new Date(1_700_000_000_000L));
        when(groupVerifyMapper.selectByIdForUpdate(VERIFY_ID)).thenReturn(row);
        when(groupVerifyMapper.selectTopicSettlementFlagForUpdate(TOPIC)).thenReturn(0);
        when(coopSettlementMapper.countByOrderId(COOP_ORDER)).thenReturn(0);
        when(groupVerifyMapper.deleteByIdAndMerchant(VERIFY_ID, MERCHANT)).thenReturn(1);
    }

    @Test
    void receptions_requireVerifyPermission_andUseOwnerScope() {
        Map<String, Object> item = new HashMap<>();
        item.put("id", VERIFY_ID);
        when(groupVerifyMapper.selectReceptionsByMerchant(MERCHANT)).thenReturn(Collections.singletonList(item));

        AjaxResult r = controller.listGroupReceptions();

        assertEquals(200, r.get("code"));
        verify(merchantAccessService).require(OPERATOR, MerchantPermission.VERIFY);
        verify(groupVerifyMapper).selectReceptionsByMerchant(MERCHANT);
    }

    @Test
    void receptions_withoutVerifyPermission_denied() {
        when(merchantAccessService.require(OPERATOR, MerchantPermission.VERIFY))
                .thenThrow(new ServiceException("无权限", 403));

        assertThrows(ServiceException.class, () -> controller.listGroupReceptions());
        verify(groupVerifyMapper, never()).selectReceptionsByMerchant(anyLong());
    }

    @Test
    void revoke_beforeSettlement_deletesRowAndAppendsAudit() {
        AjaxResult r = controller.revokeGroupReception(VERIFY_ID, " 扫错团 ");

        assertEquals(200, r.get("code"));
        verify(groupVerifyMapper).deleteByIdAndMerchant(VERIFY_ID, MERCHANT);
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        BusinessAuditEvent event = audit.getValue();
        assertEquals("GROUP_CODE_REVOKE", event.getAction());
        assertEquals("COOP_GROUP_VERIFY", event.getTargetType());
        assertEquals(VERIFY_ID, event.getTargetId());
        assertEquals(COOP_ORDER, event.getRelationId());
        assertEquals(MERCHANT, event.getOwnerMemberId());
        assertEquals(OPERATOR, event.getActorMemberId(), "审计记真实操作员,不是商家主体");
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, event.getActorRoleCode());
        assertEquals("扫错团", event.getReason());
        assertEquals("group-revoke-" + VERIFY_ID, event.getRequestId());
        assertTrue(event.getBeforeJson().contains("\"leaderMemberId\":" + LEADER), "撤销前快照必须保留领队");
        assertTrue(event.getBeforeJson().contains("\"verifyTime\":1700000000000"));
    }

    @Test
    void revoke_otherMerchantsRow_reportsNotFound_andDoesNotDelete() {
        doReturn(OTHER_MERCHANT).when(controller).getAppUserId();
        when(merchantAccessService.require(OTHER_MERCHANT, MerchantPermission.VERIFY))
                .thenReturn(new MerchantAccessContext(OTHER_MERCHANT, 12L, OTHER_MERCHANT, MerchantRoleCode.MERCHANT_OWNER));

        AjaxResult r = controller.revokeGroupReception(VERIFY_ID, "误扫");

        assertEquals(500, r.get("code"));
        assertEquals("接待记录不存在或已撤销", r.get("msg"));
        verify(groupVerifyMapper, never()).deleteByIdAndMerchant(any(), any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revoke_afterTopicSettlementClaimed_rejected() {
        when(groupVerifyMapper.selectTopicSettlementFlagForUpdate(TOPIC)).thenReturn(1);

        AjaxResult r = controller.revokeGroupReception(VERIFY_ID, "误扫");

        assertEquals(500, r.get("code"));
        assertTrue(String.valueOf(r.get("msg")).contains("已进入结算"));
        verify(groupVerifyMapper, never()).deleteByIdAndMerchant(any(), any());
    }

    @Test
    void revoke_whenOrderAlreadyHasSettlementLine_rejected() {
        when(coopSettlementMapper.countByOrderId(COOP_ORDER)).thenReturn(1);

        AjaxResult r = controller.revokeGroupReception(VERIFY_ID, "误扫");

        assertEquals(500, r.get("code"));
        verify(groupVerifyMapper, never()).deleteByIdAndMerchant(any(), any());
        verify(auditMapper, never()).insertBusinessAuditEvent(any());
    }

    @Test
    void revoke_requiresReason() {
        AjaxResult r = controller.revokeGroupReception(VERIFY_ID, "   ");

        assertEquals("请填写撤销原因", r.get("msg"));
        verify(groupVerifyMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void revoke_auditFailure_throwsSoDeleteRollsBack() {
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> controller.revokeGroupReception(VERIFY_ID, "误扫"));
    }
}
