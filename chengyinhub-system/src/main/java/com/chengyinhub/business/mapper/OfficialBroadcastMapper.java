package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.OfficialBroadcast;

/**
 * 官方通知/广播 Mapper
 *
 * @author cy
 * @date 2026-07-08
 */
public interface OfficialBroadcastMapper
{
    java.util.Map<String,Object> lockDispatch(@Param("id") Long id);
    int freezeAudience(@Param("id") Long id);
    OfficialBroadcast findRequest(@Param("owner") String owner,@Param("key") String key);
    int reserveCreation(@Param("owner") String owner,@Param("key") String key);
    java.util.Map<String,Object> readCreation(@Param("owner") String owner,@Param("key") String key);
    java.util.Map<String,Object> lockCreation(@Param("owner") String owner,@Param("key") String key);
    int finishCreation(@Param("owner") String owner,@Param("key") String key,@Param("state") String state,@Param("broadcast") Long broadcast);
    int insertOfficialBroadcast(OfficialBroadcast officialBroadcast);

    int updateOfficialBroadcast(OfficialBroadcast officialBroadcast);

    OfficialBroadcast selectOfficialBroadcastById(Long id);

    /** 发布者自己发过的通知(倒序)。 */
    List<OfficialBroadcast> selectBySender(@Param("senderAccount") Long senderAccount);

    /** 一场活动的通知真源，供受控活动 360 回读。 */
    List<OfficialBroadcast> selectByEvent(@Param("eventId") Long eventId);

    /** Quartz 定时发送:status=1 待发 且 scheduled_time <= now。 */
    List<OfficialBroadcast> selectDueScheduled();
}
