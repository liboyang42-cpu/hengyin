package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.WxPaymentCallbackOutbox;
import com.chengyinhub.business.mapper.WxPaymentCallbackOutboxMapper;
import com.chengyinhub.business.service.IWxPaymentCallbackOutboxService;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 出站重试队列的 fencing 实现(表 wx_payment_callback_outbox)。
 *
 * <p>只实现队列本身的登记/领取/终结语义,不含调度与真实微信调用(消费方由后续接入的
 * 定时任务负责:claimForRetry → 调微信查询 → complete/retryOrEscalate)。</p>
 *
 * <p>任务书 1.6 节两道闸都落在数据层,不靠调用方自觉:</p>
 * <ul>
 *   <li>{@code moneyAffecting=true} 登记时直接进 {@code manual_required},且 claim SQL 里
 *       恒有 {@code money_affecting = 0} —— 这类动作(如二次转账)永远不被自动派发;</li>
 *   <li>自动重试以 {@code max_attempts}(默认3)为上限,失败到顶即转 {@code manual_required},
 *       间隔递增(60s × attemptCount),不存在无限重试。</li>
 * </ul>
 */
@Service
public class WxPaymentCallbackOutboxServiceImpl implements IWxPaymentCallbackOutboxService
{
    /** 任务书1.6:自动重试上限,超过转人工。 */
    static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAX_ERROR_CODE_LENGTH = 64;

    @Autowired
    private WxPaymentCallbackOutboxMapper mapper;

    @Override
    @Transactional
    public boolean enqueue(int bizType, Long bizId, String idempotencyKey, boolean moneyAffecting)
    {
        if (bizType <= 0 || bizId == null || bizId <= 0 || StringUtils.isEmpty(idempotencyKey))
        {
            return false;
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH)
        {
            return false;
        }
        WxPaymentCallbackOutbox row = new WxPaymentCallbackOutbox();
        row.setBizType(bizType);
        row.setBizId(bizId);
        row.setIdempotencyKey(key);
        row.setMoneyAffecting(moneyAffecting);
        // 涉及金额变化的动作不接受自动派发:登记即人工态,人工处理完才算终结。
        row.setState(moneyAffecting
                ? WxPaymentCallbackOutbox.STATE_MANUAL_REQUIRED : WxPaymentCallbackOutbox.STATE_PENDING);
        row.setFenceToken(0L);
        row.setAttemptCount(0);
        row.setMaxAttempts(DEFAULT_MAX_ATTEMPTS);
        return mapper.insertIgnore(row) == 1;
    }

    @Override
    @Transactional
    public List<WxPaymentCallbackOutbox> claimForRetry(int limit)
    {
        if (limit <= 0)
        {
            return Collections.emptyList();
        }
        List<WxPaymentCallbackOutbox> candidates = mapper.selectClaimableForUpdate(limit);
        if (candidates == null || candidates.isEmpty())
        {
            return Collections.emptyList();
        }
        String owner = UUID.randomUUID().toString().replace("-", "");
        List<WxPaymentCallbackOutbox> claimed = new ArrayList<>(candidates.size());
        for (WxPaymentCallbackOutbox row : candidates)
        {
            // SQL 已排除 money_affecting,这里再兜一层:任何未来 SQL 改动都不能把资金动作带进自动派发。
            if (row == null || row.getId() == null || Boolean.TRUE.equals(row.getMoneyAffecting()))
            {
                continue;
            }
            if (mapper.claimById(row.getId(), owner) != 1)
            {
                continue;
            }
            row.setState(WxPaymentCallbackOutbox.STATE_DISPATCHING);
            row.setOwnerToken(owner);
            row.setFenceToken(row.getFenceToken() == null ? 1L : row.getFenceToken() + 1);
            row.setAttemptCount(row.getAttemptCount() == null ? 1 : row.getAttemptCount() + 1);
            claimed.add(row);
        }
        return claimed;
    }

    @Override
    @Transactional
    public boolean complete(Long id, String ownerToken, long fenceToken)
    {
        return !invalidOwnership(id, ownerToken, fenceToken)
                && mapper.complete(id, ownerToken, fenceToken) == 1;
    }

    @Override
    @Transactional
    public boolean retryOrEscalate(Long id, String ownerToken, long fenceToken, String errorCode)
    {
        return !invalidOwnership(id, ownerToken, fenceToken)
                && mapper.retryOrEscalate(id, ownerToken, fenceToken, truncateErrorCode(errorCode)) == 1;
    }

    @Override
    @Transactional
    public boolean escalateToManual(Long id, String ownerToken, long fenceToken, String reason)
    {
        return !invalidOwnership(id, ownerToken, fenceToken)
                && mapper.escalateToManual(id, ownerToken, fenceToken, truncateErrorCode(reason)) == 1;
    }

    private static boolean invalidOwnership(Long id, String ownerToken, long fenceToken)
    {
        return id == null || id <= 0 || StringUtils.isEmpty(ownerToken) || fenceToken <= 0;
    }

    private static String truncateErrorCode(String errorCode)
    {
        if (StringUtils.isEmpty(errorCode))
        {
            return null;
        }
        String trimmed = errorCode.trim();
        return trimmed.length() <= MAX_ERROR_CODE_LENGTH
                ? trimmed : trimmed.substring(0, MAX_ERROR_CODE_LENGTH);
    }
}
