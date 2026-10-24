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
    int insert(NpcVoiceEnrollment row);
    int claim(@Param("id") Long id);
    int complete(NpcVoiceEnrollment row);
}
