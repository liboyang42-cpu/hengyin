package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.NotificationDeliveryReceipt;
import com.chengyinhub.business.mapper.NotificationDeliveryReceiptMapper;
import com.chengyinhub.business.service.IImService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 外发身份独立提交；站内消息和收据在同一事务内提交。 */
@Service
public class NotificationDeliveryReceiptService {
    private final NotificationDeliveryReceiptMapper mapper;
    private final IImService imService;
    private final TransactionTemplate transaction;

    public NotificationDeliveryReceiptService(NotificationDeliveryReceiptMapper mapper,
                                               IImService imService,
                                               PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.imService = imService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean claim(String eventType, Long eventId, Long memberId, String channel, boolean retryFailed) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            insertIntent(eventType, eventId, memberId, channel);
            return (retryFailed ? mapper.claim(eventType, eventId, memberId, channel)
                    : mapper.claimPending(eventType, eventId, memberId, channel)) == 1;
        }));
    }

    public boolean complete(String eventType, Long eventId, Long memberId, String channel,
                            String result, String providerCode) {
        return Boolean.TRUE.equals(transaction.execute(status ->
                mapper.complete(eventType, eventId, memberId, channel, result, providerCode) == 1));
    }

    public boolean storeInApp(String eventType, Long eventId, Long memberId,
                              Integer msgType, String content, String extra) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            insertIntent(eventType, eventId, memberId, "IN_APP");
            if (mapper.claimPending(eventType, eventId, memberId, "IN_APP") != 1) return false;
            Long messageId = imService.sendSystem(memberId, msgType, content, extra);
            if (messageId == null || messageId <= 0 || mapper.complete(eventType, eventId, memberId,
                    "IN_APP", "IN_APP_STORED", null) != 1) {
                throw new IllegalStateException("站内消息与通知收据未完整落库");
            }
            return true;
        }));
    }

    public boolean confirmed(String eventType, Long eventId, Long memberId, String channel) {
        NotificationDeliveryReceipt receipt = transaction.execute(status ->
                mapper.selectByIntent(eventType, eventId, memberId, channel));
        return receipt != null && ("IN_APP".equals(channel) ? "IN_APP_STORED" : "CHANNEL_ACCEPTED")
                .equals(receipt.getStatus());
    }

    private void insertIntent(String eventType, Long eventId, Long memberId, String channel) {
        if (eventType == null || eventId == null || eventId <= 0 || memberId == null || memberId <= 0
                || !("IN_APP".equals(channel) || "WECHAT_SUBSCRIBE".equals(channel))) {
            throw new IllegalArgumentException("通知意图身份不完整");
        }
        NotificationDeliveryReceipt receipt = new NotificationDeliveryReceipt();
        receipt.setEventType(eventType);
        receipt.setEventId(eventId);
        receipt.setRecipientId(memberId);
        receipt.setChannel(channel);
        mapper.insertIntent(receipt);
    }
}
