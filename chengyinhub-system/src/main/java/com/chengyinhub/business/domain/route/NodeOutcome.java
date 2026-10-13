package com.chengyinhub.business.domain.route;

import com.chengyinhub.common.utils.StringUtils;

/** 服务端完成节点后生成的稳定结果；客户端不得直接构造 outcomeCode。 */
public final class NodeOutcome {
    private final Long nodeId;
    private final String outcomeCode;
    private final String receiptId;

    public NodeOutcome(Long nodeId, String outcomeCode, String receiptId) {
        if (nodeId == null || nodeId <= 0L) throw new IllegalArgumentException("nodeId 无效");
        if (StringUtils.isBlank(outcomeCode) || outcomeCode.length() > 64) {
            throw new IllegalArgumentException("outcomeCode 无效");
        }
        if (StringUtils.isBlank(receiptId) || receiptId.length() > 96) {
            throw new IllegalArgumentException("receiptId 无效");
        }
        this.nodeId = nodeId;
        this.outcomeCode = outcomeCode.trim();
        this.receiptId = receiptId.trim();
    }

    public Long getNodeId() { return nodeId; }
    public String getOutcomeCode() { return outcomeCode; }
    public String getReceiptId() { return receiptId; }
}
