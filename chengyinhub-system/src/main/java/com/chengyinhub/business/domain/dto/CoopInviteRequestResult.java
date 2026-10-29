package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CoopInviteRequestReceipt;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Minimal recovery projection: exact IDs, without contract text or commercial terms. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CoopInviteRequestResult {
    private String state;
    private String requestId;
    private String inviteId;
    private Boolean replayed;
    private Integer inviteType;
    private String topicId;
    private String toType;
    private String toId;
    private String scope;
    private String supplyStationId;

    public static CoopInviteRequestResult state(String state, String requestId) {
        CoopInviteRequestResult result = new CoopInviteRequestResult();
        result.state = state;
        result.requestId = requestId;
        return result;
    }
    public static CoopInviteRequestResult created(CoopInviteRequestReceipt receipt, Boolean replayed) {
        return created(receipt, replayed, null);
    }
    public static CoopInviteRequestResult created(CoopInviteRequestReceipt receipt, Boolean replayed, Long supplyStationId) {
        if (supplyStationId != null && (supplyStationId <= 0 || !Integer.valueOf(0).equals(receipt.getInviteType()))) {
            throw new IllegalArgumentException("原邀请据点身份不合法");
        }
        CoopInviteRequestResult result = state("CREATED", receipt.getRequestId());
        result.inviteId = receipt.getInviteId().toString();
        result.replayed = replayed;
        result.inviteType = receipt.getInviteType();
        result.topicId = receipt.getTopicId().toString();
        result.toType = receipt.getToType();
        result.toId = receipt.getToId().toString();
        result.scope = receipt.getScope();
        result.supplyStationId = supplyStationId == null ? null : supplyStationId.toString();
        return result;
    }
    public String getState() { return state; }
    public String getRequestId() { return requestId; }
    public String getInviteId() { return inviteId; }
    public Boolean getReplayed() { return replayed; }
    public Integer getInviteType() { return inviteType; }
    public String getTopicId() { return topicId; }
    public String getToType() { return toType; }
    public String getToId() { return toId; }
    public String getScope() { return scope; }
    public String getSupplyStationId() { return supplyStationId; }
}
