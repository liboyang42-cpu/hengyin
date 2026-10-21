package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;

/** SQL reader for reviewed eligibility rows. Missing rows refuse. Not a Spring bean and not a profile writer. */
public final class MyBatisRendezvousEligibility implements RendezvousEligibilityFacts {
    private final RendezvousEligibilityMapper mapper;
    public MyBatisRendezvousEligibility(RendezvousEligibilityMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }
    public boolean connected() { return true; }
    public String currentVersion() { return mapper.currentVersion(); }
    public boolean admits(long actor, String clientVersion, long now) {
        String current = mapper.currentVersion();
        if (current == null || clientVersion == null || !current.equals(clientVersion)) return false;
        RendezvousEligibilityMapper.Rule rule = mapper.rule(current);
        if (rule == null || !"OPEN".equals(rule.decision)) return false;
        if (rule.minAge == null || rule.minAge < 0) return false;
        if (rule.regionCode == null || rule.regionCode.trim().isEmpty()) return false;
        if (rule.windowStart == null || rule.windowEnd == null || rule.windowEnd <= rule.windowStart) return false;
        if (now < rule.windowStart || now >= rule.windowEnd) return false;
        RendezvousEligibilityMapper.Fact fact = mapper.fact(actor);
        if (fact == null || fact.ageYears == null || fact.regionCode == null) return false;
        if (!current.equals(fact.ruleVersion)) return false;
        if (fact.expiresAt <= now) return false;
        if (fact.ageYears < rule.minAge) return false;
        if (rule.maxAge != null && fact.ageYears > rule.maxAge) return false;
        return rule.regionCode.equals(fact.regionCode);
    }
}
