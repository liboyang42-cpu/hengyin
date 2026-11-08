package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.service.IChannelPurchasePaymentFactReader;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 回读渠道已验证购买事实。只读，不写任何账；歧义/未验证一律返回 null。
 */
@Service
public class ChannelPurchasePaymentFactReaderImpl implements IChannelPurchasePaymentFactReader {

    private final ChannelOrderVerificationMapper verificationMapper;

    public ChannelPurchasePaymentFactReaderImpl(ChannelOrderVerificationMapper verificationMapper) {
        this.verificationMapper = verificationMapper;
    }

    @Override
    public PaymentFact verifiedPaymentOf(String channelOrderNo) {
        if (channelOrderNo == null || channelOrderNo.trim().isEmpty()) {
            return null;
        }
        List<ChannelOrderVerification> rows =
                verificationMapper.selectVerifiedByChannelOrderNo(channelOrderNo.trim());
        if (rows == null || rows.size() != 1) {
            // 0 行 = 没有已确认付款事实；≥2 行 = 跨账户歧义，二者都不猜。
            return null;
        }
        ChannelOrderVerification row = rows.get(0);
        if (row.getAmount() == null || row.getAmount().signum() <= 0
                || row.getCurrency() == null || row.getCurrency().trim().isEmpty()) {
            return null;
        }
        long amountFen;
        try {
            amountFen = row.getAmount().movePointRight(2).longValueExact();
        } catch (ArithmeticException notIntegralFen) {
            return null;
        }
        return new PaymentFact(row.getId(), row.getChannelOrderNo(), row.getProviderKey(),
                amountFen, row.getCurrency(), row.getRawHash());
    }
}
