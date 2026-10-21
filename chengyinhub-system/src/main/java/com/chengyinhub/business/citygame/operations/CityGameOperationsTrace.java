package com.chengyinhub.business.citygame.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 把端口事实收成诊断快照。缺的槽位保持缺失，不补履约、费率或概率。 */
final class CityGameOperationsTrace {

    static final List<String> SLOTS = List.of(
            "rule", "placeFacts", "evidence", "task", "dice", "quote", "ledger", "ownership", "timeline");
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final Pattern ID = Pattern.compile("\\A[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\z");
    private static final Pattern HASH = Pattern.compile("\\A[0-9a-f]{64}\\z");
    private static final Pattern CODE = Pattern.compile("\\A[A-Z][A-Z0-9_]{0,15}\\z");
    private static final Pattern MONEY = Pattern.compile("\\A[A-Z]{3}\\z");
    private static final Set<String> FORBIDDEN = Set.of(
            "fulfilled", "fulfilment", "pointRate", "feeRate", "probability", "threshold",
            "completed", "completable", "weight", "weights", "teamAsset", "wallet", "award");

    final String json;
    final String bindingStatus;
    final Long ownerUserId;
    final String objectRole;
    final String placeMerchantId;

    private CityGameOperationsTrace(String json, String bindingStatus, Long ownerUserId, String objectRole, String placeMerchantId) {
        this.json = json;
        this.bindingStatus = bindingStatus;
        this.ownerUserId = ownerUserId;
        this.objectRole = objectRole;
        this.placeMerchantId = placeMerchantId;
    }

    static CityGameOperationsTrace capture(CityGameOperationsFactPort port, String actionId, String regionId, String sourceRequestId) {
        ObjectNode root = CityGameOperationsTexts.mapper().createObjectNode();
        ObjectNode slots = root.putObject("slots");
        ArrayNode missing = root.putArray("missingSlots");
        String binding = "UNVERIFIED";
        Long owner = null;
        String role = null;
        String placeMerchant = null;
        for (String slot : SLOTS) {
            ObjectNode accepted = accept(slot, port.read(slot, actionId, regionId));
            if ("ownership".equals(slot) && !accepted.path("missing").asBoolean()) {
                JsonNode value = accepted.get("value");
                Long ownerId = value.path("ownerUserId").isNumber() ? value.get("ownerUserId").longValue() : null;
                String objectRole = value.path("objectRole").asText(null);
                String source = value.path("sourceRequestId").asText(null);
                if (ownerId == null || objectRole == null || source == null) {
                    accepted = missingNode("INCOMPLETE_BINDING");
                } else if (!source.equals(sourceRequestId)) {
                    binding = "MISMATCH";
                } else {
                    binding = "VERIFIED";
                    owner = ownerId;
                    role = objectRole;
                }
            }
            if ("placeFacts".equals(slot) && !accepted.path("missing").asBoolean() && accepted.get("value").has("merchantId")) {
                placeMerchant = accepted.get("value").get("merchantId").asText();
            }
            slots.set(slot, accepted);
            if (accepted.path("missing").asBoolean()) {
                missing.add(slot);
            }
        }
        root.put("bindingStatus", binding);
        return new CityGameOperationsTrace(CityGameOperationsTexts.write(root), binding, owner, role, placeMerchant);
    }

    static boolean ownershipMatches(CityGameOperationsFactPort port, String actionId, String regionId, String sourceRequestId, Long ownerUserId, String objectRole) {
        CityGameOperationsTrace live = capture(port, actionId, regionId, sourceRequestId);
        return "VERIFIED".equals(live.bindingStatus)
                && ownerUserId != null
                && ownerUserId.equals(live.ownerUserId)
                && objectRole != null
                && objectRole.equals(live.objectRole);
    }

    private static ObjectNode accept(String slot, CityGameOperationsFactPort.Slot raw) {
        if (raw == null || raw.missing || raw.value == null || raw.value.isEmpty()) {
            return missingNode(raw == null || raw.reason == null ? "MISSING" : raw.reason);
        }
        JsonNode tree = CityGameOperationsTexts.mapper().valueToTree(raw.value);
        if (!tree.isObject() || forbidden(tree)) {
            return missingNode("EVIDENCE_NOT_ACCEPTED");
        }
        ObjectNode value = CityGameOperationsTexts.mapper().createObjectNode();
        switch (slot) {
            case "rule":
                copyId(tree, value, "rulesReleaseId");
                copyHash(tree, value, "rulesHash");
                copyCode(tree, value, "lifecycle");
                copyPlain(tree, value, "title", 160);
                break;
            case "placeFacts":
                copyId(tree, value, "placeId");
                copyId(tree, value, "merchantId");
                copyPlain(tree, value, "label", 160);
                break;
            case "evidence":
                copyId(tree, value, "evidenceId");
                copyPlain(tree, value, "kind", 64);
                copyPlain(tree, value, "observedAt", 64);
                break;
            case "task":
                copyId(tree, value, "taskId");
                copyPlain(tree, value, "title", 160);
                copySteps(tree, value);
                break;
            case "dice":
                copyId(tree, value, "diceId");
                copyFaces(tree, value);
                break;
            case "quote":
                if (!copyMoney(tree, value, "quoteId", "amountMinor")) {
                    return missingNode("EVIDENCE_NOT_ACCEPTED");
                }
                break;
            case "ledger":
                if (!copyMoney(tree, value, "ledgerRef", "balanceMinor")) {
                    return missingNode("EVIDENCE_NOT_ACCEPTED");
                }
                copyLong(tree, value, "memberId");
                copyLong(tree, value, "detailRows");
                break;
            case "ownership":
                copyLong(tree, value, "ownerUserId");
                copyId(tree, value, "objectRole");
                copyId(tree, value, "teamId");
                copyId(tree, value, "sourceRequestId");
                break;
            case "timeline":
                if (!copyEvents(tree, value)) {
                    return missingNode("MISSING");
                }
                break;
            default:
                return missingNode("MISSING");
        }
        if (value.isEmpty()) {
            return missingNode("MISSING");
        }
        ObjectNode node = CityGameOperationsTexts.mapper().createObjectNode();
        node.put("status", "PRESENT");
        node.put("missing", false);
        node.putNull("reason");
        node.set("value", value);
        return node;
    }

    private static ObjectNode missingNode(String reason) {
        ObjectNode node = CityGameOperationsTexts.mapper().createObjectNode();
        node.put("status", "MISSING");
        node.put("missing", true);
        node.put("reason", reason);
        node.putNull("value");
        return node;
    }

    private static boolean forbidden(JsonNode node) {
        if (node.isObject()) {
            java.util.Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (FORBIDDEN.contains(name) || forbidden(node.get(name))) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                if (forbidden(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void copyId(JsonNode raw, ObjectNode out, String key) {
        JsonNode node = raw.get(key);
        if (node != null && node.isTextual() && ID.matcher(node.asText()).matches()) {
            out.put(key, node.asText());
        }
    }

    private static void copyHash(JsonNode raw, ObjectNode out, String key) {
        JsonNode node = raw.get(key);
        if (node != null && node.isTextual() && HASH.matcher(node.asText()).matches()) {
            out.put(key, node.asText());
        }
    }

    private static void copyCode(JsonNode raw, ObjectNode out, String key) {
        JsonNode node = raw.get(key);
        if (node != null && node.isTextual() && CODE.matcher(node.asText()).matches()) {
            out.put(key, node.asText());
        }
    }

    private static void copyPlain(JsonNode raw, ObjectNode out, String key, int max) {
        JsonNode node = raw.get(key);
        if (node != null && node.isTextual()) {
            String text = node.asText().trim();
            if (!text.isEmpty() && text.length() <= max) {
                out.put(key, text);
            }
        }
    }

    private static void copyLong(JsonNode raw, ObjectNode out, String key) {
        JsonNode node = raw.get(key);
        if (node != null && node.isIntegralNumber() && node.canConvertToLong()) {
            long value = node.longValue();
            if (value > 0 && value <= MAX_SAFE) {
                out.put(key, value);
            }
        }
    }

    private static void copySteps(JsonNode raw, ObjectNode out) {
        JsonNode steps = raw.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty() || steps.size() > 20) {
            return;
        }
        ArrayNode copy = out.putArray("steps");
        for (JsonNode step : steps) {
            if (!step.isTextual() || step.asText().isBlank() || step.asText().length() > 160) {
                out.remove("steps");
                return;
            }
            copy.add(step.asText());
        }
    }

    private static void copyFaces(JsonNode raw, ObjectNode out) {
        JsonNode faces = raw.get("faces");
        if (faces == null || !faces.isArray() || faces.isEmpty() || faces.size() > 32) {
            return;
        }
        ArrayNode copy = out.putArray("faces");
        for (JsonNode face : faces) {
            if (!face.isIntegralNumber() || !face.canConvertToLong()) {
                out.remove("faces");
                return;
            }
            copy.add(face.longValue());
        }
    }

    private static boolean copyMoney(JsonNode raw, ObjectNode out, String idKey, String minorKey) {
        if (raw.has(minorKey)) {
            JsonNode minor = raw.get(minorKey);
            if (!minor.isIntegralNumber() || !minor.canConvertToLong() || minor.longValue() < 0 || minor.longValue() > MAX_SAFE) {
                return false;
            }
            if (!raw.has("currency") || !raw.get("currency").isTextual() || !MONEY.matcher(raw.get("currency").asText()).matches()) {
                return false;
            }
            out.put(minorKey, minor.longValue());
            out.put("currency", raw.get("currency").asText());
        }
        copyId(raw, out, idKey);
        return true;
    }

    private static boolean copyEvents(JsonNode raw, ObjectNode out) {
        JsonNode events = raw.get("events");
        if (events == null || !events.isArray() || events.isEmpty() || events.size() > 50) {
            return false;
        }
        ArrayNode copy = out.putArray("events");
        for (JsonNode event : events) {
            if (!event.isObject()) {
                return false;
            }
            ObjectNode item = copy.addObject();
            copyId(event, item, "eventId");
            copyPlain(event, item, "kind", 64);
            copyPlain(event, item, "at", 64);
            if (!item.has("eventId") || !item.has("kind")) {
                return false;
            }
        }
        return true;
    }
}
