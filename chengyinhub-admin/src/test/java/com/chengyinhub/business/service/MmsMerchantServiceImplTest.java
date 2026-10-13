package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MmsMerchantServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MmsMerchantServiceImplTest {
    private static final long MERCHANT = 77L;
    private static final long OLD_OWNER = 900L;
    private static final long NEW_OWNER = 902L;

    private MmsMerchantMapper merchants;
    private MerchantOperatorMapper operators;
    private MerchantOperatorInviteMapper invites;
    private UmsMemberMapper members;
    private MmsMerchantServiceImpl service;

    @BeforeEach
    void setUp() {
        merchants = mock(MmsMerchantMapper.class);
        operators = mock(MerchantOperatorMapper.class);
        invites = mock(MerchantOperatorInviteMapper.class);
        members = mock(UmsMemberMapper.class);
        service = new MmsMerchantServiceImpl();
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchants);
        ReflectionTestUtils.setField(service, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(service, "merchantOperatorInviteMapper", invites);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        when(merchants.selectMmsMerchantById(MERCHANT)).thenReturn(currentMerchant());
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(currentMerchant());
        when(members.lockActiveMemberById(OLD_OWNER)).thenReturn(OLD_OWNER);
    }

    @Test
    void ownerChangeLocksMerchantRevokesDelegationsThenUpdatesOwner() {
        MmsMerchant patch = ownerChangePatch();
        when(operators.revokeAllActiveByMerchantId(
                MERCHANT, "MERCHANT_OWNER_CHANGED", "SYSTEM_OWNER_CHANGE")).thenReturn(2);
        when(invites.revokeAllPendingByMerchantId(
                MERCHANT, "MERCHANT_OWNER_CHANGED")).thenReturn(3);
        when(merchants.updateMmsMerchant(patch)).thenReturn(1);

        assertEquals(1, service.updateMmsMerchant(patch));

        InOrder writes = inOrder(merchants, operators, invites);
        writes.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        writes.verify(operators).revokeAllActiveByMerchantId(
                MERCHANT, "MERCHANT_OWNER_CHANGED", "SYSTEM_OWNER_CHANGE");
        writes.verify(invites).revokeAllPendingByMerchantId(
                MERCHANT, "MERCHANT_OWNER_CHANGED");
        writes.verify(merchants).updateMmsMerchant(patch);
        assertNotNull(patch.getUpdateTime());
    }

    @Test
    void profileUpdateOrSameOwnerDoesNotRevokeDelegations() {
        MmsMerchant profile = new MmsMerchant();
        profile.setId(MERCHANT);
        profile.setName("新店名");
        when(merchants.updateMmsMerchant(profile)).thenReturn(1);

        assertEquals(1, service.updateMmsMerchant(profile));

        verify(operators, never()).revokeAllActiveByMerchantId(any(), any(), any());
        verify(invites, never()).revokeAllPendingByMerchantId(any(), any());

        MmsMerchant sameOwner = new MmsMerchant();
        sameOwner.setId(MERCHANT);
        sameOwner.setMemberId(OLD_OWNER);
        when(merchants.updateMmsMerchant(sameOwner)).thenReturn(1);

        assertEquals(1, service.updateMmsMerchant(sameOwner));
        verify(operators, never()).revokeAllActiveByMerchantId(any(), any(), any());
        verify(invites, never()).revokeAllPendingByMerchantId(any(), any());
    }

    @Test
    void ownerChangeWriteFailureThrowsSoTransactionCannotCommitOnlyRevocations() {
        MmsMerchant patch = ownerChangePatch();
        when(merchants.updateMmsMerchant(patch)).thenReturn(0);

        assertThrows(ServiceException.class, () -> service.updateMmsMerchant(patch));

        verify(operators).revokeAllActiveByMerchantId(
                MERCHANT, "MERCHANT_OWNER_CHANGED", "SYSTEM_OWNER_CHANGE");
        verify(invites).revokeAllPendingByMerchantId(MERCHANT, "MERCHANT_OWNER_CHANGED");
    }

    @Test
    void staleOwnerAfterTransferCannotOverwriteNewOwnersProfileOrApplicationStatus() {
        MmsMerchant transferred = currentMerchant();
        transferred.setMemberId(NEW_OWNER);
        transferred.setName("新 Owner 店铺");
        transferred.setStatus(2L);
        transferred.setAccountStatus(0L);
        transferred.setDelFlag(0);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(transferred);
        when(merchants.updateMmsMerchant(any(MmsMerchant.class))).thenAnswer(call -> {
            MmsMerchant staleWrite = call.getArgument(0);
            transferred.setName(staleWrite.getName());
            transferred.setStatus(staleWrite.getStatus());
            return 1;
        });

        MmsMerchant staleOwnerPatch = new MmsMerchant();
        staleOwnerPatch.setId(MERCHANT);
        staleOwnerPatch.setName("旧 Owner 覆盖");
        staleOwnerPatch.setStatus(0L);
        ServiceException rejected = null;
        try {
            service.updateOwnerRegistrationProfile(staleOwnerPatch, OLD_OWNER);
        } catch (ServiceException error) {
            rejected = error;
        }

        assertEquals("新 Owner 店铺", transferred.getName(), "旧 Owner 不得覆盖新 Owner 资料");
        assertEquals(Long.valueOf(2L), transferred.getStatus(), "旧 Owner 不得重开入驻状态");
        assertNotNull(rejected, "Owner 转让后的旧请求必须显式失败");
        assertEquals(403, rejected.getCode());
    }

    @Test
    void ownerRegistrationUpdateLocksOwnerAndWritesOnlyWhitelistedProfileFields() {
        MmsMerchant current = currentMerchant();
        current.setStatus(2L);
        current.setAccountStatus(0L);
        current.setDelFlag(0);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(current);
        when(members.lockActiveMemberById(OLD_OWNER)).thenReturn(OLD_OWNER);
        when(merchants.updateOwnerRegistrationProfile(any(MmsMerchant.class), eq(OLD_OWNER))).thenReturn(1);

        MmsMerchant request = new MmsMerchant();
        request.setId(MERCHANT);
        request.setMemberId(NEW_OWNER);
        request.setName("重提门店");
        request.setStatus(1L);
        request.setReson("伪造审核理由");
        request.setAccountStatus(2L);
        request.setBusinessStatus(0);
        request.setDelFlag(2);
        request.setFeaturedId(88L);
        request.setLocationLat(31.2D);

        assertEquals(1, service.updateOwnerRegistrationProfile(request, OLD_OWNER));

        ArgumentCaptor<MmsMerchant> allowed = ArgumentCaptor.forClass(MmsMerchant.class);
        InOrder writes = inOrder(merchants, members);
        writes.verify(members).lockActiveMemberById(OLD_OWNER);
        writes.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        writes.verify(merchants).updateOwnerRegistrationProfile(allowed.capture(), eq(OLD_OWNER));
        assertEquals("重提门店", allowed.getValue().getName());
        assertEquals(Long.valueOf(0L), allowed.getValue().getStatus());
        assertEquals("", allowed.getValue().getReson());
        assertEquals("900", allowed.getValue().getUpdateBy());
        assertNull(allowed.getValue().getMemberId());
        assertNull(allowed.getValue().getAccountStatus());
        assertNull(allowed.getValue().getBusinessStatus());
        assertNull(allowed.getValue().getDelFlag());
        assertNull(allowed.getValue().getFeaturedId());
    }

    @Test
    void ownerRegistrationUpdateRejectsDisabledOwnerAndChangedLifecycle() {
        MmsMerchant current = currentMerchant();
        current.setStatus(2L);
        current.setAccountStatus(0L);
        current.setDelFlag(0);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(current);
        when(members.lockActiveMemberById(OLD_OWNER)).thenReturn(null);

        ServiceException disabled = assertThrows(ServiceException.class,
                () -> service.updateOwnerRegistrationProfile(ownerProfilePatch(), OLD_OWNER));
        assertEquals(403, disabled.getCode());
        verify(merchants, never()).updateOwnerRegistrationProfile(any(), any());

        when(members.lockActiveMemberById(OLD_OWNER)).thenReturn(OLD_OWNER);
        current.setStatus(1L);
        ServiceException lifecycle = assertThrows(ServiceException.class,
                () -> service.updateOwnerRegistrationProfile(ownerProfilePatch(), OLD_OWNER));
        assertEquals(409, lifecycle.getCode());
        verify(merchants, never()).updateOwnerRegistrationProfile(any(), any());
    }

    @Test
    void ownerRegistrationConditionalWriteMissFailsClosed() {
        MmsMerchant current = currentMerchant();
        current.setStatus(2L);
        current.setAccountStatus(0L);
        current.setDelFlag(0);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(current);
        when(members.lockActiveMemberById(OLD_OWNER)).thenReturn(OLD_OWNER);
        when(merchants.updateOwnerRegistrationProfile(any(), eq(OLD_OWNER))).thenReturn(0);

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.updateOwnerRegistrationProfile(ownerProfilePatch(), OLD_OWNER));

        assertEquals(409, conflict.getCode());
    }

    @Test
    void authorizedProfileWriteRejectsOwnerChangedAfterControllerAuthorization() {
        MmsMerchant transferred = currentMerchant();
        transferred.setMemberId(NEW_OWNER);
        transferred.setStatus(1L);
        transferred.setAccountStatus(1L);
        transferred.setDelFlag(0);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(transferred);
        when(members.lockActiveMemberById(OLD_OWNER)).thenReturn(OLD_OWNER);

        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setBusinessStatus(0);

        ServiceException forbidden = assertThrows(ServiceException.class, () ->
                service.updateAuthorizedProfile(patch, OLD_OWNER, MerchantPermission.PROFILE_WRITE));

        assertEquals(403, forbidden.getCode());
        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void authorizedProfileWriteRejectsRevokedOperatorAfterControllerAuthorization() {
        MmsMerchant current = activeMerchant();
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(current);
        when(members.lockActiveMemberById(901L)).thenReturn(901L);
        when(operators.selectActiveByMemberIdForUpdate(901L)).thenReturn(null);

        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setDemand("旧员工并发覆盖");

        ServiceException forbidden = assertThrows(ServiceException.class, () ->
                service.updateAuthorizedProfile(patch, 901L, MerchantPermission.COOP_MANAGE));

        assertEquals(403, forbidden.getCode());
        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void authorizedProfileWriteLocksMerchantMemberAndCurrentOperatorBeforeWriting() {
        MmsMerchant current = activeMerchant();
        MerchantOperator operator = new MerchantOperator();
        operator.setId(5L);
        operator.setMerchantId(MERCHANT);
        operator.setOperatorMemberId(901L);
        operator.setActiveOperatorMemberId(901L);
        operator.setRoleCode("MERCHANT_MANAGER");
        operator.setStatus(MerchantOperator.STATUS_ACTIVE);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(current);
        when(members.lockActiveMemberById(901L)).thenReturn(901L);
        when(operators.selectActiveByMemberIdForUpdate(901L)).thenReturn(operator);
        when(merchants.updateMmsMerchant(any(MmsMerchant.class))).thenReturn(1);

        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setDemand("新的合作说明");

        assertEquals(1, service.updateAuthorizedProfile(
                patch, 901L, MerchantPermission.COOP_MANAGE));

        InOrder writes = inOrder(merchants, members, operators);
        writes.verify(members).lockActiveMemberById(901L);
        writes.verify(merchants).selectMmsMerchantByIdForUpdate(MERCHANT);
        writes.verify(operators).selectActiveByMemberIdForUpdate(901L);
        writes.verify(merchants).updateMmsMerchant(any(MmsMerchant.class));
    }

    @Test
    void authorizedProfileWriteRechecksCurrentRolePermissionAfterLock() {
        MmsMerchant current = activeMerchant();
        MerchantOperator operator = new MerchantOperator();
        operator.setId(6L);
        operator.setMerchantId(MERCHANT);
        operator.setOperatorMemberId(901L);
        operator.setActiveOperatorMemberId(901L);
        operator.setRoleCode("MERCHANT_MARKETING");
        operator.setStatus(MerchantOperator.STATUS_ACTIVE);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(current);
        when(members.lockActiveMemberById(901L)).thenReturn(901L);
        when(operators.selectActiveByMemberIdForUpdate(901L)).thenReturn(operator);

        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setSlogan("越权装修");

        ServiceException forbidden = assertThrows(ServiceException.class, () ->
                service.updateAuthorizedProfile(patch, 901L, MerchantPermission.PROFILE_WRITE));

        assertEquals(403, forbidden.getCode());
        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void missingMerchantReturnsZeroWithoutDelegationWrites() {
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(null);
        MmsMerchant patch = ownerChangePatch();

        assertEquals(0, service.updateMmsMerchant(patch));

        verify(operators, never()).revokeAllActiveByMerchantId(any(), any(), any());
        verify(invites, never()).revokeAllPendingByMerchantId(any(), any());
        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void platformDisableWritesTrimmedReasonThroughWhitelistedStatement() {
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(activeMerchant());
        when(merchants.updatePlatformAccountStatus(
                eq(MERCHANT), eq(2L), eq("平台风控核查"), eq("admin"), any())).thenReturn(1);

        assertEquals(1, service.updatePlatformAccountStatus(MERCHANT, 2L, " 平台风控核查 ", "admin"));

        verify(merchants).updatePlatformAccountStatus(
                eq(MERCHANT), eq(2L), eq("平台风控核查"), eq("admin"), any());
        // 停用原因只走专用白名单语句,不借通用 update 透传(否则可能顺带写坏 reson/其它资料)
        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void platformDisableWithoutReasonFailsBeforeAnyWrite() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updatePlatformAccountStatus(MERCHANT, 2L, "   ", "admin"));

        assertNotNull(error.getMessage());
        verify(merchants, never()).selectMmsMerchantByIdForUpdate(any());
        verify(merchants, never()).updatePlatformAccountStatus(any(), any(), any(), any(), any());
    }

    @Test
    void platformDisableRejectsReasonLongerThanColumn() {
        String tooLong = new String(new char[501]).replace('\0', '原');

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updatePlatformAccountStatus(MERCHANT, 2L, tooLong, "admin"));

        assertNotNull(error.getMessage());
        verify(merchants, never()).updatePlatformAccountStatus(any(), any(), any(), any(), any());
    }

    @Test
    void platformEnableClearsDisableReasonAndNeverReusesReson() {
        MmsMerchant disabled = activeMerchant();
        disabled.setAccountStatus(2L);
        disabled.setDisableReason("平台风控核查");
        disabled.setReson("执照模糊");
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(disabled);
        when(merchants.updatePlatformAccountStatus(
                eq(MERCHANT), eq(1L), eq(""), eq("admin"), any())).thenReturn(1);

        assertEquals(1, service.updatePlatformAccountStatus(MERCHANT, 1L, null, "admin"));

        // 启用清空停用原因;reson(驳回原因)不在本语句的列白名单里,原地不动
        verify(merchants).updatePlatformAccountStatus(
                eq(MERCHANT), eq(1L), eq(""), eq("admin"), any());
        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void platformStatusRejectsStatesOtherThanEnableOrDisable() {
        for (Long state : new Long[] {null, 0L, 3L}) {
            assertThrows(ServiceException.class,
                    () -> service.updatePlatformAccountStatus(MERCHANT, state, "原因", "admin"),
                    String.valueOf(state));
        }

        verify(merchants, never()).updatePlatformAccountStatus(any(), any(), any(), any(), any());
    }

    @Test
    void platformStatusFailsClosedWhenMerchantMissingDeletedOrWriteMisses() {
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(null);
        assertThrows(ServiceException.class,
                () -> service.updatePlatformAccountStatus(MERCHANT, 2L, "原因", "admin"));

        MmsMerchant deleted = activeMerchant();
        deleted.setDelFlag(2);
        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(deleted);
        assertThrows(ServiceException.class,
                () -> service.updatePlatformAccountStatus(MERCHANT, 2L, "原因", "admin"));

        when(merchants.selectMmsMerchantByIdForUpdate(MERCHANT)).thenReturn(activeMerchant());
        when(merchants.updatePlatformAccountStatus(any(), any(), any(), any(), any())).thenReturn(0);
        assertThrows(ServiceException.class,
                () -> service.updatePlatformAccountStatus(MERCHANT, 2L, "原因", "admin"));

        verify(merchants, never()).updateMmsMerchant(any());
    }

    @Test
    void updateMethodDeclaresRollbackTransactionBoundary() throws Exception {
        Transactional boundary = MmsMerchantServiceImpl.class
                .getMethod("updateMmsMerchant", MmsMerchant.class)
                .getAnnotation(Transactional.class);
        Transactional ownerBoundary = MmsMerchantServiceImpl.class
                .getMethod("updateOwnerRegistrationProfile", MmsMerchant.class, Long.class)
                .getAnnotation(Transactional.class);
        Transactional authorizedBoundary = MmsMerchantServiceImpl.class
                .getMethod("updateAuthorizedProfile", MmsMerchant.class, Long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional platformBoundary = MmsMerchantServiceImpl.class
                .getMethod("updatePlatformAccountStatus", Long.class, Long.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertNotNull(boundary);
        assertEquals(Exception.class, boundary.rollbackFor()[0]);
        assertNotNull(ownerBoundary);
        assertEquals(Exception.class, ownerBoundary.rollbackFor()[0]);
        assertNotNull(authorizedBoundary);
        assertEquals(Exception.class, authorizedBoundary.rollbackFor()[0]);
        assertNotNull(platformBoundary);
        assertEquals(Exception.class, platformBoundary.rollbackFor()[0]);
    }

    private MmsMerchant currentMerchant() {
        MmsMerchant row = new MmsMerchant();
        row.setId(MERCHANT);
        row.setMemberId(OLD_OWNER);
        return row;
    }

    private MmsMerchant activeMerchant() {
        MmsMerchant row = currentMerchant();
        row.setStatus(1L);
        row.setAccountStatus(1L);
        row.setDelFlag(0);
        return row;
    }

    private MmsMerchant ownerChangePatch() {
        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setMemberId(NEW_OWNER);
        return patch;
    }

    private MmsMerchant ownerProfilePatch() {
        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT);
        patch.setName("重提门店");
        patch.setStatus(0L);
        return patch;
    }
}
