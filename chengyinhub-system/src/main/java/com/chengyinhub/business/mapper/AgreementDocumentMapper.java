package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AgreementDocument;
import java.util.List;

public interface AgreementDocumentMapper {
    AgreementDocument selectActiveByType(String docType);

    List<AgreementDocument> selectActiveDocuments();
}
