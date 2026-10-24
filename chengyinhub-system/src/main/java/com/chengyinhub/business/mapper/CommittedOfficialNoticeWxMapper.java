package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CommittedOfficialNoticeWx;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** Only fixed OFFICIAL_BROADCAST notices; the local IM receipt remains with its existing owner. */
public interface CommittedOfficialNoticeWxMapper {
    int insert(CommittedOfficialNoticeWx intent);
    CommittedOfficialNoticeWx selectByKeyForUpdate(@Param("eventKey") String eventKey);
    CommittedOfficialNoticeWx selectByIdForUpdate(@Param("id") Long id);
    int claimUnknown(@Param("id") Long id, @Param("ownerToken") String ownerToken);
    int accepted(@Param("id") Long id, @Param("ownerToken") String ownerToken);
    Long maxPendingId();
    List<Long> selectPendingPage(@Param("afterId") Long afterId, @Param("maxId") Long maxId);
}
