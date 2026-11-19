package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.FeReferenceSettlement;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.TopicSaleSnapshotMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 场次收款人(票款归谁)的单一规则。{@code CmsActivityServiceImpl} 结算现金、F11 参考结算的付款方、
 * 店铺看到的「主办方：X」都从这里取,不再各自按主题猜(旧 organizerName 取主题俱乐部/发布者,
 * 跨俱乐部场次会指错人)。
 *
 * <ul>
 *   <li>跨俱乐部 / 四店合同的自由探索场次(2026-11-14 决定 A):场次主办方;</li>
 *   <li>商家发布的自由探索、俱乐部承办的场次:发布商家 topic.member_id;</li>
 *   <li>其余:俱乐部场次 = 俱乐部主理人(个人收款冻结人优先),否则 cms_activity.member_id。</li>
 * </ul>
 */
@Component
public class SessionPayeeResolver {

    @Autowired private ClubPrincipalPayee clubPrincipalPayee;
    @Autowired private TopicSaleSnapshotMapper topicSaleSnapshotMapper;
    @Autowired private ClubMapper clubMapper;
    @Autowired private MmsMerchantMapper mmsMerchantMapper;
    @Autowired private UmsMemberMapper umsMemberMapper;

    /** 付款方:kind = CLUB(id 为俱乐部 id)| MEMBER(id 为 member_id);memberId = 收款人 member_id。 */
    public static final class Payer {
        private final String kind;
        private final Long id;
        private final Long memberId;

        public Payer(String kind, Long id, Long memberId) {
            this.kind = kind;
            this.id = id;
            this.memberId = memberId;
        }

        public String getKind() { return kind; }
        public Long getId() { return id; }
        public Long getMemberId() { return memberId; }
    }

    /**
     * 商家发布的自由探索(topic.club_id 为空、发布者 topic.member_id),由承办俱乐部开的场次(activity.club_id>0)。
     * 2026-11-13 业主:票款归发布商家;承办俱乐部、合作店铺与商家线下结算。
     */
    public static boolean isMerchantPublishedSession(CmsActivity activity, CmsTopic topic) {
        return activity != null && topic != null
                && Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())
                && (topic.getClubId() == null || topic.getClubId() <= 0)
                && topic.getMemberId() != null && topic.getMemberId() > 0
                && activity.getClubId() != null && activity.getClubId() > 0;
    }

    /** 2026-11-14 决定 A:原无路径的跨俱乐部/四店场次归场次主办方,店铺在线下结算。 */
    public static boolean isOrganizerSettledFreeExploreSession(CmsActivity activity, CmsTopic topic,
                                                               TopicSaleSnapshotMapper snapshots) {
        if (activity == null || topic == null || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            return false;
        }
        boolean organizer = activity.getClubId() != null && activity.getClubId() > 0
                || activity.getMemberId() != null && activity.getMemberId() > 0;
        if (!organizer) return false;
        if (topic.getClubId() != null && topic.getClubId() > 0 && !topic.getClubId().equals(activity.getClubId())) {
            return true;
        }
        TopicSaleSnapshot snapshot = snapshots.selectSnapshotByTopicId(topic.getId());
        return snapshot != null && TopicSaleCanonicalCodec.read(
                snapshot.getSchemaVersion(), snapshot.getCanonicalJson(), topic.getProductType()).requiresFourStoreContract();
    }

    /** 场次收款人是否经俱乐部主理人得出(⇒ payer_kind=CLUB)。 */
    public static boolean paysThroughClub(CmsActivity activity, CmsTopic topic, TopicSaleSnapshotMapper snapshots) {
        if (activity == null || activity.getClubId() == null || activity.getClubId() <= 0) return false;
        return isOrganizerSettledFreeExploreSession(activity, topic, snapshots) || !isMerchantPublishedSession(activity, topic);
    }

    /**
     * 场次现金收款人(写路径;俱乐部个人收款模式下会冻结收款人,须在事务里调用)。
     * 与结算入账同一规则,{@code CmsActivityServiceImpl.activityPayee} 委托到这里。
     */
    public static Long payee(CmsActivity activity, CmsTopic topic, ClubPrincipalPayee principal,
                             TopicSaleSnapshotMapper snapshots) {
        if (!isOrganizerSettledFreeExploreSession(activity, topic, snapshots) && isMerchantPublishedSession(activity, topic)) {
            return topic.getMemberId();
        }
        if (activity.getClubId() == null || activity.getClubId() <= 0) {
            return activity.getMemberId();
        }
        return principal.forActivity(activity);
    }

    /** 写路径:场次的付款方(参考结算的 payer_kind / payer_id)。 */
    public Payer forSession(CmsActivity activity, CmsTopic topic) {
        Long member = payee(activity, topic, clubPrincipalPayee, topicSaleSnapshotMapper);
        return payer(activity, topic, member);
    }

    /** 已知收款人(结算时已算出)时组装付款方。 */
    public static Payer payer(CmsActivity activity, CmsTopic topic, Long payeeMemberId, TopicSaleSnapshotMapper snapshots) {
        return paysThroughClub(activity, topic, snapshots)
                ? new Payer(FeReferenceSettlement.PAYER_CLUB, activity.getClubId(), payeeMemberId)
                : new Payer(FeReferenceSettlement.PAYER_MEMBER, payeeMemberId, payeeMemberId);
    }

    public Payer payer(CmsActivity activity, CmsTopic topic, Long payeeMemberId) {
        return payer(activity, topic, payeeMemberId, topicSaleSnapshotMapper);
    }

    /** 写路径:无场次的主题级购票,收款人同主题分润口径。 */
    public Payer forTopicPurchase(CmsTopic topic) {
        Long member = clubPrincipalPayee.forTopic(topic);
        return topic.getClubId() != null && topic.getClubId() > 0
                ? new Payer(FeReferenceSettlement.PAYER_CLUB, topic.getClubId(), member)
                : new Payer(FeReferenceSettlement.PAYER_MEMBER, member, member);
    }

    /** 只读:场次付款方(不冻结、不要求事务)。activity 为空时按主题级购票。 */
    public Payer peek(CmsActivity activity, CmsTopic topic) {
        if (activity == null) {
            if (topic == null) return null;
            Long member = clubPrincipalPayee.peekTopic(topic);
            return topic.getClubId() != null && topic.getClubId() > 0
                    ? new Payer(FeReferenceSettlement.PAYER_CLUB, topic.getClubId(), member)
                    : new Payer(FeReferenceSettlement.PAYER_MEMBER, member, member);
        }
        if (paysThroughClub(activity, topic, topicSaleSnapshotMapper)) {
            return new Payer(FeReferenceSettlement.PAYER_CLUB, activity.getClubId(), clubPrincipalPayee.peekActivity(activity));
        }
        Long member = isMerchantPublishedSession(activity, topic) ? topic.getMemberId() : activity.getMemberId();
        return new Payer(FeReferenceSettlement.PAYER_MEMBER, member, member);
    }

    /** 付款方展示名:俱乐部名;个人取店铺名,否则会员昵称。取不到返回 null(不编)。 */
    public String payerName(String kind, Long id) {
        if (id == null || id <= 0) return null;
        String name = null;
        if (FeReferenceSettlement.PAYER_CLUB.equals(kind)) {
            Club club = clubMapper.selectClubById(id);
            name = club == null ? null : club.getName();
        } else {
            MmsMerchant merchant = mmsMerchantMapper.selectMmsMerchantByMemberId(id);
            name = merchant == null ? null : merchant.getName();
            if (blank(name)) {
                UmsMember member = umsMemberMapper.selectUmsMemberById(id);
                name = member == null ? null : member.getNickname();
            }
        }
        return blank(name) ? null : name.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
