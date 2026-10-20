package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.LegacyCmsTemplateReadMapper;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads legacy bodies either in one snapshot statement or under a canonical row lock.
 * Neither method provides owner/status authorization. For findLegacy, invoke through the Spring
 * transaction proxy with both mappers on the same datasource; findLegacySnapshot acquires no lock.
 * Current managed writers create new rows with marker and sidecar in one transaction; they do not
 * adopt legacy rows. Any future existing-row provenance/body mutation must share this lock;
 * this reader neither implements nor authorizes adoption.
 * Callers still enforce ownership, visibility and status. No controller is wired by this class.
 */
@Service
public class LegacyCmsTemplateBodyReader {
    private final LegacyCmsTemplateReadMapper provenance;
    private final CmsMemberTemplateMapper templates;

    @Autowired
    public LegacyCmsTemplateBodyReader(LegacyCmsTemplateReadMapper provenance,
                                       CmsMemberTemplateMapper templates) {
        this.provenance = Objects.requireNonNull(provenance, "canonical read mapper");
        this.templates = Objects.requireNonNull(templates, "template body mapper");
    }

    /** Read-only-compatible body and provenance snapshot. No new transaction or lock is required.
     * A missing/managed row has the same null appearance; database failures still propagate. */
    public CmsMemberTemplate findLegacySnapshot(Long id) {
        if (id == null || id <= 0L) return null;
        return provenance.selectLegacySnapshotById(id);
    }

    /** A normal read/write transaction is required for FOR UPDATE, despite no row mutations. */
    @Transactional
    public CmsMemberTemplate findLegacy(Long id) {
        if (id == null || id <= 0L) return null;
        Integer state = provenance.selectCanonicalStateForUpdate(id);
        if (!Integer.valueOf(0).equals(state)) return null;
        return templates.selectCmsMemberTemplateById(id);
    }
}
