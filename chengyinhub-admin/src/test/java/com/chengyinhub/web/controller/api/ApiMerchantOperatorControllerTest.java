package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.MerchantOperatorInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantOperatorInviteRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorInviteRevokeRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorRemoveRequest;
import com.chengyinhub.business.domain.dto.MerchantOperatorRoleRequest;
import com.chengyinhub.business.domain.vo.MerchantInviteCreationVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorInviteVO;
import com.chengyinhub.business.domain.vo.MerchantOperatorVO;
import com.chengyinhub.business.service.IMerchantOperatorService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiMerchantOperatorControllerTest {
    private static final long PRINCIPAL = 901L;
    private static final long OWNER = 900L;
    private static final long MERCHANT = 77L;

    @Test
    void accessMeReturnsSafeMerchantSummaryRoleAndPermissionsForEmployee() throws Exception {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(access.resolve(PRINCIPAL)).thenReturn(new MerchantAccessContext(
                PRINCIPAL, MERCHANT, OWNER, MerchantRoleCode.MERCHANT_CHECKIN));
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(MERCHANT);
        merchant.setMemberId(OWNER);
        merchant.setName("夜行咖啡");
        merchant.setLogo("logo.png");
        merchant.setPhone("13800000000");
        merchant.setBusinessLicense("private-license");
        when(merchants.selectMmsMerchantById(MERCHANT)).thenReturn(merchant);
        ApiMerchantOperatorController controller = controller(access, mock(IMerchantOperatorService.class), merchants);

        AjaxResult result = controller.accessMe();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        String json = new ObjectMapper().writeValueAsString(result.get(AjaxResult.DATA_TAG));
        assertTrue(json.contains("MERCHANT_CHECKIN"));
        assertTrue(json.contains("merchant:verify"));
        assertFalse(json.contains("13800000000"));
        assertFalse(json.contains("private-license"));
        assertFalse(json.contains(String.valueOf(OWNER)), "access/me 不下发 owner memberId");
    }

    @Test
    void accessMeReturnsExplicitInactiveForMemberWithoutMerchantRelation() {
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.resolve(PRINCIPAL)).thenReturn(null);
        ApiMerchantOperatorController controller = controller(
                access, mock(IMerchantOperatorService.class), mock(IMmsMerchantService.class));

        AjaxResult result = controller.accessMe();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(Boolean.FALSE, ((java.util.Map<?, ?>) result.get(AjaxResult.DATA_TAG)).get("active"));
    }

    @Test
    void inviteEndpointForwardsActorRoleAndIdempotencyKeyWithoutRebuildingToken() {
        IMerchantOperatorService service = mock(IMerchantOperatorService.class);
        MerchantInviteCreationVO expected = new MerchantInviteCreationVO(
                MerchantOperatorInviteVO.from(null), "one-time-token");
        when(service.invite(PRINCIPAL, MerchantRoleCode.MERCHANT_CHECKIN, "req-1")).thenReturn(expected);
        ApiMerchantOperatorController controller = controller(
                mock(IMerchantAccessService.class), service, mock(IMmsMerchantService.class));
        MerchantOperatorInviteRequest request = new MerchantOperatorInviteRequest();
        request.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        request.setRequestId("req-1");

        AjaxResult result = controller.invite(request);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(expected, result.get(AjaxResult.DATA_TAG));
        verify(service).invite(PRINCIPAL, MerchantRoleCode.MERCHANT_CHECKIN, "req-1");
    }

    @Test
    void teamMutationEndpointsReturnAuthoritativeStatusAndVersionReceipts() {
        IMerchantOperatorService service = mock(IMerchantOperatorService.class);
        MerchantOperator relation = new MerchantOperator();
        relation.setId(41L);
        relation.setRoleCode(MerchantRoleCode.MERCHANT_FINANCE);
        relation.setStatus(MerchantOperator.STATUS_ACTIVE);
        relation.setVersion(3);
        MerchantOperatorVO roleReceipt = MerchantOperatorVO.fromMutation(
                relation, MerchantOperatorVO.MUTATION_EXACT_RESULT);
        relation.setStatus(MerchantOperator.STATUS_REVOKED);
        relation.setVersion(4);
        MerchantOperatorVO removeReceipt = MerchantOperatorVO.fromMutation(
                relation, MerchantOperatorVO.MUTATION_EXACT_RESULT);
        MerchantOperatorInvite invite = new MerchantOperatorInvite();
        invite.setId(31L);
        invite.setRoleCode(MerchantRoleCode.MERCHANT_CHECKIN);
        invite.setStatus(MerchantOperatorInvite.STATUS_REVOKED);
        invite.setVersion(1);
        MerchantOperatorInviteVO revokeReceipt = MerchantOperatorInviteVO.fromMutation(
                invite, MerchantOperatorInviteVO.MUTATION_EXACT_RESULT);
        when(service.updateRole(PRINCIPAL, 41L, MerchantRoleCode.MERCHANT_FINANCE, 2, "req-role"))
                .thenReturn(roleReceipt);
        when(service.remove(PRINCIPAL, 41L, 3, "离职", "req-remove")).thenReturn(removeReceipt);
        when(service.revokeInvite(PRINCIPAL, 31L, 0, "撤销", "req-revoke")).thenReturn(revokeReceipt);
        ApiMerchantOperatorController controller = controller(
                mock(IMerchantAccessService.class), service, mock(IMmsMerchantService.class));

        MerchantOperatorRoleRequest role = new MerchantOperatorRoleRequest();
        role.setOperatorId(41L);
        role.setRoleCode(MerchantRoleCode.MERCHANT_FINANCE);
        role.setVersion(2);
        role.setRequestId("req-role");
        MerchantOperatorRemoveRequest remove = new MerchantOperatorRemoveRequest();
        remove.setOperatorId(41L);
        remove.setVersion(3);
        remove.setReason("离职");
        remove.setRequestId("req-remove");
        MerchantOperatorInviteRevokeRequest revoke = new MerchantOperatorInviteRevokeRequest();
        revoke.setInviteId(31L);
        revoke.setVersion(0);
        revoke.setReason("撤销");
        revoke.setRequestId("req-revoke");

        assertEquals(roleReceipt, controller.updateRole(role).get(AjaxResult.DATA_TAG));
        assertEquals(removeReceipt, controller.remove(remove).get(AjaxResult.DATA_TAG));
        assertEquals(revokeReceipt, controller.revokeInvite(revoke).get(AjaxResult.DATA_TAG));
    }

    private static ApiMerchantOperatorController controller(IMerchantAccessService access,
                                                            IMerchantOperatorService service,
                                                            IMmsMerchantService merchants) {
        ApiMerchantOperatorController controller = new ApiMerchantOperatorController() {
            @Override public Long getAppUserId() { return PRINCIPAL; }
        };
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "merchantOperatorService", service);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        return controller;
    }
}
