package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.MerchantAuthoringCopyMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Refuses generic deep copies that would lose W05 authoring provenance at a new CMS id.
 * This is not paid authorization. Same-id reuse does not lose the source binding.
 * Query errors propagate; this read alone does not serialize concurrent source adoption.
 */
@Component
public final class MerchantAuthoringCopyGuard {
    private final MerchantAuthoringCopyMapper mapper;

    @Autowired
    public MerchantAuthoringCopyGuard(MerchantAuthoringCopyMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "merchant authoring copy mapper");
    }

    public void requireUnboundForDeepCopy(Long templateId) {
        if (templateId == null || templateId <= 0L
                || !Integer.valueOf(0).equals(mapper.selectCopyBlockState(templateId))) {
            throw new ServiceException("商家创作来源模板不可通过通用入口另存副本");
        }
    }
}
