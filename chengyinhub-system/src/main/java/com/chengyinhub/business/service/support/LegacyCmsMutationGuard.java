package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Collection;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Canonical-row mutation guard, not owner/license authorization. Call via the Spring proxy and
 * use the same datasource/transaction for the guarded mutation. Locks remain with the caller.
 * Batch order is ascending canonical ID only: callers must preserve their existing outer
 * topic/template/projection/audit lock order. This class does not authorize reordering it.
 */
@Component
public class LegacyCmsMutationGuard {
    private final LegacyCmsTemplateReadMapper mapper;

    @Autowired
    public LegacyCmsMutationGuard(LegacyCmsTemplateReadMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "canonical locking mapper");
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void requireLegacyForMutation(Long templateId) {
        requireWriteTransaction();
        requireId(templateId);
        requireLockedLegacy(templateId);
    }

    /** Validate the entire ID set before taking any locks; an empty set still requires a transaction. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void requireLegacyForMutations(Collection<Long> templateIds) {
        requireWriteTransaction();
        if (templateIds == null) throw denied();
        SortedSet<Long> ordered = new TreeSet<>();
        for (Long id : templateIds) {
            requireId(id);
            ordered.add(id);
        }
        for (Long id : ordered) requireLockedLegacy(id);
    }

    /** Approval hook: caller has already locked canonical rows in its established outer order. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void requireGenericApprovalAllowed(CmsMemberTemplate lockedTemplate, String entry) {
        requireWriteTransaction();
        requireApprovalInput(lockedTemplate, entry);
        // A null bean marker cannot replace the authoritative sidecar/marker check.
        requireLockedLegacy(lockedTemplate.getId());
    }

    /** Preflight every locked template before ANY bundled approval is written. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void requireGenericApprovalsAllowed(Collection<CmsMemberTemplate> lockedTemplates, String entry) {
        requireWriteTransaction();
        if (lockedTemplates == null) throw denied();
        SortedSet<Long> ordered = new TreeSet<>();
        for (CmsMemberTemplate template : lockedTemplates) {
            requireApprovalInput(template, entry);
            ordered.add(template.getId());
        }
        for (Long id : ordered) requireLockedLegacy(id);
    }

    private static void requireApprovalInput(CmsMemberTemplate template, String entry) {
        if (template == null || entry == null || entry.trim().isEmpty()) throw denied();
        requireId(template.getId());
        if (template.getW18ProvenanceKind() != null) throw denied();
    }

    private void requireLockedLegacy(Long id) {
        if (!Integer.valueOf(0).equals(mapper.selectCanonicalStateForUpdate(id))) throw denied();
    }

    private static void requireId(Long id) {
        if (id == null || id <= 0L) throw denied();
    }

    private static void requireWriteTransaction() {
        // MANDATORY joins the existing transaction; readOnly=false does not upgrade a read-only one.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new ServiceException("模板修改需要写事务");
        }
    }

    private static ServiceException denied() {
        return new ServiceException("模板不可通过旧入口修改");
    }
}
