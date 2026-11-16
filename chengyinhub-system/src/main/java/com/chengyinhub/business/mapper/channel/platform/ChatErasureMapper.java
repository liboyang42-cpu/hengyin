package com.chengyinhub.business.mapper.channel.platform;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * SOC-14: chat text of deleted accounts, and the purge of deleted channels after the restore window.
 * SQL avoids multi-table UPDATE/DELETE so it runs the same on MySQL and in the H2 tests.
 */
public interface ChatErasureMapper
{
    // ---------------------------------------------------------------- account erasure

    /** Inbox preview text of conversations whose latest message is the member's. */
    int clearConversationPreviews(@Param("memberId") Long memberId);

    /** A RECALL mutation for each of the member's visible channel messages, so channel clients drop the text. */
    int insertErasureMutations(@Param("memberId") Long memberId);

    /** Edit history of the member's messages. */
    int scrubRevisions(@Param("memberId") Long memberId);

    /** Pins on the member's visible messages. */
    int deletePins(@Param("memberId") Long memberId);

    /** Text and attachments removed; visible rows become recalled. Idempotent. */
    int scrubMessages(@Param("memberId") Long memberId);

    /** Channel op-log rows keep the action without the operator. */
    int unlinkOperatorLogs(@Param("memberId") Long memberId);

    /** Idempotency receipts of channel requests the member made. */
    int deleteRequestReceipts(@Param("memberId") Long memberId);

    // ---------------------------------------------------------------- deleted channel purge

    List<Long> selectDuePurges(@Param("now") Date now, @Param("limit") int limit);

    List<Long> selectMessageIds(@Param("channelId") Long channelId, @Param("limit") int limit);

    int deleteReactions(@Param("ids") List<Long> messageIds);

    int deleteMentions(@Param("ids") List<Long> messageIds);

    int deleteRefs(@Param("ids") List<Long> messageIds);

    int deleteRevisions(@Param("ids") List<Long> messageIds);

    int deleteMessages(@Param("ids") List<Long> messageIds);

    int deleteChannelPins(@Param("channelId") Long channelId);

    int deleteChannelMutations(@Param("channelId") Long channelId);

    int closeConversation(@Param("channelId") Long channelId);

    /** status 3 (deleted, restorable) → 4 (purged), only once the restore window has passed. */
    int markPurged(@Param("channelId") Long channelId, @Param("now") Date now);
}
