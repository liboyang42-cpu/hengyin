package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.citygame.operations.mapper.CityGameOperationsMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 隔离验收用的映射，不连接数据库。 */
public class CityGameOperationsMemoryMapper implements CityGameOperationsMapper {
    public volatile boolean failTerminalUpdate;
    private final Map<Long, CityGameOperationsCaseRow> cases = new ConcurrentHashMap<Long, CityGameOperationsCaseRow>();
    private final Map<String, Long> actions = new ConcurrentHashMap<String, Long>();
    private final Map<String, CityGameOperationsRequestRow> requests = new ConcurrentHashMap<String, CityGameOperationsRequestRow>();
    private final Map<Long, CityGameOperationsAuditRow> audits = new ConcurrentHashMap<Long, CityGameOperationsAuditRow>();
    private final Map<String, CityGameOperationsStoreFactRow> stores = new ConcurrentHashMap<String, CityGameOperationsStoreFactRow>();

    @Override
    public synchronized CityGameOperationsCaseRow selectCaseById(long id) {
        CityGameOperationsCaseRow row = cases.get(id);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized CityGameOperationsCaseRow selectCaseByActionId(String actionId) {
        Long id = actions.get(actionId);
        return id == null ? null : selectCaseById(id);
    }

    @Override
    public synchronized int insertCase(CityGameOperationsCaseRow row) {
        if (row.getId() == null || cases.containsKey(row.getId()) || actions.containsKey(row.getActionId())) {
            return 0;
        }
        cases.put(row.getId(), row.copy());
        actions.put(row.getActionId(), row.getId());
        return 1;
    }

    @Override
    public synchronized int updateCase(CityGameOperationsCaseRow row) {
        CityGameOperationsCaseRow stored = cases.get(row.getId());
        if (stored == null || stored.getVersion() == null || !stored.getVersion().equals(row.getExpectedVersion())) {
            return 0;
        }
        cases.put(row.getId(), row.copy());
        return 1;
    }

    @Override
    public synchronized CityGameOperationsRequestRow selectRequest(String requestId) {
        CityGameOperationsRequestRow row = requests.get(requestId);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized int insertRequest(CityGameOperationsRequestRow row) {
        if (row.getRequestId() == null || requests.containsKey(row.getRequestId())) {
            return 0;
        }
        requests.put(row.getRequestId(), row.copy());
        return 1;
    }

    @Override
    public synchronized int updateRequestState(CityGameOperationsRequestRow row) {
        if (failTerminalUpdate) {
            return 0;
        }
        CityGameOperationsRequestRow stored = requests.get(row.getRequestId());
        if (stored == null || !"UNKNOWN".equals(stored.getState())) {
            return 0;
        }
        CityGameOperationsRequestRow next = row.copy();
        next.setBodyJson(stored.getBodyJson());
        next.setRequestHash(stored.getRequestHash());
        next.setActorUserId(stored.getActorUserId());
        next.setRoleKeys(stored.getRoleKeys());
        next.setAction(stored.getAction());
        next.setCreateTime(stored.getCreateTime());
        requests.put(row.getRequestId(), next);
        return 1;
    }

    @Override
    public synchronized List<CityGameOperationsAuditRow> selectAuditsByCaseId(long caseId) {
        return audits.values().stream()
                .filter(row -> row.getCaseId() != null && row.getCaseId().longValue() == caseId)
                .sorted(Comparator.comparing(CityGameOperationsAuditRow::getId))
                .map(CityGameOperationsAuditRow::copy)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public synchronized List<CityGameOperationsAuditRow> selectAuditsByRequestId(String requestId) {
        List<CityGameOperationsAuditRow> rows = new ArrayList<CityGameOperationsAuditRow>();
        for (CityGameOperationsAuditRow row : audits.values()) {
            if (requestId.equals(row.getRequestId())) {
                rows.add(row.copy());
            }
        }
        rows.sort(Comparator.comparing(CityGameOperationsAuditRow::getId));
        return rows;
    }

    @Override
    public synchronized int insertAudit(CityGameOperationsAuditRow row) {
        if (row.getId() == null || audits.containsKey(row.getId())) {
            return 0;
        }
        audits.put(row.getId(), row.copy());
        return 1;
    }

    @Override
    public synchronized CityGameOperationsStoreFactRow selectStore(String merchantId) {
        CityGameOperationsStoreFactRow row = stores.get(merchantId);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized int insertStore(CityGameOperationsStoreFactRow row) {
        if (row.getMerchantId() == null || stores.containsKey(row.getMerchantId())) {
            return 0;
        }
        stores.put(row.getMerchantId(), row.copy());
        return 1;
    }

    @Override
    public synchronized int updateStore(CityGameOperationsStoreFactRow row) {
        CityGameOperationsStoreFactRow stored = stores.get(row.getMerchantId());
        if (stored == null || stored.getVersion() == null || !stored.getVersion().equals(row.getExpectedVersion())) {
            return 0;
        }
        stores.put(row.getMerchantId(), row.copy());
        return 1;
    }

    public synchronized int caseCount() {
        return cases.size();
    }

    public synchronized int requestCount() {
        return requests.size();
    }

    public synchronized int auditCount() {
        return audits.size();
    }

    public synchronized int storeCount() {
        return stores.size();
    }
}
