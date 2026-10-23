package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;

/**
 * 城市定向队伍看板(Doc2 F1d):大厅 / 详情进度矩阵 / 排名。
 * 一切按当前在岗成员现算 —— 成员退出后阈值与排名自动正确,不存快照。
 */
public interface IOrientTeamBoardService {

    /** 公开可加入的队伍(不含邀请码)。ownerType:1=主题 2=场次。 */
    List<Map<String, Object>> lobby(Long viewerId, Integer ownerType, Long ownerId);

    /** 队伍详情 + 进度矩阵;仅在岗成员可看。 */
    Map<String, Object> detail(Long viewerId, Long teamId);

    /** 同一主题/场次下的队伍排名;只暴露队名与进度,不暴露成员。 */
    List<Map<String, Object>> ranking(Long viewerId, Integer ownerType, Long ownerId);
}
