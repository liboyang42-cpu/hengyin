package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.util.RefundPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Date;

/**
 * F10 §3.4:自由探索作者自己发布开售时,canonical-15 里原本由运营人工填写的字段按自由探索给默认值。
 *
 * <p>集中在这一个类里,便于审计「平台替作者填了什么」:
 * <ul>
 *   <li>participationUnitPolicy:一张票 = 1 位成人(F12 家庭票之前);</li>
 *   <li>sessionVenues:店铺营业场所 / 店铺负责;</li>
 *   <li>clubWorkPackage:无(自由探索没有俱乐部执行包);</li>
 *   <li>refundReschedulePolicy:沿用自由探索票的三档退款快照(OR-005 保持分档),不改期、不跨期转让;</li>
 *   <li>completionPolicy.evidenceRule:店铺核销;</li>
 *   <li>relationshipPolicy:店铺与主办方线下结算(FE-G22)。</li>
 * </ul>
 * 派生字段(票、场次窗口、章节、供给、店铺、权益、执行人)只放占位,由 {@link TopicSaleCanonicalBuilder#rebuild} 按实时数据覆盖。
 */
public final class FreeExploreCanonicalDefaults {

    /** completionPolicy.evidenceRule:店铺核销(区别于四店探店日 FOUR_STORE_REDEEM 的固定合同)。 */
    public static final String EVIDENCE_STORE_REDEEM = "STORE_REDEEM";
    public static final String NONE = "无";
    public static final String VENUE = "店铺营业场所";
    public static final String VENUE_RESPONSIBLE = "店铺负责";
    public static final String OFFLINE_SETTLEMENT = "店铺与主办方线下结算";

    private static final ObjectMapper JSON = new ObjectMapper();

    private FreeExploreCanonicalDefaults() {
    }

    /**
     * @param topic          自由探索主题(取起止时间与作者)
     * @param totalInventory 全部票的总库存;用作场地成人容量与草稿占位的销售目标
     */
    public static TopicSaleCanonicalV1 draft(CmsTopic topic, long totalInventory) {
        if (topic == null || topic.getId() == null) throw new IllegalStateException("主题不存在");
        long capacity = Math.max(totalInventory, 1L);
        long start = millis(topic.getStartDate(), System.currentTimeMillis());
        long end = millis(topic.getEndDate(), start);
        ObjectNode root = JSON.createObjectNode();
        root.put("storeCount", 0);
        // —— 以下派生字段只是占位,rebuild 用实时数据覆盖 ——
        root.set("ticketPricingPolicy", JSON.createObjectNode().put("pointsAllowed", false).put("discountMode", "NONE"));
        root.put("salesTargetTotal", capacity);
        root.set("sessionWindows", JSON.createObjectNode().put("startDate", start).put("endDate", end));
        root.set("merchantCashSettlementMode", JSON.createObjectNode().put("merchantDepositRequired", false));
        root.set("storeCluster", JSON.createArrayNode());
        root.set("microExperienceCatalog", JSON.createObjectNode().put("source", "DERIVED"));
        root.set("merchantWorkPackage", JSON.createObjectNode().put("source", "DERIVED"));
        // —— 以下是原本人工填写的字段:自由探索默认值 ——
        root.set("participationUnitPolicy", JSON.createObjectNode().put("adultCount", 1).put("petMax", 0)
                .put("childrenPolicy", "WITH_GUARDIAN").put("extraAdultPolicy", "NOT_ALLOWED")
                .put("companionPolicy", "SAME_TICKET"));
        root.set("sessionVenues", JSON.createObjectNode().put("adultCapacity", capacity).put("petCapacity", 0)
                .put("authorization", VENUE).put("rainPlan", VENUE).put("evacuationPlan", VENUE)
                .put("responsibleParty", VENUE_RESPONSIBLE).put("insuranceScope", VENUE));
        root.set("clubWorkPackage", JSON.createObjectNode().put("opening", NONE).put("patrol", NONE)
                .put("photoWrap", NONE).put("closing", NONE).put("community", NONE).put("HPlan", NONE)
                .put("contractRef", NONE));
        ObjectNode rights = JSON.createObjectNode().put("assetRef", "topic:" + topic.getId())
                .put("owner", "member:" + topic.getMemberId()).put("licensedEntity", "chengyin")
                .put("validFrom", start).put("validTo", end).put("editingRight", true)
                .put("humanPortraitConsent", false).put("petPortraitConsent", false);
        rights.putArray("channels").add("mini-program").add("app");
        root.set("contentRightsPackage", rights);
        root.set("completionPolicy", JSON.createObjectNode().put("evidenceRule", EVIDENCE_STORE_REDEEM));
        root.set("relationshipPolicy", JSON.createObjectNode().put("platformTransaction", true)
                .put("clubCommunityOptIn", false).put("forcedFollow", false).put("forcedGroupJoin", false)
                .put("merchantContactDisclosure", false).put("merchantSettlement", OFFLINE_SETTLEMENT));
        root.set("refundReschedulePolicy", JSON.createObjectNode().put("rescheduleDeadline", start)
                .put("rescheduleLimit", 0).put("cancellationPolicy", RefundPolicy.SESSION_REFUND_RULE_TEXT)
                .put("merchantAbsencePolicy", "FULL_REFUND").put("noShowPolicy", "NO_REFUND")
                .put("crossEditionTransferAllowed", false));
        try {
            TopicSaleCanonicalV1 draft = JSON.treeToValue(root, TopicSaleCanonicalV1.class);
            draft.validateDraft();
            return draft;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("自由探索开售默认参数生成失败", e);
        }
    }

    private static long millis(Date date, long fallback) {
        return date == null ? fallback : date.getTime();
    }
}
