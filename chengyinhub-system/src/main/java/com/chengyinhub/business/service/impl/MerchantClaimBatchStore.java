package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantClaimBatchCommand;
import com.chengyinhub.business.domain.MerchantClaimBatchResult;
import com.chengyinhub.business.exception.MerchantClaimException;
import com.chengyinhub.business.mapper.MerchantClaimMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
public class MerchantClaimBatchStore {
    private final MerchantClaimMapper mapper;
    public MerchantClaimBatchStore(MerchantClaimMapper mapper) { this.mapper = mapper; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public MerchantClaimBatchCommand open(Long actorId, String requestId, String fingerprint, String action) {
        MerchantClaimBatchCommand existing = mapper.selectBatchCommandForUpdate(actorId, requestId);
        if (existing != null) {
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new MerchantClaimException("IDEMPOTENCY_CONFLICT", "同一 requestId 的批量内容不一致", 409);
            }
            return existing;
        }
        MerchantClaimBatchCommand command = new MerchantClaimBatchCommand();
        command.setActorId(actorId);
        command.setRequestId(requestId);
        command.setRequestFingerprint(fingerprint);
        command.setAction(action);
        command.setCreateTime(new Date());
        try {
            if (mapper.insertBatchCommand(command) != 1) {
                throw new MerchantClaimException("BATCH_CREATE_FAILED", "批量命令创建失败", 500);
            }
        } catch (DuplicateKeyException e) {
            MerchantClaimBatchCommand raced = mapper.selectBatchCommandForUpdate(actorId, requestId);
            if (raced == null || !fingerprint.equals(raced.getRequestFingerprint())) {
                throw new MerchantClaimException("IDEMPOTENCY_CONFLICT", "同一 requestId 的批量内容不一致", 409);
            }
            return raced;
        }
        return command;
    }

    public MerchantClaimBatchResult findResult(Long commandId, Long claimId) {
        return mapper.selectBatchResult(commandId, claimId);
    }

    public List<MerchantClaimBatchResult> listResults(Long commandId) {
        return mapper.selectBatchResults(commandId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveResult(MerchantClaimBatchResult result) {
        try {
            if (mapper.insertBatchResult(result) != 1) {
                throw new MerchantClaimException("RESULT_SAVE_FAILED", "批量逐项结果保存失败", 500);
            }
        } catch (DuplicateKeyException e) {
            if (mapper.selectBatchResult(result.getBatchCommandId(), result.getClaimId()) == null) {
                throw new MerchantClaimException("RESULT_SAVE_FAILED", "批量逐项结果保存失败", 500);
            }
        }
    }
}
