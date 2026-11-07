package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;

import java.util.List;

/** 俱乐部带队场次命令服务。 */
public interface IClubSessionService {
    /** 主理人可主动开团的生效合作主题。 */
    List<CmsTopic> selectOpenableTopics(Long clubId, Long memberId);

    /**
     * 主理人在生效俱乐部合作下主动开团。
     *
     * @return 新场次 id
     * @throws IllegalArgumentException 参数、主理人归属或合作单门禁不满足
     */
    Long open(ClubSessionOpenRequest request, Long memberId, String username);

    /**
     * C-21:场次日期落在主题自身日期(cms_topic.start_date/end_date)之外时给一句提醒;都在窗口内或主题没写日期时返回 null。
     * 只提醒不拒绝:跨窗口复跑、已开系列等既有流程不受影响。
     */
    default String themeWindowWarning(Long topicId, java.util.List<java.time.LocalDate> dates) { return null; }
}
