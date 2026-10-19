package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.snapshot.TopicSaleCanonicalV1;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentCaptureBuilderTest {
    private final ContentCaptureBuilder builder=new ContentCaptureBuilder();

    @Test void capturesOrderFullStateAndChapterSourcesWithoutInventingApproval() throws Exception {
        ContentCaptureRows r=rows(); Collections.reverse(r.nodes);
        JsonNode root=json(builder.build(r));
        assertEquals(1,root.at("/components/orderedNodes/content/orderedNodeIds/0").asInt());
        assertEquals("flag",root.at("/components/stateKeyDeclarations/content/0/keyType").asText());
        assertEquals("run",root.at("/components/stateKeyDeclarations/content/0/scope").asText());
        assertEquals(3,root.at("/components/chapterSources/content").size());
        assertFalse(root.path("completeRelease").asBoolean());
        assertEquals("MISSING",root.at("/components/commercialRightsEvidence/status").asText());
    }
    @Test void rejectsEqualSortWithoutInventingIdTieBreak() { ContentCaptureRows r=rows(); r.nodes.get(1).setSortId(1L); reject(r); }
    @Test void rejectsNullAndZeroSortAmbiguity() { ContentCaptureRows r=rows(); r.nodes.get(0).setSortId(null); r.nodes.get(1).setSortId(0L); reject(r); }
    @Test void retainsFutureStateScopesWithoutClaimingRuntimeSupport() throws Exception {
        ContentCaptureRows r=rows(); r.keys.get(0).setScope("party");
        assertEquals("party",json(builder.build(r)).at("/components/stateKeyDeclarations/content/0/scope").asText());
        assertTrue(builder.build(r).serverOnlyJson().contains("REGISTERED_RULE_REFERENCES"));
    }
    @Test void rejectsCrossTopicNode() { ContentCaptureRows r=rows(); r.nodes.get(0).setTopicId(99L); reject(r); }
    @Test void rejectsCrossTopicChapter() { ContentCaptureRows r=rows(); r.chapters.get(0).setTopicId(99L); reject(r); }
    @Test void rejectsCrossTopicStateKey() { ContentCaptureRows r=rows(); r.keys.get(0).setTopicId(99L); reject(r); }
    @Test void rejectsMissingTemplateAndChapter() {
        ContentCaptureRows r=rows(); r.templates.clear(); reject(r);
        r=rows(); r.nodes.get(0).setChapterId(90L); reject(r);
    }
    @Test void rejectsDanglingUnlockAndDuplicateNodeOrKey() {
        ContentCaptureRows r=rows(); r.nodes.get(0).setUnlockAfterNodeId(99L); reject(r);
        r=rows(); r.nodes.add(r.nodes.get(0)); reject(r);
        r=rows(); r.keys.add(r.keys.get(0)); reject(r);
    }
    @Test void rejectsUnreferencedAndWrongIdentityTemplate() {
        ContentCaptureRows r=rows(); CmsMemberTemplate extra=new CmsMemberTemplate(); extra.setId(999L); r.templates.put(999L,extra); reject(r);
        r=rows(); r.templates.get(101L).setId(999L); reject(r);
    }
    @Test void rejectsMalformedDataInsteadOfCertifyingLegacyFallback() {
        ContentCaptureRows r=rows(); r.templates.get(101L).setAdvancedConfigJson("{"); reject(r);
        r=rows(); r.chapters.get(0).setBlocksJson("{"); reject(r);
        r=rows(); r.topic.setJourneyStoryJson("{} {}"); reject(r);
    }
    @Test void rejectsFractionalConditionalCouponAndDuplicateJsonKey() {
        ContentCaptureRows r=rows(); r.templates.get(101L).setRewardRulesJson("[{\"outcomeCode\":\"HIT\",\"couponId\":1.5,\"xp\":1}]"); reject(r);
        r=rows(); r.templates.get(101L).setAdvancedConfigJson("{\"x\":1,\"x\":2}"); reject(r);
    }
    @Test void rejectsOverflowingRewardXpBeforeLegacyNumericCoercion() {
        ContentCaptureRows r=rows(); r.templates.get(101L).setRewardRulesJson("[{\"outcomeCode\":\"HIT\",\"couponId\":51,\"xp\":4294967296}]"); reject(r);
    }
    @Test void zeroOriginalTemplateSentinelDoesNotBecomeSourceIdentity() throws Exception {
        ContentCaptureRows r=rows(); r.templates.get(101L).setOriginalTemplateId(0L);
        assertTrue(json(builder.build(r)).at("/components/sourceProvenance/content/templates/0/originalTemplateReference").isNull());
    }
    @Test void noCouponReferencesAreExplicitlyNotApplicableWithoutClearingRewardPolicy() throws Exception {
        JsonNode root=json(builder.build(rows()));
        assertEquals("NOT_APPLICABLE",root.at("/dependencyStatus/REFERENCED_COUPON_TERMS").asText());
        assertEquals("MISSING",root.at("/dependencyStatus/REWARD_POLICY_APPROVAL").asText());
    }

    @Test void freezesMerchantPrecedenceAndConditionalCouponSources() throws Exception {
        ContentCaptureRows r=rewardRows(); JsonNode root=json(builder.build(r));
        assertEquals("cms_registration_merchant:81",root.at("/components/rewardInputs/content/nodes/0/merchantOverride/sourceReference").asText());
        assertEquals(52,root.at("/components/rewardInputs/content/nodes/0/merchantOverride/couponId").asInt());
        assertEquals(51,root.at("/components/rewardInputs/content/nodes/0/template/couponId").asInt());
        assertEquals("POSITIVE_MERCHANT_OVERRIDE_THEN_TEMPLATE",root.at("/components/rewardInputs/content/nodes/0/couponPrecedence").asText());
        assertEquals(3,root.at("/components/rewardInputs/content/couponTerms").size());
        assertFalse(builder.build(r).serverOnlyJson().contains("receiveCount"));
    }
    @Test void missingCouponIsExplicitAndNeverIssuable() throws Exception {
        ContentCaptureRows r=rewardRows(); r.coupons.put(52L,null); ContentCaptureCandidate c=builder.build(r);
        assertTrue(c.serverOnlyJson().contains("MISSING_COUPON:52")); assertThrows(IllegalStateException.class,c::requireCompleteRelease);
    }
    @Test void rejectsCrossTopicMerchantAndMismatchedCouponIdentity() {
        ContentCaptureRows r=rewardRows(); r.merchants.get(1L).setTopicId(99L); reject(r);
        r=rewardRows(); r.coupons.get(51L).setId(99L); reject(r);
        r=rewardRows(); r.coupons.remove(53L); reject(r);
    }
    @Test void sourceLineageRemainsSeparateFromLicense() throws Exception {
        ContentCaptureRows r=rows(); r.topic.setSourceTemplateTopicId(90L); r.templates.get(101L).setOriginalTemplateId(44L);
        JsonNode source=json(builder.build(r)).at("/components/sourceProvenance/content");
        assertEquals("cms_topic:90",source.path("topicSourceReference").asText());
        assertEquals("cms_template_library:44",source.at("/templates/0/originalTemplateReference").asText());
        assertTrue(source.at("/templates/0/immutableSourceVersion").isNull());
        assertEquals("MISSING",source.at("/templates/0/licenseStatus").asText());
    }
    @Test void commercialEvidenceDoesNotClearAssetRightsOrGrantApproval() throws Exception {
        ContentCaptureRows r=rows(); r.sale=sale(); ContentCaptureCandidate c=builder.build(r); JsonNode root=json(c);
        assertEquals("FROZEN",root.at("/components/commercialRightsEvidence/status").asText());
        assertEquals(71,root.at("/components/commercialRightsEvidence/content/sourceReviewTaskId").asInt());
        assertFalse(root.at("/components/commercialRightsEvidence/content/rightsApprovalInferred").asBoolean());
        assertTrue(c.serverOnlyJson().contains("ASSET_RIGHTS")); assertFalse(c.isCompleteRelease());
    }
    @Test void rejectsCrossTopicCommercialEvidenceOrMissingPackage() {
        ContentCaptureRows r=rows(); r.sale=sale(); r.sale.setTopicId(99L); reject(r);
        r=rows(); r.sale=sale(); r.sale.setCanonicalJson("{}"); reject(r);
    }
    @Test void anyCapturedAnswerRewardEndingOrSourceMutationChangesHash() {
        String baseline=builder.build(rewardRows()).contentHash();
        ContentCaptureRows r=rewardRows(); r.templates.get(101L).setQuestionAnswer("new"); assertNotEquals(baseline,builder.build(r).contentHash());
        r=rewardRows(); r.templates.get(101L).setXpValue(77); assertNotEquals(baseline,builder.build(r).contentHash());
        r=rewardRows(); r.chapters.get(0).setBlocksJson("[{\"type\":\"text\",\"content\":\"new ending\"}]"); assertNotEquals(baseline,builder.build(r).contentHash());
        r=rewardRows(); r.templates.get(101L).setOriginalTemplateId(999L); assertNotEquals(baseline,builder.build(r).contentHash());
    }
    @Test void bingoOnlyCouponIsCapturedAndMissingTermsCannotBeNotApplicable() throws Exception {
        ContentCaptureRows r=rows(); r.topic.setCompleteRuleJson(bingo("54")); r.coupons.put(54L,null);
        JsonNode root=json(builder.build(r));
        assertEquals("MISSING",root.at("/dependencyStatus/REFERENCED_COUPON_TERMS").asText());
        assertTrue(root.path("unresolvedDependencies").toString().contains("MISSING_COUPON:54"));
        r.coupons.clear(); reject(r);
    }
    @Test void rejectsMalformedCompletionRulesAndFractionalOrOverflowingBingoIds() {
        ContentCaptureRows r=rows(); r.topic.setCompleteRuleJson("{"); reject(r);
        r=rows(); r.topic.setCompleteRuleJson(bingo("1.5")); reject(r);
        r=rows(); r.topic.setCompleteRuleJson(bingo("9223372036854775808")); reject(r);
        r=rows(); r.topic.setCompleteRuleJson("{\"bingo\":{\"enabled\":true,\"cells\":[]}}"); reject(r);
    }
    @Test void couponTypeAndVisibleTermsChangeHash() {
        ContentCaptureRows r=rewardRows(); String before=builder.build(r).contentHash();
        r.coupons.get(51L).setCouponType(2); assertNotEquals(before,builder.build(r).contentHash());
        r=rewardRows(); r.coupons.get(51L).setDescription("new terms"); assertNotEquals(before,builder.build(r).contentHash());
    }

    @Test void candidateDoesNotRetainMutableRowsAndDefaultSerializationHasNoSecrets() throws Exception {
        ContentCaptureRows r=rows(); ContentCaptureCandidate c=builder.build(r); String before=c.serverOnlyJson();
        r.nodes.clear(); r.templates.get(101L).setQuestionAnswer("mutation"); r.topic.setId(99L);
        assertEquals(before,c.serverOnlyJson()); assertEquals(10,c.topicId());
        assertFalse(CanonicalContentJson.JSON.writeValueAsString(c).contains("secret"));
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(c).contains("secret"));
    }

    private void reject(ContentCaptureRows r) { assertThrows(IllegalArgumentException.class,()->builder.build(r)); }
    private static JsonNode json(ContentCaptureCandidate c) throws Exception { return CanonicalContentJson.JSON.readTree(c.serverOnlyJson()); }
    static ContentCaptureRows rows() {
        JourneyContentSnapshotBuilderTest.Fixture f=JourneyContentSnapshotBuilderTest.fixture(false,true);
        ContentCaptureRows r=new ContentCaptureRows(); r.topic=f.topic; r.nodes=f.nodes; r.chapters=f.chapters; r.keys=f.keys; r.templates.putAll(f.templates); return r;
    }
    static ContentCaptureRows rewardRows() {
        ContentCaptureRows r=rows(); r.templates.get(101L).setCouponId(51L); r.templates.get(101L).setRewardRulesJson("[{\"outcomeCode\":\"HIT\",\"couponId\":53,\"xp\":3}]");
        CmsRegistrationMerchant m=new CmsRegistrationMerchant(); m.setId(81L); m.setTopicId(10L); m.setNodeId(1L); m.setMemberId(8L); m.setAuditStatus(1L); m.setDelFlag(0); m.setCouponId(52L); r.merchants.put(1L,m);
        for(long id:Arrays.asList(51L,52L,53L)) { SmsCoupon c=new SmsCoupon(); c.setId(id); c.setMemberId(id==52?8L:7L); c.setReceiveCount(100L); r.coupons.put(id,c); }
        return r;
    }
    static String bingo(String couponId) {
        return "{\"bingo\":{\"enabled\":true,\"cells\":[{\"couponId\":"+couponId+"},{},{},{},{},{},{},{},{}]}}";
    }
    static TopicSaleSnapshot sale() {
        TopicSaleSnapshot s=new TopicSaleSnapshot(); s.setId(61L); s.setTopicId(10L); s.setSourceReviewTaskId(71L); s.setSchemaVersion(TopicSaleCanonicalV1.SCHEMA_VERSION); s.setParameterVersion(3L);
        s.setCanonicalJson("{\"contentRightsPackage\":{\"assetRef\":\"synthetic\",\"owner\":\"test\",\"licensedEntity\":\"test\",\"channels\":[\"test\"],\"validFrom\":\"2026-01-01\",\"validTo\":\"2026-12-31\",\"editingRight\":false,\"humanPortraitConsent\":false,\"petPortraitConsent\":false}}"); return s;
    }
}
