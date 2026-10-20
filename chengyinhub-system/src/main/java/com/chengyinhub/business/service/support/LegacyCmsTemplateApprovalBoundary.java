package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Existing generic approvals cannot confer W18 rights. No license, paid-state, topic or AuditTask locks are acquired here. */
@Service
public class LegacyCmsTemplateApprovalBoundary {
    private final LegacyCmsTemplateReadMapper provenance;
    public LegacyCmsTemplateApprovalBoundary(LegacyCmsTemplateReadMapper provenance){this.provenance=Objects.requireNonNull(provenance,"canonical provenance reader");}
    /** Caller already owns canonical template locks in its existing order. Missing schema/provider is never legacy. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void requireGenericApprovalAllowed(CmsMemberTemplate lockedTemplate){
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("LEGACY_TEMPLATE_APPROVAL_TRANSACTION_REQUIRED");
        if(lockedTemplate==null || lockedTemplate.getId()==null || lockedTemplate.getId()<=0)throw new ServiceException("模板来源无法验证，不能通过旧审核入口",409);
        // Always query both signals, including when a legacy DTO omitted or normalized the marker.
        if(!Integer.valueOf(0).equals(provenance.selectCanonicalStateForUpdate(lockedTemplate.getId())))throw new ServiceException("此模板含受保护来源，不能通过旧审核入口",409);
    }
}
