package com.chengyinhub.business.service.ai.npc;

import org.springframework.stereotype.Component;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.NpcProfileMapper;

/** Real canonical-ID adapter. Never reads profile knowledge/persona or merchant business facts. */
@Component
public class MerchantNpcDirectory
{
    private final MmsMerchantMapper merchants;
    private final NpcProfileMapper profiles;

    public MerchantNpcDirectory(MmsMerchantMapper merchants, NpcProfileMapper profiles)
    {
        this.merchants = merchants; this.profiles = profiles;
    }

    public boolean matches(long merchantId, long ownerMemberId, long profileId)
    {
        MmsMerchant merchant = merchants.selectNpcScopeOwnerById(merchantId);
        if (merchant == null || !Long.valueOf(merchantId).equals(merchant.getId())
                || !Long.valueOf(ownerMemberId).equals(merchant.getMemberId())
                || !Long.valueOf(1).equals(merchant.getStatus())
                || !Long.valueOf(1).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) return false;
        NpcProfile profile = profiles.selectMerchantNpcScopeIdentity(profileId);
        return profile != null && Long.valueOf(profileId).equals(profile.getId())
                && Integer.valueOf(2).equals(profile.getScopeType())
                && Long.valueOf(merchantId).equals(profile.getScopeId())
                && Integer.valueOf(1).equals(profile.getEnabled())
                && Integer.valueOf(1).equals(profile.getAuditStatus()) && "0".equals(profile.getDelFlag());
    }
}
