package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 批5 · 探店日「完局面」只读视图:图鉴(F4)+ 通关奖励到账明细 + 完局后的 J/R90 自愿入口。
 *
 * <p><b>为什么要有它</b>:这三样的写侧都齐了、读侧一个都没有 ——
 * <ul>
 *   <li>F4:{@code member_collectible.coll_type = 5} 只写不读,{@code selectListByMember} 全仓
 *       三个调用点分别传 3/1/2,没有一处传 5 ⇒ 核销一章落一条凭证,玩家永远看不到;</li>
 *   <li>通关奖励:2-4 把勋章/积分/券搬到核销侧并真发了,但玩家端读不到任何一项 ⇒
 *       「核销完 4 店零勋章零积分」的观感依旧,而账其实是对的;</li>
 *   <li>J(自愿关注/入群)的分子来源是玩家的一个**动作**,完局后没有入口 ⇒ 分子结构性恒 0
 *       —— 这与「有埋点没数据」不同,是**没有产生数据的动作**。</li>
 * </ul>
 *
 * <p><b>fail-closed 口径(每条都有负控)</b>:
 * <ul>
 *   <li>没有积分流水 ⇒ {@link Awards#points} 为 {@code null},<b>不写 0</b>。
 *       0 表示「发了 0 分」,null 表示「没有这条事实」,两者对玩家和对账都不是一回事;</li>
 *   <li>没有下期 ⇒ {@link Revisit#nextEdition} 为 {@code null},<b>不写 0/0%</b>
 *       (与漏斗 R90「未满窗报观察中、不得记 0」同向);</li>
 *   <li>期的执行俱乐部事实缺失或活动/主题两侧冲突 ⇒ {@link Revisit#clubId} 为 {@code null},
 *       入口整块不出现 —— 不猜一个俱乐部给玩家去关注。</li>
 * </ul>
 */
public class ExploreCompletionVO {

    /** 完局面章节凭证(图鉴一格)。ref_id 恒为 chapter_id,跨期靠 topic_id 过滤,不改幂等键。 */
    public static class Stamp {
        private Long chapterId;
        private String title;
        private Date obtainedAt;
        private boolean collected;

        public Long getChapterId() { return chapterId; }
        public void setChapterId(Long chapterId) { this.chapterId = chapterId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Date getObtainedAt() { return obtainedAt; }
        public void setObtainedAt(Date obtainedAt) { this.obtainedAt = obtainedAt; }
        public boolean isCollected() { return collected; }
        public void setCollected(boolean collected) { this.collected = collected; }
    }

    /** 通关奖励到账明细。读的是**已落库的到账事实**,不是 grantOnce 那一刻的返回体。 */
    public static class Award {
        private String kind;   // BADGE / POINTS / COUPON / XP
        private String title;
        private String iconUrl;
        private Integer amount;
        private Date grantedAt;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getIconUrl() { return iconUrl; }
        public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
        public Integer getAmount() { return amount; }
        public void setAmount(Integer amount) { this.amount = amount; }
        public Date getGrantedAt() { return grantedAt; }
        public void setGrantedAt(Date grantedAt) { this.grantedAt = grantedAt; }
    }

    public static class Awards {
        /** 是否读到了任何一条到账事实。false 且 completed=true ⇒ 该报「奖励发放中」而不是「没有奖励」。 */
        private boolean credited;
        /** 实际到账积分;<b>无流水时为 null,不得写 0</b>。 */
        private Integer points;
        /** 本期通关实际到账探索值；无发放事实时为 null。 */
        private Integer earnedXp;
        /** 券是否已发;主题未配通关券时为 null(「没配」不等于「配了没发」)。 */
        private Boolean couponGranted;
        private List<Award> items = new ArrayList<>();

        public boolean isCredited() { return credited; }
        public void setCredited(boolean credited) { this.credited = credited; }
        public Integer getPoints() { return points; }
        public void setPoints(Integer points) { this.points = points; }
        public Integer getEarnedXp() { return earnedXp; }
        public void setEarnedXp(Integer earnedXp) { this.earnedXp = earnedXp; }
        public Boolean getCouponGranted() { return couponGranted; }
        public void setCouponGranted(Boolean couponGranted) { this.couponGranted = couponGranted; }
        public List<Award> getItems() { return items; }
        public void setItems(List<Award> items) { this.items = items; }
    }

    /** 下期预告(R90 回访入口的落点)。查不到就整个为 null,不造一个空壳。 */
    public static class NextEdition {
        private Long topicId;
        private String name;
        private String imgUrl;
        private Date startDate;

        public Long getTopicId() { return topicId; }
        public void setTopicId(Long topicId) { this.topicId = topicId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getImgUrl() { return imgUrl; }
        public void setImgUrl(String imgUrl) { this.imgUrl = imgUrl; }
        public Date getStartDate() { return startDate; }
        public void setStartDate(Date startDate) { this.startDate = startDate; }
    }

    /** 完局后的 J(自愿关注/入群)与 R90(回访)入口。全部自愿,不与任何权益交换。 */
    public static class Revisit {
        private Long clubId;
        private String clubName;
        private String clubLogo;
        /** 关注对象 = 俱乐部主体 member(Club.memberId);缺失即不出关注入口。 */
        private Long clubLeaderMemberId;
        private boolean followed;
        private boolean joined;
        private NextEdition nextEdition;

        public Long getClubId() { return clubId; }
        public void setClubId(Long clubId) { this.clubId = clubId; }
        public String getClubName() { return clubName; }
        public void setClubName(String clubName) { this.clubName = clubName; }
        public String getClubLogo() { return clubLogo; }
        public void setClubLogo(String clubLogo) { this.clubLogo = clubLogo; }
        public Long getClubLeaderMemberId() { return clubLeaderMemberId; }
        public void setClubLeaderMemberId(Long clubLeaderMemberId) { this.clubLeaderMemberId = clubLeaderMemberId; }
        public boolean isFollowed() { return followed; }
        public void setFollowed(boolean followed) { this.followed = followed; }
        public boolean isJoined() { return joined; }
        public void setJoined(boolean joined) { this.joined = joined; }
        public NextEdition getNextEdition() { return nextEdition; }
        public void setNextEdition(NextEdition nextEdition) { this.nextEdition = nextEdition; }
    }

    private Long registrationId;
    private Long topicId;
    private boolean completed;
    private int requiredChapterCount;
    private int redeemedChapterCount;
    private List<Stamp> stamps = new ArrayList<>();
    private Awards awards = new Awards();
    private Revisit revisit = new Revisit();

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getTopicId() { return topicId; }
    public void setTopicId(Long topicId) { this.topicId = topicId; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public int getRequiredChapterCount() { return requiredChapterCount; }
    public void setRequiredChapterCount(int requiredChapterCount) { this.requiredChapterCount = requiredChapterCount; }
    public int getRedeemedChapterCount() { return redeemedChapterCount; }
    public void setRedeemedChapterCount(int redeemedChapterCount) { this.redeemedChapterCount = redeemedChapterCount; }
    public List<Stamp> getStamps() { return stamps; }
    public void setStamps(List<Stamp> stamps) { this.stamps = stamps; }
    public Awards getAwards() { return awards; }
    public void setAwards(Awards awards) { this.awards = awards; }
    public Revisit getRevisit() { return revisit; }
    public void setRevisit(Revisit revisit) { this.revisit = revisit; }
}
