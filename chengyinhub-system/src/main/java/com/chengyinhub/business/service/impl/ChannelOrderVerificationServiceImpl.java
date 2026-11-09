package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.dto.ChannelAccountRequest;
import com.chengyinhub.business.domain.dto.ChannelAccountView;
import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationRequest;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationView;
import com.chengyinhub.business.domain.dto.ChannelVerificationCommand;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.service.IChannelOrderVerificationService;
import com.chengyinhub.business.service.IChannelRefundService;
import com.chengyinhub.business.service.IChannelVoucherService;
import com.chengyinhub.business.service.support.ChannelOrderVerificationProviderRegistry;
import com.chengyinhub.business.service.support.ChannelVoucherCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 渠道主体 + 可信订单验证 + 入站回调。
 *
 * <p><b>唯一可信来源</b>：本类不解析“BR 前缀 + 邮箱”，也不接受运营手填金额；
 * 只有 {@link com.chengyinhub.business.service.ChannelOrderVerificationProvider}
 * 返回 VERIFIED 时才把事实写成 {@code STATUS_VERIFIED}。默认 provider 返回
 * {@code PROVIDER_NOT_CONFIGURED}，所以真实凭据落地前 L1 保持未启用。
 *
 * <p><b>不改钱</b>：本类只写 marketing_channel_* 表；不写 settlement_detail、
 * merchant_payable、cms_registration.payment_status 或任何可提现余额。
 */
@Service
public class ChannelOrderVerificationServiceImpl implements IChannelOrderVerificationService {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final ChannelVoucherAccountMapper accountMapper;
    private final ChannelOrderVerificationMapper verificationMapper;
    private final ChannelOrderVerificationProviderRegistry providerRegistry;
    private final IChannelVoucherService voucherService;

    @Autowired(required = false)
    private IChannelRefundService refundService;

    public ChannelOrderVerificationServiceImpl(
            ChannelVoucherAccountMapper accountMapper,
            ChannelOrderVerificationMapper verificationMapper,
            ChannelOrderVerificationProviderRegistry providerRegistry,
            IChannelVoucherService voucherService) {
        this.accountMapper = accountMapper;
        this.verificationMapper = verificationMapper;
        this.providerRegistry = providerRegistry;
        this.voucherService = voucherService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelAccountView registerAccount(long operatorUserId, ChannelAccountRequest request) {
        requireOperator(operatorUserId);
        if (request == null) {
            throw ChannelVoucherException.badRequest("ACCOUNT_REQUIRED", "缺少渠道主体参数");
        }
        String channelKey = requirePattern(request.getChannelKey(), "[a-z0-9_-]{2,40}", "CHANNEL_KEY_INVALID");
        String displayName = requireText(request.getDisplayName(), 120, "CHANNEL_NAME_INVALID");
        String contractRef = requireText(request.getContractRef(), 120, "CONTRACT_REQUIRED");
        Integer settlementMode = request.getSettlementMode() == null
                ? Integer.valueOf(ChannelVoucherAccount.SETTLEMENT_NONE) : request.getSettlementMode();
        if (settlementMode < ChannelVoucherAccount.SETTLEMENT_NONE
                || settlementMode > ChannelVoucherAccount.SETTLEMENT_INVOICE) {
            throw ChannelVoucherException.badRequest("SETTLEMENT_MODE_INVALID", "渠道结算模式不合法");
        }
        Date now = new Date();
        Date effectiveFrom = request.getEffectiveFrom() == null ? now : request.getEffectiveFrom();
        if (request.getEffectiveTo() != null && !request.getEffectiveTo().after(effectiveFrom)) {
            throw ChannelVoucherException.badRequest("EFFECTIVE_WINDOW_INVALID", "渠道生效窗口不合法");
        }
        ChannelVoucherAccount existing = accountMapper.selectByKey(channelKey);
        if (existing != null) {
            boolean same = contractRef.equals(existing.getContractRef())
                    && displayName.equals(existing.getDisplayName())
                    && settlementMode.equals(existing.getSettlementMode());
            if (!same) {
                throw ChannelVoucherException.conflict("ACCOUNT_CONFLICT", "渠道主体已存在且合同/名称不同");
            }
            ChannelAccountView view = toAccountView(existing);
            view.setReplayed(Boolean.TRUE);
            return view;
        }
        ChannelVoucherAccount account = new ChannelVoucherAccount();
        account.setChannelKey(channelKey);
        account.setDisplayName(displayName);
        account.setContractRef(contractRef);
        account.setSettlementMode(settlementMode);
        account.setStatus(ChannelVoucherAccount.STATUS_PENDING);
        account.setEffectiveFrom(effectiveFrom);
        account.setEffectiveTo(request.getEffectiveTo());
        account.setCreateBy(operatorUserId);
        account.setCreateTime(now);
        account.setUpdateTime(now);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException duplicate) {
            throw ChannelVoucherException.conflict("ACCOUNT_CONFLICT", "渠道主体并发创建，请重试");
        }
        ChannelAccountView view = toAccountView(account);
        view.setReplayed(Boolean.FALSE);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelAccountView updateAccountStatus(long operatorUserId, Long accountId, Integer status) {
        requireOperator(operatorUserId);
        if (accountId == null || accountId <= 0 || status == null
                || status < ChannelVoucherAccount.STATUS_PENDING
                || status > ChannelVoucherAccount.STATUS_REVOKED) {
            throw ChannelVoucherException.badRequest("ACCOUNT_STATUS_INVALID", "渠道主体状态不合法");
        }
        ChannelVoucherAccount account = accountMapper.selectByIdForUpdate(accountId);
        if (account == null) {
            throw ChannelVoucherException.notFound("ACCOUNT_NOT_FOUND", "渠道主体不存在");
        }
        accountMapper.updateStatus(accountId, status, new Date());
        account.setStatus(status);
        account.setUpdateTime(new Date());
        ChannelAccountView view = toAccountView(account);
        view.setReplayed(Boolean.FALSE);
        return view;
    }

    @Override
    public List<ChannelAccountView> listAccounts(Integer status, int limit) {
        List<ChannelVoucherAccount> rows = accountMapper.selectList(status, normalizeLimit(limit));
        List<ChannelAccountView> views = new ArrayList<>();
        if (rows != null) {
            for (ChannelVoucherAccount row : rows) {
                views.add(toAccountView(row));
            }
        }
        return views;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelOrderVerificationView verifyByReference(long operatorUserId,
                                                          ChannelOrderVerificationRequest request) {
        requireOperator(operatorUserId);
        if (request == null) {
            throw ChannelVoucherException.badRequest("VERIFICATION_REQUIRED", "缺少渠道订单参数");
        }
        Long accountId = request.getAccountId();
        if (accountId == null || accountId <= 0) {
            throw ChannelVoucherException.badRequest("ACCOUNT_REQUIRED", "缺少渠道主体");
        }
        String channelOrderNo = requireText(request.getChannelOrderNo(), 96, "CHANNEL_ORDER_NO_REQUIRED");
        String requestId = requireText(request.getRequestId(), 64, "REQUEST_ID_REQUIRED");
        String providerKey = isBlank(request.getProviderKey()) ? null : request.getProviderKey().trim();
        ChannelVoucherAccount account = accountMapper.selectByIdForUpdate(accountId);
        if (account == null) {
            throw ChannelVoucherException.notFound("ACCOUNT_NOT_FOUND", "渠道主体不存在");
        }
        if (!account.issuableAt(new Date())) {
            throw ChannelVoucherException.conflict("CHANNEL_INACTIVE", "渠道未启用、合同缺失或不在生效窗口");
        }
        String effectiveProviderKey = providerKey == null ? account.getChannelKey() : providerKey;
        String fingerprint = sha256(String.valueOf(accountId) + "|" + effectiveProviderKey + "|"
                + channelOrderNo + "|" + valueOf(request.getExternalReference()) + "|"
                + valueOf(request.getAmount()) + "|" + valueOf(request.getCurrency()) + "|" + requestId);

        ChannelOrderVerification byRequest = verificationMapper.selectByAccountRequest(accountId, requestId);
        if (byRequest != null) {
            if (!fingerprint.equals(byRequest.getRequestFingerprint())) {
                throw ChannelVoucherException.conflict("REQUEST_CONFLICT", "同一请求键对应了不同渠道订单");
            }
            ChannelOrderVerificationView view = toVerificationView(byRequest);
            view.setReplayed(Boolean.TRUE);
            return view;
        }
        ChannelOrderVerification existing = verificationMapper.selectByAccountOrderNo(accountId, channelOrderNo);
        if (existing != null && Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(existing.getStatus())) {
            ChannelOrderVerificationView view = toVerificationView(existing);
            view.setReplayed(Boolean.TRUE);
            return view;
        }

        Date now = new Date();
        ChannelOrderVerification row = existing != null ? existing : new ChannelOrderVerification();
        row.setAccountId(accountId);
        row.setChannelKey(account.getChannelKey());
        row.setProviderKey(effectiveProviderKey);
        row.setChannelOrderNo(channelOrderNo);
        row.setExternalReference(trimToNull(request.getExternalReference()));
        row.setAmount(request.getAmount());
        row.setCurrency(trimToNull(request.getCurrency()));
        row.setSkuJson(trimToNull(request.getSkuJson()));
        row.setStatus(ChannelOrderVerification.STATUS_PENDING);
        row.setReasonCode(null);
        row.setRequestId(requestId);
        row.setRequestFingerprint(fingerprint);
        row.setUpdateTime(now);
        if (existing == null) {
            row.setCreateTime(now);
            verificationMapper.insert(row);
        }

        if (!providerRegistry.hasRealProvider(effectiveProviderKey)) {
            verificationMapper.markRejected(row.getId(), "PROVIDER_NOT_CONFIGURED", null, now);
            row.setStatus(ChannelOrderVerification.STATUS_REJECTED);
            row.setReasonCode("PROVIDER_NOT_CONFIGURED");
            ChannelOrderVerificationView view = toVerificationView(row);
            view.setReplayed(Boolean.FALSE);
            return view;
        }

        ChannelVerificationCommand command = new ChannelVerificationCommand();
        command.setMode(ChannelVerificationCommand.MODE_PULL);
        command.setAccountId(accountId);
        command.setChannelKey(account.getChannelKey());
        command.setProviderKey(effectiveProviderKey);
        command.setChannelOrderNo(channelOrderNo);
        command.setExternalReference(request.getExternalReference());
        command.setDeclaredAmount(request.getAmount());
        command.setDeclaredCurrency(request.getCurrency());
        command.setSkuJson(request.getSkuJson());
        ChannelVerificationOutcome outcome;
        try {
            outcome = providerRegistry.require(effectiveProviderKey).verify(command);
        } catch (RuntimeException providerFailure) {
            verificationMapper.markRejected(row.getId(), "PROVIDER_ERROR", null, now);
            row.setStatus(ChannelOrderVerification.STATUS_REJECTED);
            row.setReasonCode("PROVIDER_ERROR");
            ChannelOrderVerificationView view = toVerificationView(row);
            view.setReplayed(Boolean.FALSE);
            return view;
        }
        return applyOutcome(row, command, outcome, now);
    }

    @Override
    public ChannelOrderVerificationView getVerification(long operatorUserId, Long verificationId) {
        requireOperator(operatorUserId);
        if (verificationId == null || verificationId <= 0) {
            throw ChannelVoucherException.badRequest("VERIFICATION_ID_REQUIRED", "缺少验证记录 id");
        }
        ChannelOrderVerification row = verificationMapper.selectById(verificationId);
        if (row == null) {
            throw ChannelVoucherException.notFound("VERIFICATION_NOT_FOUND", "验证记录不存在");
        }
        return toVerificationView(row);
    }

    @Override
    public List<ChannelOrderVerificationView> listVerifications(Long accountId, Integer status,
                                                               Date from, Date to, int limit) {
        if (accountId == null || accountId <= 0) {
            throw ChannelVoucherException.badRequest("ACCOUNT_REQUIRED", "缺少渠道主体");
        }
        if (status != null && (status < ChannelOrderVerification.STATUS_PENDING
                || status > ChannelOrderVerification.STATUS_REVOKED)) {
            throw ChannelVoucherException.badRequest("VERIFICATION_STATUS_INVALID", "验证状态不合法");
        }
        List<ChannelOrderVerification> rows = verificationMapper.selectByAccount(accountId, status, from, to,
                normalizeLimit(limit));
        List<ChannelOrderVerificationView> views = new ArrayList<>();
        if (rows != null) {
            for (ChannelOrderVerification row : rows) {
                views.add(toVerificationView(row));
            }
        }
        return views;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelOrderVerificationView revokeVerification(long operatorUserId, Long verificationId,
                                                            String reason, String requestId) {
        requireOperator(operatorUserId);
        String why = requireText(reason, 120, "REVOKE_REASON_REQUIRED");
        if (verificationId == null || verificationId <= 0) {
            throw ChannelVoucherException.badRequest("VERIFICATION_ID_REQUIRED", "缺少验证记录 id");
        }
        ChannelOrderVerification row = verificationMapper.selectByIdForUpdate(verificationId);
        if (row == null) {
            throw ChannelVoucherException.notFound("VERIFICATION_NOT_FOUND", "验证记录不存在");
        }
        if (Integer.valueOf(ChannelOrderVerification.STATUS_REVOKED).equals(row.getStatus())) {
            return toVerificationView(row);
        }
        // 与发行、兑换、退款一致，先锁验证事实，再锁码。
        com.chengyinhub.business.domain.dto.ChannelVoucherView voucher =
                voucherService.revokeByVerification(verificationId, why, "CHANNEL");
        if (voucher != null && Boolean.TRUE.equals(voucher.getRevokePending())) {
            ChannelOrderVerificationView pending = toVerificationView(row);
            pending.setReasonCode("REFUND_REQUIRED");
            return pending;
        }
        row = verificationMapper.selectByIdForUpdate(verificationId);
        if (row == null) {
            throw ChannelVoucherException.conflict("VERIFICATION_CONFLICT", "验证记录状态冲突");
        }
        Date now = new Date();
        verificationMapper.markRevoked(verificationId, "CHANNEL_REVOKED", now);
        row.setStatus(ChannelOrderVerification.STATUS_REVOKED);
        row.setReasonCode("CHANNEL_REVOKED");
        row.setUpdateTime(now);
        return toVerificationView(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelInboundCallbackView handleInbound(String providerKey, Map<String, String> headers, String rawBody) {
        if (isBlank(providerKey) || isBlank(rawBody)) {
            throw ChannelVoucherException.badRequest("CALLBACK_INVALID", "回调缺少渠道标识或报文");
        }
        ChannelInboundCallbackView view = new ChannelInboundCallbackView();
        view.setAccepted(Boolean.FALSE);
        if (!providerRegistry.hasRealProvider(providerKey)) {
            view.setReasonCode("PROVIDER_NOT_CONFIGURED");
            return view;
        }
        ChannelVerificationCommand command = new ChannelVerificationCommand();
        command.setMode(ChannelVerificationCommand.MODE_INBOUND);
        command.setProviderKey(providerKey);
        command.setRawBody(rawBody);
        command.setHeaders(headers);
        ChannelVerificationOutcome outcome;
        try {
            outcome = providerRegistry.require(providerKey).verify(command);
        } catch (RuntimeException providerFailure) {
            view.setReasonCode("PROVIDER_ERROR");
            return view;
        }
        if (outcome == null || outcome.getStatus() != ChannelOrderVerification.STATUS_VERIFIED) {
            view.setReasonCode(outcome == null ? "PROVIDER_NO_RESULT" : valueOf(outcome.getReasonCode()));
            return view;
        }
        if (outcome.getEventKind() == ChannelVerificationOutcome.EVENT_REFUND) {
            if (refundService == null) {
                view.setReasonCode("REFUND_SERVICE_NOT_CONFIGURED");
                return view;
            }
            return refundService.handleVerifiedRefund(providerKey, outcome, rawBody);
        }
        String eventId = requireText(outcome.getEventId(), 96, "EVENT_ID_REQUIRED");
        String channelOrderNo = requireText(outcome.getChannelOrderNo(), 96, "CHANNEL_ORDER_NO_REQUIRED");
        String channelKey = requireText(outcome.getChannelKey(), 40, "CHANNEL_KEY_INVALID");
        ChannelOrderVerification existingEvent = verificationMapper.selectByProviderEvent(providerKey, eventId);
        if (existingEvent != null) {
            view.setAccepted(Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED)
                    .equals(existingEvent.getStatus()));
            view.setReplayed(Boolean.TRUE);
            view.setEventId(eventId);
            view.setChannelOrderNo(existingEvent.getChannelOrderNo());
            view.setStatus(existingEvent.getStatus());
            view.setReasonCode(existingEvent.getReasonCode());
            return view;
        }
        ChannelVoucherAccount account = accountMapper.selectByKey(channelKey);
        Date now = new Date();
        String rawHash = ChannelVoucherCodec.sha256Hex(rawBody);
        String reason = validateOutcome(account, outcome, command);
        if (reason != null) {
            ChannelOrderVerification existingOrder = account == null ? null
                    : verificationMapper.selectByAccountOrderNo(account.getId(), channelOrderNo);
            if (existingOrder == null) {
                ChannelOrderVerification rejected = new ChannelOrderVerification();
                rejected.setAccountId(account == null ? null : account.getId());
                rejected.setChannelKey(channelKey);
                rejected.setProviderKey(providerKey);
                rejected.setChannelOrderNo(channelOrderNo);
                rejected.setEventId(eventId);
                rejected.setAmount(outcome.getAmount());
                rejected.setCurrency(outcome.getCurrency());
                rejected.setSkuJson(outcome.getSkuJson());
                rejected.setProductId(outcome.getProductId());
                rejected.setQuantity(outcome.getQuantity());
                rejected.setStatus(ChannelOrderVerification.STATUS_REJECTED);
                rejected.setReasonCode(reason);
                rejected.setRawHash(rawHash);
                rejected.setRequestId(eventId);
                rejected.setRequestFingerprint(ChannelVoucherCodec.sha256Hex(
                        providerKey + "|" + eventId + "|" + rawHash));
                rejected.setCreateTime(now);
                rejected.setUpdateTime(now);
                verificationMapper.insert(rejected);
            }
            view.setReasonCode(reason);
            view.setEventId(eventId);
            view.setChannelOrderNo(channelOrderNo);
            view.setStatus(ChannelOrderVerification.STATUS_REJECTED);
            return view;
        }
        ChannelOrderVerification row = verificationMapper.selectByAccountOrderNo(account.getId(), channelOrderNo);
        if (row == null) {
            row = new ChannelOrderVerification();
            row.setAccountId(account.getId());
            row.setChannelKey(channelKey);
            row.setProviderKey(providerKey);
            row.setChannelOrderNo(channelOrderNo);
            row.setEventId(eventId);
            row.setAmount(outcome.getAmount());
            row.setCurrency(outcome.getCurrency());
            row.setSkuJson(outcome.getSkuJson());
            row.setProductId(outcome.getProductId());
            row.setQuantity(outcome.getQuantity());
            row.setStatus(ChannelOrderVerification.STATUS_PENDING);
            row.setRawHash(rawHash);
            row.setRequestId(eventId);
            row.setRequestFingerprint(ChannelVoucherCodec.sha256Hex(providerKey + "|" + eventId + "|" + rawHash));
            row.setCreateTime(now);
            row.setUpdateTime(now);
            verificationMapper.insert(row);
        }
        ChannelOrderVerificationView applied = applyOutcome(row, command, outcome, now);
        view.setAccepted(Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(applied.getStatus()));
        view.setEventId(eventId);
        view.setChannelOrderNo(channelOrderNo);
        view.setStatus(applied.getStatus());
        view.setReasonCode(applied.getReasonCode());
        return view;
    }

    private ChannelOrderVerificationView applyOutcome(ChannelOrderVerification row,
                                                       ChannelVerificationCommand command,
                                                       ChannelVerificationOutcome outcome,
                                                       Date now) {
        if (outcome == null) {
            verificationMapper.markRejected(row.getId(), "PROVIDER_NO_RESULT", null, now);
            row.setStatus(ChannelOrderVerification.STATUS_REJECTED);
            row.setReasonCode("PROVIDER_NO_RESULT");
            return toVerificationView(row);
        }
        ChannelVoucherAccount account = row.getAccountId() == null ? null : accountMapper.selectById(row.getAccountId());
        String reason = validateOutcome(account, outcome, command);
        if (reason != null) {
            verificationMapper.markRejected(row.getId(), reason, outcome.getRawHash(), now);
            row.setStatus(ChannelOrderVerification.STATUS_REJECTED);
            row.setReasonCode(reason);
            return toVerificationView(row);
        }
        Date verifiedAt = outcome.getVerifiedAt() == null ? now : outcome.getVerifiedAt();
        int updated = verificationMapper.markVerified(row.getId(), outcome.getProductId(), outcome.getQuantity(),
                outcome.getAmount(), outcome.getCurrency(), outcome.getSkuJson(),
                outcome.getRawHash() == null ? ChannelVoucherCodec.sha256Hex(String.valueOf(row.getId())) : outcome.getRawHash(),
                verifiedAt, null, now);
        if (updated == 0) {
            ChannelOrderVerification current = verificationMapper.selectById(row.getId());
            if (current == null) {
                throw ChannelVoucherException.conflict("VERIFICATION_CONFLICT", "验证记录状态冲突");
            }
            return toVerificationView(current);
        }
        row.setStatus(ChannelOrderVerification.STATUS_VERIFIED);
        row.setReasonCode(null);
        row.setProductId(outcome.getProductId());
        row.setQuantity(outcome.getQuantity());
        row.setAmount(outcome.getAmount());
        row.setCurrency(outcome.getCurrency());
        row.setVerifiedAt(verifiedAt);
        row.setUpdateTime(now);
        return toVerificationView(row);
    }

    /**
     * 验证结果必须同时满足：状态 VERIFIED、渠道/单号一致、金额>0、币种非空、
     * 商品与数量由 provider 映射。调用方自报金额只作交叉核对，不一致直接拒绝。
     */
    private String validateOutcome(ChannelVoucherAccount account,
                                   ChannelVerificationOutcome outcome,
                                   ChannelVerificationCommand command) {
        if (outcome == null) return "PROVIDER_NO_RESULT";
        if (outcome.getStatus() != ChannelOrderVerification.STATUS_VERIFIED) {
            return isBlank(outcome.getReasonCode()) ? "VERIFICATION_REJECTED" : outcome.getReasonCode();
        }
        if (account == null) return "ACCOUNT_NOT_FOUND";
        if (!account.issuableAt(new Date())) return "CHANNEL_INACTIVE";
        if (!account.getChannelKey().equals(outcome.getChannelKey())) return "CHANNEL_KEY_MISMATCH";
        if (!isBlank(command.getChannelOrderNo())
                && !command.getChannelOrderNo().equals(outcome.getChannelOrderNo())) {
            return "CHANNEL_ORDER_MISMATCH";
        }
        if (outcome.getAmount() == null || outcome.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return "AMOUNT_INVALID";
        }
        if (isBlank(outcome.getCurrency()) || outcome.getCurrency().trim().length() > 8) {
            return "CURRENCY_INVALID";
        }
        if (outcome.getProductId() == null || outcome.getProductId() <= 0) {
            return "PRODUCT_MAPPING_REQUIRED";
        }
        if (outcome.getQuantity() == null || outcome.getQuantity() <= 0) {
            return "QUANTITY_INVALID";
        }
        if (command.getDeclaredAmount() != null
                && command.getDeclaredAmount().compareTo(outcome.getAmount()) != 0) {
            return "AMOUNT_MISMATCH";
        }
        if (!isBlank(command.getDeclaredCurrency())
                && !command.getDeclaredCurrency().equalsIgnoreCase(outcome.getCurrency())) {
            return "CURRENCY_MISMATCH";
        }
        return null;
    }

    private ChannelAccountView toAccountView(ChannelVoucherAccount account) {
        ChannelAccountView view = new ChannelAccountView();
        view.setId(account.getId());
        view.setChannelKey(account.getChannelKey());
        view.setDisplayName(account.getDisplayName());
        view.setContractRef(account.getContractRef());
        view.setSettlementMode(account.getSettlementMode());
        view.setStatus(account.getStatus());
        view.setEffectiveFrom(account.getEffectiveFrom());
        view.setEffectiveTo(account.getEffectiveTo());
        view.setCreateTime(account.getCreateTime());
        view.setUpdateTime(account.getUpdateTime());
        return view;
    }

    private ChannelOrderVerificationView toVerificationView(ChannelOrderVerification row) {
        ChannelOrderVerificationView view = new ChannelOrderVerificationView();
        view.setId(row.getId());
        view.setAccountId(row.getAccountId());
        view.setChannelKey(row.getChannelKey());
        view.setProviderKey(row.getProviderKey());
        view.setChannelOrderNo(row.getChannelOrderNo());
        view.setExternalReference(row.getExternalReference());
        view.setStatus(row.getStatus());
        view.setReasonCode(row.getReasonCode());
        view.setAmount(row.getAmount());
        view.setCurrency(row.getCurrency());
        view.setProductId(row.getProductId());
        view.setQuantity(row.getQuantity());
        view.setVerifiedAt(row.getVerifiedAt());
        view.setExpiresAt(row.getExpiresAt());
        return view;
    }

    private void requireOperator(long operatorUserId) {
        if (operatorUserId <= 0) {
            throw ChannelVoucherException.unauthorized("LOGIN_REQUIRED", "请先登录管理端");
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return DEFAULT_LIST_LIMIT;
        return Math.min(limit, MAX_LIST_LIMIT);
    }

    private String requireText(String value, int maxLength, String reasonCode) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw ChannelVoucherException.badRequest(reasonCode, "缺少必填参数");
        }
        if (trimmed.length() > maxLength) {
            throw ChannelVoucherException.badRequest(reasonCode, "参数长度超限");
        }
        return trimmed;
    }

    private String requirePattern(String value, String pattern, String reasonCode) {
        String trimmed = requireText(value, 40, reasonCode);
        if (!trimmed.matches(pattern)) {
            throw ChannelVoucherException.badRequest(reasonCode, "参数格式不合法");
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String valueOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256(String value) {
        return ChannelVoucherCodec.sha256Hex(value);
    }
}
