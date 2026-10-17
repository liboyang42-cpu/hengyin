package com.chengyinhub.business.access.merchant;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyLong;

class MerchantAccessServiceImplTest {

    @Test
    void ownerResolutionIgnoresDeletedHistoryAndUsesTheOnlyActiveMerchantRow() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, mock(UmsMemberMapper.class));
        MmsMerchant deletedHistory = withDelFlag(activeMerchant(10L, 9001L), 1);
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(deletedHistory);
        when(merchantService.selectMmsMerchantList(any(MmsMerchant.class)))
                .thenReturn(Collections.singletonList(activeMerchant(11L, 9001L)));

        MerchantAccessContext context = service.require(9001L, MerchantPermission.BASIC_READ);

        assertEquals(11L, context.getMerchantId());
        ArgumentCaptor<MmsMerchant> query = ArgumentCaptor.forClass(MmsMerchant.class);
        verify(merchantService).selectMmsMerchantList(query.capture());
        assertEquals(9001L, query.getValue().getMemberId());
        assertEquals(1L, query.getValue().getStatus());
        assertEquals(1L, query.getValue().getAccountStatus());
        assertEquals(0, query.getValue().getDelFlag());
        verify(merchantService, never()).selectMmsMerchantByMemberId(9001L);
    }

    @Test
    void ownerResolutionFailsClosedWhenTwoActiveMerchantRowsExist() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, mock(UmsMemberMapper.class));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));
        when(merchantService.selectMmsMerchantList(any(MmsMerchant.class))).thenReturn(Arrays.asList(
                activeMerchant(11L, 9001L), activeMerchant(12L, 9001L)));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.require(9001L, MerchantPermission.BASIC_READ));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        verify(operatorMapper, never()).selectActiveByMemberId(9001L);
        verify(merchantService, never()).selectMmsMerchantByMemberId(9001L);
    }

    @Test
    void derivesOwnerAndKeepsMerchantRowIdSeparateFromOwnerMemberId() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantAccessServiceImpl service = service(merchantService,
                mock(MerchantOperatorMapper.class), mock(UmsMemberMapper.class));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));

        MerchantAccessContext context = service.require(9001L, MerchantPermission.FINANCE_READ);

        assertEquals(9001L, context.getPrincipalMemberId());
        assertEquals(9001L, context.getOwnerMemberId());
        assertEquals(11L, context.getMerchantId());
        assertEquals(MerchantRoleCode.MERCHANT_OWNER, context.getRoleCode());
    }

    @Test
    void resolvesActiveEmployeeWithoutChangingGlobalMemberRole() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, memberMapper);
        MerchantOperator relation = activeOperator(51L, 11L, 9002L, MerchantRoleCode.MERCHANT_CHECKIN);
        when(merchantService.selectMmsMerchantByMemberId(9002L)).thenReturn(null);
        when(operatorMapper.selectActiveByMemberId(9002L)).thenReturn(relation);
        when(merchantService.selectMmsMerchantById(11L)).thenReturn(activeMerchant(11L, 9001L));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));
        when(memberMapper.selectUmsMemberById(9002L)).thenReturn(activeMember(9002L));

        MerchantAccessContext context = service.require(9002L, MerchantPermission.VERIFY);

        assertEquals(9002L, context.getPrincipalMemberId());
        assertEquals(9001L, context.getOwnerMemberId());
        assertEquals(11L, context.getMerchantId());
        assertEquals(MerchantRoleCode.MERCHANT_CHECKIN, context.getRoleCode());
        assertThrows(ServiceException.class,
                () -> service.require(9002L, MerchantPermission.FINANCE_READ));
    }

    @Test
    void operatorResolutionFailsClosedWhenOwnerHasTwoActiveMerchantRows() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, memberMapper);
        when(operatorMapper.selectActiveByMemberId(9002L)).thenReturn(
                activeOperator(51L, 11L, 9002L, MerchantRoleCode.MERCHANT_MANAGER));
        when(merchantService.selectMmsMerchantById(11L)).thenReturn(activeMerchant(11L, 9001L));
        when(memberMapper.selectUmsMemberById(9002L)).thenReturn(activeMember(9002L));
        when(merchantService.selectMmsMerchantList(argThat(query -> Long.valueOf(9001L).equals(query.getMemberId()))))
                .thenReturn(Arrays.asList(activeMerchant(11L, 9001L), activeMerchant(12L, 9001L)));

        ServiceException resolveError = assertThrows(ServiceException.class,
                () -> service.resolve(9002L));
        ServiceException coopError = assertThrows(ServiceException.class,
                () -> service.require(9002L, MerchantPermission.COOP_MANAGE));
        ServiceException crmError = assertThrows(ServiceException.class,
                () -> service.require(9002L, MerchantPermission.CRM_READ));

        assertEquals(HttpStatus.FORBIDDEN, resolveError.getCode());
        assertEquals(HttpStatus.FORBIDDEN, coopError.getCode());
        assertEquals(HttpStatus.FORBIDDEN, crmError.getCode());
    }

    @Test
    void operatorResolutionUsesTheOnlyActiveMerchantRowOwnedByItsOwner() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, memberMapper);
        when(operatorMapper.selectActiveByMemberId(9002L)).thenReturn(
                activeOperator(51L, 11L, 9002L, MerchantRoleCode.MERCHANT_MANAGER));
        when(merchantService.selectMmsMerchantById(11L)).thenReturn(activeMerchant(11L, 9001L));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));
        when(memberMapper.selectUmsMemberById(9002L)).thenReturn(activeMember(9002L));

        MerchantAccessContext context = service.require(9002L, MerchantPermission.COOP_MANAGE);

        assertEquals(11L, context.getMerchantId());
        assertEquals(9001L, context.getOwnerMemberId());
        assertEquals(MerchantRoleCode.MERCHANT_MANAGER, context.getRoleCode());
        verify(merchantService).selectMmsMerchantList(argThat(query ->
                Long.valueOf(9001L).equals(query.getMemberId())
                        && Long.valueOf(1L).equals(query.getStatus())
                        && Long.valueOf(1L).equals(query.getAccountStatus())
                        && Integer.valueOf(0).equals(query.getDelFlag())));
    }

    @Test
    void ownerResolutionHasPriorityAndNeverFallsThroughToEmployeeRelation() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, mock(UmsMemberMapper.class));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));

        assertEquals(MerchantRoleCode.MERCHANT_OWNER,
                service.require(9001L, MerchantPermission.OPERATOR_MANAGE).getRoleCode());

        verify(operatorMapper, never()).selectActiveByMemberId(9001L);
    }

    @Test
    void employeeResolutionFailsClosedForUnknownRoleDisabledMemberOrMerchant() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantOperatorMapper operatorMapper = mock(MerchantOperatorMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        MerchantAccessServiceImpl service = service(merchantService, operatorMapper, memberMapper);
        when(merchantService.selectMmsMerchantByMemberId(9002L)).thenReturn(null);
        when(merchantService.selectMmsMerchantByMemberId(9003L)).thenReturn(null);
        when(merchantService.selectMmsMerchantByMemberId(9004L)).thenReturn(null);
        when(operatorMapper.selectActiveByMemberId(9002L)).thenReturn(
                activeOperator(51L, 11L, 9002L, "MERCHANT_UNKNOWN"));
        when(operatorMapper.selectActiveByMemberId(9003L)).thenReturn(
                activeOperator(52L, 12L, 9003L, MerchantRoleCode.MERCHANT_MANAGER));
        when(operatorMapper.selectActiveByMemberId(9004L)).thenReturn(
                activeOperator(53L, 13L, 9004L, MerchantRoleCode.MERCHANT_MANAGER));
        when(merchantService.selectMmsMerchantById(11L)).thenReturn(activeMerchant(11L, 9001L));
        when(merchantService.selectMmsMerchantById(12L)).thenReturn(activeMerchant(12L, 9001L));
        when(merchantService.selectMmsMerchantById(13L)).thenReturn(inactiveMerchant(13L, 9001L));
        when(memberMapper.selectUmsMemberById(9002L)).thenReturn(activeMember(9002L));
        UmsMember disabled = activeMember(9003L);
        disabled.setStatus(2L);
        when(memberMapper.selectUmsMemberById(9003L)).thenReturn(disabled);
        when(memberMapper.selectUmsMemberById(9004L)).thenReturn(activeMember(9004L));

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class,
                () -> service.require(9002L, MerchantPermission.BASIC_READ)).getCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class,
                () -> service.require(9003L, MerchantPermission.BASIC_READ)).getCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ServiceException.class,
                () -> service.require(9004L, MerchantPermission.BASIC_READ)).getCode());
    }

    @Test
    void rejectsUnknownPrincipalAndInactiveMerchantWithForbiddenCode() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantAccessServiceImpl service = service(merchantService,
                mock(MerchantOperatorMapper.class), mock(UmsMemberMapper.class));
        when(merchantService.selectMmsMerchantByMemberId(77L)).thenReturn(null);
        when(merchantService.selectMmsMerchantByMemberId(88L)).thenReturn(inactiveMerchant(12L, 88L));

        ServiceException unknown = assertThrows(ServiceException.class,
                () -> service.require(77L, MerchantPermission.CRM_READ));
        ServiceException inactive = assertThrows(ServiceException.class,
                () -> service.require(88L, MerchantPermission.CRM_READ));

        assertEquals(HttpStatus.FORBIDDEN, unknown.getCode());
        assertEquals(HttpStatus.FORBIDDEN, inactive.getCode());
    }

    @Test
    void rejectsMissingOrUnknownPermissionFailClosed() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantAccessServiceImpl service = service(merchantService,
                mock(MerchantOperatorMapper.class), mock(UmsMemberMapper.class));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ServiceException.class, () -> service.require(9001L, null)).getCode());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ServiceException.class, () -> service.require(9001L, "merchant:unknown")).getCode());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ServiceException.class, () -> service.require(null, MerchantPermission.VERIFY)).getCode());
    }

    @Test
    void rejectsMerchantRowWhoseOwnerDoesNotMatchPrincipal() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantAccessServiceImpl service = service(merchantService,
                mock(MerchantOperatorMapper.class), mock(UmsMemberMapper.class));
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9002L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.require(9001L, MerchantPermission.BASIC_READ));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
    }

    @Test
    void disabledOwnerOldSessionCannotKeepMerchantOwnerPermissions() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        MerchantAccessServiceImpl service = service(merchantService,
                mock(MerchantOperatorMapper.class), memberMapper);
        when(merchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));
        UmsMember disabled = activeMember(9001L);
        disabled.setStatus(2L);
        when(memberMapper.selectUmsMemberById(9001L)).thenReturn(disabled);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.require(9001L, MerchantPermission.OPERATOR_MANAGE));

        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
    }

    @Test
    void rejectsEveryIncompleteOrDisabledMerchantState() {
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MerchantAccessServiceImpl service = service(merchantService,
                mock(MerchantOperatorMapper.class), mock(UmsMemberMapper.class));

        MmsMerchant[] invalidRows = new MmsMerchant[] {
                withId(activeMerchant(11L, 101L), null),
                withOwner(activeMerchant(12L, 102L), null),
                withStatus(activeMerchant(13L, 103L), null),
                withStatus(activeMerchant(14L, 104L), 0L),
                withAccountStatus(activeMerchant(15L, 105L), null),
                withAccountStatus(activeMerchant(16L, 106L), 0L),
                withAccountStatus(activeMerchant(17L, 107L), 2L),
                withDelFlag(activeMerchant(18L, 108L), null),
                withDelFlag(activeMerchant(19L, 109L), 1)
        };
        for (int index = 0; index < invalidRows.length; index++) {
            long principal = 101L + index;
            when(merchantService.selectMmsMerchantByMemberId(principal)).thenReturn(invalidRows[index]);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> service.require(principal, MerchantPermission.BASIC_READ));
            assertEquals(HttpStatus.FORBIDDEN, error.getCode());
        }

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ServiceException.class,
                        () -> service.require(0L, MerchantPermission.BASIC_READ)).getCode());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ServiceException.class,
                        () -> service.require(-1L, MerchantPermission.BASIC_READ)).getCode());
    }

    @Test
    void merchantProfileDoesNotGrantOwnerPermissionToPlayerRole() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        MerchantAccessServiceImpl service = service(merchants, mock(MerchantOperatorMapper.class), members);
        when(merchants.selectMmsMerchantByMemberId(9001L)).thenReturn(activeMerchant(11L, 9001L));
        UmsMember player = activeMember(9001L); player.setRole("player");
        when(members.selectUmsMemberById(9001L)).thenReturn(player);
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.require(9001L, MerchantPermission.FINANCE_READ)).getCode());
    }

    private static MmsMerchant activeMerchant(Long id, Long ownerMemberId) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(id);
        merchant.setMemberId(ownerMemberId);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }

    private static MmsMerchant inactiveMerchant(Long id, Long ownerMemberId) {
        MmsMerchant merchant = activeMerchant(id, ownerMemberId);
        merchant.setStatus(0L);
        return merchant;
    }

    private static MmsMerchant withId(MmsMerchant merchant, Long id) {
        merchant.setId(id);
        return merchant;
    }

    private static MmsMerchant withOwner(MmsMerchant merchant, Long ownerMemberId) {
        merchant.setMemberId(ownerMemberId);
        return merchant;
    }

    private static MmsMerchant withStatus(MmsMerchant merchant, Long status) {
        merchant.setStatus(status);
        return merchant;
    }

    private static MmsMerchant withAccountStatus(MmsMerchant merchant, Long status) {
        merchant.setAccountStatus(status);
        return merchant;
    }

    private static MmsMerchant withDelFlag(MmsMerchant merchant, Integer delFlag) {
        merchant.setDelFlag(delFlag);
        return merchant;
    }

    private static MerchantAccessServiceImpl service(IMmsMerchantService merchants,
                                                     MerchantOperatorMapper operators,
                                                     UmsMemberMapper members) {
        when(merchants.selectMmsMerchantList(any(MmsMerchant.class))).thenAnswer(invocation -> {
            MmsMerchant query = invocation.getArgument(0, MmsMerchant.class);
            MmsMerchant row = merchants.selectMmsMerchantByMemberId(query.getMemberId());
            return row == null ? Collections.emptyList() : Collections.singletonList(row);
        });
        when(members.selectUmsMemberById(anyLong())).thenAnswer(invocation ->
                activeMember(invocation.getArgument(0, Long.class)));
        MerchantAccessServiceImpl service = new MerchantAccessServiceImpl();
        ReflectionTestUtils.setField(service, "merchantService", merchants);
        ReflectionTestUtils.setField(service, "merchantOperatorMapper", operators);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
        return service;
    }

    private static MerchantOperator activeOperator(Long id, Long merchantId, Long memberId, String roleCode) {
        MerchantOperator operator = new MerchantOperator();
        operator.setId(id);
        operator.setMerchantId(merchantId);
        operator.setOperatorMemberId(memberId);
        operator.setStatus(MerchantOperator.STATUS_ACTIVE);
        operator.setRoleCode(roleCode);
        return operator;
    }

    private static UmsMember activeMember(Long id) {
        UmsMember member = new UmsMember();
        member.setId(id);
        member.setRole("merchant");
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }
}
