package com.chengyinhub.business.domain.vo;

/**
 * 作者端发布状态的窄投影:草稿 / 审核 / 公开 / 售卖 / 可玩 分开表达。
 *
 * <p>为什么不能只有一个「状态」:专业主题先公开后审 —— {@code status=1,userStatus=1,checkStatus=0}
 * 同时意味着「玩家看得见」和「还没过审」。把它压成一个字段,作者就会以为已过审、运营就会以为已验收。
 * 所以这里是五段事实,每段只回答一个问题,互不替代:
 * <ul>
 *   <li>{@code draft} —— 还是草稿吗,还能不能改;</li>
 *   <li>{@code review} —— 审核走到哪一步,过没过(过审的唯一判据是 checkStatus=1 / 人工通过);</li>
 *   <li>{@code visibility} —— 玩家现在看不看得见,看不见的原因;</li>
 *   <li>{@code sale} —— 现在能不能卖,不能卖的下一步和售卖闸原话;</li>
 *   <li>{@code playable} —— 已发布的运行快照在不在,开局会不会被判「路线尚未发布」。</li>
 * </ul>
 *
 * <p>这是只读投影,不是第二套 readiness 判据:所有取值都来自既有真源
 * (TopicLifecycleState / cms_topic.status·userStatus·checkStatus / 审核单 / SaleAvailabilityPolicy),
 * 不新造状态机,也不替代发布前的规则校验(那是 TopicPublishCheckService + 发布闸)。
 */
public class TopicPublicationStatusVO {

    /**
     * 审核段的状态值:草稿 / 待审(有未结审核单)/ 升级人工 / 已通过 / 已驳回 /
     * 后审(已公开但当前没有未结审核单 —— 不能假装有一张单在跑)。
     */
    public static final String REVIEW_DRAFT = "DRAFT";
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_ESCALATED = "ESCALATED";
    public static final String REVIEW_PASSED = "PASSED";
    public static final String REVIEW_REJECTED = "REJECTED";
    public static final String REVIEW_AWAITING = "AWAITING";

    private final Draft draft;
    private final Review review;
    private final Visibility visibility;
    private final Sale sale;
    private final Playable playable;

    public TopicPublicationStatusVO(Draft draft, Review review, Visibility visibility,
                                    Sale sale, Playable playable) {
        this.draft = draft;
        this.review = review;
        this.visibility = visibility;
        this.sale = sale;
        this.playable = playable;
    }

    public Draft getDraft() { return draft; }
    public Review getReview() { return review; }
    public Visibility getVisibility() { return visibility; }
    public Sale getSale() { return sale; }
    public Playable getPlayable() { return playable; }

    /** 草稿段:是否是草稿、现在还能不能编辑、不能编辑的原因。 */
    public static final class Draft {
        private final boolean draft;
        private final boolean editable;
        private final String editBlockedReason;

        public Draft(boolean draft, boolean editable, String editBlockedReason) {
            this.draft = draft;
            this.editable = editable;
            this.editBlockedReason = editBlockedReason;
        }

        public boolean isDraft() { return draft; }
        public boolean isEditable() { return editable; }
        public String getEditBlockedReason() { return editBlockedReason; }
    }

    /** 审核段:过审(passed)只认 checkStatus=1;pending 是「提交了但还没结论」。 */
    public static final class Review {
        private final String state;
        private final String label;
        private final boolean passed;
        private final boolean pending;
        private final Long auditTaskId;
        private final String detail;

        public Review(String state, String label, boolean passed, boolean pending,
                      Long auditTaskId, String detail) {
            this.state = state;
            this.label = label;
            this.passed = passed;
            this.pending = pending;
            this.auditTaskId = auditTaskId;
            this.detail = detail;
        }

        public String getState() { return state; }
        public String getLabel() { return label; }
        public boolean isPassed() { return passed; }
        public boolean isPending() { return pending; }
        public Long getAuditTaskId() { return auditTaskId; }
        public String getDetail() { return detail; }
    }

    /** 公开段:玩家现在能不能看到。 */
    public static final class Visibility {
        private final boolean visible;
        private final String reason;

        public Visibility(boolean visible, String reason) {
            this.visible = visible;
            this.reason = reason;
        }

        public boolean isVisible() { return visible; }
        public String getReason() { return reason; }
    }

    /**
     * 售卖段。
     *
     * <p>不变量:{@code onSale == (lifecycleSaleOpen && blockReason == null)} ——
     * 「在售」与「不可售原因」不可能同时成立。{@code blockReason} 是售卖闸原话
     * ({@code SaleAvailabilityPolicy#sellableBlockReason},内部调原 assert),文案与下单时同一句。
     */
    public static final class Sale {
        private final boolean onSale;
        private final boolean lifecycleSaleOpen;
        private final boolean awaitingReview;
        private final String nextStep;
        private final String blockReason;

        public Sale(boolean onSale, boolean lifecycleSaleOpen, boolean awaitingReview,
                    String nextStep, String blockReason) {
            this.onSale = onSale;
            this.lifecycleSaleOpen = lifecycleSaleOpen;
            this.awaitingReview = awaitingReview;
            this.nextStep = nextStep;
            this.blockReason = blockReason;
        }

        /** 现在真的能卖:生命周期放行 且 售卖闸放行。 */
        public boolean isOnSale() { return onSale; }
        /** 生命周期是否处于直接售卖态(SELLING);售止已过时它仍可为 true。 */
        public boolean isLifecycleSaleOpen() { return lifecycleSaleOpen; }
        public boolean isAwaitingReview() { return awaitingReview; }
        public String getNextStep() { return nextStep; }
        public String getBlockReason() { return blockReason; }
    }

    /**
     * 可玩段:<b>只答内容侧</b>「已发布快照是否可用于开局」。
     *
     * <p>与另外两件事明确分开,不互相顶替:
     * <ul>
     *   <li>作者内容是否可发布 —— 看 {@code GET /api/topic/publish-check} 的规则清单;</li>
     *   <li>玩家此刻能否开局 —— 报名/权益由 {@code CurrentPlayRegistration.require} 判、
     *       可玩时段由 {@code TopicPlaySetupService.requireOpen} 判,都在开局时由运行期完成,
     *       本字段不预判、不查报名表;</li>
     *   <li>玩家能否看见 —— 看 {@link Visibility};已下架但已发布的主题可以 visible=false 而 playable=true。</li>
     * </ul>
     *
     * <p>{@code gate} 常量把这条边界写进协议,消费方无法把它当成完整开局许可。
     */
    public static final class Playable {

        public static final String GATE_PUBLISHED_SNAPSHOT_ONLY = "PUBLISHED_SNAPSHOT_ONLY";

        private final boolean playable;
        private final String reason;
        private final Long publishedConfigVersion;
        private final boolean beta;

        public Playable(boolean playable, String reason, Long publishedConfigVersion, boolean beta) {
            this.playable = playable;
            this.reason = reason;
            this.publishedConfigVersion = publishedConfigVersion;
            this.beta = beta;
        }

        public boolean isPlayable() { return playable; }
        /** 不可开局的内容侧原因;与运行期 {@code ensureBranchSession} / {@code ensureJourneySession} 同一句。 */
        public String getReason() { return reason; }
        public Long getPublishedConfigVersion() { return publishedConfigVersion; }
        public boolean isBeta() { return beta; }
        /** 固定为 {@link #GATE_PUBLISHED_SNAPSHOT_ONLY}:本段只表示内容侧快照就绪。 */
        public String getGate() { return GATE_PUBLISHED_SNAPSHOT_ONLY; }
    }
}
