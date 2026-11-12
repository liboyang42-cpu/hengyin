package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import com.chengyinhub.business.service.MarketingChannelRefundFactByIdReader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按不可变主键回读渠道退款事实。只读、不改消费标记，供收入分配域在队列消费时复核。
 *
 * <p>范围来源：{@code marketing_channel_redemption.registration_ids_json}
 * （兑换时冻结的权威注册行快照，含多作品包的 N 行）。快照缺失时退回单主单 id，
 * 两者都没有则返回空范围，由调用方按"无范围"显式失败/待人工，而不是猜一行。
 */
@Service
public class ChannelMarketingRefundFactByIdReaderImpl implements MarketingChannelRefundFactByIdReader {

    private final ChannelRefundVerificationMapper refundMapper;
    private final ChannelRedemptionMapper redemptionMapper;

    public ChannelMarketingRefundFactByIdReaderImpl(ChannelRefundVerificationMapper refundMapper,
                                                    ChannelRedemptionMapper redemptionMapper) {
        this.refundMapper = refundMapper;
        this.redemptionMapper = redemptionMapper;
    }

    @Override
    public Fact requireVerifiedById(long factId) {
        if (factId <= 0) {
            return null;
        }
        ChannelRefundVerification row = refundMapper.selectById(factId);
        if (row == null) {
            return null;
        }
        if (!Integer.valueOf(ChannelRefundVerification.STATUS_VERIFIED).equals(row.getStatus())) {
            // 申请/未确认/部分退款未支持：一律视为"未验证"，让冲销任务保留待重试而不是入账。
            return null;
        }
        ChannelRedemption redemption = redemptionMapper.selectByChannelOrderNo(row.getChannelOrderNo());
        List<Long> registrationIds = parseIds(redemption == null ? null : redemption.getRegistrationIdsJson());
        Long primary = registrationIds.isEmpty()
                ? (redemption == null ? null : redemption.getRegistrationId())
                : registrationIds.get(0);
        if (registrationIds.isEmpty() && primary != null) {
            registrationIds = new ArrayList<>();
            registrationIds.add(primary);
        }
        return new Fact(row.getId(), row.getRawHash(), row.getChannelRefundNo(), primary,
                row.getAmount(), row.getCurrency(), true,
                row.getChannelOrderNo(), row.getProviderKey(),
                Integer.valueOf(1).equals(row.getFullRefund()), registrationIds);
    }

    private List<Long> parseIds(String json) {
        List<Long> ids = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return ids;
        }
        for (String part : json.replace("[", "").replace("]", "").split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(trimmed);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException damaged) {
                // 快照损坏：返回空范围，让冲销显式失败待人工，绝不猜一行去冲销。
                return new ArrayList<>();
            }
        }
        return ids;
    }
}
