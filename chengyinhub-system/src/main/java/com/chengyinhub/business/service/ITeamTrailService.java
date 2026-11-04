package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

/**
 * 第九轮 m2 第 2 项:同一场次(cms_activity)里队伍互相看到路线。
 * 只有同一场、已付费在场、自己开着分享的人能读写;作者可在主题配置里整体关掉(teamTrail=false)。
 * 位置只进 Redis,四位小数(约 11 米)存,场次结束后至多 1 小时删除,最后一次上报后至多 6 小时删除。
 */
public interface ITeamTrailService {

    /** 我在这一场能不能用、现在是否分享(没表过态时取默认值)。不可用时只回 available=false。 */
    Map<String, Object> state(Long memberId, Long activityId);

    /** 开局前本人表态。关掉立即删除自己已存的点。 */
    Map<String, Object> consent(Long memberId, Long activityId, boolean share);

    /**
     * 上报自己新走的点并取回同场其他队伍。finished=true 表示本人已结束:删除自己的点、不再回任何队伍。
     * points 每项 [lat, lng](gcj02)。
     */
    Map<String, Object> sync(Long memberId, Long activityId, List<List<Double>> points, Integer done, boolean finished);
}
