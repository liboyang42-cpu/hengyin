package com.chengyinhub.business.service.support;

import java.util.Collections;
import java.util.List;

/** W2 合作单快照落库后的只读阵容 seam；缺冻结快照必须 fail-closed。 */
public interface CoopOrderLineupSource {

    ReadResult readEffectiveLineup(Long topicId);

    final class ReadResult {
        private final List<LineupEntry> entries;
        private final List<String> repairReasons;

        public ReadResult(List<LineupEntry> entries, List<String> repairReasons) {
            this.entries = entries == null ? Collections.<LineupEntry>emptyList() : entries;
            this.repairReasons = repairReasons == null ? Collections.<String>emptyList() : repairReasons;
        }

        public List<LineupEntry> getEntries() { return entries; }
        public List<String> getRepairReasons() { return repairReasons; }
    }

    final class LineupEntry {
        private final String subjectType;
        private final Long subjectId;
        private final String role;
        private final String scopeType;
        private final Long scopeId;
        private final String contractRefType;
        private final Long contractRefId;
        private final boolean cashObligation;
        private final Long initiatorMemberId;

        public LineupEntry(String subjectType, Long subjectId, String role, String scopeType, Long scopeId,
                           String contractRefType, Long contractRefId, boolean cashObligation) {
            this(subjectType, subjectId, role, scopeType, scopeId, contractRefType, contractRefId,
                    cashObligation, null);
        }

        public LineupEntry(String subjectType, Long subjectId, String role, String scopeType, Long scopeId,
                           String contractRefType, Long contractRefId, boolean cashObligation,
                           Long initiatorMemberId) {
            this.subjectType = subjectType;
            this.subjectId = subjectId;
            this.role = role;
            this.scopeType = scopeType;
            this.scopeId = scopeId;
            this.contractRefType = contractRefType;
            this.contractRefId = contractRefId;
            this.cashObligation = cashObligation;
            this.initiatorMemberId = initiatorMemberId;
        }

        public String getSubjectType() { return subjectType; }
        public Long getSubjectId() { return subjectId; }
        public String getRole() { return role; }
        public String getScopeType() { return scopeType; }
        public Long getScopeId() { return scopeId; }
        public String getContractRefType() { return contractRefType; }
        public Long getContractRefId() { return contractRefId; }
        public boolean isCashObligation() { return cashObligation; }
        public Long getInitiatorMemberId() { return initiatorMemberId; }
    }
}
