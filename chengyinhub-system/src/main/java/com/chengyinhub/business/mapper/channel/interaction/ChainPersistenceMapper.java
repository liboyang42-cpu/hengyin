package com.chengyinhub.business.mapper.channel.interaction;

import com.chengyinhub.business.service.channel.interaction.ChainCommandIdentity;
import com.chengyinhub.business.service.channel.interaction.ChainParticipationTransition;
import com.chengyinhub.business.service.channel.interaction.ChainReceiptRecord;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Storage primitives for a future ChainCommandExecutor.Store. No permission implementation.
 * The caller must authenticate and revalidate current access, then hold the chain and entry
 * locks while count, relation, receipt and event are committed in ONE transaction.
 * A failed count/entry CAS or uniqueness violation must roll back the entire command.
 * Read original receipts before computing transitions. Do not retry an unknown commit.
 */
public interface ChainPersistenceMapper
{
    Map<String, Object> lockChain(@Param("chainId") long chainId,
            @Param("conversationId") long conversationId);
    Map<String, Object> lockEntry(@Param("chainId") long chainId, @Param("memberId") long memberId);
    ChainReceiptRecord findOriginal(@Param("chainId") long chainId, @Param("requestId") String requestId);
    int updateCount(@Param("proposal") ChainParticipationTransition.Proposal proposal);
    int insertEntry(@Param("command") ChainCommandIdentity command, @Param("position") int position);
    int updateEntry(@Param("command") ChainCommandIdentity command,
            @Param("proposal") ChainParticipationTransition.Proposal proposal);
    int insertReceipt(ChainReceiptRecord receipt);
    int insertEvent(@Param("chainId") long chainId, @Param("receiptId") String receiptId);
}
