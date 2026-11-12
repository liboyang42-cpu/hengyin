package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ChannelGrantCommand;
import com.chengyinhub.business.domain.dto.ChannelGrantResult;
import com.chengyinhub.business.domain.dto.ChannelGrantRevocation;
import com.chengyinhub.business.domain.dto.ChannelGrantTopic;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.ChannelRedemptionGrantPort;
import com.chengyinhub.business.service.ChannelRevocationOutcome;
import com.chengyinhub.business.service.FulfillmentCommand;
import com.chengyinhub.business.service.FulfillmentOutcome;
import com.chengyinhub.business.service.GrantLine;
import com.chengyinhub.business.service.IMarketingProductFulfillmentService;
import com.chengyinhub.business.service.PaymentFacts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 把渠道 grant 命令转成 M2-F 的原注册桥接调用。
 *
 * <p><b>真正接线</b>：调用 {@link IMarketingProductFulfillmentService#grant}，
 * 由 M2-F 在同一事务内产出 {@code cms_registration}；M8 不直接写注册/权益。
 * 模块金额分摊已由 {@code ChannelGrantPlanner} 守恒计算，这里只做字段映射。
 *
 * <p><b>撤销</b>：调用 M2-F {@code revokeChannel} 只做判定；可撤销时返回
 * {@code REFUND_REQUIRED}，不先改状态。真正的撤准入由已验证渠道退款事实经
 * {@code consumeVerifiedChannelRefund} 消费。
 */
@Component
public class ChannelRegistrationGrantAdapter implements ChannelRedemptionGrantPort {

    private final IMarketingProductFulfillmentService fulfillment;
    private final CmsRegistrationMapper registrationMapper;

    public ChannelRegistrationGrantAdapter(IMarketingProductFulfillmentService fulfillment,
                                           CmsRegistrationMapper registrationMapper) {
        this.fulfillment = fulfillment;
        this.registrationMapper = registrationMapper;
    }

    @Override
    public ChannelGrantResult grant(ChannelGrantCommand command) {
        if (command == null) {
            throw ChannelVoucherException.badRequest("GRANT_COMMAND_REQUIRED", "缺少渠道成交命令");
        }
        FulfillmentCommand fulfillmentCommand = new FulfillmentCommand();
        fulfillmentCommand.requestId = command.getRequestId();
        fulfillmentCommand.parentOrderNo = command.getChannelOrderNo();
        fulfillmentCommand.memberId = command.getMemberId();
        fulfillmentCommand.productId = command.getProductId();
        fulfillmentCommand.quantity = command.getQuantity() == null ? 0 : command.getQuantity();
        fulfillmentCommand.seatCount = command.getSeatCount() == null ? 0 : command.getSeatCount();
        fulfillmentCommand.requestFingerprint = command.getRequestFingerprint();

        PaymentFacts payment = new PaymentFacts();
        payment.mode = PaymentFacts.MODE_CHANNEL;
        payment.externalFactId = command.getExternalFactId();
        payment.externalFactHash = command.getExternalFactHash();
        payment.sourceProviderKey = command.getProviderKey();
        payment.externalOrderNo = command.getChannelOrderNo();
        payment.verifiedAmount = command.getExternalAmount();
        payment.transactionId = command.getChannelOrderNo();
        fulfillmentCommand.payment = payment;

        List<GrantLine> lines = new ArrayList<>();
        if (command.getTopics() != null) {
            for (ChannelGrantTopic topic : command.getTopics()) {
                GrantLine line = new GrantLine();
                line.topicId = topic.getTopicId();
                line.listAmount = topic.getListAmount() == null
                        ? topic.getAllocatedAmount() : topic.getListAmount();
                line.payableAmount = topic.getAllocatedAmount();
                line.validityKind = toGrantValidity(command.getValidityKind());
                line.validDays = command.getValidDays() == null ? 0 : command.getValidDays();
                line.fixedValidFrom = command.getFixedValidFrom();
                line.fixedValidTo = command.getFixedValidTo();
                line.exploreCapacity = false;
                lines.add(line);
            }
        }
        fulfillmentCommand.lines = lines;

        FulfillmentOutcome outcome = fulfillment.grant(fulfillmentCommand);
        if (outcome == null || outcome.pendingActivation) {
            throw ChannelVoucherException.conflict("GRANT_INCOMPLETE", "权威授权未完成，不能标记兑换成功");
        }
        ChannelGrantResult result = new ChannelGrantResult();
        result.setPrimaryRegistrationId(outcome.registrationIds == null || outcome.registrationIds.isEmpty()
                ? null : outcome.registrationIds.get(0));
        result.setRegistrationIds(outcome.registrationIds == null
                ? new ArrayList<>() : outcome.registrationIds);
        Long primaryId = result.getPrimaryRegistrationId();
        if (primaryId != null) {
            CmsRegistration primary = registrationMapper.selectCmsRegistrationById(primaryId);
            result.setPrimaryRegistrationNo(primary == null ? null : primary.getRegistrationNo());
        }
        result.setGrantRef(outcome.parentOrderNo);
        result.setReplayed(outcome.replayed);
        result.setPendingActivation(false);
        result.setGrantedAt(new Date());
        result.setValidFrom(command.getValidityKind() != null
                && command.getValidityKind() == ChannelGrantCommand.VALIDITY_FIXED_WINDOW
                ? command.getFixedValidFrom() : command.getGrantedAt());
        result.setValidTo(command.getValidityKind() != null
                && command.getValidityKind() == ChannelGrantCommand.VALIDITY_FIXED_WINDOW
                ? command.getFixedValidTo() : command.getExpiresAt());
        return result;
    }

    @Override
    public ChannelGrantResult revoke(ChannelGrantRevocation command) {
        if (command == null || command.getChannelOrderNo() == null
                || command.getChannelOrderNo().trim().isEmpty()) {
            throw ChannelVoucherException.badRequest("CHANNEL_ORDER_NO_REQUIRED", "缺少渠道单号");
        }
        ChannelRevocationOutcome outcome = fulfillment.revokeChannel(
                command.getChannelOrderNo().trim(), command.getReason(), command.getRequestId());
        if (outcome == null) {
            throw ChannelVoucherException.conflict("CHANNEL_REVOKE_UNKNOWN", "渠道撤销判定无结果");
        }
        ChannelGrantResult result = new ChannelGrantResult();
        result.setRegistrationIds(outcome.registrationIds == null
                ? new ArrayList<>() : outcome.registrationIds);
        result.setPrimaryRegistrationId(result.getRegistrationIds().isEmpty()
                ? null : result.getRegistrationIds().get(0));
        result.setReplayed(outcome.replayed);
        result.setRevocable(outcome.revocable);
        result.setReasonCode(outcome.reasonCode);
        result.setMessage(outcome.message);
        return result;
    }

    private int toGrantValidity(Integer validityKind) {
        if (validityKind == null || validityKind == ChannelGrantCommand.VALIDITY_NONE) {
            return GrantLine.VALIDITY_NONE;
        }
        if (validityKind == ChannelGrantCommand.VALIDITY_FIXED_WINDOW) {
            return GrantLine.VALIDITY_FIXED_WINDOW;
        }
        return GrantLine.VALIDITY_RELATIVE_DAYS;
    }
}
