package com.chengyinhub.business.service.channel;

import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelAccount4Identity;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelCatalogEntry;
import com.chengyinhub.business.service.channel.ChannelGovernanceTypes.ChannelRulesCas;

/**
 * C08 独占：规则同意闸。加入只认目录快照捕获的已发布 P04 版本。pending 不能准入。
 */
public final class ChannelRulesAdmission {

    public final ChannelAccount4Identity identity;
    public final int consentedVersion;

    public ChannelRulesAdmission(ChannelAccount4Identity identity, int consentedVersion) {
        if (consentedVersion <= 0) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CAS_CONFLICT);
        }
        this.identity = identity;
        this.consentedVersion = consentedVersion;
    }

    public int admit(ChannelAccount4Identity identity, ChannelCatalogEntry catalog) {
        if (identity == null || !identity.equals(this.identity)) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.ACCOUNT4_MISMATCH);
        }
        ChannelRulesCas cas = catalog.rules;
        if (!cas.isCurrent()) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CAS_CONFLICT);
        }
        if (cas.pendingVersion != null && consentedVersion == cas.pendingVersion.intValue()) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CAS_CONFLICT);
        }
        if (consentedVersion != cas.publishedVersion) {
            throw new ChannelAuthorizationException(ChannelAuthorizationException.Code.RULES_CONSENT_REQUIRED);
        }
        return cas.publishedVersion;
    }
}
