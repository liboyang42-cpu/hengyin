package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.PostGameOutcomeReportVO;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 完局维护与有效复报的只读报表服务。
 *
 * <p>主指标是「全历史首笔有效报名后 N 日内形成另一笔有效报名」的去重会员占比，分母只含观察窗已成熟的首报名会员；
 * 六类结果（正式完成／奖励发放／私人照片／公开分享／推荐点击／第二笔有效报名）分别计数、互不合并。
 *
 * <p>只读：本服务不落库、不写修订快照。需要对比历史请留存响应体（含 computedAt 与各来源水位）。
 */
public interface IPostGameOutcomeReportService {

    PostGameOutcomeReportVO generate(LocalDate cohortDate, int windowDays);

    /**
     * Metrics that cannot be attributed to a topic (they are platform-wide member facts in {@link #generate}) and are
     * therefore left out of a topic-scoped report instead of being shown with platform numbers.
     */
    List<String> TOPIC_SCOPE_OMITTED_METRICS = List.of("REWARD_ISSUANCE", "PRIVATE_PHOTO", "PUBLIC_SHARE",
            "RECOMMENDATION_CLICK", "CLIENT_OBSERVED_EVENT");

    /**
     * U08-04 creator scope: the same cohort rules (full-history first valid registration, maturity, refunds, seat and
     * same-order dedup) restricted to members whose first valid registration was on one of {@code topicIds}.
     * OFFICIAL_COMPLETION counts only completed route sessions on those topics, and SECOND_VALID_REGISTRATION keeps
     * the B8 definition (any other valid registration in the window). The metrics in
     * {@link #TOPIC_SCOPE_OMITTED_METRICS} and the roam revisit block are omitted.
     */
    PostGameOutcomeReportVO generateForTopics(LocalDate cohortDate, int windowDays, Collection<Long> topicIds);
}
