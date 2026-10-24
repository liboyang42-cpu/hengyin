package com.chengyinhub.business.service.notify;

import java.util.List;
import java.util.Map;

/**
 * 正常回复/提及入口。必须带上已装配的频道口，不能用缺省的 null 注入收尾。
 * 不新造回执库，不调用 {@code IImService.send}。
 */
public final class ChannelReplyMentionEntry {
    private final ImmutableReceiptProducer producer;

    public ChannelReplyMentionEntry(ImmutableReceiptProducer.OriginalReceiptStore store,
            ImmutableReceiptProducer.ChannelMessageOriginalSend channel) {
        if (store == null || channel == null) {
            throw new ImmutableReceiptProducer.WaitingSourceException(ImmutableReceiptProducer.CHANNEL_MESSAGE_SEND);
        }
        this.producer = new ImmutableReceiptProducer(store, null, null, channel);
    }

    public ImmutableReceiptProducer.OriginalReceipt reply(String requestId, Long actorId, Long conversationId,
            Integer msgType, String content, String extraJson, Long replyToId) {
        return producer.sendOriginal(request(requestId, ImmutableReceiptProducer.Kind.REPLY), actorId, conversationId,
                msgType, content, extraJson, replyToId, List.of(), List.of(), false);
    }

    public ImmutableReceiptProducer.OriginalReceipt mention(String requestId, Long actorId, Long conversationId,
            Integer msgType, String content, String extraJson, List<Long> mentionMemberIds,
            List<Long> mentionRoleIds, boolean mentionEveryone) {
        return producer.sendOriginal(request(requestId, ImmutableReceiptProducer.Kind.MENTION), actorId,
                conversationId, msgType, content, extraJson, null, mentionMemberIds, mentionRoleIds, mentionEveryone);
    }

    public Map<String, Object> recover(Long actorId, String requestId) {
        return producer.recoverChannelReceipt(actorId, requestId);
    }

    private static ImmutableReceiptProducer.OriginalRequest request(String requestId,
            ImmutableReceiptProducer.Kind kind) {
        return new ImmutableReceiptProducer.OriginalRequest(requestId, kind, "{\"fp\":1}",
                ImmutableReceiptProducer.OUTCOME_ACCEPTED);
    }
}
