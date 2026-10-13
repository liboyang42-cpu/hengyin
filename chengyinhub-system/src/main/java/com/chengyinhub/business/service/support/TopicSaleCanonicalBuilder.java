package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.CoopPerk;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.CoopPerkMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 从 canonical 草稿的人工事实 + 当前票/章/offer/节点权威读重建 canonical-15。 */
@Component
public class TopicSaleCanonicalBuilder {
    private final CommercialTopicPolicy commercialTopicPolicy;
    private final CmsTopicNodeMapper nodeMapper;
    private final CmsTopicTemplateMetaMapper templateMetaMapper;
    private final CoopPerkMapper perkMapper;
    private final CoopOrderLineupSource lineupSource;
    private final IProductRoleService productRoleService;
    private final ObjectMapper json = new ObjectMapper();

    public TopicSaleCanonicalBuilder(CommercialTopicPolicy commercialTopicPolicy, CmsTopicNodeMapper nodeMapper,
                                     CmsTopicTemplateMetaMapper templateMetaMapper, CoopPerkMapper perkMapper,
                                     CoopOrderLineupSource lineupSource,
                                     IProductRoleService productRoleService) {
        this.commercialTopicPolicy = commercialTopicPolicy;
        this.nodeMapper = nodeMapper;
        this.templateMetaMapper = templateMetaMapper;
        this.perkMapper = perkMapper;
        this.lineupSource = lineupSource;
        this.productRoleService = productRoleService;
    }

    public TopicSaleCanonicalV1 rebuild(CmsTopic topic, TopicSaleCanonicalV1 draft) {
        if (topic == null || draft == null) throw new IllegalStateException("canonical15 重建参数不完整");
        draft.validateDraft();
        CommercialTopicPolicy.Evaluation evaluation = commercialTopicPolicy.evaluate(topic.getId());
        Map<String, Object> live = commercialTopicPolicy.saleParameters(topic, evaluation);
        ObjectNode out = json.valueToTree(draft);
        ArrayNode tickets = json.valueToTree(live.get("tickets"));
        ArrayNode chapters = json.valueToTree(live.get("chapters"));
        ArrayNode offers = json.valueToTree(live.get("offers"));

        Set<Long> offerMerchants = new HashSet<Long>();
        for (JsonNode offer : offers) {
            if (offer.path("merchantId").canConvertToLong()) offerMerchants.add(offer.path("merchantId").longValue());
        }

        ObjectNode pricing = json.createObjectNode();
        pricing.put("pointsAllowed", false);
        pricing.put("discountMode", "NONE");
        pricing.set("tickets", tickets);
        out.set("ticketPricingPolicy", pricing);

        long total = 0L;
        for (JsonNode ticket : tickets) total += ticket.path("totalInventory").asLong(0L);
        out.put("salesTargetTotal", total);

        ObjectNode windows = json.createObjectNode();
        windows.set("startDate", json.valueToTree(live.get("startDate")));
        windows.set("endDate", json.valueToTree(live.get("endDate")));
        windows.set("saleEndAt", json.valueToTree(live.get("saleEndAt")));
        windows.set("tickets", tickets);
        out.set("sessionWindows", windows);

        ObjectNode venues = asObject(out.get("sessionVenues"));
        venues.set("tickets", tickets);
        out.set("sessionVenues", venues);

        Long directPayer = com.chengyinhub.business.support.ClubDirectMerchantPolicy.payerClubId(
                topic, productRoleService.activeContentSlot(topic.getId()));
        for (JsonNode offer : offers) {
            if (!"REVSHARE".equals(offer.path("termsMode").asText()) || draft.requiresFourStoreContract()) continue;
            if (directPayer == null || directPayer.longValue() != offer.path("payerClubId").asLong()) {
                throw new IllegalStateException("SALE_SNAPSHOT_MISSING_CLUB_DIRECT_PAYER");
            }
        }
        ObjectNode settlement = json.createObjectNode();
        settlement.put("merchantDepositRequired", false);
        settlement.set("chapters", chapters);
        settlement.set("offers", offers);
        out.set("merchantCashSettlementMode", settlement);

        CmsTopicNode query = new CmsTopicNode();
        query.setTopicId(topic.getId());
        ArrayNode stores = json.createArrayNode();
        java.util.List<CmsTopicNode> nodes = nodeMapper.selectCmsTopicNodeList(query);
        if (nodes != null) for (CmsTopicNode node : nodes) {
            if (node == null || node.getMerchantMemberId() == null
                    || !Integer.valueOf(1).equals(node.getNodeAuditStatus())) continue;
            ObjectNode value = json.createObjectNode();
            value.put("nodeId", node.getId());
            value.put("chapterId", node.getChapterId());
            value.put("merchantMemberId", node.getMerchantMemberId());
            value.put("address", node.getAddress());
            value.put("longitude", node.getLongitude());
            value.put("latitude", node.getLatitude());
            stores.add(value);
        }
        Set<Long> nodeMerchants = new HashSet<Long>();
        for (JsonNode store : stores) nodeMerchants.add(store.path("merchantMemberId").longValue());
        if (!offerMerchants.equals(nodeMerchants)) {
            throw new IllegalStateException("SALE_SNAPSHOT_MISSING_STORE_CLUSTER");
        }
        out.put("storeCount", nodeMerchants.size());
        out.set("storeCluster", stores);

        ObjectNode experiences = asObject(out.get("microExperienceCatalog"));
        experiences.set("chapters", chapters);
        experiences.set("offers", offers);
        ArrayNode perks = json.createArrayNode();
        Set<Long> perkIds = new HashSet<Long>();
        for (JsonNode offer : offers) {
            if (!offer.path("perkId").canConvertToLong()) continue;
            Long perkId = offer.path("perkId").longValue();
            if (!perkIds.add(perkId)) continue;
            CoopPerk perk = perkMapper.selectById(perkId);
            if (perk == null || Integer.valueOf(2).equals(perk.getDelFlag())) continue;
            ObjectNode value = json.createObjectNode();
            value.put("id", perk.getId());
            value.put("name", perk.getName());
            value.put("perkType", perk.getPerkType());
            value.put("retailValue", perk.getRetailValue() == null ? null
                    : perk.getRetailValue().stripTrailingZeros().toPlainString());
            value.put("redeemRule", perk.getRedeemRule());
            perks.add(value);
        }
        experiences.set("perks", perks);
        out.set("microExperienceCatalog", experiences);
        ObjectNode merchantWork = asObject(out.get("merchantWorkPackage"));
        merchantWork.set("chapters", chapters);
        ArrayNode workItems = json.createArrayNode();
        java.util.List<CmsTopicChapterTemplateMeta> metas = templateMetaMapper.selectChapterMetaByTopicId(topic.getId());
        if (metas != null) for (CmsTopicChapterTemplateMeta meta : metas) {
            if (meta == null) continue;
            ObjectNode value = json.createObjectNode();
            value.put("chapterId", meta.getChapterId());
            value.put("roleNeed", meta.getRoleNeed());
            value.put("effortStaffMinutes", meta.getEffortStaffMinutes());
            value.put("effortVenue", meta.getEffortVenue());
            value.put("effortMaterials", meta.getEffortMaterials());
            value.put("effortAvailableSlots", meta.getEffortAvailableSlots());
            value.put("effortQualifications", meta.getEffortQualifications());
            workItems.add(value);
        }
        merchantWork.set("workItems", workItems);
        out.set("merchantWorkPackage", merchantWork);
        ObjectNode clubWork = asObject(out.get("clubWorkPackage"));
        ArrayNode organizers = json.createArrayNode();
        CoopOrderLineupSource.ReadResult lineup = lineupSource.readEffectiveLineup(topic.getId());
        for (CoopOrderLineupSource.LineupEntry entry : lineup.getEntries()) {
            if (!"ORGANIZER".equals(entry.getRole())) continue;
            ObjectNode value = json.createObjectNode();
            value.put("subjectType", entry.getSubjectType());
            value.put("subjectId", entry.getSubjectId());
            value.put("role", entry.getRole());
            value.put("scopeType", entry.getScopeType());
            value.put("scopeId", entry.getScopeId());
            organizers.add(value);
        }
        clubWork.set("organizers", organizers);
        ProductRole organizerActor = productRoleService.activeOrganizerSlot(topic.getId());
        if (organizerActor == null && !draft.requiresFourStoreContract()
                && Integer.valueOf(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)
                        .equals(topic.getProductType())) {
            organizerActor = productRoleService.activeContentSlot(topic.getId());
        }
        if (organizerActor == null || organizerActor.getId() == null
                || organizerActor.getEntityType() == null
                || organizerActor.getCreatedAt() == null) {
            throw new IllegalStateException("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR");
        }
        ObjectNode frozenActor = json.createObjectNode();
        frozenActor.put("roleId", organizerActor.getId());
        frozenActor.put("actorType", organizerActor.getEntityType());
        if (organizerActor.getEntityId() == null) frozenActor.putNull("actorId");
        else frozenActor.put("actorId", organizerActor.getEntityId());
        Long declaredBy = organizerActor.getDeclaredBy();
        if (declaredBy == null && !draft.requiresFourStoreContract()) declaredBy = topic.getMemberId();
        if (declaredBy == null) throw new IllegalStateException("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR");
        frozenActor.put("declaredBy", declaredBy);
        frozenActor.put("declaredAt", organizerActor.getCreatedAt().getTime());
        clubWork.set("organizerActor", frozenActor);
        out.set("clubWorkPackage", clubWork);
        ObjectNode completion = asObject(out.get("completionPolicy"));
        ArrayNode requiredIds = json.createArrayNode();
        for (JsonNode chapter : chapters) if (chapter.path("required").asInt(0) == 1) requiredIds.add(chapter.path("id"));
        completion.set("requiredChapterIds", requiredIds);
        out.set("completionPolicy", completion);
        ObjectNode refunds = asObject(out.get("refundReschedulePolicy"));
        refunds.set("ticketPolicies", tickets);
        out.set("refundReschedulePolicy", refunds);

        try {
            TopicSaleCanonicalV1 rebuilt = json.treeToValue(out, TopicSaleCanonicalV1.class);
            rebuilt.validateComplete(topic.getProductType());
            rebuilt.maxTicketPerAccount(topic.getProductType());
            return rebuilt;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("canonical15 重建失败", e);
        }
    }

    private ObjectNode asObject(JsonNode value) {
        if (value != null && value.isObject()) return ((ObjectNode) value).deepCopy();
        ObjectNode wrapped = json.createObjectNode();
        wrapped.set("manual", value == null ? json.nullNode() : value.deepCopy());
        return wrapped;
    }
}
