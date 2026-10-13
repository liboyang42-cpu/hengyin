package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.TopicSaleSnapshot;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

/** canonical15 测试正控；业务测试共享，避免弱占位 JSON 被误当合法快照。 */
public final class TopicSaleCanonicalTestFixture {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TopicSaleCanonicalTestFixture() {}

    public static TopicSaleCanonicalV1 canonical() {
        try {
            ObjectNode canonical = json();
            for (com.fasterxml.jackson.databind.JsonNode perk
                    : canonical.path("microExperienceCatalog").path("perks")) {
                BigDecimal retailValue = perk.path("retailValue").decimalValue();
                ((ObjectNode) perk).put("retailValue", retailValue.stripTrailingZeros().toPlainString());
            }
            return JSON.treeToValue(canonical, TopicSaleCanonicalV1.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static TopicSaleSnapshot snapshot(Long topicId) {
        TopicSaleSnapshot snapshot = new TopicSaleSnapshot();
        snapshot.setTopicId(topicId);
        snapshot.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION);
        snapshot.setParameterVersion(1L);
        snapshot.setCanonicalJson(TopicSaleCanonicalCodec.write(canonical()));
        return snapshot;
    }

    public static ObjectNode json() {
        ObjectNode root = JSON.createObjectNode();
        root.put("storeCount", 4);
        root.put("salesTargetTotal", 120);

        ArrayNode tickets = JSON.createArrayNode();
        ObjectNode ticket = JSON.createObjectNode();
        ticket.put("id", 61L).put("price", "69.00").put("totalInventory", 120L)
                .put("startTime", 1000L).put("endTime", 2000L).put("signupDeadline", 900L)
                .put("meetingPoint", "中心广场").put("gatherLng", "121.1").put("gatherLat", "31.1")
                .put("refundRule", "开场前可退").put("subType", "explore");
        tickets.add(ticket);
        root.set("ticketPricingPolicy", JSON.createObjectNode().put("pointsAllowed", false)
                .put("discountMode", "NONE").set("tickets", tickets.deepCopy()));
        root.set("sessionWindows", JSON.createObjectNode().put("startDate", 1000L).put("endDate", 2000L)
                .put("saleEndAt", 900L).set("tickets", tickets.deepCopy()));
        root.set("participationUnitPolicy", JSON.createObjectNode().put("adultCount", 1).put("petMax", 1)
                .put("childrenPolicy", "WITH_GUARDIAN").put("extraAdultPolicy", "NOT_ALLOWED")
                .put("companionPolicy", "SAME_TICKET").put("maxTicketPerAccount", 1));
        root.set("sessionVenues", JSON.createObjectNode().put("adultCapacity", 120).put("petCapacity", 120)
                .put("authorization", "asset://venue-auth").put("rainPlan", "INDOOR")
                .put("evacuationPlan", "asset://evacuation").put("responsibleParty", "club:7")
                .put("insuranceScope", "asset://policy").set("tickets", tickets.deepCopy()));

        ArrayNode chapters = JSON.createArrayNode();
        ArrayNode offers = JSON.createArrayNode();
        ArrayNode stores = JSON.createArrayNode();
        ArrayNode perks = JSON.createArrayNode();
        ArrayNode workItems = JSON.createArrayNode();
        ArrayNode requiredIds = JSON.createArrayNode();
        for (int i = 0; i < 4; i++) {
            long chapterId = 100L + i;
            long merchantId = 200L + i;
            long perkId = 300L + i;
            chapters.add(JSON.createObjectNode().put("id", chapterId).put("required", 1)
                    .put("termsMode", "PERK").put("description", "体验" + i).put("blocksJson", "[{}]"));
            offers.add(JSON.createObjectNode().put("id", 400L + i).put("chapterId", chapterId)
                    .put("merchantId", merchantId).put("perkId", perkId).put("termsMode", "PERK")
                    .putNull("perHeadFee"));
            stores.add(JSON.createObjectNode().put("nodeId", 500L + i).put("chapterId", chapterId)
                    .put("merchantMemberId", merchantId).put("address", "门店" + i)
                    .put("longitude", "121." + i).put("latitude", "31." + i));
            perks.add(JSON.createObjectNode().put("id", perkId).put("name", "权益" + i).put("perkType", 0)
                    .put("retailValue", new BigDecimal("20.00")).put("redeemRule", "到店核销"));
            workItems.add(JSON.createObjectNode().put("chapterId", chapterId).put("roleNeed", "店员")
                    .put("effortStaffMinutes", 30).put("effortVenue", "柜台")
                    .put("effortMaterials", "礼包").put("effortAvailableSlots", "10:00-18:00")
                    .put("effortQualifications", "在岗"));
            requiredIds.add(chapterId);
        }
        ObjectNode settlement = JSON.createObjectNode().put("merchantDepositRequired", false);
        settlement.set("chapters", chapters.deepCopy());
        settlement.set("offers", offers.deepCopy());
        root.set("merchantCashSettlementMode", settlement);
        root.set("storeCluster", stores);
        ObjectNode experiences = JSON.createObjectNode();
        experiences.set("chapters", chapters.deepCopy());
        experiences.set("offers", offers.deepCopy());
        experiences.set("perks", perks);
        root.set("microExperienceCatalog", experiences);
        ObjectNode merchantWork = JSON.createObjectNode();
        merchantWork.set("chapters", chapters.deepCopy());
        merchantWork.set("workItems", workItems);
        root.set("merchantWorkPackage", merchantWork);
        root.set("clubWorkPackage", JSON.createObjectNode().put("opening", "开场")
                .put("patrol", "巡场").put("photoWrap", "摄影收口").put("closing", "闭场")
                .put("community", "自愿入群").put("HPlan", "一期固定")
                .put("contractRef", "asset://club-contract")
                .set("organizerActor", JSON.createObjectNode().put("roleId", 91L)
                        .put("actorType", "PLATFORM").putNull("actorId")
                        .put("declaredBy", 9001L).put("declaredAt", 123456L)));
        ((ObjectNode) root.get("clubWorkPackage")).set("organizers", JSON.createArrayNode()
                        .add(JSON.createObjectNode().put("subjectType", "CLUB").put("subjectId", 7L)
                                .put("role", "ORGANIZER").put("scopeType", "TOPIC").put("scopeId", 83L)));
        ObjectNode contentRights = JSON.createObjectNode().put("assetRef", "asset://rights")
                .put("owner", "merchant-alliance").put("licensedEntity", "chengyin")
                .put("validFrom", 1000L).put("validTo", 2000L)
                .put("editingRight", true).put("humanPortraitConsent", true).put("petPortraitConsent", true);
        contentRights.putArray("channels").add("mini-program");
        root.set("contentRightsPackage", contentRights);
        root.set("completionPolicy", JSON.createObjectNode().put("evidenceRule", "FOUR_STORE_REDEEM")
                .set("requiredChapterIds", requiredIds));
        root.set("relationshipPolicy", JSON.createObjectNode().put("platformTransaction", true)
                .put("clubCommunityOptIn", true).put("forcedFollow", false).put("forcedGroupJoin", false)
                .put("merchantContactDisclosure", false));
        root.set("refundReschedulePolicy", JSON.createObjectNode().put("rescheduleDeadline", 800L)
                .put("rescheduleLimit", 1).put("cancellationPolicy", "REFUND_AND_REBUY")
                .put("merchantAbsencePolicy", "FULL_REFUND").put("noShowPolicy", "NO_REFUND")
                .put("crossEditionTransferAllowed", false).set("ticketPolicies", tickets.deepCopy()));
        return root;
    }
}
