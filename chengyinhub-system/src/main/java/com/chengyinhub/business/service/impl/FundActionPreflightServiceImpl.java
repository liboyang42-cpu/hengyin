package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.FundActionPreflight;
import com.chengyinhub.business.domain.dto.BankWithdrawalPreflightRequest;
import com.chengyinhub.business.domain.vo.FundActionPreflightVO;
import com.chengyinhub.business.exception.FundActionPreflightException;
import com.chengyinhub.business.mapper.FundActionPreflightMapper;
import com.chengyinhub.business.security.WithdrawalPiiProtector;
import com.chengyinhub.business.service.IFundActionPreflightService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

@Service
public class FundActionPreflightServiceImpl implements IFundActionPreflightService {
    static final String BANK_WITHDRAWAL = "BANK_WITHDRAWAL";

    private final FundActionPreflightMapper mapper;
    private final FundChallengeTokenCodec tokenCodec;
    private final long challengeTtlMillis;
    private final BigDecimal warningAmountThreshold;

    public FundActionPreflightServiceImpl(FundActionPreflightMapper mapper,
                                          FundChallengeTokenCodec tokenCodec,
                                          @Value("${fund.preflight.challenge-seconds:120}") long challengeSeconds,
                                          @Value("${fund.preflight.warning-amount:500.00}") BigDecimal warningAmountThreshold) {
        this.mapper = mapper;
        this.tokenCodec = tokenCodec;
        this.challengeTtlMillis = challengeSeconds * 1000L;
        this.warningAmountThreshold = warningAmountThreshold;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundActionPreflightVO prepareBankWithdrawal(Long memberId, BankWithdrawalPreflightRequest request,
                                                       String contextWhere, String contextDevice) {
        requireMemberAndRequest(memberId, request);
        contextWhere = normalizeContext(contextWhere, 128);
        contextDevice = normalizeContext(contextDevice, 64);
        String fingerprint = fingerprint(memberId, request);
        FundChallengeTokenCodec.FundChallengeBinding binding = binding(memberId, request.getRequestId(), fingerprint);
        Date now = new Date();
        FundActionPreflight existing = mapper.selectByIntent(memberId, BANK_WITHDRAWAL, request.getRequestId());
        if (existing != null) {
            return reuseExisting(existing, memberId, fingerprint, binding, now, contextWhere, contextDevice);
        }

        String counterpartyHash = tokenCodec.counterpartyHash(BANK_WITHDRAWAL,
                request.getBankName(), request.getBankAccount());
        boolean firstDestination = mapper.countConsumedByCounterparty(memberId, BANK_WITHDRAWAL, counterpartyHash) == 0;
        FundActionPreflightPolicy.RiskResult risk = FundActionPreflightPolicy.evaluate(
                firstDestination, request.getAmount(), warningAmountThreshold, null);
        String nonce = tokenCodec.nonce();
        String token = tokenCodec.token(binding, nonce);
        FundActionPreflight row = new FundActionPreflight();
        row.setMemberId(memberId);
        row.setActionType(BANK_WITHDRAWAL);
        row.setRequestId(request.getRequestId());
        row.setRequestFingerprint(fingerprint);
        row.setCounterpartyKeyHash(counterpartyHash);
        row.setChallengeNonce(nonce);
        row.setChallengeTokenHash(tokenCodec.hash(token));
        row.setState(FundActionPreflight.STATE_PENDING);
        row.setAmount(request.getAmount());
        row.setCurrency("CNY");
        row.setDisplayName(request.getBankName().trim());
        row.setDisplayAccountMask(WithdrawalPiiProtector.maskBankAccount(request.getBankAccount().trim()));
        row.setDisplayPhoneMask(WithdrawalPiiProtector.maskPhone(request.getMobilephone().trim()));
        row.setContextInitiatedAt(now);
        row.setContextWhere(contextWhere);
        row.setContextDevice(contextDevice);
        row.setRiskLevel(risk.getLevel().name());
        row.setRiskReasonCodes(String.join(",", risk.getReasonCodes()));
        row.setExpiresAt(new Date(now.getTime() + challengeTtlMillis));
        row.setCreateTime(now);
        row.setUpdateTime(now);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException duplicate) {
            FundActionPreflight winner = mapper.selectByIntent(memberId, BANK_WITHDRAWAL, request.getRequestId());
            if (winner == null) throw duplicate;
            return reuseExisting(winner, memberId, fingerprint, binding, now, contextWhere, contextDevice);
        }
        return response(row, token, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundActionPreflightVO confirm(Long memberId, Long challengeId, String challengeToken) {
        FundActionPreflight row = requireOwned(challengeId, memberId);
        Date now = new Date();
        int changed = mapper.confirmIfPending(row.getId(), memberId, row.getActionType(), row.getRequestId(),
                row.getRequestFingerprint(), tokenCodec.hash(requireToken(challengeToken)), now);
        if (changed != 1) throw stateError(mapper.selectById(challengeId), now);
        row = mapper.selectById(challengeId);
        return response(row, null, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FundActionPreflightVO reject(Long memberId, Long challengeId, String challengeToken) {
        requireOwned(challengeId, memberId);
        Date now = new Date();
        if (mapper.rejectIfPending(challengeId, memberId,
                tokenCodec.hash(requireToken(challengeToken)), now) != 1) {
            throw stateError(mapper.selectById(challengeId), now);
        }
        return response(mapper.selectById(challengeId), null, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void consumeBankWithdrawal(Long memberId, Long challengeId,
                                      BankWithdrawalPreflightRequest actualRequest) {
        requireMemberAndRequest(memberId, actualRequest);
        String fingerprint = fingerprint(memberId, actualRequest);
        Date now = new Date();
        if (mapper.consumeIfConfirmed(challengeId, memberId, BANK_WITHDRAWAL, actualRequest.getRequestId(),
                fingerprint, now) != 1) {
            FundActionPreflight current = mapper.selectById(challengeId);
            if (current == null || !memberId.equals(current.getMemberId())) {
                throw new FundActionPreflightException("PREFLIGHT_NOT_FOUND", "确认请求不存在或无权访问");
            }
            if (current.getState() == FundActionPreflight.STATE_CONSUMED) {
                if (BANK_WITHDRAWAL.equals(current.getActionType())
                        && actualRequest.getRequestId().equals(current.getRequestId())
                        && fingerprint.equals(current.getRequestFingerprint())) {
                    return;
                }
                throw new FundActionPreflightException(
                        "PREFLIGHT_BINDING_MISMATCH", "确认凭证与当前资金操作不匹配");
            }
            throw stateError(current, now);
        }
    }

    private FundActionPreflightVO response(FundActionPreflight row, String token, Date now) {
        FundActionPreflightVO vo = new FundActionPreflightVO();
        vo.setChallengeId(row.getId());
        vo.setChallengeToken(token);
        vo.setState(stateName(row.getState()));
        vo.setServerTime(now);
        vo.setExpiresAt(row.getExpiresAt());
        vo.setExpiresInSeconds(Math.max(0L, (row.getExpiresAt().getTime() - now.getTime()) / 1000L));
        vo.setQuestion("你确认将资金提现到" + row.getDisplayName() + "（" + row.getDisplayAccountMask() + "）吗？");
        vo.setConsequence("确认并提交后将扣减可用余额并进入提现审核；打款完成后可能无法撤回。");
        vo.setRiskLevel(row.getRiskLevel());
        vo.setRiskReasonCodes(row.getRiskReasonCodes() == null || row.getRiskReasonCodes().isEmpty()
                ? Collections.emptyList() : Arrays.asList(row.getRiskReasonCodes().split(",")));
        vo.setCanProceed(!FundActionPreflightPolicy.RiskLevel.BLOCKED.name().equals(row.getRiskLevel()));
        vo.setAmount(row.getAmount());
        vo.setCurrency(row.getCurrency());
        vo.setAccountMask(row.getDisplayAccountMask());
        vo.setPhoneMask(row.getDisplayPhoneMask());
        vo.setContextWhen(row.getContextInitiatedAt());
        vo.setContextWhere(row.getContextWhere());
        vo.setContextWhereStatus(row.getContextWhere() == null ? "UNAVAILABLE" : "AVAILABLE");
        vo.setContextDevice(row.getContextDevice());
        vo.setSafetyMessages(Arrays.asList("城瘾不会通过电话要求你确认资金操作。",
                "如果你不认识或不信任收款账户，请拒绝；资金可能无法追回。"));
        return vo;
    }

    private FundActionPreflightVO reuseExisting(FundActionPreflight existing, Long memberId, String fingerprint,
                                                  FundChallengeTokenCodec.FundChallengeBinding binding, Date now,
                                                  String contextWhere, String contextDevice) {
        requireSameFingerprint(existing, fingerprint);
        if (existing.getState() == FundActionPreflight.STATE_REJECTED
                || existing.getState() == FundActionPreflight.STATE_CONSUMED) {
            throw new FundActionPreflightException("PREFLIGHT_INTENT_CLOSED", "该资金意图已结束，请重新发起");
        }
        if (!existing.getExpiresAt().after(now)) {
            String nonce = tokenCodec.nonce();
            String token = tokenCodec.token(binding, nonce);
            if (mapper.rearmExpired(existing.getId(), memberId, existing.getState(), nonce,
                    tokenCodec.hash(token), new Date(now.getTime() + challengeTtlMillis), now) != 1) {
                throw new FundActionPreflightException("PREFLIGHT_STATE_CHANGED", "确认状态已变化，请刷新后重试");
            }
            existing = mapper.selectById(existing.getId());
        }
        String responseToken = existing.getState() == FundActionPreflight.STATE_PENDING
                ? tokenCodec.token(binding, existing.getChallengeNonce()) : null;
        return response(existing, responseToken, now);
    }

    private void requireMemberAndRequest(Long memberId, BankWithdrawalPreflightRequest request) {
        if (memberId == null || memberId <= 0) throw new FundActionPreflightException("LOGIN_REQUIRED", "请先登录");
        if (request == null || request.getRequestId() == null || request.getAmount() == null
                || request.getAmount().compareTo(BigDecimal.ZERO) <= 0
                || blank(request.getRealname()) || blank(request.getBankName())
                || blank(request.getBankAccount()) || blank(request.getMobilephone())) {
            throw new FundActionPreflightException("PREFLIGHT_INPUT_INVALID", "资金确认参数不完整");
        }
        BigDecimal normalizedAmount = request.getAmount().stripTrailingZeros();
        if (normalizedAmount.scale() > 2
                || request.getAmount().compareTo(new BigDecimal("999999999999.99")) > 0
                || request.getRequestId().length() > 64 || request.getRealname().length() > 64
                || request.getBankName().length() > 128 || request.getBankAccount().length() > 64
                || request.getMobilephone().length() > 32) {
            throw new FundActionPreflightException("PREFLIGHT_INPUT_INVALID", "资金确认参数超出允许范围");
        }
    }

    private FundActionPreflight requireOwned(Long id, Long memberId) {
        FundActionPreflight row = id == null ? null : mapper.selectById(id);
        if (row == null || !memberId.equals(row.getMemberId()))
            throw new FundActionPreflightException("PREFLIGHT_NOT_FOUND", "确认请求不存在或无权访问");
        return row;
    }

    private void requireSameFingerprint(FundActionPreflight row, String fingerprint) {
        if (!fingerprint.equals(row.getRequestFingerprint()))
            throw new FundActionPreflightException("PREFLIGHT_INTENT_CONFLICT", "同一幂等键已绑定不同的资金内容");
    }

    private String fingerprint(Long memberId, BankWithdrawalPreflightRequest request) {
        return FundActionPreflightPolicy.bankWithdrawalFingerprint(memberId, request.getAmount(), request.getRealname(),
                request.getBankName(), request.getBankAccount(), request.getMobilephone());
    }

    private FundChallengeTokenCodec.FundChallengeBinding binding(Long memberId, String requestId, String fingerprint) {
        return new FundChallengeTokenCodec.FundChallengeBinding(memberId, BANK_WITHDRAWAL, requestId, fingerprint);
    }

    private String requireToken(String token) {
        if (token == null || token.trim().isEmpty())
            throw new FundActionPreflightException("PREFLIGHT_TOKEN_REQUIRED", "确认凭证不能为空");
        return token.trim();
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalizeContext(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new FundActionPreflightException("PREFLIGHT_CONTEXT_INVALID", "操作上下文超出允许范围");
        }
        return normalized;
    }

    private FundActionPreflightException stateError(FundActionPreflight row, Date now) {
        if (row == null) return new FundActionPreflightException("PREFLIGHT_NOT_FOUND", "确认请求不存在");
        if (!row.getExpiresAt().after(now)) return new FundActionPreflightException("PREFLIGHT_EXPIRED", "确认已过期，请重新获取");
        if (row.getState() == FundActionPreflight.STATE_REJECTED)
            return new FundActionPreflightException("PREFLIGHT_REJECTED", "该资金操作已被拒绝");
        if (row.getState() == FundActionPreflight.STATE_CONSUMED)
            return new FundActionPreflightException("PREFLIGHT_CONSUMED", "确认凭证已使用");
        return new FundActionPreflightException("PREFLIGHT_BINDING_MISMATCH", "确认凭证与当前资金操作不匹配");
    }

    private String stateName(Integer state) {
        if (state == null) return "UNKNOWN";
        switch (state) {
            case FundActionPreflight.STATE_PENDING: return "PENDING";
            case FundActionPreflight.STATE_CONFIRMED: return "CONFIRMED";
            case FundActionPreflight.STATE_REJECTED: return "REJECTED";
            case FundActionPreflight.STATE_CONSUMED: return "CONSUMED";
            default: return "UNKNOWN";
        }
    }
}
