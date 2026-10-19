package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberBalanceDetail;
import com.chengyinhub.business.domain.UmsMemberWithdrawal;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.business.mapper.UmsMemberBalanceDetailMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberWithdrawalMapper;
import com.chengyinhub.business.security.WithdrawalPiiProtector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WithdrawalPiiStorageTest {

    @Test
    void newWithdrawalPersistsOnlyEncryptedSensitiveData() {
        UmsMemberWithdrawalMapper withdrawalMapper = mock(UmsMemberWithdrawalMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        UmsMemberBalanceDetailMapper detailMapper = mock(UmsMemberBalanceDetailMapper.class);
        UmsMember member = new UmsMember();
        member.setBalance(new BigDecimal("100.00"));
        when(memberMapper.selectUmsMemberById(7L)).thenReturn(member);
        when(memberMapper.deductWithdrawableBalance(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(new BigDecimal("20.00")), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(withdrawalMapper.insertUmsMemberWithdrawal(any(UmsMemberWithdrawal.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, UmsMemberWithdrawal.class).setId(9L);
            return 1;
        });
        when(detailMapper.insertUmsMemberBalanceDetail(any(UmsMemberBalanceDetail.class))).thenReturn(1);

        UmsMemberWithdrawalServiceImpl service = new UmsMemberWithdrawalServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberWithdrawalMapper", withdrawalMapper);
        // [赏金冻结] withdrawalRequest 会问 frozenAmount;本用例不管冻结,哑 mock 返 0(见 ReferralBountyFreezeTest)。
        com.chengyinhub.business.service.impl.MerchantReferralBountyService bountySvc =
                mock(com.chengyinhub.business.service.impl.MerchantReferralBountyService.class);
        when(bountySvc.frozenAmount(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.math.BigDecimal.ZERO);
        com.chengyinhub.business.service.IPlayerWithdrawalCooldownService cooldownStub = org.mockito.Mockito.mock(com.chengyinhub.business.service.IPlayerWithdrawalCooldownService.class);
        when(cooldownStub.frozenAmount(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.math.BigDecimal.ZERO);
        ReflectionTestUtils.setField(service, "frozenAmountResolver",
                new com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver(bountySvc, cooldownStub));
        ReflectionTestUtils.setField(service, "balanceAdmissionService",
                com.chengyinhub.business.longrun.OWN_C04_21Test.bankFixture(memberMapper, (com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver) ReflectionTestUtils.getField(service, "frozenAmountResolver")));
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "umsMemberBalanceDetailMapper", detailMapper);
        ReflectionTestUtils.setField(service, "withdrawalPiiProtector",
                new WithdrawalPiiProtector("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="));

        WithdrawalDTO request = new WithdrawalDTO();
        request.setRealname("张三");
        request.setBankName("招商银行");
        request.setBankAccount("6222021234567890");
        request.setMobilephone("13800138000");
        request.setWithdrawalAmount(new BigDecimal("20.00"));
        request.setRequestId("pii-storage-withdrawal");

        service.withdrawalRequest(request, 7L, "merchant");

        ArgumentCaptor<UmsMemberWithdrawal> captor = ArgumentCaptor.forClass(UmsMemberWithdrawal.class);
        org.mockito.Mockito.verify(withdrawalMapper).insertUmsMemberWithdrawal(captor.capture());
        UmsMemberWithdrawal stored = captor.getValue();
        assertNull(stored.getRealname());
        assertNull(stored.getBankName());
        assertNull(stored.getBankAccount());
        assertNull(stored.getMobilephone());
        assertTrue(stored.getBankAccountEncrypted().startsWith("v1:"));
        assertEquals("************7890", stored.getBankAccountMask());
        assertEquals("138****8000", stored.getMobilephoneMask());
    }
}
