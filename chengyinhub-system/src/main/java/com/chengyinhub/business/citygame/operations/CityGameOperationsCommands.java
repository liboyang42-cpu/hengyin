package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.citygame.operations.mapper.CityGameOperationsMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

interface CityGameOperationsCommand {
    boolean supports(String kind);

    CityGameOperationsCommandResult execute(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper,
            CityGameOperationsAssetExecution assets);
}

final class CityGameOperationsCommandResult {
    final int httpStatus;
    final String errorCode;
    final String message;
    final String storeMerchantId;
    final String storeFactStatus;

    private CityGameOperationsCommandResult(int httpStatus, String errorCode, String message, String storeMerchantId, String storeFactStatus) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
        this.storeMerchantId = storeMerchantId;
        this.storeFactStatus = storeFactStatus;
    }

    static CityGameOperationsCommandResult ok(String merchantId, String factStatus) {
        return new CityGameOperationsCommandResult(200, null, "操作成功", merchantId, factStatus);
    }

    static CityGameOperationsCommandResult fail(int httpStatus, String errorCode, String message) {
        return new CityGameOperationsCommandResult(httpStatus, errorCode, message, null, null);
    }
}

@Component
class CityGameOperationsCompensateCommand implements CityGameOperationsCommand {
    @Override
    public boolean supports(String kind) {
        return "COMPENSATE".equals(kind);
    }

    @Override
    public CityGameOperationsCommandResult execute(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper,
            CityGameOperationsAssetExecution assets) {
        CityGameOperationsAssetExecution.Result execution = assets.execute("COMPENSATE");
        row.setStatus("RECORDED");
        row.setCorrectionState("COMPENSATE");
        row.setExecution("NON_EXECUTABLE");
        row.setBlocker(execution.blocker);
        row.setRollbackJson("{\"execution\":\"NON_EXECUTABLE\"}");
        return CityGameOperationsCommandResult.ok(null, null);
    }
}

@Component
class CityGameOperationsRevokeCommand implements CityGameOperationsCommand {
    @Override
    public boolean supports(String kind) {
        return "REVOKE".equals(kind);
    }

    @Override
    public CityGameOperationsCommandResult execute(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper,
            CityGameOperationsAssetExecution assets) {
        String effective = row.getCorrectionState();
        if (effective == null || "REVOKE".equals(effective)) {
            return CityGameOperationsCommandResult.fail(409, "INVALID_STATE", "当前状态不能撤销");
        }
        if ("COMPENSATE".equals(effective)) {
            row.setStatus("APPLIED");
            row.setCorrectionState("REVOKE");
            row.setExecution("NON_EXECUTABLE");
            row.setBlocker("ASSET_EXECUTION_DISABLED");
            return CityGameOperationsCommandResult.ok(null, null);
        }
        if ("CORRECT".equals(effective)) {
            JsonNode prior = CityGameOperationsTexts.read(row.getRollbackJson() == null ? "{}" : row.getRollbackJson());
            row.setClassification(textOrNull(prior.get("priorClassification")));
            row.setOpsNote(textOrNull(prior.get("priorOpsNote")));
            row.setStatus("APPLIED");
            row.setCorrectionState("REVOKE");
            row.setExecution("CONFIG_ONLY");
            row.setBlocker(null);
            return CityGameOperationsCommandResult.ok(null, null);
        }
        if ("RESTORE".equals(effective)) {
            JsonNode prior = CityGameOperationsTexts.read(row.getRollbackJson() == null ? "{}" : row.getRollbackJson());
            String fact = prior.path("priorFactStatus").asText("");
            if (!"PAUSED".equals(fact) && !"DISABLED".equals(fact)) {
                return CityGameOperationsCommandResult.fail(409, "INVALID_STATE", "没有可恢复的本店状态");
            }
            return store(row, "REVOKE", fact);
        }
        if (("PAUSE_MERCHANT".equals(effective) || "DISABLE".equals(effective)) && row.getMerchantId() != null) {
            return store(row, "REVOKE", "REVOKED");
        }
        if ("DISABLE".equals(effective)) {
            row.setStatus("APPLIED");
            row.setCorrectionState("REVOKE");
            row.setExecution("CONFIG_ONLY");
            row.setBlocker(null);
            return CityGameOperationsCommandResult.ok(null, null);
        }
        return CityGameOperationsCommandResult.fail(409, "INVALID_STATE", "当前状态不能撤销");
    }

    private static CityGameOperationsCommandResult store(CityGameOperationsCaseRow row, String state, String fact) {
        row.setStatus("APPLIED");
        row.setCorrectionState(state);
        row.setExecution("CONFIG_ONLY");
        row.setBlocker(null);
        return CityGameOperationsCommandResult.ok(row.getMerchantId(), fact);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}

@Component
class CityGameOperationsDisableCommand implements CityGameOperationsCommand {
    @Override
    public boolean supports(String kind) {
        return "DISABLE".equals(kind) || "PAUSE_MERCHANT".equals(kind);
    }

    @Override
    public CityGameOperationsCommandResult execute(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper,
            CityGameOperationsAssetExecution assets) {
        if ("PAUSE_MERCHANT".equals(row.getKind()) || "MERCHANT_STORE".equals(row.getObjectType())) {
            String merchantId = merchant(row);
            if (merchantId == null) {
                return CityGameOperationsCommandResult.fail(400, "MISSING_PARAM", "缺少必要参数");
            }
            String blocked = CityGameOperationsStoreWrites.blocked(row, mapper, merchantId);
            if (blocked != null) {
                return CityGameOperationsCommandResult.fail(409, blocked, "纠错范围超出本店");
            }
            String fact = "PAUSE_MERCHANT".equals(row.getKind()) ? "PAUSED" : "DISABLED";
            CityGameOperationsStoreFactRow current = mapper.selectStore(merchantId);
            if (current != null && fact.equals(current.getFactStatus())) {
                return CityGameOperationsCommandResult.fail(409, "INVALID_STATE", "当前状态不能执行");
            }
            row.setRollbackJson(priorFact(current));
            row.setMerchantId(merchantId);
            row.setStatus("APPLIED");
            row.setCorrectionState(row.getKind());
            row.setExecution("CONFIG_ONLY");
            row.setBlocker(null);
            return CityGameOperationsCommandResult.ok(merchantId, fact);
        }
        if (!"ACTION".equals(row.getObjectType())) {
            return CityGameOperationsCommandResult.fail(403, "ASSET_EXECUTION_DISABLED", "资金和资产操作不能执行");
        }
        row.setStatus("APPLIED");
        row.setCorrectionState("DISABLE");
        row.setExecution("CONFIG_ONLY");
        row.setBlocker(null);
        return CityGameOperationsCommandResult.ok(null, null);
    }

    private static String merchant(CityGameOperationsCaseRow row) {
        Object value = CityGameOperationsTexts.map(row.getProposalJson()).get("merchantId");
        return value instanceof String ? (String) value : null;
    }

    private static String priorFact(CityGameOperationsStoreFactRow current) {
        ObjectNode node = CityGameOperationsTexts.mapper().createObjectNode();
        node.put("priorFactStatus", current == null ? "NONE" : current.getFactStatus());
        return CityGameOperationsTexts.write(node);
    }
}

@Component
class CityGameOperationsRestoreCommand implements CityGameOperationsCommand {
    @Override
    public boolean supports(String kind) {
        return "RESTORE".equals(kind);
    }

    @Override
    public CityGameOperationsCommandResult execute(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper,
            CityGameOperationsAssetExecution assets) {
        Object merchant = CityGameOperationsTexts.map(row.getProposalJson()).get("merchantId");
        if (!(merchant instanceof String) || row.getMerchantId() == null || !row.getMerchantId().equals(merchant)) {
            return CityGameOperationsCommandResult.fail(409, "OBJECT_SCOPE", "纠错范围超出本店");
        }
        CityGameOperationsStoreFactRow store = mapper.selectStore(row.getMerchantId());
        if (store == null || !row.getId().equals(store.getCaseId())
                || (!"PAUSED".equals(store.getFactStatus()) && !"DISABLED".equals(store.getFactStatus()))) {
            return CityGameOperationsCommandResult.fail(409, "INVALID_STATE", "当前状态不能恢复");
        }
        ObjectNode node = CityGameOperationsTexts.mapper().createObjectNode();
        node.put("priorFactStatus", store.getFactStatus());
        row.setRollbackJson(CityGameOperationsTexts.write(node));
        row.setStatus("APPLIED");
        row.setCorrectionState("RESTORE");
        row.setExecution("CONFIG_ONLY");
        row.setBlocker(null);
        return CityGameOperationsCommandResult.ok(row.getMerchantId(), "RESTORED");
    }
}

@Component
class CityGameOperationsCorrectCommand implements CityGameOperationsCommand {
    @Override
    public boolean supports(String kind) {
        return "CORRECT".equals(kind);
    }

    @Override
    public CityGameOperationsCommandResult execute(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper,
            CityGameOperationsAssetExecution assets) {
        Map<String, Object> proposal = CityGameOperationsTexts.map(row.getProposalJson());
        Object classification = proposal.get("classification");
        Object note = proposal.get("opsNote");
        if (!(classification instanceof String) && !(note instanceof String)) {
            return CityGameOperationsCommandResult.fail(400, "MISSING_PARAM", "缺少必要参数");
        }
        ObjectNode prior = CityGameOperationsTexts.mapper().createObjectNode();
        if (row.getClassification() == null) {
            prior.putNull("priorClassification");
        } else {
            prior.put("priorClassification", row.getClassification());
        }
        if (row.getOpsNote() == null) {
            prior.putNull("priorOpsNote");
        } else {
            prior.put("priorOpsNote", row.getOpsNote());
        }
        row.setRollbackJson(CityGameOperationsTexts.write(prior));
        if (classification instanceof String) {
            row.setClassification((String) classification);
        }
        if (note instanceof String) {
            row.setOpsNote((String) note);
        }
        row.setStatus("APPLIED");
        row.setCorrectionState("CORRECT");
        row.setExecution("CONFIG_ONLY");
        row.setBlocker(null);
        return CityGameOperationsCommandResult.ok(null, null);
    }
}

final class CityGameOperationsStoreWrites {
    private CityGameOperationsStoreWrites() {
    }

    static String blocked(CityGameOperationsCaseRow row, CityGameOperationsMapper mapper, String merchantId) {
        if (row.getMerchantId() != null && !row.getMerchantId().equals(merchantId)) {
            return "OBJECT_SCOPE";
        }
        CityGameOperationsStoreFactRow store = mapper.selectStore(merchantId);
        if (store != null && !row.getId().equals(store.getCaseId())
                && ("PAUSED".equals(store.getFactStatus()) || "DISABLED".equals(store.getFactStatus()))) {
            return "STORE_SCOPE";
        }
        return null;
    }

    static boolean write(CityGameOperationsMapper mapper, String merchantId, String factStatus, CityGameOperationsCaseRow row) {
        CityGameOperationsStoreFactRow current = mapper.selectStore(merchantId);
        Date now = new Date();
        if (current == null) {
            CityGameOperationsStoreFactRow created = new CityGameOperationsStoreFactRow();
            created.setMerchantId(merchantId);
            created.setFactStatus(factStatus);
            created.setVersion(1L);
            created.setCaseId(row.getId());
            created.setActionId(row.getActionId());
            created.setUpdateTime(now);
            return mapper.insertStore(created) == 1;
        }
        current.setExpectedVersion(current.getVersion());
        current.setVersion(current.getVersion() + 1);
        current.setFactStatus(factStatus);
        current.setCaseId(row.getId());
        current.setActionId(row.getActionId());
        current.setUpdateTime(now);
        return mapper.updateStore(current) == 1;
    }
}
