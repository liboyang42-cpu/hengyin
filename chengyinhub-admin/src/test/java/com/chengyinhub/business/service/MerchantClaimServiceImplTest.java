package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantClaim;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantClaimDTO;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.mapper.MerchantClaimMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MerchantClaimServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantClaimServiceImplTest {
    @Test
    void exactSubmitReplayReturnsOriginalBeforeCheckingNewIdentityState() {
        Dependencies d = dependencies();
        MerchantClaim original = new MerchantClaim();
        original.setId(55L);
        MerchantClaimDTO.SubmitRequest request = submitRequest();
        // 先用同一实现生成的首次写入候选来取得指纹，再模拟重放命中。
        when(d.memberMapper.lockMemberById(7L)).thenReturn(7L);
        when(d.merchantMapper.selectMmsMerchantByIdForUpdate(5L)).thenReturn(claimableMerchant());
        when(d.claimMapper.insertClaim(any(MerchantClaim.class))).thenAnswer(invocation -> {
            MerchantClaim inserted = invocation.getArgument(0);
            inserted.setId(55L);
            original.setSubmitFingerprint(inserted.getSubmitFingerprint());
            return 1;
        });
        when(d.claimMapper.insertProof(any())).thenReturn(1);
        MerchantClaim created = d.service.submit(7L, request);
        when(d.claimMapper.selectByClaimantRequestForUpdate(7L, "claim-stable-key")).thenReturn(original);
        when(d.merchantMapper.countMmsMerchantRowsByMemberIdForUpdate(7L)).thenReturn(1);

        MerchantClaim replay = d.service.submit(7L, request);

        assertEquals(created.getId(), replay.getId());
        verify(d.claimMapper, org.mockito.Mockito.times(1)).insertClaim(any(MerchantClaim.class));
    }

    @Test
    void losingOwnerCasFailsClosedBeforeAuditReceipt() {
        Dependencies d = dependencies();
        MerchantClaim claim = pendingClaim();
        when(d.claimMapper.selectById(3L)).thenReturn(claim);
        when(d.claimMapper.selectByIdForUpdate(3L)).thenReturn(claim);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(5L);
        when(d.merchantMapper.selectMmsMerchantByIdForUpdate(5L)).thenReturn(merchant);
        when(d.memberMapper.lockMemberById(7L)).thenReturn(7L);
        when(d.merchantMapper.claimOwnerCas(5L, 7L, 99L)).thenReturn(0);

        MerchantClaimException error = assertThrows(MerchantClaimException.class,
                () -> d.service.audit(99L, approveRequest()));

        assertEquals("OWNER_CAS_LOST", error.getBusinessCode());
        verify(d.capabilityService, never()).grant(anyLong(), anyString(), anyLong());
        verify(d.claimMapper, never()).auditCas(anyLong(), anyString(), anyInt(), anyString(),
                anyLong(), any(), anyString(), anyString());
    }

    @Test
    void statusCasMustAffectExactlyOneRow() {
        Dependencies d = dependencies();
        MerchantClaim claim = pendingClaim();
        when(d.claimMapper.selectById(3L)).thenReturn(claim);
        when(d.claimMapper.selectByIdForUpdate(3L)).thenReturn(claim);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(5L);
        when(d.merchantMapper.selectMmsMerchantByIdForUpdate(5L)).thenReturn(merchant);
        when(d.memberMapper.lockMemberById(7L)).thenReturn(7L);
        when(d.merchantMapper.claimOwnerCas(5L, 7L, 99L)).thenReturn(1);
        when(d.claimMapper.auditCas(anyLong(), anyString(), anyInt(), anyString(), anyLong(), any(), anyString(), anyString()))
                .thenReturn(0);

        MerchantClaimException error = assertThrows(MerchantClaimException.class,
                () -> d.service.audit(99L, approveRequest()));

        assertEquals("STATE_CAS_LOST", error.getBusinessCode());
    }

    private MerchantClaim pendingClaim() {
        MerchantClaim claim = new MerchantClaim();
        claim.setId(3L);
        claim.setMerchantId(5L);
        claim.setClaimantMemberId(7L);
        claim.setStatus("PENDING");
        claim.setVersion(0);
        return claim;
    }

    private MerchantClaimDTO.AuditRequest approveRequest() {
        MerchantClaimDTO.AuditRequest request = new MerchantClaimDTO.AuditRequest();
        request.setClaimId(3L);
        request.setAction("APPROVE");
        request.setRequestId("audit-stable-key");
        request.setExpectedStatus("PENDING");
        request.setExpectedVersion(0);
        return request;
    }

    private MerchantClaimDTO.SubmitRequest submitRequest() {
        MerchantClaimDTO.SubmitRequest request = new MerchantClaimDTO.SubmitRequest();
        request.setMerchantId(5L);
        request.setRequestId("claim-stable-key");
        request.setContactName("联系人");
        request.setContactPhone("13800000000");
        MerchantClaimDTO.Proof proof = new MerchantClaimDTO.Proof();
        proof.setProofType("BUSINESS_LICENSE");
        proof.setAssetId("asset-key");
        request.setProofs(java.util.Collections.singletonList(proof));
        return request;
    }

    private MmsMerchant claimableMerchant() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(5L);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        return merchant;
    }

    private Dependencies dependencies() {
        Dependencies d = new Dependencies();
        d.claimMapper = mock(MerchantClaimMapper.class);
        d.merchantMapper = mock(MmsMerchantMapper.class);
        d.memberMapper = mock(UmsMemberMapper.class);
        d.operatorMapper = mock(MerchantOperatorMapper.class);
        d.capabilityService = mock(ICapabilityService.class);
        d.merchantService = mock(IMmsMerchantService.class);
        d.service = new MerchantClaimServiceImpl(d.claimMapper, d.merchantMapper, d.memberMapper,
                d.operatorMapper, d.capabilityService);
        org.springframework.test.util.ReflectionTestUtils.setField(d.service, "merchantService", d.merchantService);
        return d;
    }

    private static class Dependencies {
        MerchantClaimMapper claimMapper;
        MmsMerchantMapper merchantMapper;
        UmsMemberMapper memberMapper;
        MerchantOperatorMapper operatorMapper;
        ICapabilityService capabilityService;
        IMmsMerchantService merchantService;
        MerchantClaimServiceImpl service;
    }
}
