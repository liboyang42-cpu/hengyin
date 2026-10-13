package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantClaim;
import com.chengyinhub.business.domain.MerchantClaimBatchCommand;
import com.chengyinhub.business.domain.MerchantClaimBatchResult;
import com.chengyinhub.business.domain.dto.MerchantClaimDTO;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.service.IMerchantClaimBatchService;
import com.chengyinhub.business.service.IMerchantClaimService;
import com.chengyinhub.business.service.support.CanonicalDigest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MerchantClaimBatchServiceImpl implements IMerchantClaimBatchService {
    private static final int MAX_BATCH_SIZE = 100;
    private final IMerchantClaimService claimService;
    private final MerchantClaimBatchStore store;

    public MerchantClaimBatchServiceImpl(IMerchantClaimService claimService, MerchantClaimBatchStore store) {
        this.claimService = claimService;
        this.store = store;
    }

    @Override
    public List<MerchantClaimBatchResult> auditBatch(Long auditorId, MerchantClaimDTO.BatchRequest request) {
        validate(auditorId, request);
        String fingerprint = fingerprint(request);
        MerchantClaimBatchCommand command = store.open(auditorId, request.getRequestId().trim(), fingerprint, request.getAction());
        List<MerchantClaimBatchResult> results = new ArrayList<>();
        for (MerchantClaimDTO.BatchItem item : request.getItems()) {
            MerchantClaimBatchResult existing = store.findResult(command.getId(), item.getClaimId());
            if (existing != null) {
                results.add(existing);
                continue;
            }
            MerchantClaimBatchResult result = executeOne(auditorId, command.getId(), request, item);
            store.saveResult(result);
            results.add(result);
        }
        return results;
    }

    private MerchantClaimBatchResult executeOne(Long auditorId, Long commandId, MerchantClaimDTO.BatchRequest batch,
                                                 MerchantClaimDTO.BatchItem item) {
        MerchantClaimBatchResult result = new MerchantClaimBatchResult();
        result.setBatchCommandId(commandId);
        result.setClaimId(item.getClaimId());
        MerchantClaimDTO.AuditRequest request = new MerchantClaimDTO.AuditRequest();
        request.setClaimId(item.getClaimId());
        request.setRequestId(batch.getRequestId() + ":" + item.getClaimId());
        request.setAction(batch.getAction());
        request.setReason(batch.getReason());
        request.setExpectedStatus(item.getExpectedStatus());
        request.setExpectedVersion(item.getExpectedVersion());
        try {
            MerchantClaim claim = claimService.audit(auditorId, request);
            result.setSuccess(true);
            result.setCode("OK");
            result.setMessage("处理成功");
            result.setCurrentStatus(claim == null ? null : claim.getStatus());
        } catch (MerchantClaimException e) {
            result.setSuccess(false);
            result.setCode(e.getBusinessCode());
            result.setMessage(e.getMessage());
            MerchantClaim current = claimService.get(item.getClaimId());
            result.setCurrentStatus(current == null ? null : current.getStatus());
        } catch (RuntimeException e) {
            result.setSuccess(false);
            result.setCode("INTERNAL_ERROR");
            result.setMessage("处理失败，请稍后重试");
            MerchantClaim current = claimService.get(item.getClaimId());
            result.setCurrentStatus(current == null ? null : current.getStatus());
        }
        return result;
    }

    private void validate(Long auditorId, MerchantClaimDTO.BatchRequest request) {
        if (auditorId == null || auditorId <= 0) throw error("UNAUTHORIZED", "请先登录", 401);
        if (request == null || request.getRequestId() == null || request.getRequestId().trim().isEmpty()
                || request.getRequestId().trim().length() > 64
                || request.getItems() == null || request.getItems().isEmpty()
                || request.getItems().size() > MAX_BATCH_SIZE
                || !("APPROVE".equals(request.getAction()) || "REJECT".equals(request.getAction()))) {
            throw error("INVALID_REQUEST", "批量命令需包含 requestId、动作和 1-100 个项目", 400);
        }
        if ("REJECT".equals(request.getAction())
                && (request.getReason() == null || request.getReason().trim().isEmpty())) {
            throw error("REASON_REQUIRED", "批量驳回必须填写原因", 400);
        }
        if (request.getReason() != null && request.getReason().trim().length() > 500) {
            throw error("INVALID_REASON", "批量审核原因过长", 400);
        }
        Set<Long> ids = new HashSet<>();
        for (MerchantClaimDTO.BatchItem item : request.getItems()) {
            if (item == null || item.getClaimId() == null || item.getClaimId() <= 0
                    || item.getExpectedVersion() == null || !"PENDING".equals(item.getExpectedStatus())
                    || !ids.add(item.getClaimId())) {
                throw error("INVALID_ITEM", "批量项目必须唯一且带 PENDING 状态与版本", 400);
            }
        }
    }

    private String fingerprint(MerchantClaimDTO.BatchRequest request) {
        List<String> items = new ArrayList<>();
        for (MerchantClaimDTO.BatchItem item : request.getItems()) {
            items.add(item.getClaimId() + ":" + item.getExpectedStatus() + ":" + item.getExpectedVersion());
        }
        items.sort(String::compareTo);
        return CanonicalDigest.sha256Hex(CanonicalDigest.join(request.getAction(),
                request.getReason() == null ? "" : request.getReason().trim(), String.join(",", items)));
    }

    private MerchantClaimException error(String code, String message, int httpCode) {
        return new MerchantClaimException(code, message, httpCode);
    }
}
