package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.AgreementDocument;
import java.util.Map;

public interface IAgreementDocumentService {
    AgreementDocument getActive(String docType);

    Map<String, String> activeVersions();
}
