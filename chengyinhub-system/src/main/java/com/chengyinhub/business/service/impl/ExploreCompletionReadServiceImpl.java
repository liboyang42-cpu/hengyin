package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ClubEditionCompensationTerms;
import com.chengyinhub.business.domain.MemberCollectible;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.vo.ExploreCompletionVO;
import com.chengyinhub.business.domain.vo.TopicCompletionAwardVO;
import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.ClubEditionCompensationMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IExploreCompletionReadService;
import com.chengyinhub.business.service.IPerkLedgerService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批5 完局面只读实现。三块读面各自 fail-closed:读不到事实就留空,绝不用 0 或默认值顶替。
 */
@Service
public class ExploreCompletionReadServiceImpl implements IExploreCompletionReadService {

    @Autowired private com.chengyinhub.business.service.support.PureContentExploreService pureContentExploreService;

    /** 通关积分的事件类型,与 TopicCompletionAwardServiceImpl 里 pointsAwardService.award(_, 13L, _) 同值。 */
    static final long POINTS_EVENT_TOPIC_FINISH = 13L;
    /** PlayerBadge.sourceType=2 表示「来自主题」,与 grantOnce 写入侧同值。 */
    static final int BADGE_SOURCE_TOPIC = 2;

    @Autowired private IPerkLedgerService perkLedgerService;
    @Autowired private MemberCollectibleMapper memberCollectibleMapper;
    @Autowired private PlayerBadgeMapper playerBadgeMapper;
    @Autowired private IUmsMemberPointsDetailService umsMemberPointsDetailService;
    @Autowired private CmsTopicCompletionCouponRewardMapper couponRewardMapper;
    @Autowired private TopicCompletionXpAwardMapper xpAwardMapper;
    @Autowired private ICmsTopicService topicService;
    @Autowired private ICmsTopicChapterService topicChapterService;
    @Autowired private IClubService clubService;
    @Autowired private IClubMemberService clubMemberService;
    @Autowired private IUmsMemberFollowService memberFollowService;
    @Autowired private com.chengyinhub.business.service.ICmsActivityService activityService;
    @Autowired private ClubEditionCompensationMapper compensationMapper;

    @Override
    public ExploreCompletionVO read(Long registrationId, Long memberId, Long topicId, Long activityId) {
        ExploreCompletionVO vo = new ExploreCompletionVO();
        vo.setRegistrationId(registrationId);
        vo.setTopicId(topicId);
        if (registrationId == null || memberId == null || topicId == null) {
            // 期缺失时不猜:空壳的 completed=false / stamps 空 / awards.credited=false,
            // 前端据此展示「暂无数据」,不会渲染出一个 0/0 的假进度。
            return vo;
        }

        List<RegistrationEntitlement> entitlements = safeList(perkLedgerService.listEntitlements(registrationId));
        vo.setCompleted(perkLedgerService.isExploreCompleted(registrationId, topicId));
        vo.setRequiredChapterCount(entitlements.size());

        if (pureContentExploreService != null && pureContentExploreService.isPureContent(topicId)) {
            java.util.Set<Long> completed = pureContentExploreService.completedChapters(registrationId, topicId);
            List<com.chengyinhub.business.domain.CmsTopicChapter> chapters = pureContentExploreService.requiredChapters(topicId);
            vo.setRequiredChapterCount(chapters.size());
            vo.setRedeemedChapterCount(completed.size());
            List<ExploreCompletionVO.Stamp> stamps = new ArrayList<>();
            for (com.chengyinhub.business.domain.CmsTopicChapter chapter : chapters) {
                ExploreCompletionVO.Stamp stamp = new ExploreCompletionVO.Stamp();
                stamp.setChapterId(chapter.getId());
                stamp.setTitle(chapter.getName());
                stamp.setCollected(completed.contains(chapter.getId()));
                stamps.add(stamp);
            }
            vo.setStamps(stamps);
        } else {
            fillStamps(vo, entitlements, memberId, topicId);
        }
        fillAwards(vo, memberId, topicId);
        fillRevisit(vo, memberId, topicId, activityId);
        return vo;
    }

    // ---------- F4 图鉴:coll_type=5 的读侧 ----------

    private void fillStamps(ExploreCompletionVO vo, List<RegistrationEntitlement> entitlements,
                            Long memberId, Long topicId) {
        // ⚠️ 幂等键是 (member, type, ref) 不含 topic_id,ref_id 恒为 chapter_id ——
        //    跨期只能靠行上的 topic_id 过滤,绝不能把 ref_id 换成 merchant_id(会跨期串)。
        Map<Long, MemberCollectible> byChapter = new HashMap<>();
        for (MemberCollectible row : safeList(
                memberCollectibleMapper.selectListByMember(memberId, MemberCollectible.TYPE_EXPLORE_STAMP))) {
            if (row == null || row.getRefId() == null) continue;
            if (row.getTopicId() == null || !topicId.equals(row.getTopicId())) continue;
            byChapter.putIfAbsent(row.getRefId(), row);
        }

        int redeemed = 0;
        List<ExploreCompletionVO.Stamp> stamps = new ArrayList<>();
        for (RegistrationEntitlement e : entitlements) {
            if (e == null || e.getChapterId() == null) continue;
            ExploreCompletionVO.Stamp stamp = new ExploreCompletionVO.Stamp();
            stamp.setChapterId(e.getChapterId());
            MemberCollectible got = byChapter.get(e.getChapterId());
            // 图鉴凭证会保留，但撤销或失效的本票权益不再计入当前核销进度。
            boolean currentlyRedeemed = Integer.valueOf(1).equals(e.getStatus()) && got != null;
            stamp.setCollected(currentlyRedeemed);
            if (currentlyRedeemed) {
                redeemed++;
                stamp.setTitle(got.getContent());
                stamp.setObtainedAt(got.getCreateTime());
            }
            if (stamp.getTitle() == null) stamp.setTitle(chapterTitle(e));
            stamps.add(stamp);
        }
        vo.setStamps(stamps);
        vo.setRedeemedChapterCount(redeemed);
    }

    private String chapterTitle(RegistrationEntitlement e) {
        if (e.getChapterName() != null && !e.getChapterName().trim().isEmpty()) return e.getChapterName();
        try {
            com.chengyinhub.business.domain.CmsTopicChapter chapter =
                    topicChapterService.selectCmsTopicChapterById(e.getChapterId());
            if (chapter != null && chapter.getName() != null) return chapter.getName();
        } catch (RuntimeException ignored) { /* 章节名拿不到就退到编号,不阻断整个读面 */ }
        return "章节 " + e.getChapterId();
    }

    // ---------- 通关奖励到账明细 ----------

    private void fillAwards(ExploreCompletionVO vo, Long memberId, Long topicId) {
        ExploreCompletionVO.Awards awards = vo.getAwards();

        for (PlayerBadge badge : safeList(playerBadgeMapper.selectListByMember(memberId))) {
            if (badge == null || badge.getSourceId() == null || badge.getSourceType() == null) continue;
            if (badge.getSourceType() != BADGE_SOURCE_TOPIC || !topicId.equals(badge.getSourceId())) continue;
            ExploreCompletionVO.Award item = new ExploreCompletionVO.Award();
            item.setKind("BADGE");
            item.setTitle(badge.getBadgeName());
            item.setIconUrl(badge.getIconUrl());
            item.setGrantedAt(badge.getObtainTime());
            awards.getItems().add(item);
        }

        // 积分:③ 的 pointsSourceId 是活动壳 id(CmsRegistrationServiceImpl 传 registration.ownerId),
        // 同一 eventId 会被同期多个玩家共用 ⇒ 必须带 memberId 一起查,不能只按 (eventType, eventId)。
        UmsMemberPointsDetail probe = new UmsMemberPointsDetail();
        probe.setMemberId(memberId);
        probe.setEventType(POINTS_EVENT_TOPIC_FINISH);
        Integer points = null;
        Date pointsAt = null;
        for (UmsMemberPointsDetail row : safeList(
                umsMemberPointsDetailService.selectUmsMemberPointsDetailList(probe))) {
            if (row == null || row.getChangePoints() == null) continue;
            if (!belongsToEdition(row, topicId)) continue;
            points = (points == null ? 0 : points) + row.getChangePoints().intValue();
            if (pointsAt == null) pointsAt = row.getCreateTime();
        }
        // ★ 没有流水 ⇒ 保持 null。写 0 会让玩家看到「获得 0 积分」,也让验收误判 2-4 没做。
        awards.setPoints(points);
        if (points != null) {
            ExploreCompletionVO.Award item = new ExploreCompletionVO.Award();
            item.setKind("POINTS");
            item.setTitle("通关积分");
            item.setAmount(points);
            item.setGrantedAt(pointsAt);
            awards.getItems().add(item);
        }

        TopicCompletionAwardVO xpFact = xpAwardMapper.selectOne(memberId, topicId);
        if (xpFact != null && xpFact.getEarnedXp() > 0) {
            awards.setEarnedXp(xpFact.getEarnedXp());
            ExploreCompletionVO.Award item = new ExploreCompletionVO.Award();
            item.setKind("XP");
            item.setTitle("通关探索值");
            item.setAmount(xpFact.getEarnedXp());
            item.setGrantedAt(xpFact.getEarnedXpAt());
            awards.getItems().add(item);
        }

        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        boolean couponConfigured = topic != null && topic.getCompleteRewardCouponId() != null
                && topic.getCompleteRewardCouponId() > 0;
        if (couponConfigured) {
            Date grantedAt = couponRewardMapper.selectGrantedAt(topicId, memberId);
            awards.setCouponGranted(grantedAt != null);
            if (grantedAt != null) {
                ExploreCompletionVO.Award item = new ExploreCompletionVO.Award();
                item.setKind("COUPON");
                item.setTitle("通关奖励券");
                item.setGrantedAt(grantedAt);
                awards.getItems().add(item);
            }
        } // 未配券 ⇒ couponGranted 保持 null:「没配」不等于「配了没发」。

        awards.setCredited(!awards.getItems().isEmpty());
    }

    /**
     * 这笔积分流水属不属于本期。③ 的 eventId 是活动壳 id ⇒ 用「该期的活动壳集合」去比;
     * 一期一活动壳时就是一条。拿不到期的活动壳事实 ⇒ 判否(宁可少算,不可把别期的分算进来)。
     */
    private boolean belongsToEdition(UmsMemberPointsDetail row, Long topicId) {
        if (row.getEventId() == null) return false;
        if (topicId.equals(row.getEventId())) return true; // ① 主题线直接就是 topicId
        CmsActivity shell = activityShell(row.getEventId());
        return shell != null && topicId.equals(shell.getTopicId());
    }

    private CmsActivity activityShell(Long activityId) {
        try {
            return activityService.selectCmsActivityById(activityId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- 完局后的 J(自愿关注/入群)与 R90(回访)入口 ----------

    private void fillRevisit(ExploreCompletionVO vo, Long memberId, Long topicId, Long activityId) {
        ExploreCompletionVO.Revisit revisit = vo.getRevisit();
        CmsTopic topic = topicService.selectCmsTopicById(topicId);
        Long clubId = resolveEditionClub(topicId, topic, activityId);
        if (clubId == null) return; // 没有承接俱乐部事实、或三侧冲突 ⇒ 整块入口不出现,不猜

        Club club = clubService.selectClubById(clubId);
        if (club == null) return;
        revisit.setClubId(clubId);
        revisit.setClubName(club.getName());
        revisit.setClubLogo(club.getLogo());
        revisit.setClubLeaderMemberId(club.getMemberId());

        // 已关注 / 已入群 只用来决定「还要不要引导」,不做任何写。
        if (club.getMemberId() != null && club.getMemberId() > 0) {
            List<UmsMemberFollow> follows = safeList(memberFollowService
                    .selectUmsMemberFollowsByMemberAndFollowMemberIds(memberId,
                            Collections.singletonList(club.getMemberId())));
            revisit.setFollowed(!follows.isEmpty());
        }
        revisit.setJoined(clubMemberService.selectByClubAndMember(clubId, memberId) != null);

        revisit.setNextEdition(findNextEdition(clubId, topicId));
    }

    /**
     * 本期主办俱乐部 —— 口径与 {@code freezeTicketSourceOnPayment} 逐条对齐:
     * 活动、主题与开售条款的 {@code executing_club_id} 三侧不得冲突，且至少一侧明确有值；
     * 否则返 null。这个判据与支付成功时 {@code freezeTicketSourceOnPayment} 的三侧互校一致，
     * 不再漏掉探店日真正冻结下来的执行俱乐部。
     */
    private Long resolveEditionClub(Long topicId, CmsTopic topic, Long activityId) {
        Long topicClub = topic == null ? null : positive(topic.getClubId());
        Long activityClub = null;
        if (activityId != null) {
            CmsActivity shell = activityShell(activityId);
            activityClub = shell == null ? null : positive(shell.getClubId());
        }
        ClubEditionCompensationTerms terms = compensationMapper.selectTerms(topicId);
        Long executingClub = terms == null ? null : positive(terms.getExecutingClubId());
        Long resolved = null;
        for (Long candidate : new Long[]{topicClub, activityClub, executingClub}) {
            if (candidate == null) continue;
            if (resolved != null && !resolved.equals(candidate)) return null;
            resolved = candidate;
        }
        return resolved;
    }

    private static Long positive(Long value) {
        return value != null && value > 0L ? value : null;
    }

    /**
     * 下期预告 = 同一俱乐部的另一期探店日,仍在售票中且售票窗未过。
     * 查不到就返 null —— R90 的读面口径是「未满窗报观察中、不得记 0」,前端据此展示
     * 「下期开售后再来」,而不是渲染一个 0 场/0%。
     */
    private ExploreCompletionVO.NextEdition findNextEdition(Long clubId, Long currentTopicId) {
        CmsTopic query = new CmsTopic();
        query.setClubId(clubId);
        query.setProductType(ProductType.FREE_EXPLORE);
        query.setLifecycle(TopicLifecycleState.SELLING.getCode());
        List<CmsTopic> candidates;
        try {
            candidates = safeList(topicService.selectCmsTopicList(query));
        } catch (RuntimeException e) {
            return null;
        }
        Date now = new Date();
        CmsTopic best = null;
        for (CmsTopic candidate : candidates) {
            if (candidate == null || candidate.getId() == null) continue;
            if (currentTopicId.equals(candidate.getId())) continue;
            if (candidate.getSaleEndAt() == null || !candidate.getSaleEndAt().after(now)) continue;
            if (best == null || earlier(candidate.getStartDate(), best.getStartDate())) best = candidate;
        }
        if (best == null) return null;
        ExploreCompletionVO.NextEdition next = new ExploreCompletionVO.NextEdition();
        next.setTopicId(best.getId());
        next.setName(best.getName());
        next.setImgUrl(best.getImgUrl());
        next.setStartDate(best.getStartDate());
        return next;
    }

    private boolean earlier(Date a, Date b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.before(b);
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }
}
