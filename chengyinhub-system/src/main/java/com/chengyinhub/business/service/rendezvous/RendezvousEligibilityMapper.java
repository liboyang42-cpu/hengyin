package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;

/** Read-only reviewed eligibility rows. Not an authorization API and not a scanned mapper bean.
 * This mapper does not read member profile, birthday, address, or identity ciphertext. */
public interface RendezvousEligibilityMapper {
    String currentVersion();
    Rule rule(@Param("versionId") String versionId);
    Fact fact(@Param("actor") long actor);

    final class Rule {
        public String decision, regionCode;
        public Integer minAge, maxAge;
        public Long windowStart, windowEnd;
    }
    final class Fact {
        public String ruleVersion, regionCode;
        public Integer ageYears;
        public long reviewedAt, expiresAt;
    }
}
