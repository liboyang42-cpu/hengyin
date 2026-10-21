package com.chengyinhub.business.citygame.operations.mapper;

import com.chengyinhub.business.citygame.operations.CityGameOperationsAuditRow;
import com.chengyinhub.business.citygame.operations.CityGameOperationsCaseRow;
import com.chengyinhub.business.citygame.operations.CityGameOperationsRequestRow;
import com.chengyinhub.business.citygame.operations.CityGameOperationsStoreFactRow;

import java.util.List;

public interface CityGameOperationsMapper {
    CityGameOperationsCaseRow selectCaseById(long id);

    CityGameOperationsCaseRow selectCaseByActionId(String actionId);

    int insertCase(CityGameOperationsCaseRow row);

    int updateCase(CityGameOperationsCaseRow row);

    CityGameOperationsRequestRow selectRequest(String requestId);

    int insertRequest(CityGameOperationsRequestRow row);

    /** 只把 UNKNOWN 推进到已知结果。不改正文，不删行。 */
    int updateRequestState(CityGameOperationsRequestRow row);

    List<CityGameOperationsAuditRow> selectAuditsByCaseId(long caseId);

    List<CityGameOperationsAuditRow> selectAuditsByRequestId(String requestId);

    int insertAudit(CityGameOperationsAuditRow row);

    CityGameOperationsStoreFactRow selectStore(String merchantId);

    int insertStore(CityGameOperationsStoreFactRow row);

    int updateStore(CityGameOperationsStoreFactRow row);
}
