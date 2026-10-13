package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayerNodeProgress;

public interface IShopDayProofService {
    /**
     * 真实到店:写本人该节点的到店行(checkin_time)。
     * R16-ARRIVE:同主题同章节同地点的兄弟节点一并补写到店行(只补到店事实,不给完成/照片);
     * 已存在的行(含并发写入)不覆盖,重复调用幂等。
     */
    PlayerNodeProgress markArrived(Long memberId, Long activityId, CmsTopicNode node);
    PlayerNodeProgress submitPhoto(Long memberId, Long activityId, Long nodeId, String picUrl);

    /** 是否已扫店内码进店(B5 收窄:游戏题面与作答都以进店为前置)。 */
    boolean hasArrived(Long memberId, Long activityId, Long nodeId);

    /** 游戏完成:进店行 status 0→1 条件更新;返回 true = 本次为首次完成(该发奖)。 */
    boolean markGameDone(Long memberId, Long activityId, Long topicId, Long nodeId);
}
