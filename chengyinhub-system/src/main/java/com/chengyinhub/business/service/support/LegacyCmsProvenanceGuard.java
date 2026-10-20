package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.LegacyCmsProvenanceMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Read-only legacy-path recognition, not ownership or license authorization.
 * Only an existing row with neither provenance signal is legacy. Schema/query failures propagate.
 * This check does not serialize a later write; mutating callers need their own transaction locks.
 */
@Component
public final class LegacyCmsProvenanceGuard {
    private final LegacyCmsProvenanceMapper mapper;

    @Autowired
    public LegacyCmsProvenanceGuard(LegacyCmsProvenanceMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "provenance mapper");
    }

    public void requireLegacy(Long templateId) {
        if (templateId == null || templateId <= 0L) {
            throw new ServiceException("模板不可通过旧入口访问");
        }
        Integer state = mapper.selectLegacyBlockState(templateId);
        if (!Integer.valueOf(0).equals(state)) {
            throw new ServiceException("模板不可通过旧入口访问");
        }
    }
}
