package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubEventCampaign;
import com.chengyinhub.business.domain.dto.ClubEventCampaignRequest;

import java.util.Map;

public interface IClubEventNotificationService {
    Map<String, Object> preview(Long actorMemberId, ClubEventCampaignRequest request);

    /**
     * 只读回一个通知任务的当前投递回执(总数 / 已送达 / 失败 / 状态)。
     *
     * <p>群发是后台投递的:send 返回时常常还是 PROCESSING。页面若只留着那一次响应,
     * 数字就永远停在「4 / 0 / 0」(CU-C-135)。鉴权与 {@link #retryFailed} 同一道闸 ——
     * 能对这个俱乐部发通知的人才看得到任务回执,不能凭一个 campaignId 读别人的。
     */
    ClubEventCampaign campaign(Long actorMemberId, Long campaignId);

    /**
     * 各受众分组各有多少人（稿 R4 290:526：每一行右侧都带人数）。
     * 一次把四个分组都算出来，页面进来就能显示，不必为了看人数先选一个受众再 preview。
     * activityId 为空时，三个「本场」分组回 null 而不是 0 —— 「还没选活动」不等于「零人」。
     */
    Map<String, Object> audienceCounts(Long actorMemberId, Long clubId, Long activityId);
    ClubEventCampaign send(Long actorMemberId, ClubEventCampaignRequest request);
    ClubEventCampaign retryFailed(Long actorMemberId, Long campaignId);
    int retryDue();
}
