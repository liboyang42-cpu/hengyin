package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.MerchantClaim;
import com.chengyinhub.business.domain.MerchantClaimProof;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.dto.MerchantClaimDTO;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.mapper.MerchantClaimMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IMerchantClaimService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Service
public class MerchantClaimServiceImpl implements IMerchantClaimService {
    private final MerchantClaimMapper claimMapper;
    private final MmsMerchantMapper merchantMapper;
    private final UmsMemberMapper memberMapper;
    private final MerchantOperatorMapper operatorMapper;
    private final ICapabilityService capabilityService;
    @Autowired private IMmsMerchantService merchantService;

    public MerchantClaimServiceImpl(MerchantClaimMapper claimMapper, MmsMerchantMapper merchantMapper,
                                    UmsMemberMapper memberMapper, MerchantOperatorMapper operatorMapper,
                                    ICapabilityService capabilityService) {
        this.claimMapper = claimMapper;
        this.merchantMapper = merchantMapper;
        this.memberMapper = memberMapper;
        this.operatorMapper = operatorMapper;
        this.capabilityService = capabilityService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantClaim submit(Long claimantMemberId, MerchantClaimDTO.SubmitRequest request) {
        validatePrincipal(claimantMemberId);
        validateSubmit(request);
        String fingerprint = submitFingerprint(request);
        if (memberMapper.lockMemberById(claimantMemberId) == null) {
            throw error("MEMBER_NOT_FOUND", "申请人不存在", 404);
        }
        MerchantClaim replay = claimMapper.selectByClaimantRequestForUpdate(claimantMemberId, request.getRequestId());
        if (replay != null) {
            if (!fingerprint.equals(replay.getSubmitFingerprint())) {
                throw error("IDEMPOTENCY_CONFLICT", "同一 requestId 的认领内容不一致", 409);
            }
            return replay;
        }
        if (merchantMapper.countMmsMerchantRowsByMemberIdForUpdate(claimantMemberId) > 0
                || operatorMapper.selectActiveByMemberIdForUpdate(claimantMemberId) != null
                || capabilityService.hasCapability(claimantMemberId, MemberCapability.CAP_CLUB_LEADER)) {
            throw error("IDENTITY_CONFLICT", "当前账号已有互斥的商家或主理人身份", 409);
        }
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByIdForUpdate(request.getMerchantId());
        if (merchant == null || !Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || Integer.valueOf(2).equals(merchant.getDelFlag())) {
            throw error("MERCHANT_NOT_CLAIMABLE", "商家不存在或不可认领", 409);
        }
        if (merchant.getMemberId() != null) {
            throw error("MERCHANT_ALREADY_OWNED", "该商家已有所有者", 409);
        }
        if (claimMapper.selectActiveByMerchantForUpdate(request.getMerchantId()) != null) {
            throw error("ACTIVE_CLAIM_EXISTS", "该商家已有处理中或已通过的认领", 409);
        }
        Date now = new Date();
        MerchantClaim claim = new MerchantClaim();
        claim.setMerchantId(request.getMerchantId());
        claim.setClaimantMemberId(claimantMemberId);
        claim.setContactName(trim(request.getContactName()));
        claim.setContactPhone(trim(request.getContactPhone()));
        claim.setStatement(trim(request.getStatement()));
        claim.setSubmitRequestId(trim(request.getRequestId()));
        claim.setSubmitFingerprint(fingerprint);
        claim.setStatus("PENDING");
        claim.setVersion(0);
        claim.setSubmittedAt(now);
        claim.setCreateTime(now);
        claim.setUpdateTime(now);
        try {
            if (claimMapper.insertClaim(claim) != 1) throw error("CLAIM_CREATE_FAILED", "认领提交失败", 500);
        } catch (DuplicateKeyException e) {
            throw error("CLAIM_CONFLICT", "该商家已有认领或 requestId 已使用", 409);
        }
        for (MerchantClaimDTO.Proof proofRequest : request.getProofs()) {
            MerchantClaimProof proof = new MerchantClaimProof();
            proof.setClaimId(claim.getId());
            proof.setProofType(trim(proofRequest.getProofType()));
            proof.setAssetId(trim(proofRequest.getAssetId()));
            if (claimMapper.insertProof(proof) != 1) throw error("PROOF_CREATE_FAILED", "证明材料保存失败", 500);
        }
        return claim;
    }

    @Override
    public List<MerchantClaim> status(Long claimantMemberId) {
        validatePrincipal(claimantMemberId);
        List<MerchantClaim> claims = claimMapper.selectByClaimant(claimantMemberId);
        return enrich(claims);
    }

    @Override
    public List<MerchantClaim> pending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return enrich(claimMapper.selectPending(safeLimit));
    }

    @Override
    public MerchantClaim get(Long claimId) {
        MerchantClaim claim = claimId == null ? null : claimMapper.selectById(claimId);
        if (claim != null) claim.setProofs(claimMapper.selectProofsByClaimId(claim.getId()));
        return claim;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MerchantClaim audit(Long auditorId, MerchantClaimDTO.AuditRequest request) {
        validatePrincipal(auditorId);
        validateAudit(request);
        MerchantClaim initial = claimMapper.selectById(request.getClaimId());
        if (initial == null) throw error("CLAIM_NOT_FOUND", "认领申请不存在", 404);
        if ("APPROVE".equals(request.getAction()) && memberMapper.lockMemberById(initial.getClaimantMemberId()) == null) {
            throw error("MEMBER_NOT_FOUND", "申请人不存在", 404);
        }
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByIdForUpdate(initial.getMerchantId());
        MerchantClaim claim = claimMapper.selectByIdForUpdate(request.getClaimId());
        if (claim == null) throw error("CLAIM_NOT_FOUND", "认领申请不存在", 404);
        String fingerprint = auditFingerprint(request);
        if (!"PENDING".equals(claim.getStatus())) {
            if (request.getRequestId().equals(claim.getAuditRequestId())
                    && fingerprint.equals(claim.getAuditFingerprint())) return claim;
            throw error("STATE_CONFLICT", "认领状态已变化", 409);
        }
        if (!request.getExpectedStatus().equals(claim.getStatus())
                || !request.getExpectedVersion().equals(claim.getVersion())) {
            throw error("STATE_CONFLICT", "认领状态或版本已变化", 409);
        }
        String nextStatus = "APPROVE".equals(request.getAction()) ? "APPROVED" : "REJECTED";
        if ("APPROVED".equals(nextStatus)) {
            if (merchant == null || merchant.getMemberId() != null) {
                throw error("MERCHANT_ALREADY_OWNED", "商家已被认领", 409);
            }
            Long claimantId = claim.getClaimantMemberId();
            if (merchantMapper.countMmsMerchantRowsByMemberIdForUpdate(claimantId) > 0
                    || operatorMapper.selectActiveByMemberIdForUpdate(claimantId) != null) {
                throw error("IDENTITY_CONFLICT", "申请人身份已不满足认领条件", 409);
            }
            merchantService.activateMerchantOwner(claimantId);
            if (merchantMapper.claimOwnerCas(claim.getMerchantId(), claimantId, auditorId) != 1) {
                throw error("OWNER_CAS_LOST", "商家已被其他申请人认领", 409);
            }
        }
        if (claimMapper.auditCas(claim.getId(), request.getExpectedStatus(), request.getExpectedVersion(),
                nextStatus, auditorId, trim(request.getReason()), trim(request.getRequestId()), fingerprint) != 1) {
            throw error("STATE_CAS_LOST", "认领状态已被其他审核操作更新", 409);
        }
        return get(claim.getId());
    }

    private void validateSubmit(MerchantClaimDTO.SubmitRequest request) {
        if (request == null || request.getMerchantId() == null || request.getMerchantId() <= 0
                || !hasText(request.getRequestId()) || request.getRequestId().length() > 64
                || !hasText(request.getContactName()) || !hasText(request.getContactPhone())
                || request.getContactName().trim().length() > 64 || request.getContactPhone().trim().length() > 32
                || (request.getStatement() != null && request.getStatement().trim().length() > 1000)
                || request.getProofs() == null || request.getProofs().isEmpty() || request.getProofs().size() > 10) {
            throw error("INVALID_REQUEST", "商家、requestId、联系人、电话和 1-10 份证明材料必填", 400);
        }
        for (MerchantClaimDTO.Proof proof : request.getProofs()) {
            if (proof == null || !hasText(proof.getProofType()) || !hasText(proof.getAssetId())) {
                throw error("INVALID_PROOF", "证明材料类型和资源标识必填", 400);
            }
            if (proof.getProofType().trim().length() > 32 || proof.getAssetId().trim().length() > 255) {
                throw error("INVALID_PROOF", "证明材料类型或资源标识过长", 400);
            }
        }
    }

    private void validateAudit(MerchantClaimDTO.AuditRequest request) {
        if (request == null || request.getClaimId() == null || request.getClaimId() <= 0
                || !hasText(request.getRequestId()) || request.getRequestId().trim().length() > 96
                || request.getExpectedVersion() == null
                || !"PENDING".equals(request.getExpectedStatus())
                || !("APPROVE".equals(request.getAction()) || "REJECT".equals(request.getAction()))) {
            throw error("INVALID_REQUEST", "审核请求缺少稳定 requestId、PENDING 状态或版本", 400);
        }
        if ("REJECT".equals(request.getAction()) && !hasText(request.getReason())) {
            throw error("REASON_REQUIRED", "驳回必须填写原因", 400);
        }
        if (request.getReason() != null && request.getReason().trim().length() > 500) {
            throw error("INVALID_REASON", "审核原因过长", 400);
        }
    }

    private String submitFingerprint(MerchantClaimDTO.SubmitRequest request) {
        List<String> proofs = new ArrayList<>();
        for (MerchantClaimDTO.Proof proof : request.getProofs()) {
            proofs.add(trim(proof.getProofType()) + ":" + trim(proof.getAssetId()));
        }
        proofs.sort(Comparator.naturalOrder());
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(String.valueOf(request.getMerchantId()),
                trim(request.getContactName()), trim(request.getContactPhone()), trim(request.getStatement()),
                String.join(",", proofs)));
    }

    private String auditFingerprint(MerchantClaimDTO.AuditRequest request) {
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(String.valueOf(request.getClaimId()), request.getAction(),
                trim(request.getReason()), request.getExpectedStatus(), String.valueOf(request.getExpectedVersion())));
    }

    private void validatePrincipal(Long principal) {
        if (principal == null || principal <= 0) throw error("UNAUTHORIZED", "请先登录", 401);
    }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private MerchantClaimException error(String code, String message, int httpCode) {
        return new MerchantClaimException(code, message, httpCode);
    }

    private List<MerchantClaim> enrich(List<MerchantClaim> claims) {
        if (claims == null) return new ArrayList<>();
        for (MerchantClaim claim : claims) {
            claim.setProofs(claimMapper.selectProofsByClaimId(claim.getId()));
        }
        return claims;
    }
}
