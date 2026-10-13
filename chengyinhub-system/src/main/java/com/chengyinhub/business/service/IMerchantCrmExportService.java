package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.MerchantCrmExportRequest;
import com.chengyinhub.business.domain.vo.MerchantCrmExportFile;
import com.chengyinhub.business.domain.vo.MerchantCrmExportTaskVO;

public interface IMerchantCrmExportService {
    MerchantCrmExportTaskVO create(Long principalMemberId, MerchantCrmExportRequest request);
    MerchantCrmExportTaskVO status(Long principalMemberId, Long taskId);
    MerchantCrmExportFile authorizeDownload(Long principalMemberId, Long taskId, String rawToken);
    void completeDownload(MerchantCrmExportFile authorizedFile);
    boolean runNextPending();
    int expireAndClean();
}
