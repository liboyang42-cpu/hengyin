package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayTeam;
import com.chengyinhub.business.domain.vo.PlayTeamNearbyVO;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface IPlayTeamService {
    default PlayTeam create(Long memberId, Integer ownerType, Long ownerId, String title, Integer maxMembers) {
        return create(memberId, ownerType, ownerId, title, maxMembers, null);
    }
    /** joinMode:1=邀请制 2=公开申请制(默认,出现在地图附近队伍)。 */
    PlayTeam create(Long memberId, Integer ownerType, Long ownerId, String title, Integer maxMembers, Integer joinMode);
    /** 队长切换组队方式:1=邀请制(退出公开地图) 2=公开申请制。返回生效后的模式。 */
    int updateJoinMode(Long teamId, Long leaderMemberId, Integer joinMode);
    Map<String, Object> info(Long teamId, String inviteCode, Long viewerMemberId);
    PlayTeam join(String inviteCode, Long memberId);
    /** 公开申请;返回申请失效时间。 */
    Date apply(Long teamId, Long memberId, String message);
    void withdraw(Long teamId, Long memberId);
    /** 举报公开队伍,进通用审核工作台(TEAM);返回审核任务 id。 */
    Long report(Long teamId, Long memberId, String reason);
    void handle(Long teamId, Long leaderMemberId, Long targetMemberId, boolean approved);
    /** 队长视角的待审申请(已过期的不返回)。 */
    List<Map<String, Object>> applications(Long teamId, Long leaderMemberId);
    /** 我的申请中 / 被队长拒绝的队伍。 */
    List<Map<String, Object>> myApplications(Long memberId);
    void quit(Long teamId, Long memberId);
    void kick(Long teamId, Long leaderMemberId, Long targetMemberId);
    void disband(Long teamId, Long leaderMemberId);
    List<PlayTeam> my(Long memberId);
    /** 地图附近可申请的公开队伍;radius 米,<=0 取默认。 */
    List<PlayTeamNearbyVO> nearby(Long viewerMemberId, double lat, double lng, int radius);
    List<Long> selectExpiredTeamIds(Date now, int limit);
    int endExpiredTeam(Long teamId, Date now);
    int expireStaleApplications(Date now);

    /** 带机器可读 errorCode 的业务拒绝;小程序按 errorCode 分支,不解析文案。 */
    class TeamRejection extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String errorCode;

        public TeamRejection(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() { return errorCode; }
    }
}
