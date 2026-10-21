package com.chengyinhub.business.service.rendezvous;

import org.apache.ibatis.annotations.Param;

/** Read-only evidence facts. Confirming a challenge does not insert a row here. */
public interface RendezvousMeetingEvidenceMapper {
    RendezvousMeetingEvidence.Fact find(@Param("matchId") String matchId);
}
