package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayArriveGuardLog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Guard facts + the authenticated original command receipt; never derives a run identity from log fields alone. */
public final class GuardAppealEligibility {
    private GuardAppealEligibility() { }

    /** The caller must obtain receipt and current run validity through the authoritative owned-command reader. */
    public static boolean matchesOwnedRejection(Long actorId, PlayArriveGuardLog log, JsonNode receipt) {
        if (actorId == null || log == null || receipt == null || !receipt.isObject()
                || !Objects.equals(actorId, log.getMemberId()) || !"REJECT".equals(log.getVerdict())
                || !(ArriveLocationGuardPolicy.SPEED.equals(log.getReasonCode())
                    || ArriveLocationGuardPolicy.WINDOW_DISTANCE.equals(log.getReasonCode()))) return false;
        if (!receipt.path("appealable").isBoolean() || !receipt.path("appealable").asBoolean()
                || !ArriveLocationGuard.APPEAL_BIZ_TYPE.equals(receipt.path("appealBizType").asText())
                || !log.getReasonCode().equals(receipt.path("errorCode").asText())
                || !sameId(receipt.get("guardLogId"), log.getId()) || !sameId(receipt.get("nodeId"), log.getNodeId())
                || !exactPositiveId(receipt.get("routeSessionId"))) return false;
        // Shared runtime identity must be complete; individual receipts may omit both fields.
        if (receipt.has("runtimeInstance") || receipt.has("authorityEpoch")) {
            return receipt.path("runtimeInstance").isTextual() && !receipt.path("runtimeInstance").asText().trim().isEmpty()
                    && receipt.path("authorityEpoch").isIntegralNumber() && receipt.path("authorityEpoch").canConvertToInt()
                    && receipt.path("authorityEpoch").asInt() >= 0;
        }
        return true;
    }

    /** An accepted result enables a fresh on-site attempt only. This is not arrival proof or a guard bypass. */
    public static boolean mayReverify(String appealState, boolean originalRunActive, boolean ownedReceiptMatches) {
        return "ACCEPTED".equals(appealState) && originalRunActive && ownedReceiptMatches;
    }

    private static boolean sameId(JsonNode node, Long expected) {
        return expected != null && exactPositiveId(node) && expected.toString().equals(node.asText());
    }
    private static boolean exactPositiveId(JsonNode node) {
        if (node == null || !node.isTextual() || !node.asText().matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(node.asText()) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }
}
