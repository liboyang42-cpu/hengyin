package com.chengyinhub.business.service;

import java.util.Map;

/** 俱乐部对自己主题的设置(Figma J4)。只管俱乐部这一层的三个开关,不碰票与内容。 */
public interface IClubTopicSettingService {
    /** 读:需要 club:activity:read。 */
    Map<String, Object> detail(Long principalMemberId, Long clubId, Long topicId);

    /** 写:需要 club:activity:manage。 */
    /**
     * 结束主题:先停售,再把这个主题下所有在办场次逐场取消并全额退款。
     * 稿 J5 164:118 承诺的就是这件事 —— 「停止售卖并把未核销的票原价退回」。
     */
    Map<String, Object> endTopic(Long principalMemberId, String username, Long clubId, Long topicId, String reason);
    Map<String,Object> endTopic(Long principalMemberId,String username,Long clubId,Long topicId,String reason,Long cancellationEpoch);

    Map<String, Object> save(Long principalMemberId, Long clubId, Long topicId,
                             Boolean coopOpen, Boolean pinned, Boolean memberOnly);
}
