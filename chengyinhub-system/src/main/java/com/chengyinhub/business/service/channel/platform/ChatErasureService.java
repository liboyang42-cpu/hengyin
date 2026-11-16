package com.chengyinhub.business.service.channel.platform;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.mapper.channel.platform.ChatErasureMapper;

/**
 * SOC-14. Two jobs:
 * <ol>
 *   <li><b>Account erasure</b> (called from the deregistration erasure, inside its transaction). With
 *       {@code chengyin.erasure.im-messages=SCRUB} (default) every message the member sent loses its text and
 *       attachments and shows as recalled; the row stays so replies and threads keep their shape, and channel
 *       clients render it as 该用户已注销 (the sender is a deregistered account). {@code KEEP} leaves the text, as
 *       before. With {@code chengyin.erasure.channel-operator-ids=SCRUB} (default) channel op-log rows drop the
 *       operator and the member's channel request receipts are deleted; {@code KEEP} keeps them for audit.
 *       Both are owner and counsel decisions (PIPL Art. 47); the defaults follow the conservative reading.</li>
 *   <li><b>Purge</b> of deleted channels (status 3) once {@code purge_at} has passed: messages and their
 *       reactions, mentions, thread links, revisions, pins and mutations are deleted in batches, the
 *       conversation is closed and the channel becomes status 4 (purged).</li>
 * </ol>
 */
@Service
public class ChatErasureService
{
    private static final Logger log = LoggerFactory.getLogger(ChatErasureService.class);
    static final int MESSAGE_BATCH = 500;
    /** Message batches per channel per run; a larger channel continues on the next run. */
    static final int MAX_BATCHES_PER_CHANNEL = 40;

    public enum Policy { SCRUB, KEEP;
        static Policy parse(String raw)
        {
            return raw != null && "KEEP".equals(raw.trim().toUpperCase(Locale.ROOT)) ? KEEP : SCRUB;
        }
    }

    private final ChatErasureMapper mapper;
    private final Policy messages;
    private final Policy operatorIds;

    public ChatErasureService(ChatErasureMapper mapper,
                              @Value("${chengyin.erasure.im-messages:SCRUB}") String messages,
                              @Value("${chengyin.erasure.channel-operator-ids:SCRUB}") String operatorIds)
    {
        this.mapper = mapper;
        this.messages = Policy.parse(messages);
        this.operatorIds = Policy.parse(operatorIds);
    }

    public Policy messagePolicy() { return messages; }

    public Policy operatorIdPolicy() { return operatorIds; }

    /** Erases the member's chat text. Returns affected rows per step (for logs and tests). */
    public Map<String, Integer> eraseMember(Long memberId)
    {
        if (memberId == null || memberId <= 0) throw new IllegalArgumentException("Invalid erasure member");
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (messages == Policy.SCRUB)
        {
            // Order matters: previews, mutations and pins look at status = 0 before the scrub recalls the rows.
            counts.put("im_conversation.last_msg_text", mapper.clearConversationPreviews(memberId));
            counts.put("im_message_mutation", mapper.insertErasureMutations(memberId));
            counts.put("im_message_revision", mapper.scrubRevisions(memberId));
            counts.put("im_message_pin", mapper.deletePins(memberId));
            counts.put("im_message", mapper.scrubMessages(memberId));
        }
        if (operatorIds == Policy.SCRUB)
        {
            counts.put("im_channel_op_log", mapper.unlinkOperatorLogs(memberId));
            counts.put("im_channel_request_receipt", mapper.deleteRequestReceipts(memberId));
        }
        return counts;
    }

    /** Purges up to {@code channelLimit} deleted channels whose restore window has passed. Returns channels purged. */
    public int purgeDeletedChannels(int channelLimit, Date now)
    {
        int limit = channelLimit <= 0 ? 50 : Math.min(channelLimit, 500);
        List<Long> due = mapper.selectDuePurges(now, limit);
        int purged = 0;
        for (Long channelId : due)
        {
            try
            {
                if (purgeChannel(channelId, now)) purged++;
            }
            catch (DataAccessException e)
            {
                // One bad channel must not stop the rest; the next run retries it.
                log.warn("channel purge failed channelId={} reason={}", channelId, e.getClass().getSimpleName());
            }
        }
        return purged;
    }

    /** Children before parents, so an interrupted run leaves nothing dangling and simply continues next time. */
    boolean purgeChannel(Long channelId, Date now)
    {
        for (int batch = 0; batch < MAX_BATCHES_PER_CHANNEL; batch++)
        {
            List<Long> ids = mapper.selectMessageIds(channelId, MESSAGE_BATCH);
            if (ids == null || ids.isEmpty())
            {
                mapper.deleteChannelPins(channelId);
                mapper.deleteChannelMutations(channelId);
                mapper.closeConversation(channelId);
                return mapper.markPurged(channelId, now) == 1;
            }
            mapper.deleteReactions(ids);
            mapper.deleteMentions(ids);
            mapper.deleteRefs(ids);
            mapper.deleteRevisions(ids);
            mapper.deleteMessages(ids);
        }
        return false;
    }
}
