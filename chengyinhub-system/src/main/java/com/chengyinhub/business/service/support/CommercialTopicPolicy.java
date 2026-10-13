package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** H4 商业主题客观事实单一真源：收费票、开招商、有效商家供给位任一成立即为商业主题。 */
@Component
public class CommercialTopicPolicy {

    private final OmsTicketMapper ticketMapper;
    private final CmsTopicChapterMapper chapterMapper;
    private final MerchantChapterOfferMapper offerMapper;
    private final CmsTopicMapper topicMapper;

    public CommercialTopicPolicy(OmsTicketMapper ticketMapper, CmsTopicChapterMapper chapterMapper,
                                 MerchantChapterOfferMapper offerMapper, CmsTopicMapper topicMapper) {
        this.ticketMapper = ticketMapper;
        this.chapterMapper = chapterMapper;
        this.offerMapper = offerMapper;
        this.topicMapper = topicMapper;
    }

    public Evaluation evaluate(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("topicId 不能为空");

        OmsTicket ticketQuery = new OmsTicket();
        ticketQuery.setOwnerType(1L);
        ticketQuery.setOwnerId(topicId);
        List<OmsTicket> tickets = safe(ticketMapper.selectOmsTicketList(ticketQuery));

        CmsTopicChapter chapterQuery = new CmsTopicChapter();
        chapterQuery.setTopicId(topicId);
        List<CmsTopicChapter> chapters = safe(chapterMapper.selectCmsTopicChapterList(chapterQuery));
        List<MerchantChapterOffer> offers = safe(offerMapper.selectByTopic(topicId));

        List<Long> paidTicketIds = new ArrayList<Long>();
        for (OmsTicket ticket : tickets) {
            if (ticket != null && !Integer.valueOf(2).equals(ticket.getDelFlag())
                    && ticket.getPrice() != null && ticket.getPrice().signum() > 0) {
                paidTicketIds.add(ticket.getId());
            }
        }
        List<Long> recruitingChapterIds = new ArrayList<Long>();
        for (CmsTopicChapter chapter : chapters) {
            if (chapter != null && Integer.valueOf(1).equals(chapter.getRecruitEnabled())) {
                recruitingChapterIds.add(chapter.getId());
            }
        }
        List<Long> supplyOfferIds = new ArrayList<Long>();
        for (MerchantChapterOffer offer : offers) {
            if (offer != null) supplyOfferIds.add(offer.getId());
        }
        CmsTopic topic = topicMapper.selectCmsTopicById(topicId);
        boolean paidSelfPlay = topic != null && Integer.valueOf(1).equals(topic.getSelfPlay())
                && topic.getSelfPlayPrice() != null && topic.getSelfPlayPrice().signum() > 0;
        boolean freeExplore = topic != null
                && Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType());
        return new Evaluation(paidTicketIds, recruitingChapterIds, supplyOfferIds, paidSelfPlay, freeExplore,
                tickets, chapters, offers);
    }

    /**
     * 「免费主题」唯一口径(报名侧 F03 与俱乐部池侧 F04 共用):仍在 DRAFT、未删除、且无任何商业事实。
     * PENDING_REVIEW/RECRUITING/PRICING 已进入商业生命周期,一律不算免费态;在架/开池等开放条件由调用方各自再判。
     */
    public boolean isFreeTopic(CmsTopic topic) {
        return topic != null && topic.getId() != null
                && Integer.valueOf(TopicLifecycleState.DRAFT.getCode()).equals(topic.getLifecycle())
                && !Integer.valueOf(2).equals(topic.getDelFlag())
                && !evaluate(topic.getId()).isCommercial();
    }

    public static final String FREE_TO_COMMERCIAL_REJECTED = "免费主题不能转为商业主题,如需收费请重新发起主题";

    /**
     * 9-15 拍板:已发布(is_draft≠1)的免费主题不能转商业,只能发起人重新发起;草稿编辑期仍可补成商业。
     * 写入口在写前取本值,写后调 {@link #assertNotConvertedToCommercial}。
     */
    public boolean isConversionProtected(CmsTopic topic) {
        return isFreeTopic(topic) && !Integer.valueOf(1).equals(topicMapper.selectTopicIsDraft(topic.getId()));
    }

    /** 同一事务内写后复核:写前受保护、写后出现任一商业事实 ⇒ 抛出回滚。 */
    public void assertNotConvertedToCommercial(Long topicId, boolean wasProtected) {
        if (wasProtected && evaluate(topicId).isCommercial()) {
            throw new IllegalStateException(FREE_TO_COMMERCIAL_REJECTED);
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? Collections.<T>emptyList() : values;
    }

    /** H3 开售审核快照：票价/场次/主题履约窗/章节与四店供给必须与批准时完全一致。 */
    public Map<String, Object> saleParameters(CmsTopic topic, Evaluation evaluation) {
        if (topic == null || evaluation == null) throw new IllegalArgumentException("开售审核快照参数不完整");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("topicId", topic.getId());
        result.put("productType", topic.getProductType());
        result.put("startDate", epoch(topic.getStartDate()));
        result.put("endDate", epoch(topic.getEndDate()));
        result.put("saleEndAt", epoch(topic.getSaleEndAt()));
        result.put("selfPlay", topic.getSelfPlay());
        result.put("selfPlayPrice", decimal(topic.getSelfPlayPrice()));
        result.put("selfPlayQuota", topic.getSelfPlayQuota());
        result.put("feePlatformRate", decimal(topic.getFeePlatformRate()));
        result.put("feeGuaranteeBaseRate", decimal(topic.getFeeGuaranteeBaseRate()));
        result.put("feeGuaranteeStepRate", decimal(topic.getFeeGuaranteeStepRate()));
        result.put("feeGuaranteeMaxMerchant", topic.getFeeGuaranteeMaxMerchant());
        result.put("tickets", evaluation.ticketParameters());
        result.put("chapters", evaluation.chapterParameters());
        result.put("offers", evaluation.offerParameters());
        return result;
    }

    /** H3 立项审核快照：商圈、履约窗、章节和四店供给在批准迁移时必须仍与提审一致。 */
    public Map<String, Object> projectParameters(CmsTopic topic, Evaluation evaluation) {
        if (topic == null || evaluation == null) throw new IllegalArgumentException("立项审核快照参数不完整");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("topicId", topic.getId());
        result.put("addressName", topic.getAddressName());
        result.put("startDate", epoch(topic.getStartDate()));
        result.put("endDate", epoch(topic.getEndDate()));
        result.put("chapters", evaluation.chapterParameters());
        result.put("offers", evaluation.offerParameters());
        return result;
    }

    private static Long epoch(java.util.Date value) { return value == null ? null : value.getTime(); }
    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    public static final class Evaluation {
        private final List<Long> paidTicketIds;
        private final List<Long> recruitingChapterIds;
        private final List<Long> supplyOfferIds;
        private final boolean paidSelfPlay;
        private final boolean freeExplore;
        private final List<OmsTicket> tickets;
        private final List<CmsTopicChapter> chapters;
        private final List<MerchantChapterOffer> offers;

        private Evaluation(List<Long> paidTicketIds, List<Long> recruitingChapterIds, List<Long> supplyOfferIds,
                           boolean paidSelfPlay,
                           boolean freeExplore,
                           List<OmsTicket> tickets, List<CmsTopicChapter> chapters,
                           List<MerchantChapterOffer> offers) {
            this.paidTicketIds = paidTicketIds;
            this.recruitingChapterIds = recruitingChapterIds;
            this.supplyOfferIds = supplyOfferIds;
            this.paidSelfPlay = paidSelfPlay;
            this.freeExplore = freeExplore;
            this.tickets = tickets;
            this.chapters = chapters;
            this.offers = offers;
        }

        public static Evaluation ugc() {
            return new Evaluation(Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                    Collections.<Long>emptyList(), false, false, Collections.<OmsTicket>emptyList(),
                    Collections.<CmsTopicChapter>emptyList(), Collections.<MerchantChapterOffer>emptyList());
        }

        public static Evaluation commercial(String fact, Long sourceId) {
            if ("paidTicket".equals(fact)) {
                return new Evaluation(Collections.singletonList(sourceId), Collections.<Long>emptyList(),
                        Collections.<Long>emptyList(), false, false, Collections.<OmsTicket>emptyList(),
                        Collections.<CmsTopicChapter>emptyList(), Collections.<MerchantChapterOffer>emptyList());
            }
            if ("recruitmentOpen".equals(fact)) {
                return new Evaluation(Collections.<Long>emptyList(), Collections.singletonList(sourceId),
                        Collections.<Long>emptyList(), false, false, Collections.<OmsTicket>emptyList(),
                        Collections.<CmsTopicChapter>emptyList(), Collections.<MerchantChapterOffer>emptyList());
            }
            return new Evaluation(Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                    Collections.singletonList(sourceId), false, false, Collections.<OmsTicket>emptyList(),
                    Collections.<CmsTopicChapter>emptyList(), Collections.<MerchantChapterOffer>emptyList());
        }

        public static Evaluation freeExplore() {
            return new Evaluation(Collections.<Long>emptyList(), Collections.<Long>emptyList(),
                    Collections.<Long>emptyList(), false, true, Collections.<OmsTicket>emptyList(),
                    Collections.<CmsTopicChapter>emptyList(), Collections.<MerchantChapterOffer>emptyList());
        }

        public boolean isCommercial() {
            return freeExplore || isPaidTicket() || paidSelfPlay || isRecruitmentOpen() || isMerchantSupply();
        }

        public boolean isPaidTicket() { return !paidTicketIds.isEmpty(); }
        public boolean isRecruitmentOpen() { return !recruitingChapterIds.isEmpty(); }
        public boolean isMerchantSupply() { return !supplyOfferIds.isEmpty(); }

        /** 审核快照保留触发事实和源 ID，后台可解释“为何进入审核”。 */
        public Map<String, Object> toSnapshotFacts() {
            Map<String, Object> facts = new LinkedHashMap<String, Object>();
            facts.put("commercial", isCommercial());
            facts.put("paidTicketIds", paidTicketIds);
            facts.put("paidSelfPlay", paidSelfPlay);
            facts.put("freeExplore", freeExplore);
            facts.put("recruitingChapterIds", recruitingChapterIds);
            facts.put("supplyOfferIds", supplyOfferIds);
            return facts;
        }

        private List<Map<String, Object>> ticketParameters() {
            List<OmsTicket> sorted = new ArrayList<OmsTicket>(tickets);
            sorted.sort(Comparator.comparing(OmsTicket::getId, Comparator.nullsFirst(Long::compareTo)));
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            for (OmsTicket ticket : sorted) {
                if (ticket == null || Integer.valueOf(2).equals(ticket.getDelFlag())) continue;
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("id", ticket.getId());
                value.put("name", ticket.getName());
                value.put("price", decimal(ticket.getPrice()));
                value.put("startTime", epoch(ticket.getStartTime()));
                value.put("endTime", epoch(ticket.getEndTime()));
                value.put("totalInventory", ticket.getTotalInventory());
                value.put("mode", ticket.getMode());
                value.put("subType", ticket.getSubType());
                value.put("meetingPoint", ticket.getMeetingPoint());
                value.put("gatherLng", ticket.getGatherLng());
                value.put("gatherLat", ticket.getGatherLat());
                value.put("teamSize", ticket.getTeamSize());
                value.put("signupDeadline", epoch(ticket.getSignupDeadline()));
                value.put("refundRule", ticket.getRefundRule());
                value.put("needAudit", ticket.getNeedAudit());
                values.add(value);
            }
            return values;
        }

        private List<Map<String, Object>> chapterParameters() {
            List<CmsTopicChapter> sorted = new ArrayList<CmsTopicChapter>(chapters);
            sorted.sort(Comparator.comparing(CmsTopicChapter::getId, Comparator.nullsFirst(Long::compareTo)));
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            for (CmsTopicChapter chapter : sorted) {
                if (chapter == null) continue;
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("id", chapter.getId());
                value.put("name", chapter.getName());
                value.put("description", chapter.getDescription());
                value.put("blocksJson", chapter.getBlocksJson());
                value.put("recruitEnabled", chapter.getRecruitEnabled());
                value.put("required", chapter.getRequired());
                value.put("termsMode", chapter.getTermsMode());
                value.put("maxMerchant", chapter.getMaxMerchant());
                values.add(value);
            }
            return values;
        }

        private List<Map<String, Object>> offerParameters() {
            List<MerchantChapterOffer> sorted = new ArrayList<MerchantChapterOffer>(offers);
            sorted.sort(Comparator.comparing(MerchantChapterOffer::getId, Comparator.nullsFirst(Long::compareTo)));
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            for (MerchantChapterOffer offer : sorted) {
                if (offer == null) continue;
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("id", offer.getId());
                value.put("chapterId", offer.getChapterId());
                value.put("merchantId", offer.getMerchantId());
                value.put("perkId", offer.getPerkId());
                value.put("termsMode", offer.getTermsMode());
                value.put("perHeadFee", decimal(offer.getPerHeadFee()));
                value.put("payerClubId", offer.getPayerClubId());
                value.put("paymentRoute", !"REVSHARE".equals(offer.getTermsMode()) ? "NONE"
                        : offer.getPayerClubId() == null ? "PLATFORM" : "CLUB_DIRECT");
                value.put("quotaTotal", offer.getQuotaTotal());
                value.put("validEnd", epoch(offer.getValidEnd()));
                value.put("allowedValidationMethods", offer.getAllowedValidationMethods());
                value.put("maxNodeXp", offer.getMaxNodeXp());
                values.add(value);
            }
            return values;
        }
    }
}
