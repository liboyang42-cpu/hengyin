package com.chengyinhub.business.service.channel.interaction;

/** Immutable-history row mapping only; never infer this result from the current entry. */
public class ChainReceiptRecord
{
    private String receiptId;
    private long chainId;
    private long conversationId;
    private long memberId;
    private String requestId;
    private String action;
    private String content;
    private long expectedChainVersion;
    private long expectedEntryVersion;
    private int expectedActiveCount;
    private int nextActiveCount;
    private String effect;
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String value) { receiptId = value; }
    public long getChainId() { return chainId; }
    public void setChainId(long value) { chainId = value; }
    public long getConversationId() { return conversationId; }
    public void setConversationId(long value) { conversationId = value; }
    public long getMemberId() { return memberId; }
    public void setMemberId(long value) { memberId = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public long getExpectedChainVersion() { return expectedChainVersion; }
    public void setExpectedChainVersion(long value) { expectedChainVersion = value; }
    public long getExpectedEntryVersion() { return expectedEntryVersion; }
    public void setExpectedEntryVersion(long value) { expectedEntryVersion = value; }
    public int getExpectedActiveCount() { return expectedActiveCount; }
    public void setExpectedActiveCount(int value) { expectedActiveCount = value; }
    public int getNextActiveCount() { return nextActiveCount; }
    public void setNextActiveCount(int value) { nextActiveCount = value; }
    public String getEffect() { return effect; }
    public void setEffect(String value) { effect = value; }

    public ChainCommandExecutor.Receipt toReceipt()
    {
        var command = new ChainCommandIdentity(chainId, conversationId, memberId,
                requestId, ChainParticipationTransition.Action.valueOf(action), content);
        var proposal = new ChainParticipationTransition.Proposal(chainId, conversationId, memberId,
                expectedChainVersion, expectedEntryVersion, expectedActiveCount, nextActiveCount,
                ChainParticipationTransition.Effect.valueOf(effect));
        return new ChainCommandExecutor.Receipt(receiptId, command, proposal);
    }

    public static ChainReceiptRecord from(ChainCommandExecutor.Receipt receipt)
    {
        ChainReceiptRecord row = new ChainReceiptRecord();
        var command = receipt.command();
        var proposal = receipt.proposal();
        row.receiptId = receipt.receiptId();
        row.chainId = command.chainId();
        row.conversationId = command.conversationId();
        row.memberId = command.memberId();
        row.requestId = command.requestId();
        row.action = command.action().name();
        row.content = command.content();
        row.expectedChainVersion = proposal.expectedChainVersion();
        row.expectedEntryVersion = proposal.expectedEntryVersion();
        row.expectedActiveCount = proposal.expectedActiveCount();
        row.nextActiveCount = proposal.nextActiveCount();
        row.effect = proposal.effect().name();
        return row;
    }
}
