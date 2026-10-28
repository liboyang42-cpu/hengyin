package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.route.PlayCheckReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Wire-only projection. Never remove calculation values from stored check state/receipts. */
public final class CheckReceiptProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CheckReceiptProjection() { }

    public static com.fasterxml.jackson.databind.JsonNode response(Object value) {
        com.fasterxml.jackson.databind.JsonNode tree = JSON.valueToTree(value);
        visit(tree);
        return tree;
    }

    private static void visit(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject() && (node.has("breakdown") || node.has("dice") || node.has("checkId") || node.has("attemptId"))) redact((ObjectNode) node);
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            java.util.List<String> exactIds = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(name -> {
                var value = object.get(name);
                if ((name.equals("id") || name.endsWith("Id")) && value.isIntegralNumber()
                        && value.bigIntegerValue().abs().compareTo(java.math.BigInteger.valueOf(9007199254740991L)) > 0) exactIds.add(name);
            });
            exactIds.forEach(name -> object.put(name, object.get(name).asText()));
        }
        if (node.isContainerNode()) node.forEach(CheckReceiptProjection::visit);
    }

    public static ObjectNode project(PlayCheckReceipt receipt) {
        return receipt == null ? null : (ObjectNode) response(receipt);
    }

    public static ObjectNode redact(ObjectNode view) {
        var breakdown = view.path("breakdown");
        if (view.path("detailsHidden").asBoolean() || view.path("hidden").asBoolean() || view.path("redacted").asBoolean()
                || breakdown.path("hidden").asBoolean() || breakdown.path("redacted").asBoolean()
                || breakdown.path("skill").path("hidden").asBoolean()
                || breakdown.path("skill").path("redacted").asBoolean()
                || breakdown.path("situational").path("hidden").asBoolean()
                || breakdown.path("situational").path("redacted").asBoolean()) {
            view.put("detailsHidden", true);
            view.putNull("total"); view.putNull("kept"); view.putNull("dc");
            view.remove(java.util.List.of("margin", "marginText", "difference"));
            view.putArray("dice"); view.putArray("mods"); view.put("nat", "");
            view.putObject("breakdown").put("hidden", true);
        }
        return view;
    }
}
