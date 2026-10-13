package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.chengyinhub.business.service.support.TopicSaleCanonicalCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TopicSaleCanonicalV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void eachCanonicalFieldMissingFailsWithItsOwnStableCode() throws Exception {
        Map<String, String> codes = new LinkedHashMap<String, String>();
        codes.put("storeCount", "SALE_SNAPSHOT_MISSING_STORE_COUNT");
        codes.put("ticketPricingPolicy", "SALE_SNAPSHOT_MISSING_TICKET_PRICE");
        codes.put("salesTargetTotal", "SALE_SNAPSHOT_MISSING_SALES_TARGET");
        codes.put("sessionWindows", "SALE_SNAPSHOT_MISSING_SESSION_WINDOWS");
        codes.put("participationUnitPolicy", "SALE_SNAPSHOT_MISSING_PARTICIPATION_UNIT");
        codes.put("sessionVenues", "SALE_SNAPSHOT_MISSING_SESSION_VENUES");
        codes.put("merchantCashSettlementMode", "SALE_SNAPSHOT_MISSING_MERCHANT_SETTLEMENT");
        codes.put("storeCluster", "SALE_SNAPSHOT_MISSING_STORE_CLUSTER");
        codes.put("microExperienceCatalog", "SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES");
        codes.put("merchantWorkPackage", "SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE");
        codes.put("clubWorkPackage", "SALE_SNAPSHOT_MISSING_CLUB_WORK_PACKAGE");
        codes.put("contentRightsPackage", "SALE_SNAPSHOT_MISSING_CONTENT_RIGHTS");
        codes.put("completionPolicy", "SALE_SNAPSHOT_MISSING_COMPLETION_POLICY");
        codes.put("relationshipPolicy", "SALE_SNAPSHOT_MISSING_RELATIONSHIP_POLICY");
        codes.put("refundReschedulePolicy", "SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY");

        for (Map.Entry<String, String> entry : codes.entrySet()) {
            ObjectNode json = completeJson();
            json.set(entry.getKey(), null);
            TopicSaleCanonicalV1 snapshot = JSON.treeToValue(json, TopicSaleCanonicalV1.class);

            IllegalStateException error = assertThrows(IllegalStateException.class, snapshot::validateComplete,
                    entry.getKey());
            assertEquals(entry.getValue(), error.getMessage(), entry.getKey());
        }
    }

    @Test
    void organizerActorIdentityCannotBeReducedToLiveForeignKey() {
        ObjectNode json = TopicSaleCanonicalTestFixture.json();
        ObjectNode actor = (ObjectNode) json.path("clubWorkPackage").path("organizerActor");
        actor.remove("actorType");

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                JSON.treeToValue(json, TopicSaleCanonicalV1.class).validateComplete());

        assertEquals("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR", error.getMessage());
    }

    /** 自由探索已启用故事流后，开售快照同样必须冻结已物化 blocks。 */
    @Test
    void freeExploreAlsoRequiresBlocksJsonBeforeSale() throws Exception {
        ObjectNode json = completeJson();
        for (com.fasterxml.jackson.databind.JsonNode chapter : json.path("microExperienceCatalog").path("chapters")) {
            ((ObjectNode) chapter).remove("blocksJson");
        }
        TopicSaleCanonicalV1 snapshot = JSON.treeToValue(json, TopicSaleCanonicalV1.class);

        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class,
                        () -> snapshot.validateComplete(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE))
                        .getMessage(), "③ 已启用故事流后不得再豁免 blocksJson");
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, () -> snapshot.maxTicketPerAccount(
                        com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)).getMessage());
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, snapshot::maxTicketPerAccount).getMessage(),
                "maxTicketPerAccount 的无参重载同样是严格版");

        // 其余产品类型与未知类型同样严格。
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class,
                        () -> snapshot.validateComplete(com.chengyinhub.common.constant.ProductType.ORIENTEERING))
                        .getMessage(), "①经典定向不得被豁免");
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, () -> snapshot.validateComplete((Integer) null))
                        .getMessage(), "产品类型未知时必须 fail-closed,不许当成③放行");
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, snapshot::validateComplete)
                        .getMessage(), "无参重载是严格版,语义不得被这次豁免改掉");
    }

    @Test
    void optionalPlatformPrologueDoesNotChangeFourMerchantRoleContract() throws Exception {
        ObjectNode json = completeJson();
        ObjectNode prologue = JSON.createObjectNode().put("id", 99L).put("required", 0)
                .put("description", "平台序章").put("blocksJson", "[{}]");
        ((ArrayNode) json.path("microExperienceCatalog").path("chapters")).insert(0, prologue.deepCopy());
        ((ArrayNode) json.path("merchantWorkPackage").path("chapters")).insert(0, prologue.deepCopy());
        ObjectNode prologueWorkItem = ((ObjectNode) json.path("merchantWorkPackage")
                .path("workItems").get(0)).deepCopy();
        prologueWorkItem.put("chapterId", 99L).put("roleNeed", "平台序章");
        ((ArrayNode) json.path("merchantWorkPackage").path("workItems")).insert(0, prologueWorkItem);

        JSON.treeToValue(json, TopicSaleCanonicalV1.class)
                .validateComplete(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
    }

    /**
     * 豁免只放 blocksJson 这一项。③ 自身其它 canonical15 要求该红时仍要红 ——
     * 否则「按产品类型收口」会退化成「③ 整段不验」。
     */
    @Test
    void freeExploreExemptionDoesNotWeakenTheRestOfMicroExperiences() throws Exception {
        // ③ 缺 perks:必须照旧红(与 blocksJson 无关)
        ObjectNode missingPerks = completeJson();
        ((ObjectNode) missingPerks.path("microExperienceCatalog")).putArray("perks");
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, () -> JSON.treeToValue(missingPerks, TopicSaleCanonicalV1.class)
                        .validateComplete(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)).getMessage());

        // ③ 章节缺 description:必须照旧红(同一条断言里的另一必填项)
        ObjectNode missingDesc = completeJson();
        for (com.fasterxml.jackson.databind.JsonNode chapter : missingDesc.path("microExperienceCatalog").path("chapters")) {
            ((ObjectNode) chapter).remove("description");
        }
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, () -> JSON.treeToValue(missingDesc, TopicSaleCanonicalV1.class)
                        .validateComplete(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)).getMessage());

        // ③ 章节数不是 4:必须照旧红
        ObjectNode wrongCount = completeJson();
        ArrayNode chapters = (ArrayNode) wrongCount.path("microExperienceCatalog").path("chapters");
        chapters.remove(0);
        assertEquals("SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES",
                assertThrows(IllegalStateException.class, () -> JSON.treeToValue(wrongCount, TopicSaleCanonicalV1.class)
                        .validateComplete(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE)).getMessage());
    }

    @Test
    void canonicalFieldOrderAndNamesAreFixed() {
        assertEquals(java.util.Arrays.asList(
                "storeCount", "ticketPricingPolicy", "salesTargetTotal", "sessionWindows",
                "participationUnitPolicy", "sessionVenues", "merchantCashSettlementMode", "storeCluster",
                "microExperienceCatalog", "merchantWorkPackage", "clubWorkPackage", "contentRightsPackage",
                "completionPolicy", "relationshipPolicy", "refundReschedulePolicy"),
                TopicSaleCanonicalV1.FIELD_NAMES);
    }

    @Test
    void validTypedCanonicalPassesAndFreeExploreLimitIsFrozenAtOne() throws Exception {
        TopicSaleCanonicalV1 value = JSON.treeToValue(completeJson(), TopicSaleCanonicalV1.class);

        value.validateComplete();

        assertEquals(1, value.maxTicketPerAccount());
    }

    @Test
    void legitimateFourSessionsOfFortySeatsAreNotRejectedByHiddenCap() throws Exception {
        ObjectNode json = completeJson();
        json.put("salesTargetTotal", 160);

        JSON.treeToValue(json, TopicSaleCanonicalV1.class).validateComplete();
    }

    @Test
    void nestedAuthoritativeFieldsAreAlsoFailClosed() throws Exception {
        assertNestedHole("ticketPricingPolicy", "SALE_SNAPSHOT_MISSING_TICKET_PRICE", json ->
                ((ObjectNode) ((ArrayNode) json.path("ticketPricingPolicy").path("tickets")).get(0)).remove("price"));
        assertNestedHole("sessionVenues", "SALE_SNAPSHOT_MISSING_SESSION_VENUES", json ->
                ((ObjectNode) json.path("sessionVenues")).remove("authorization"));
        assertNestedHole("merchantWorkPackage", "SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE", json ->
                ((ObjectNode) ((ArrayNode) json.path("merchantWorkPackage").path("workItems")).get(0))
                        .remove("effortStaffMinutes"));
        assertNestedHole("clubWorkPackage", "SALE_SNAPSHOT_MISSING_CLUB_WORK_PACKAGE", json ->
                ((ObjectNode) json.path("clubWorkPackage")).set("organizers", JSON.createArrayNode()));
        assertNestedHole("contentRightsPackage", "SALE_SNAPSHOT_MISSING_CONTENT_RIGHTS", json ->
                ((ObjectNode) json.path("contentRightsPackage")).remove("assetRef"));
    }

    @Test
    void unknownSchemaAndWeakTopLevelShapeAreRejected() {
        IllegalStateException schema = assertThrows(IllegalStateException.class,
                () -> TopicSaleCanonicalCodec.read("CANONICAL_15_V2", "{}"));
        assertEquals("SALE_SNAPSHOT_UNKNOWN_SCHEMA_VERSION:CANONICAL_15_V2", schema.getMessage());

        ObjectNode weak = completeJson();
        weak.put("ticketPricingPolicy", "not-an-object");
        assertThrows(Exception.class, () -> JSON.treeToValue(weak, TopicSaleCanonicalV1.class));
    }

    private static void assertNestedHole(String field, String code,
                                         java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode json = completeJson();
        mutation.accept(json);
        TopicSaleCanonicalV1 value = JSON.treeToValue(json, TopicSaleCanonicalV1.class);
        IllegalStateException error = assertThrows(IllegalStateException.class, value::validateComplete, field);
        assertEquals(code, error.getMessage(), field);
    }

    @Test
    void ordinaryFreeAcceptsOnlyExplicitClubDirectRevshare() throws Exception {
        ObjectNode value = ordinaryFreeTwoChapterFixture();
        for (String parent : new String[]{"merchantCashSettlementMode", "microExperienceCatalog"}) {
            for (com.fasterxml.jackson.databind.JsonNode offer : value.path(parent).path("offers")) {
                ((ObjectNode) offer).put("termsMode", "REVSHARE").put("perHeadFee", "0.01")
                        .put("payerClubId", 61L).put("paymentRoute", "CLUB_DIRECT");
            }
        }
        JSON.treeToValue(value, TopicSaleCanonicalV1.class).validateComplete(2);
        for (String field : new String[]{"payerClubId", "paymentRoute", "perHeadFee"}) {
            ObjectNode invalid = value.deepCopy();
            ((ObjectNode) invalid.path("merchantCashSettlementMode").path("offers").get(0)).remove(field);
            assertThrows(IllegalStateException.class, () -> JSON.treeToValue(invalid, TopicSaleCanonicalV1.class).validateComplete(2));
        }
        ObjectNode invalid = value.deepCopy();
        ((ObjectNode) invalid.path("merchantCashSettlementMode").path("offers").get(0)).put("perHeadFee", "-0.01");
        assertThrows(IllegalStateException.class, () -> JSON.treeToValue(invalid, TopicSaleCanonicalV1.class).validateComplete(2));
    }

    private static ObjectNode completeJson() {
        return TopicSaleCanonicalTestFixture.json();
    }
    @Test
    void ordinaryFreeExplorationSupportsTwoTrafficChaptersWithoutPerksOrGathering() throws Exception {
        ObjectNode value = ordinaryFreeTwoChapterFixture();
        JSON.treeToValue(value, TopicSaleCanonicalV1.class).validateComplete(
                com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        assertThrows(IllegalStateException.class,
                () -> JSON.treeToValue(value, TopicSaleCanonicalV1.class).validateComplete());
        assertThrows(IllegalStateException.class,
                () -> JSON.treeToValue(value, TopicSaleCanonicalV1.class).validateComplete(
                        com.chengyinhub.common.constant.ProductType.ORIENTEERING));
    }

    @Test
    void ordinaryFreeExplorationAllowsOneMerchantToSupplySeveralChapters() throws Exception {
        ObjectNode value = ordinaryFreeTwoChapterFixture();
        value.put("storeCount", 1);
        for (com.fasterxml.jackson.databind.JsonNode store : value.path("storeCluster"))
            ((ObjectNode) store).put("merchantMemberId", 200L);
        for (String parent : new String[]{"merchantCashSettlementMode", "microExperienceCatalog"})
            for (com.fasterxml.jackson.databind.JsonNode offer : value.path(parent).path("offers"))
                ((ObjectNode) offer).put("merchantId", 200L);
        JSON.treeToValue(value, TopicSaleCanonicalV1.class).validateComplete(
                com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
    }

    @Test
    void ordinaryFreeExplorationSupportsOwnerRunChaptersWithNoMerchants() throws Exception {
        ObjectNode value = ordinaryFreeTwoChapterFixture();
        value.put("storeCount", 0);
        value.putArray("storeCluster");
        ((ObjectNode) value.get("merchantCashSettlementMode")).putArray("offers");
        ((ObjectNode) value.get("microExperienceCatalog")).putArray("offers");
        TopicSaleCanonicalV1 snapshot = JSON.treeToValue(value, TopicSaleCanonicalV1.class);
        snapshot.validateDraft();
        snapshot.validateComplete(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
    }

    @Test
    void ordinaryFreeExplorationRejectsUnbackedStoresCompletionAndPerkReferences() throws Exception {
        for (String mutation : new String[]{"store", "completion", "perk", "chapter"}) {
            ObjectNode value = ordinaryFreeTwoChapterFixture();
            if ("store".equals(mutation)) value.put("storeCount", 3);
            if ("completion".equals(mutation))
                ((com.fasterxml.jackson.databind.node.ArrayNode) value.path("completionPolicy")
                        .path("requiredChapterIds")).add(999L);
            ObjectNode offer = (ObjectNode) value.path("merchantCashSettlementMode").path("offers").get(0);
            if ("perk".equals(mutation)) offer.put("termsMode", "PERK").put("perkId", 999L);
            if ("chapter".equals(mutation)) offer.put("chapterId", 999L);
            ((ObjectNode) value.get("microExperienceCatalog")).set("offers",
                    value.path("merchantCashSettlementMode").path("offers").deepCopy());
            assertThrows(IllegalStateException.class,
                    () -> JSON.treeToValue(value, TopicSaleCanonicalV1.class).validateComplete(
                            com.chengyinhub.common.constant.ProductType.FREE_EXPLORE), mutation);
        }
    }

    private ObjectNode ordinaryFreeTwoChapterFixture() {
        ObjectNode value = TopicSaleCanonicalTestFixture.json();
        ((ObjectNode) value.path("completionPolicy")).put("evidenceRule", "ALL_REQUIRED_CHAPTERS");
        value.put("storeCount", 2);
        for (com.fasterxml.jackson.databind.JsonNode parent : new com.fasterxml.jackson.databind.JsonNode[]{
                value.path("merchantCashSettlementMode"), value.path("microExperienceCatalog"),
                value.path("merchantWorkPackage")}) {
            com.fasterxml.jackson.databind.node.ArrayNode chapters = (com.fasterxml.jackson.databind.node.ArrayNode) parent.get("chapters");
            while (chapters.size() > 2) chapters.remove(chapters.size() - 1);
            for (com.fasterxml.jackson.databind.JsonNode chapter : chapters) ((ObjectNode) chapter).put("termsMode", "TRAFFIC");
        }
        for (String parent : new String[]{"merchantCashSettlementMode", "microExperienceCatalog"}) {
            com.fasterxml.jackson.databind.node.ArrayNode offers = (com.fasterxml.jackson.databind.node.ArrayNode) value.path(parent).path("offers");
            while (offers.size() > 2) offers.remove(offers.size() - 1);
            for (com.fasterxml.jackson.databind.JsonNode offer : offers) {
                ((ObjectNode) offer).put("termsMode", "TRAFFIC");
                ((ObjectNode) offer).remove("perkId");
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode stores = (com.fasterxml.jackson.databind.node.ArrayNode) value.get("storeCluster");
        while (stores.size() > 2) stores.remove(stores.size() - 1);
        ((ObjectNode) value.get("microExperienceCatalog")).putArray("perks");
        ((ObjectNode) value.get("merchantWorkPackage")).putArray("workItems");
        ((ObjectNode) value.get("clubWorkPackage")).putArray("organizers");
        ((ObjectNode) value.get("completionPolicy")).putArray("requiredChapterIds").add(100L).add(101L);
        ((ObjectNode) value.get("sessionWindows")).remove("saleEndAt");
        for (com.fasterxml.jackson.databind.JsonNode ticket : value.path("sessionWindows").path("tickets"))
            ((ObjectNode) ticket).remove("signupDeadline");
        for (com.fasterxml.jackson.databind.JsonNode ticket : value.path("sessionVenues").path("tickets")) {
            ((ObjectNode) ticket).remove("meetingPoint");
            ((ObjectNode) ticket).remove("gatherLng");
            ((ObjectNode) ticket).remove("gatherLat");
        }
        for (com.fasterxml.jackson.databind.JsonNode ticket : value.path("refundReschedulePolicy").path("ticketPolicies"))
            ((ObjectNode) ticket).remove("signupDeadline");
        return value;
    }

}
