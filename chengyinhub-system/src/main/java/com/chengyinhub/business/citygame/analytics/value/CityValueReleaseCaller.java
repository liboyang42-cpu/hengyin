package com.chengyinhub.business.citygame.analytics.value;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardReleaseRow;
import com.chengyinhub.business.cityboard.mapper.CityBoardMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从当前冻结发布拉取定义与覆盖缺口。这是报告查询实际调用的适配器，不读取积分账本或商户核销。
 */
@Component
public class CityValueReleaseCaller {
    private static final String[] FAMILIES = {
            ValueKinds.ENTRY, ValueKinds.MEANINGFUL_ACTION, ValueKinds.SERVICE_SUCCESS, ValueKinds.PUBLIC_GOAL,
            ValueKinds.COOPERATIVE_GOAL, ValueKinds.SKIP, ValueKinds.EXIT, ValueKinds.SERVICE_RATING,
            ValueKinds.NET_POINTS, ValueKinds.VISIT_BUDGET
    };
    private static final Map<String, String> EMITTERS = emitters();

    private final CityBoardMapper boards;
    private final ValueEventIngestor ingestor;

    public CityValueReleaseCaller(CityBoardMapper boards, ValueEventIngestor ingestor) {
        this.boards = boards;
        this.ingestor = ingestor;
    }

    public ValueOutcome pull(String regionId) {
        if (!CityBoardDraftDocument.isId(regionId)) {
            return ValueOutcome.fail(400, "MISSING_PARAM", "区域无效", status("MISSING_PARAM"));
        }
        CityBoardReleaseRow release = boards.selectCurrentRelease(regionId);
        if (release == null) {
            Map<String, Object> data = status("NO_CURRENT_BOARD");
            data.put("regionId", regionId);
            return ValueOutcome.ok(data);
        }
        Date occurredAt = release.getCreateTime() == null ? new Date() : release.getCreateTime();
        int accepted = 0;
        int replayed = 0;
        ValueEventIngestor.Accept definition = ingestor.acceptFact(fact(release, ValueKinds.DEFINITION, null,
                definitionDetail(release), occurredAt));
        ValueEventIngestor.Accept failed = count(definition);
        if (failed != null) {
            return ValueOutcome.fail(409, failed.errorCode, failed.message, status(failed.errorCode));
        }
        if ("ACCEPTED".equals(definition.status)) {
            accepted++;
        } else {
            replayed++;
        }
        for (String family : FAMILIES) {
            ObjectNode detail = CityBoardDraftDocument.mapper().createObjectNode();
            detail.put("coverage", "MISSING_EMITTER");
            detail.put("emitter", EMITTERS.get(family));
            detail.put("family", family);
            ValueEventIngestor.Accept accept = ingestor.acceptFact(fact(release, ValueKinds.COVERAGE, family,
                    write(detail), occurredAt));
            failed = count(accept);
            if (failed != null) {
                return ValueOutcome.fail(409, failed.errorCode, failed.message, status(failed.errorCode));
            }
            if ("ACCEPTED".equals(accept.status)) {
                accepted++;
            } else {
                replayed++;
            }
        }
        Map<String, Object> data = status("ADAPTED");
        data.put("accepted", accepted);
        data.put("boardId", release.getBoardId());
        data.put("contentHash", release.getContentHash());
        data.put("regionId", release.getRegionId());
        data.put("replayed", replayed);
        data.put("revision", release.getRevision());
        data.put("rulesReleaseId", release.getRulesReleaseId());
        data.put("seasonId", release.getSeasonId());
        return ValueOutcome.ok(data);
    }

    private static ValueEventIngestor.Accept count(ValueEventIngestor.Accept accept) {
        if ("ACCEPTED".equals(accept.status) || "REPLAYED".equals(accept.status)) {
            return null;
        }
        return accept;
    }

    private static ValueEventRow fact(CityBoardReleaseRow release, String kind, String family, String detail, Date occurredAt) {
        ValueEventRow row = new ValueEventRow();
        row.setKind(kind);
        row.setRegionId(release.getRegionId());
        row.setBoardId(release.getBoardId());
        row.setSeasonId(release.getSeasonId());
        row.setRulesReleaseId(release.getRulesReleaseId());
        row.setMemberId(0L);
        row.setOccurredAt(occurredAt);
        row.setDetailJson(detail);
        if (ValueKinds.DEFINITION.equals(kind)) {
            String hash = CityBoardDraftDocument.isId(release.getContentHash()) ? release.getContentHash() : "unreadable";
            row.setGoalId(hash);
        } else {
            row.setActionCode(family);
        }
        return row;
    }

    private static String definitionDetail(CityBoardReleaseRow release) {
        ObjectNode detail = CityBoardDraftDocument.mapper().createObjectNode();
        if (release.getContentHash() != null) {
            detail.put("contentHash", release.getContentHash());
        }
        if (release.getRevision() != null) {
            detail.put("revision", release.getRevision());
        }
        detail.put("rulesReleaseId", release.getRulesReleaseId());
        CityBoardDraftDocument document = readable(release.getSnapshotJson());
        if (document == null) {
            detail.put("document", "DOCUMENT_UNREADABLE");
        } else {
            JsonNode budget = document.canonical.path("season").path("budget");
            if (budget.hasNonNull("amountMinor") && budget.hasNonNull("currency")) {
                detail.put("seasonBudgetAmountMinor", budget.get("amountMinor").asLong());
                detail.put("seasonBudgetCurrency", budget.get("currency").asText());
            } else {
                detail.put("seasonBudget", "MISSING_FIELD");
            }
            JsonNode fee = document.canonical.get("serviceFee");
            if (fee != null && fee.hasNonNull("amountMinor") && fee.hasNonNull("currency")) {
                detail.put("serviceFeeAmountMinor", fee.get("amountMinor").asLong());
                detail.put("serviceFeeCurrency", fee.get("currency").asText());
            } else {
                detail.put("serviceFee", "MISSING_FIELD");
            }
            JsonNode cost = document.canonical.get("pointCost");
            if (cost != null && cost.hasNonNull("amount") && cost.hasNonNull("unit")) {
                detail.put("pointCostAmount", cost.get("amount").asLong());
                detail.put("pointCostUnit", cost.get("unit").asText());
                if (cost.hasNonNull("currency")) {
                    detail.put("pointCostCurrency", cost.get("currency").asText());
                }
            } else {
                detail.put("pointCost", "MISSING_FIELD");
            }
            JsonNode tasks = document.canonical.get("tasks");
            detail.put("taskCount", tasks != null && tasks.isArray() ? tasks.size() : 0);
        }
        detail.put("serviceCatalog", "MISSING_FIELD");
        detail.put("goalVisibility", "MISSING_FIELD");
        detail.put("participation", "UNAVAILABLE");
        detail.put("pointsProjection", "UNAVAILABLE");
        return write(detail);
    }

    private static CityBoardDraftDocument readable(String snapshotJson) {
        if (snapshotJson == null) {
            return null;
        }
        try {
            JsonNode root = CityBoardDraftDocument.mapper().readTree(snapshotJson);
            JsonNode document = root.get("document");
            if (document == null) {
                return null;
            }
            CityBoardDraftDocument parsed = CityBoardDraftDocument.parse(document);
            return parsed.structural.isEmpty() && parsed.canonical != null ? parsed : null;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String write(ObjectNode node) {
        try {
            return CityBoardDraftDocument.mapper().writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> status(String status) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("candidate", Boolean.TRUE);
        data.put("contract", "CITY_VALUE_READ_V1");
        data.put("status", status);
        data.put("transport", "NOT_APPROVED");
        return data;
    }

    private static Map<String, String> emitters() {
        Map<String, String> emitters = new LinkedHashMap<String, String>();
        emitters.put(ValueKinds.ENTRY, "ApiCityGameReadController.current / playerCurrent 只读，不写进入事件");
        emitters.put(ValueKinds.MEANINGFUL_ACTION, "CITY 棋盘域没有有意义动作发出点；点击、走路、每日登录、收租登录不算");
        emitters.put(ValueKinds.SERVICE_SUCCESS, "冻结快照没有服务完成事件；不读取商户 citynode 核销");
        emitters.put(ValueKinds.PUBLIC_GOAL, "tasks 没有公共目标字段，也没有完成发出点");
        emitters.put(ValueKinds.COOPERATIVE_GOAL, "tasks 没有合作目标字段，也没有完成发出点");
        emitters.put(ValueKinds.SKIP, "没有主动跳过发出点");
        emitters.put(ValueKinds.EXIT, "没有主动退出发出点");
        emitters.put(ValueKinds.SERVICE_RATING, "没有服务评分发出点");
        emitters.put(ValueKinds.NET_POINTS, "playerPoints 固定 CITY_POINTS_UNAVAILABLE，不读积分账本");
        emitters.put(ValueKinds.VISIT_BUDGET, "season.budget 是棋盘预算，不是单次访问预算，没有访问预算发出点");
        return Map.copyOf(emitters);
    }
}
