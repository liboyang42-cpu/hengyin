package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Converts a node's merchant owner member ID to the scope-2 merchant row ID.
 * This is source identity resolution, not a role grant or a release-candidate dependency.
 */
@Component
public class MerchantNpcIdentityDirectory {
    private final MmsMerchantMapper merchantMapper;

    public MerchantNpcIdentityDirectory(MmsMerchantMapper merchantMapper) {
        this.merchantMapper = merchantMapper;
    }

    /**
     * No arbitrary first row, numeric ID alias, or disabled-row prefilter is allowed.
     * Availability follows ApiMerchantController.isPubliclyVisible: approved, enabled,
     * undeleted, with unknown values closed. businessStatus is display-only.
     * Invalid/missing evidence contributes no merchant fallback. Storage errors propagate;
     * the calling endpoint retains its existing error boundary.
     */
    public Long availableMerchantIdForMember(Long memberId) {
        if (memberId == null || memberId <= 0) return null;
        MmsMerchant probe = new MmsMerchant();
        probe.setMemberId(memberId);
        List<MmsMerchant> matches = merchantMapper.selectMmsMerchantList(probe);
        if (matches == null || matches.size() != 1) return null;
        MmsMerchant merchant = matches.get(0);
        if (!available(merchant) || !memberId.equals(merchant.getMemberId())) return null;
        return merchant.getId();
    }

    /** Homepage callers already have a row ID; never reinterpret it as an owner member ID. */
    public Long availableMerchantId(Long merchantId) {
        if (merchantId == null || merchantId <= 0) return null;
        MmsMerchant merchant = merchantMapper.selectMmsMerchantById(merchantId);
        return available(merchant) && merchantId.equals(merchant.getId()) ? merchantId : null;
    }

    private boolean available(MmsMerchant merchant) {
        return merchant != null && merchant.getId() != null && merchant.getId() > 0
                && Long.valueOf(1).equals(merchant.getStatus())
                && Long.valueOf(1).equals(merchant.getAccountStatus())
                && Integer.valueOf(0).equals(merchant.getDelFlag());
    }
}
