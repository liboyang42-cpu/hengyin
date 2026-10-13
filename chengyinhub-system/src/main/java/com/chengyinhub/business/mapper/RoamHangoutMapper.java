package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamHangout;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

/** 漫游·附近的局。成员数 / isMember 全部从 im_conversation_member 联查,本 mapper 不写成员。 */
public interface RoamHangoutMapper {

    int insert(RoamHangout hangout);

    /** 带 viewer 视角联查(人数 / isMember / 局主头像),viewerId 可为 0。 */
    RoamHangout selectById(@Param("id") Long id, @Param("viewerId") Long viewerId);

    RoamHangout selectByIdForUpdate(@Param("id") Long id);

    /** 只取状态(群访问校验高频路径,不带成员计数子查询);不存在/已删返回 null */
    Integer selectStatusById(@Param("id") Long id);

    /**
     * 包围盒粗筛 + haversine 精筛,一条 SQL 顺带出人数 / isMember,并排除 viewer 拉黑的局主(im_block)。
     * 只出 status=0。
     */
    List<RoamHangout> selectNearby(@Param("lat") double lat, @Param("lng") double lng,
                                   @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                   @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                   @Param("radius") double radius, @Param("viewerId") Long viewerId,
                                   @Param("limit") int limit);

    /** 冷启动探半径用:同口径只数个数。 */
    int countNearby(@Param("lat") double lat, @Param("lng") double lng,
                    @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                    @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                    @Param("radius") double radius, @Param("viewerId") Long viewerId);

    /** 同层只读叠加:已上架、未取消、有坐标的活动(商家活动 / 俱乐部场次挂的活动)。 */
    List<Map<String, Object>> selectNearbyActivities(@Param("lat") double lat, @Param("lng") double lng,
                                                     @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                                     @Param("minLng") double minLng, @Param("maxLng") double maxLng,
                                                     @Param("radius") double radius, @Param("limit") int limit,
                                                     @Param("viewerId") Long viewerId);

    int countActiveByMember(@Param("memberId") Long memberId);

    /** 注销账号时关掉该成员进行中的局。 */
    List<RoamHangout> selectOpenByMember(@Param("memberId") Long memberId);

    /** 群成员头像堆(最多 limit 个,局主排第一)。 */
    List<Map<String, Object>> selectMemberBriefs(@Param("conversationId") Long conversationId,
                                                 @Param("ownerId") Long ownerId, @Param("limit") int limit);

    int countActiveMembers(@Param("conversationId") Long conversationId);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    int updateConversationId(@Param("id") Long id, @Param("conversationId") Long conversationId);

    /** 过期候选:有时间的局 start_at+24h;常驻局按群最后一条消息(无消息按建局时间)+14 天。 */
    List<RoamHangout> selectExpired(@Param("now") Date now);
}
