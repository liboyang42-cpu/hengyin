package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.NpcVoiceEnrollment;

public interface NpcVoiceEnrollmentMapper
{
    NpcProfile lockProfile(@Param("npcId") Long npcId, @Param("scopeType") int scopeType,
                           @Param("scopeId") Long scopeId);
    NpcVoiceEnrollment find(@Param("npcId") Long npcId, @Param("sampleSha256") String sampleSha256);
    NpcVoiceEnrollment findUnresolved(@Param("npcId") Long npcId);
    NpcVoiceEnrollment findRequest(@Param("npcId") Long npcId, @Param("requestId") String requestId);
    NpcVoiceEnrollment findById(@Param("npcId") Long npcId, @Param("id") Long id);
    NpcVoiceEnrollment findLatest(@Param("npcId") Long npcId);
    java.util.List<NpcVoiceEnrollment> history(@Param("npcId") Long npcId, @Param("actorMemberId") Long actorMemberId);
    int insert(NpcVoiceEnrollment row);
    int claim(@Param("id") Long id);
    int complete(NpcVoiceEnrollment row);
}
