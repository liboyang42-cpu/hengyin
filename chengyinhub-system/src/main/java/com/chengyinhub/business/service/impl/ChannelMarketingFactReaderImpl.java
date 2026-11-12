package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.service.MarketingChannelFactReader;
import org.springframework.stereotype.Service;

/**
 * 供 M2-F 权威注册桥接回读渠道不可变事实。
 *
 * <p>所有字段以库内 VERIFIED 行为准；调用方传入的金额/hash 只用于一致性核对。
 * 绑定判定同时覆盖：码绑定的受益人、码状态、是否已有兑换回执。
 */
@Service
public class ChannelMarketingFactReaderImpl implements MarketingChannelFactReader {

    private final ChannelOrderVerificationMapper verificationMapper;
    private final ChannelVoucherMapper voucherMapper;
    private final ChannelRedemptionMapper redemptionMapper;

    public ChannelMarketingFactReaderImpl(ChannelOrderVerificationMapper verificationMapper,
                                          ChannelVoucherMapper voucherMapper,
                                          ChannelRedemptionMapper redemptionMapper) {
        this.verificationMapper = verificationMapper;
        this.voucherMapper = voucherMapper;
        this.redemptionMapper = redemptionMapper;
    }

    @Override
    public ChannelFact requireVerified(ChannelFactQuery query) {
        if (query == null || query.factId <= 0 || query.memberId <= 0 || query.productId <= 0
                || query.quantity <= 0 || query.claimedAmount == null || query.claimedAmount.signum() <= 0
                || isBlank(query.providerKey) || isBlank(query.externalOrderNo)
                || isBlank(query.claimedCurrency)) {
            throw new IllegalStateException("CHANNEL_FACT_QUERY_INVALID");
        }
        ChannelOrderVerification row = verificationMapper.selectByIdForUpdate(query.factId);
        if (row == null) {
            throw new IllegalStateException("CHANNEL_FACT_NOT_FOUND");
        }
        if (!Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(row.getStatus())) {
            throw new IllegalStateException("CHANNEL_FACT_NOT_VERIFIED");
        }
        if (!query.providerKey.equals(row.getProviderKey())) {
            throw new IllegalStateException("CHANNEL_PROVIDER_MISMATCH");
        }
        if (!query.externalOrderNo.equals(row.getChannelOrderNo())) {
            throw new IllegalStateException("CHANNEL_ORDER_MISMATCH");
        }
        if (row.getProductId() == null || row.getProductId().longValue() != query.productId) {
            throw new IllegalStateException("CHANNEL_PRODUCT_MISMATCH");
        }
        if (row.getQuantity() == null || row.getQuantity() != query.quantity) {
            throw new IllegalStateException("CHANNEL_QUANTITY_MISMATCH");
        }
        if (row.getAmount() == null || row.getAmount().compareTo(query.claimedAmount) != 0) {
            throw new IllegalStateException("CHANNEL_AMOUNT_MISMATCH");
        }
        if (row.getCurrency() == null || !row.getCurrency().equalsIgnoreCase(query.claimedCurrency)) {
            throw new IllegalStateException("CHANNEL_CURRENCY_MISMATCH");
        }

        ChannelVoucher voucher = voucherMapper.selectByVerification(row.getId());
        if (voucher == null) {
            throw new IllegalStateException("CHANNEL_VOUCHER_NOT_FOUND");
        }
        if (Integer.valueOf(ChannelVoucher.SUBJECT_BOUND_MEMBER).equals(voucher.getSubjectMode())
                && (voucher.getBoundMemberId() == null || voucher.getBoundMemberId() != query.memberId)) {
            throw new IllegalStateException("CHANNEL_BENEFICIARY_MISMATCH");
        }
        if (Integer.valueOf(ChannelVoucher.STATUS_REVOKED).equals(voucher.getStatus())
                || Integer.valueOf(ChannelVoucher.STATUS_EXPIRED).equals(voucher.getStatus())) {
            throw new IllegalStateException("CHANNEL_VOUCHER_NOT_REDEEMABLE");
        }
        ChannelRedemption redemption = redemptionMapper.selectByVoucherId(voucher.getId());
        boolean bound = Integer.valueOf(ChannelVoucher.STATUS_REDEEMED).equals(voucher.getStatus())
                || (redemption != null
                && !Integer.valueOf(ChannelRedemption.STATUS_REVOKED).equals(redemption.getStatus()));
        String providerTransactionId = isBlank(row.getEventId())
                ? row.getChannelOrderNo() : row.getEventId();
        return new ChannelFact(row.getChannelOrderNo(), row.getAmount(), row.getCurrency(),
                providerTransactionId, bound);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
