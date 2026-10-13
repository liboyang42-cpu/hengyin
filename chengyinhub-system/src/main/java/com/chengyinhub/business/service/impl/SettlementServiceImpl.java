package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.SettlementBatch;
import com.chengyinhub.business.domain.SettlementDetail;
import com.chengyinhub.business.domain.dto.SettlementGenerateDTO;
import com.chengyinhub.business.domain.enums.SettlementBatchState;
import com.chengyinhub.business.domain.vo.SettlementSummaryVO;
import com.chengyinhub.business.mapper.SettlementBatchMapper;
import com.chengyinhub.business.mapper.SettlementDetailMapper;
import com.chengyinhub.business.service.ISettlementService;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class SettlementServiceImpl implements ISettlementService {
    @Autowired
    private SettlementBatchMapper settlementBatchMapper;

    @Autowired
    private SettlementDetailMapper settlementDetailMapper;

    /**
     * 把某商家所有待结算明细（自动入账写入的 status=0 / batch_id=0）汇成一个批次。
     * 不再从请求 DTO 取明细，避免与自动明细双写撞 uk_source_receiver。
     *
     * <p>⚠️ 并发幽灵批次修复(P1-6-S块 1.1):{@code bindBatch}（{@code UPDATE ... WHERE
     * batch_id=0 AND status=0}）是这条路径上唯一真正的互斥点——两次并发调用各自的
     * {@code selectPendingByMerchant} 读快照都不加锁，若各自按自己的快照算好
     * total_amount/detail_count 再插入批次，后提交 bindBatch 的那次会绑到 0 行，却仍留下
     * 一个声称有金额、实际零绑定明细的"幽灵批次"。因此这里改为：先插入占位批次（金额/条数为0）
     * 拿到自增 id，再执行 bindBatch，用它的**实际生效行数**反过来查出真被绑定的明细、据此回填
     * total_amount/detail_count；bindBatch 绑到 0 行时直接抛异常，让本方法的
     * {@code @Transactional(rollbackFor)} 把占位批次一并回滚，不留痕迹。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long generateBatch(SettlementGenerateDTO request) {
        validateGenerateRequest(request);
        SettlementBatch existing = settlementBatchMapper.selectByIdempotencyKey(request.getIdempotencyKey());
        if (existing != null) {
            if (!request.getMerchantId().equals(existing.getMerchantId())) {
                throw new IllegalArgumentException("结算幂等键已属于其他商家");
            }
            return existing.getId();
        }

        List<SettlementDetail> pending = settlementDetailMapper.selectPendingByMerchant(request.getMerchantId());
        if (pending == null || pending.isEmpty()) {
            throw new IllegalStateException("no pending settlement details for merchant " + request.getMerchantId());
        }

        SettlementBatch batch = new SettlementBatch();
        batch.setMerchantId(request.getMerchantId());
        batch.setBatchNo(request.getBatchNo());
        batch.setPeriodStart(request.getPeriodStart());
        batch.setPeriodEnd(request.getPeriodEnd());
        batch.setIdempotencyKey(request.getIdempotencyKey());
        batch.setTotalAmount(BigDecimal.ZERO);
        batch.setDetailCount(0L);
        batch.setSuccessCount(0L);
        batch.setFailedCount(0L);
        batch.setStatus(SettlementBatchState.PENDING.getCode());
        if (settlementBatchMapper.insertSettlementBatch(batch) != 1 || batch.getId() == null) {
            throw new IllegalStateException("结算批次占位写入失败");
        }

        int bound = settlementDetailMapper.bindBatch(batch.getId(), request.getMerchantId());
        if (bound <= 0) {
            throw new IllegalStateException("no pending settlement details for merchant " + request.getMerchantId());
        }

        SettlementDetail boundQuery = new SettlementDetail();
        boundQuery.setBatchId(batch.getId());
        List<SettlementDetail> boundDetails = settlementDetailMapper.selectDetailList(boundQuery);
        if (boundDetails == null || boundDetails.size() != bound) {
            throw new IllegalStateException("结算批次绑定条数与回读不一致");
        }
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (SettlementDetail detail : boundDetails) {
            totalAmount = totalAmount.add(defaultZero(detail.getSettlementAmount()));
        }
        if (settlementBatchMapper.updateBatchTotals(batch.getId(), totalAmount, (long) boundDetails.size()) != 1) {
            throw new IllegalStateException("结算批次金额回填失败");
        }
        return batch.getId();
    }

    /**
     * 确认对账：乐观锁守卫批次只能从待结算流转一次，然后把明细置已结算。重复调用返回 0。
     *
     * <p>⚠️ 本方法不打款，settlement_detail 是纯账本。真实打款由系统1
     * （{@code CmsActivityServiceImpl.settleActivity} / {@code CmsTopicServiceImpl.settleTopic}，
     * 以 {@code is_settlement} 原子领取）独占承担 —— 两套系统曾对同一笔 gross 各切一次，
     * 系统2 一旦也打款即双花，故此处的加余额能力已拆除，见 SettlementPayoutDecommissionedTest。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirmBatch(Long batchId, Long operatorId) {
        if (batchId == null) {
            throw new IllegalArgumentException("batchId is required");
        }
        SettlementBatch batch = settlementBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("settlement batch not found");
        }
        int rows = settlementBatchMapper.updateBatchStatusIfPending(
                batchId, SettlementBatchState.CONFIRMED.getCode(), operatorId, DateUtils.getNowDate());
        if (rows <= 0) {
            return 0;
        }
        settlementDetailMapper.markBatchSettled(batchId);
        return rows;
    }

    @Override
    public List<SettlementBatch> selectBatchList(SettlementBatch query) {
        return settlementBatchMapper.selectBatchList(query);
    }

    @Override
    public List<SettlementDetail> selectDetailList(SettlementDetail query) {
        return settlementDetailMapper.selectDetailList(query);
    }

    @Override
    public SettlementSummaryVO getSummary(Long merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        SettlementSummaryVO summary = settlementBatchMapper.selectSummaryByMerchantId(merchantId);
        if (summary == null) {
            summary = new SettlementSummaryVO();
            summary.setMerchantId(merchantId);
        }
        return summary;
    }

    @Override
    public SettlementSummaryVO getReconciliation(Long merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        SettlementSummaryVO vo = settlementDetailMapper.selectReconciliation(merchantId);
        if (vo == null) {
            vo = new SettlementSummaryVO();
            vo.setMerchantId(merchantId);
            vo.setSettledAmount(BigDecimal.ZERO);
            vo.setReversalAmount(BigDecimal.ZERO);
            vo.setNetAmount(BigDecimal.ZERO);
        }
        return vo;
    }

    private void validateGenerateRequest(SettlementGenerateDTO request) {
        if (request == null || request.getMerchantId() == null || request.getBatchNo() == null
                || request.getBatchNo().trim().isEmpty() || request.getIdempotencyKey() == null
                || request.getIdempotencyKey().trim().isEmpty()) {
            throw new IllegalArgumentException("merchantId, batchNo and idempotencyKey are required");
        }
    }

    private BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
