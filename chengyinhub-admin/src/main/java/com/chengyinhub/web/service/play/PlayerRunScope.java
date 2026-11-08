package com.chengyinhub.web.service.play;

import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;

/**
 * 本局身份解析（P7/P8 共用）。只读、非加锁：{@code selectAccessibleById} 按 {@code member_id} 过滤，
 * 不带 {@code for update}。
 *
 * <p>为什么要 runId：同一主题重开的历史局各有自己的 {@code play_instance_id}
 * （默认局 {@code default}，重玩局 {@code replay:<sessionId>}），
 * {@code selectCurrentByOwner} 只给<b>最新</b>一局。只有按会话 id 取回历史局，
 * 界面才能在又开了一局之后重开同一局旧战报，而不是被最新局顶掉。
 *
 * <p>越权/错作用域一律拒绝，绝不回落成"当前局"或按主题历史兜底：那会把别的局的内容
 * 冒充成这一局。
 */
final class PlayerRunScope {

    static final String REASON_RUN_NOT_FOUND = "RUN_NOT_FOUND";
    static final String REASON_RUN_SCOPE_MISMATCH = "RUN_SCOPE_MISMATCH";

    private PlayerRunScope() {
    }

    /** 解析结果：session 可能为 null（该作用域还没有局）；reasonCode 非空表示拒绝。 */
    static final class Resolution {
        private final TopicRouteSession session;
        private final boolean explicit;
        private final String reasonCode;

        private Resolution(TopicRouteSession session, boolean explicit, String reasonCode) {
            this.session = session;
            this.explicit = explicit;
            this.reasonCode = reasonCode;
        }

        static Resolution of(TopicRouteSession session, boolean explicit) {
            return new Resolution(session, explicit, null);
        }

        static Resolution rejected(String reasonCode) {
            return new Resolution(null, true, reasonCode);
        }

        TopicRouteSession session() { return session; }

        /** 调用方是否点名了 runId（点名时不得静默改用当前局）。 */
        boolean explicit() { return explicit; }

        String reasonCode() { return reasonCode; }

        boolean ok() { return reasonCode == null; }

        /**
         * 本局能否把自己的进度行与别的局分开：需要真实 play_instance_id。
         * 会话存在但没有实例值时，只能报"不可归因"，不能拿主题历史行凑数。
         */
        boolean instanceBound() {
            return session != null && session.getPlayInstanceId() != null
                    && !session.getPlayInstanceId().isBlank();
        }
    }

    static Resolution resolve(TopicRouteSessionMapper mapper, Long memberId, Long activityId, Long topicId,
                              Long runId) {
        long scopeActivity = activityId == null || activityId <= 0L ? 0L : activityId;
        if (runId == null || runId <= 0L) {
            return Resolution.of(mapper.selectCurrentByOwner(scopeActivity, topicId, memberId), false);
        }
        TopicRouteSession chosen = mapper.selectAccessibleById(runId, memberId);
        if (chosen == null) {
            // 不存在或不属于本人都一样：不区分，避免拿 id 探测他人的局。
            return Resolution.rejected(REASON_RUN_NOT_FOUND);
        }
        long chosenActivity = chosen.getActivityId() == null ? 0L : chosen.getActivityId();
        if (chosen.getTopicId() == null || !chosen.getTopicId().equals(topicId) || chosenActivity != scopeActivity) {
            return Resolution.rejected(REASON_RUN_SCOPE_MISMATCH);
        }
        return Resolution.of(chosen, true);
    }

    /**
     * 主题级暂停行（{@code player_run_session}）只按 member+activity+topic 存，没有局身份，
     * 因此只有"该作用域最新一局"能认领它；历史局没有自己的耗时事实。
     */
    static boolean ownsPauseRow(TopicRouteSessionMapper mapper, Long memberId, Long activityId, Long topicId,
                                TopicRouteSession chosen) {
        if (chosen == null || chosen.getId() == null) return false;
        long scopeActivity = activityId == null || activityId <= 0L ? 0L : activityId;
        TopicRouteSession latest = mapper.selectCurrentByOwner(scopeActivity, topicId, memberId);
        return latest != null && chosen.getId().equals(latest.getId());
    }
}
