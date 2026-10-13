package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AgreementDocument;
import com.chengyinhub.business.mapper.AgreementDocumentMapper;
import com.chengyinhub.business.service.IAgreementDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgreementDocumentServiceImpl implements IAgreementDocumentService {
    @Autowired
    private AgreementDocumentMapper agreementDocumentMapper;

    @Override
    public AgreementDocument getActive(String docType) {
        return agreementDocumentMapper.selectActiveByType(docType);
    }

    @Override
    public Map<String, String> activeVersions() {
        List<AgreementDocument> documents = agreementDocumentMapper.selectActiveDocuments();
        Map<String, String> versions = new LinkedHashMap<>();
        if (documents == null) {
            return versions;
        }
        for (AgreementDocument document : documents) {
            versions.put(document.getDocType(), document.getVersion());
        }
        return versions;
    }
}
