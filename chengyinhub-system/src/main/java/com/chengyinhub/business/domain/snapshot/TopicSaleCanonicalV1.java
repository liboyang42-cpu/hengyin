package com.chengyinhub.business.domain.snapshot;

import com.chengyinhub.common.constant.ProductType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 探店日开售不可变 canonical-15；顶层字段名和顺序属于持久化合同。 */
public class TopicSaleCanonicalV1 {

    public static final String SCHEMA_VERSION = "CANONICAL_15_V1";

    /**
     * fail-closed 默认值(C:U-016):crossEditionTransferAllowed / pointsAllowed / merchantDepositRequired
     * 这些开关本快照只接受显式的 false。值缺失或不是布尔(对象、数组、乱写的字符串)时,
     * {@code asBoolean} 返回本默认值 true,于是校验按「开关被打开」处理而失败,不会被当成 false 放行。
     */
    private static final boolean UNPROVEN_FLAG_COUNTS_AS_TRUE = true;

    public static final List<String> FIELD_NAMES = Collections.unmodifiableList(Arrays.asList(
            "storeCount", "ticketPricingPolicy", "salesTargetTotal", "sessionWindows",
            "participationUnitPolicy", "sessionVenues", "merchantCashSettlementMode", "storeCluster",
            "microExperienceCatalog", "merchantWorkPackage", "clubWorkPackage", "contentRightsPackage",
            "completionPolicy", "relationshipPolicy", "refundReschedulePolicy"));

    private Integer storeCount;
    private ObjectNode ticketPricingPolicy;
    private Long salesTargetTotal;
    private ObjectNode sessionWindows;
    private ObjectNode participationUnitPolicy;
    private ObjectNode sessionVenues;
    private ObjectNode merchantCashSettlementMode;
    private ArrayNode storeCluster;
    private ObjectNode microExperienceCatalog;
    private ObjectNode merchantWorkPackage;
    private ObjectNode clubWorkPackage;
    private ObjectNode contentRightsPackage;
    private ObjectNode completionPolicy;
    private ObjectNode relationshipPolicy;
    private ObjectNode refundReschedulePolicy;

    /**
     * 严格版:产品类型未知时一律不豁免任何一项。
     *
     * <p>保留无参重载是有意的 —— 拿不到产品类型的调用方必须落在严格那一侧,
     * 不许因为「不知道是不是③」就顺手放行。
     */
    public void validateComplete() {
        validateComplete((Integer) null);
    }

    /**
     * 按产品类型保留调用接口；当前两种产品都支持并要求物化 blocks，校验口径一致。
     * 可额外带一个 required=0 的平台序章，但 required=1 的商家角色仍固定为四个。
     */
    public void validateComplete(Integer productType) {
        if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(productType) && !requiresFourStoreContract()) {
            validateFreeExploration();
            return;
        }
        validateTopLevel();
        validateStoreCount();
        validatePricing();
        validateSalesTarget();
        validateSessionWindows();
        validateParticipationUnit();
        validateSessionVenues();
        validateMerchantSettlement();
        validateStoreCluster();
        validateMicroExperiences(productType);
        validateMerchantWork();
        validateClubWork();
        validateContentRights();
        validateCompletion();
        validateRelationship();
        validateRefundReschedule();
    }

    /** 已批准完成规则声明四店探店日时，继续执行原有专用合同。 */
    public boolean requiresFourStoreContract() {
        return completionPolicy != null
                && "FOUR_STORE_REDEEM".equals(completionPolicy.path("evidenceRule").asText());
    }

    /** PRICING 草稿只严验人工权威字段；服务端派生段在提审/终检时覆盖并做完整校验。 */
    public void validateDraft() {
        validateTopLevel(true);
        validateParticipationUnit();
        requireObjectFields(sessionVenues, "SALE_SNAPSHOT_MISSING_SESSION_VENUES", "adultCapacity", "petCapacity",
                "authorization", "rainPlan", "evacuationPlan", "responsibleParty", "insuranceScope");
        requireObjectFields(clubWorkPackage, "SALE_SNAPSHOT_MISSING_CLUB_WORK_PACKAGE", "opening", "patrol",
                "photoWrap", "closing", "community", "HPlan", "contractRef");
        validateContentRights();
        requireObjectFields(completionPolicy, "SALE_SNAPSHOT_MISSING_COMPLETION_POLICY", "evidenceRule");
        validateRelationship();
        requireObjectFields(refundReschedulePolicy, "SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY",
                "rescheduleDeadline", "rescheduleLimit", "cancellationPolicy", "merchantAbsencePolicy",
                "noShowPolicy", "crossEditionTransferAllowed");
        if (refundReschedulePolicy.path("crossEditionTransferAllowed").asBoolean(UNPROVEN_FLAG_COUNTS_AS_TRUE)) {
            fail("SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY");
        }
    }

    private void validateTopLevel() { validateTopLevel(false); }

    private void validateTopLevel(boolean freeExploration) {
        if (storeCount == null || (freeExploration ? storeCount < 0 : storeCount <= 0)) fail("SALE_SNAPSHOT_MISSING_STORE_COUNT");
        require("ticketPricingPolicy", ticketPricingPolicy, "SALE_SNAPSHOT_MISSING_TICKET_PRICE");
        if (salesTargetTotal == null || salesTargetTotal <= 0L) fail("SALE_SNAPSHOT_MISSING_SALES_TARGET");
        require("sessionWindows", sessionWindows, "SALE_SNAPSHOT_MISSING_SESSION_WINDOWS");
        require("participationUnitPolicy", participationUnitPolicy,
                "SALE_SNAPSHOT_MISSING_PARTICIPATION_UNIT");
        require("sessionVenues", sessionVenues, "SALE_SNAPSHOT_MISSING_SESSION_VENUES");
        require("merchantCashSettlementMode", merchantCashSettlementMode,
                "SALE_SNAPSHOT_MISSING_MERCHANT_SETTLEMENT");
        if (freeExploration) requireArrayItems(storeCluster, true, "SALE_SNAPSHOT_MISSING_STORE_CLUSTER");
        else require("storeCluster", storeCluster, "SALE_SNAPSHOT_MISSING_STORE_CLUSTER");
        require("microExperienceCatalog", microExperienceCatalog,
                "SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES");
        require("merchantWorkPackage", merchantWorkPackage,
                "SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE");
        require("clubWorkPackage", clubWorkPackage, "SALE_SNAPSHOT_MISSING_CLUB_WORK_PACKAGE");
        require("contentRightsPackage", contentRightsPackage, "SALE_SNAPSHOT_MISSING_CONTENT_RIGHTS");
        require("completionPolicy", completionPolicy, "SALE_SNAPSHOT_MISSING_COMPLETION_POLICY");
        require("relationshipPolicy", relationshipPolicy, "SALE_SNAPSHOT_MISSING_RELATIONSHIP_POLICY");
        require("refundReschedulePolicy", refundReschedulePolicy,
                "SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY");
    }

    /** 严格版:产品类型未知时不豁免任何一项。 */
    public int maxTicketPerAccount() {
        return maxTicketPerAccount(null);
    }

    /**
     * 按产品类型收口后再取一账号限购。
     *
     * <p>⚠️ 这里必须跟着分档:它自己也会跑一遍 {@code validateComplete}。
     * 2026-08-18 实测,漏了这一处就等于豁免没生效 —— {@code TopicSaleCanonicalBuilder.rebuild}
     * 先用产品类型校验通过、紧接着调它又按严格档重验一次,③ 照旧红在
     * SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES,而错误码与豁免前一模一样,极难看出是这里。
     */
    public int maxTicketPerAccount(Integer productType) {
        validateComplete(productType);
        JsonNode value = participationUnitPolicy.get("maxTicketPerAccount");
        if (value == null || !value.canConvertToInt() || value.intValue() != 1) {
            throw new IllegalStateException("SALE_SNAPSHOT_INVALID_MAX_TICKET_PER_ACCOUNT");
        }
        return value.intValue();
    }

    public JsonNode field(String name) {
        if ("storeCount".equals(name)) return IntNode.valueOf(storeCount);
        if ("ticketPricingPolicy".equals(name)) return ticketPricingPolicy;
        if ("salesTargetTotal".equals(name)) return LongNode.valueOf(salesTargetTotal);
        if ("sessionWindows".equals(name)) return sessionWindows;
        if ("participationUnitPolicy".equals(name)) return participationUnitPolicy;
        if ("sessionVenues".equals(name)) return sessionVenues;
        if ("merchantCashSettlementMode".equals(name)) return merchantCashSettlementMode;
        if ("storeCluster".equals(name)) return storeCluster;
        if ("microExperienceCatalog".equals(name)) return microExperienceCatalog;
        if ("merchantWorkPackage".equals(name)) return merchantWorkPackage;
        if ("clubWorkPackage".equals(name)) return clubWorkPackage;
        if ("contentRightsPackage".equals(name)) return contentRightsPackage;
        if ("completionPolicy".equals(name)) return completionPolicy;
        if ("relationshipPolicy".equals(name)) return relationshipPolicy;
        if ("refundReschedulePolicy".equals(name)) return refundReschedulePolicy;
        throw new IllegalArgumentException("未知 canonical 字段:" + name);
    }

    private static void require(String name, JsonNode value, String code) {
        boolean empty = value == null || value.isNull()
                || (value.isTextual() && value.asText().trim().isEmpty())
                || ((value.isArray() || value.isObject()) && value.size() == 0);
        if (empty) throw new IllegalStateException(code);
    }

    private void validateStoreCount() {
        if (storeCount == null || storeCount.intValue() != 4) fail("SALE_SNAPSHOT_MISSING_STORE_COUNT");
    }

    private void validatePricing() {
        String code = "SALE_SNAPSHOT_MISSING_TICKET_PRICE";
        requireObject(ticketPricingPolicy, code);
        if (!ticketPricingPolicy.has("pointsAllowed") || ticketPricingPolicy.path("pointsAllowed").asBoolean(UNPROVEN_FLAG_COUNTS_AS_TRUE)
                || !"NONE".equals(ticketPricingPolicy.path("discountMode").asText())) fail(code);
        requireArrayItems(ticketPricingPolicy.get("tickets"), code, "id", "price", "totalInventory");
    }

    private void validateSalesTarget() {
        if (salesTargetTotal == null) fail("SALE_SNAPSHOT_MISSING_SALES_TARGET");
        if (salesTargetTotal.longValue() <= 0) {
            fail("SALE_SNAPSHOT_INVALID_SALES_TARGET");
        }
    }

    private void validateSessionWindows() {
        String code = "SALE_SNAPSHOT_MISSING_SESSION_WINDOWS";
        requireObjectFields(sessionWindows, code, "startDate", "endDate", "saleEndAt");
        requireArrayItems(sessionWindows.get("tickets"), code,
                "id", "startTime", "endTime", "signupDeadline");
    }

    private void validateParticipationUnit() {
        String code = "SALE_SNAPSHOT_MISSING_PARTICIPATION_UNIT";
        requireObjectFields(participationUnitPolicy, code, "adultCount", "petMax", "childrenPolicy",
                "extraAdultPolicy", "companionPolicy", "maxTicketPerAccount");
        if (participationUnitPolicy.path("adultCount").asInt(0) <= 0
                || participationUnitPolicy.path("petMax").asInt(-1) < 0
                || participationUnitPolicy.path("maxTicketPerAccount").asInt(0) != 1) fail(code);
    }

    private void validateSessionVenues() {
        String code = "SALE_SNAPSHOT_MISSING_SESSION_VENUES";
        requireObjectFields(sessionVenues, code, "adultCapacity", "petCapacity", "authorization",
                "rainPlan", "evacuationPlan", "responsibleParty", "insuranceScope");
        requireArrayItems(sessionVenues.get("tickets"), code,
                "id", "meetingPoint", "gatherLng", "gatherLat");
    }

    private void validateMerchantSettlement() {
        String code = "SALE_SNAPSHOT_MISSING_MERCHANT_SETTLEMENT";
        requireObject(merchantCashSettlementMode, code);
        if (!merchantCashSettlementMode.has("merchantDepositRequired")
                || merchantCashSettlementMode.path("merchantDepositRequired").asBoolean(UNPROVEN_FLAG_COUNTS_AS_TRUE)) fail(code);
        JsonNode chapters = requireArrayItems(merchantCashSettlementMode.get("chapters"), code,
                "id", "termsMode");
        JsonNode offers = requireArrayItems(merchantCashSettlementMode.get("offers"), code,
                "id", "chapterId", "merchantId", "termsMode");
        for (JsonNode chapter : chapters) if (!"PERK".equals(chapter.path("termsMode").asText())) fail(code);
        for (JsonNode offer : offers) {
            if (!"PERK".equals(offer.path("termsMode").asText())) fail(code);
            JsonNode fee = offer.get("perHeadFee");
            if (fee != null && !fee.isNull() && fee.asDouble(0D) != 0D) fail(code);
        }
    }

    private void validateStoreCluster() {
        String code = "SALE_SNAPSHOT_MISSING_STORE_CLUSTER";
        JsonNode stores = requireArrayItems(storeCluster, code,
                "nodeId", "chapterId", "merchantMemberId", "address", "longitude", "latitude");
        if (stores.size() != 4) fail(code);
    }

    private void validateMicroExperiences(Integer productType) {
        String code = "SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES";
        requireObject(microExperienceCatalog, code);
        // 两种产品都支持故事流；开售快照必须带已物化 blocks，不能依赖客户端现算。
        String[] chapterFields = new String[]{"id", "required", "description", "blocksJson"};
        JsonNode chapters = requireArrayItems(microExperienceCatalog.get("chapters"), code, chapterFields);
        JsonNode offers = requireArrayItems(microExperienceCatalog.get("offers"), code,
                "id", "chapterId", "merchantId", "perkId");
        JsonNode perks = requireArrayItems(microExperienceCatalog.get("perks"), code,
                "id", "name", "perkType", "retailValue", "redeemRule");
        if (requiredChapterCount(chapters) != 4 || offers.size() != 4 || perks.size() != 4) fail(code);
    }

    private void validateMerchantWork() {
        String code = "SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE";
        requireObject(merchantWorkPackage, code);
        JsonNode chapters = requireArrayItems(merchantWorkPackage.get("chapters"), code, "id", "required");
        JsonNode workItems = requireArrayItems(merchantWorkPackage.get("workItems"), code, "chapterId", "roleNeed",
                "effortStaffMinutes", "effortVenue", "effortMaterials", "effortAvailableSlots",
                "effortQualifications");
        if (requiredChapterCount(chapters) != 4 || requiredWorkItemCount(chapters, workItems) != 4) fail(code);
    }

    /** 平台序章可作为 required=0 的额外章节；四个商家角色合同仍固定为 required=1。 */
    private int requiredChapterCount(JsonNode chapters) {
        int count = 0;
        for (JsonNode chapter : chapters) {
            if (chapter.path("required").asInt(0) == 1) count++;
        }
        return count;
    }

    private int requiredWorkItemCount(JsonNode chapters, JsonNode workItems) {
        Set<Long> requiredChapterIds = new HashSet<Long>();
        for (JsonNode chapter : chapters) {
            if (chapter.path("required").asInt(0) == 1) {
                requiredChapterIds.add(chapter.path("id").asLong());
            }
        }
        Set<Long> coveredChapterIds = new HashSet<Long>();
        for (JsonNode workItem : workItems) {
            long chapterId = workItem.path("chapterId").asLong();
            if (requiredChapterIds.contains(chapterId)) coveredChapterIds.add(chapterId);
        }
        return coveredChapterIds.size();
    }

    private void validateClubWork() { validateClubWork(false); }

    private void validateClubWork(boolean freeExploration) {
        String code = "SALE_SNAPSHOT_MISSING_CLUB_WORK_PACKAGE";
        requireObjectFields(clubWorkPackage, code, "opening", "patrol", "photoWrap", "closing",
                "community", "HPlan", "contractRef");
        JsonNode actor = clubWorkPackage.get("organizerActor");
        requireObjectFields(actor, "SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR",
                "roleId", "actorType", "declaredBy", "declaredAt");
        String actorType = actor.path("actorType").asText();
        if (actor.path("roleId").asLong(0L) <= 0L || actor.path("declaredBy").asLong(0L) <= 0L
                || actor.path("declaredAt").asLong(0L) <= 0L) {
            fail("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR");
        }
        JsonNode actorId = actor.get("actorId");
        if ("PLATFORM".equals(actorType)) {
            if (actorId != null && !actorId.isNull()) fail("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR");
        } else if (actorId == null || !actorId.canConvertToLong() || actorId.longValue() <= 0L) {
            fail("SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR");
        }
        JsonNode organizers = requireArrayItems(clubWorkPackage.get("organizers"), freeExploration, code,
                "subjectType", "subjectId", "role", "scopeType", "scopeId");
        if (freeExploration ? organizers.size() > 1 : organizers.size() != 1) fail(code);
    }

    private void validateContentRights() {
        requireObjectFields(contentRightsPackage, "SALE_SNAPSHOT_MISSING_CONTENT_RIGHTS", "assetRef", "owner",
                "licensedEntity", "channels", "validFrom", "validTo", "editingRight",
                "humanPortraitConsent", "petPortraitConsent");
    }

    private void validateCompletion() {
        String code = "SALE_SNAPSHOT_MISSING_COMPLETION_POLICY";
        requireObjectFields(completionPolicy, code, "evidenceRule");
        JsonNode ids = requireArrayItems(completionPolicy.get("requiredChapterIds"), code);
        if (ids.size() != 4) fail(code);
    }

    private void validateRelationship() {
        requireObjectFields(relationshipPolicy, "SALE_SNAPSHOT_MISSING_RELATIONSHIP_POLICY",
                "platformTransaction", "clubCommunityOptIn", "forcedFollow", "forcedGroupJoin",
                "merchantContactDisclosure");
    }

    private void validateRefundReschedule() {
        String code = "SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY";
        requireObjectFields(refundReschedulePolicy, code, "rescheduleDeadline", "rescheduleLimit",
                "cancellationPolicy", "merchantAbsencePolicy", "noShowPolicy", "crossEditionTransferAllowed");
        if (refundReschedulePolicy.path("crossEditionTransferAllowed").asBoolean(UNPROVEN_FLAG_COUNTS_AS_TRUE)) fail(code);
        requireArrayItems(refundReschedulePolicy.get("ticketPolicies"), code,
                "id", "refundRule", "signupDeadline");
    }

    /** 普通自由探索按真实章、店、供给验证，不套用四店探店日的固定合同。 */
    private void validateFreeExploration() {
        validateTopLevel(true);
        validatePricing();
        validateSalesTarget();
        requireObjectFields(sessionWindows, "SALE_SNAPSHOT_MISSING_SESSION_WINDOWS", "startDate", "endDate");
        requireArrayItems(sessionWindows.get("tickets"), "SALE_SNAPSHOT_MISSING_SESSION_WINDOWS",
                "id", "startTime", "endTime");
        validateParticipationUnit();
        requireObjectFields(sessionVenues, "SALE_SNAPSHOT_MISSING_SESSION_VENUES", "adultCapacity", "petCapacity",
                "authorization", "rainPlan", "evacuationPlan", "responsibleParty", "insuranceScope");
        requireArrayItems(sessionVenues.get("tickets"), "SALE_SNAPSHOT_MISSING_SESSION_VENUES", "id");
        String settlementCode = "SALE_SNAPSHOT_MISSING_MERCHANT_SETTLEMENT";
        requireObject(merchantCashSettlementMode, settlementCode);
        if (!merchantCashSettlementMode.has("merchantDepositRequired")
                || merchantCashSettlementMode.path("merchantDepositRequired").asBoolean(UNPROVEN_FLAG_COUNTS_AS_TRUE)) fail(settlementCode);
        requireArrayItems(merchantCashSettlementMode.get("chapters"), settlementCode, "id", "termsMode");
        JsonNode offers = requireArrayItems(merchantCashSettlementMode.get("offers"), true, settlementCode,
                "id", "chapterId", "merchantId", "termsMode");
        for (JsonNode offer : offers) {
            String mode = offer.path("termsMode").asText();
            if ("REVSHARE".equals(mode)) {
                if (!"CLUB_DIRECT".equals(offer.path("paymentRoute").asText())
                        || !offer.path("payerClubId").canConvertToLong() || offer.path("payerClubId").asLong() <= 0
                        || !offer.hasNonNull("perHeadFee")
                        || !offer.path("perHeadFee").isValueNode()) fail(settlementCode);
                try {
                    if (new java.math.BigDecimal(offer.path("perHeadFee").asText()).signum() < 0) fail(settlementCode);
                } catch (NumberFormatException invalidFee) { fail(settlementCode); }
            } else {
                if (!"PERK".equals(mode) && !"TRAFFIC".equals(mode)) fail(settlementCode);
                if (offer.hasNonNull("perHeadFee") && offer.path("perHeadFee").asDouble() != 0D) fail(settlementCode);
                if (offer.hasNonNull("payerClubId")) fail(settlementCode);
            }
        }
        String storesCode = "SALE_SNAPSHOT_MISSING_STORE_CLUSTER";
        JsonNode stores = requireArrayItems(storeCluster, true, storesCode,
                "nodeId", "chapterId", "merchantMemberId", "address", "longitude", "latitude");
        Set<Long> storeMerchants = new HashSet<Long>();
        for (JsonNode store : stores) storeMerchants.add(store.path("merchantMemberId").asLong());
        Set<Long> offerMerchants = new HashSet<Long>();
        for (JsonNode offer : offers) offerMerchants.add(offer.path("merchantId").asLong());
        if (storeCount.intValue() != storeMerchants.size() || !storeMerchants.equals(offerMerchants)) fail(storesCode);
        String experienceCode = "SALE_SNAPSHOT_MISSING_MICRO_EXPERIENCES";
        requireObject(microExperienceCatalog, experienceCode);
        JsonNode chapters = requireArrayItems(microExperienceCatalog.get("chapters"), experienceCode,
                "id", "required", "description", "blocksJson");
        JsonNode experienceOffers = requireArrayItems(microExperienceCatalog.get("offers"), true, experienceCode,
                "id", "chapterId", "merchantId");
        if (!offers.equals(experienceOffers)) fail(experienceCode);
        JsonNode perks = requireArrayItems(microExperienceCatalog.get("perks"), true, experienceCode,
                "id", "name", "perkType", "retailValue", "redeemRule");
        Set<Long> chapterIds = new HashSet<Long>();
        Set<Long> requiredIds = new HashSet<Long>();
        for (JsonNode chapter : chapters) {
            if (!chapterIds.add(chapter.path("id").asLong())) fail(experienceCode);
            if (chapter.path("required").asInt() == 1) requiredIds.add(chapter.path("id").asLong());
        }
        Set<Long> perkIds = new HashSet<Long>();
        for (JsonNode perk : perks) perkIds.add(perk.path("id").asLong());
        for (JsonNode offer : offers) {
            if (!chapterIds.contains(offer.path("chapterId").asLong())) fail(experienceCode);
            if ("PERK".equals(offer.path("termsMode").asText())
                    && (!offer.path("perkId").canConvertToLong()
                        || !perkIds.contains(offer.path("perkId").asLong()))) fail(experienceCode);
        }
        String workCode = "SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE";
        requireObject(merchantWorkPackage, workCode);
        requireArrayItems(merchantWorkPackage.get("chapters"), workCode, "id", "required");
        JsonNode work = requireArrayItems(merchantWorkPackage.get("workItems"), true, workCode,
                "chapterId", "roleNeed", "effortStaffMinutes", "effortVenue", "effortMaterials",
                "effortAvailableSlots", "effortQualifications");
        for (JsonNode item : work) if (!chapterIds.contains(item.path("chapterId").asLong())) fail(workCode);
        validateClubWork(true);
        validateContentRights();
        String completionCode = "SALE_SNAPSHOT_MISSING_COMPLETION_POLICY";
        requireObjectFields(completionPolicy, completionCode, "evidenceRule");
        JsonNode completed = requireArrayItems(completionPolicy.get("requiredChapterIds"), true, completionCode);
        Set<Long> completedIds = new HashSet<Long>();
        for (JsonNode id : completed) if (!id.canConvertToLong() || !completedIds.add(id.asLong())) fail(completionCode);
        if (!completedIds.equals(requiredIds)) fail(completionCode);
        validateRelationship();
        String refundCode = "SALE_SNAPSHOT_MISSING_REFUND_RESCHEDULE_POLICY";
        requireObjectFields(refundReschedulePolicy, refundCode, "rescheduleDeadline", "rescheduleLimit",
                "cancellationPolicy", "merchantAbsencePolicy", "noShowPolicy", "crossEditionTransferAllowed");
        if (refundReschedulePolicy.path("crossEditionTransferAllowed").asBoolean(UNPROVEN_FLAG_COUNTS_AS_TRUE)) fail(refundCode);
        requireArrayItems(refundReschedulePolicy.get("ticketPolicies"), refundCode, "id", "refundRule");
    }

    private static JsonNode requireArrayItems(JsonNode value, boolean allowEmpty, String code, String... fields) {
        if (allowEmpty && value != null && value.isArray() && value.size() == 0) return value;
        return requireArrayItems(value, code, fields);
    }

    private static void requireObject(JsonNode value, String code) {
        if (value == null || !value.isObject() || value.size() == 0) fail(code);
    }

    private static void requireObjectFields(JsonNode value, String code, String... fields) {
        requireObject(value, code);
        for (String field : fields) if (missing(value.get(field))) fail(code);
    }

    private static JsonNode requireArrayItems(JsonNode value, String code, String... fields) {
        if (value == null || !value.isArray() || value.size() == 0) fail(code);
        for (JsonNode item : value) {
            if (item == null || item.isNull()) fail(code);
            for (String field : fields) if (missing(item.get(field))) fail(code);
        }
        return value;
    }

    private static boolean missing(JsonNode value) {
        return value == null || value.isNull()
                || (value.isTextual() && value.asText().trim().isEmpty())
                || ((value.isArray() || value.isObject()) && value.size() == 0);
    }

    private static void fail(String code) { throw new IllegalStateException(code); }

    public Integer getStoreCount() { return storeCount; }
    public void setStoreCount(Integer storeCount) { this.storeCount = storeCount; }
    public ObjectNode getTicketPricingPolicy() { return ticketPricingPolicy; }
    public void setTicketPricingPolicy(ObjectNode ticketPricingPolicy) { this.ticketPricingPolicy = ticketPricingPolicy; }
    public Long getSalesTargetTotal() { return salesTargetTotal; }
    public void setSalesTargetTotal(Long salesTargetTotal) { this.salesTargetTotal = salesTargetTotal; }
    public ObjectNode getSessionWindows() { return sessionWindows; }
    public void setSessionWindows(ObjectNode sessionWindows) { this.sessionWindows = sessionWindows; }
    public ObjectNode getParticipationUnitPolicy() { return participationUnitPolicy; }
    public void setParticipationUnitPolicy(ObjectNode participationUnitPolicy) { this.participationUnitPolicy = participationUnitPolicy; }
    public ObjectNode getSessionVenues() { return sessionVenues; }
    public void setSessionVenues(ObjectNode sessionVenues) { this.sessionVenues = sessionVenues; }
    public ObjectNode getMerchantCashSettlementMode() { return merchantCashSettlementMode; }
    public void setMerchantCashSettlementMode(ObjectNode merchantCashSettlementMode) { this.merchantCashSettlementMode = merchantCashSettlementMode; }
    public ArrayNode getStoreCluster() { return storeCluster; }
    public void setStoreCluster(ArrayNode storeCluster) { this.storeCluster = storeCluster; }
    public ObjectNode getMicroExperienceCatalog() { return microExperienceCatalog; }
    public void setMicroExperienceCatalog(ObjectNode microExperienceCatalog) { this.microExperienceCatalog = microExperienceCatalog; }
    public ObjectNode getMerchantWorkPackage() { return merchantWorkPackage; }
    public void setMerchantWorkPackage(ObjectNode merchantWorkPackage) { this.merchantWorkPackage = merchantWorkPackage; }
    public ObjectNode getClubWorkPackage() { return clubWorkPackage; }
    public void setClubWorkPackage(ObjectNode clubWorkPackage) { this.clubWorkPackage = clubWorkPackage; }
    public ObjectNode getContentRightsPackage() { return contentRightsPackage; }
    public void setContentRightsPackage(ObjectNode contentRightsPackage) { this.contentRightsPackage = contentRightsPackage; }
    public ObjectNode getCompletionPolicy() { return completionPolicy; }
    public void setCompletionPolicy(ObjectNode completionPolicy) { this.completionPolicy = completionPolicy; }
    public ObjectNode getRelationshipPolicy() { return relationshipPolicy; }
    public void setRelationshipPolicy(ObjectNode relationshipPolicy) { this.relationshipPolicy = relationshipPolicy; }
    public ObjectNode getRefundReschedulePolicy() { return refundReschedulePolicy; }
    public void setRefundReschedulePolicy(ObjectNode refundReschedulePolicy) { this.refundReschedulePolicy = refundReschedulePolicy; }
}
