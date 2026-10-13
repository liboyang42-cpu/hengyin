package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.support.CircleThemePublishPolicy;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 圈层城市实例发布闸：8 个候选不是 8 店打卡，实例只启用互补的 3—4 个。 */
class CircleThemePublishPolicyTest {

    @Test
    void ordinaryTopicKeepsExistingPublishRules() {
        assertDoesNotThrow(() -> CircleThemePublishPolicy.assertPublishable(new CmsTopic(), Collections.emptyList()));
    }

    @Test
    void circleInstanceRejectsFewerThanThreeOrMoreThanFourEnabledCandidates() {
        CmsTopic topic = circleTopic();

        IllegalStateException tooFew = assertThrows(IllegalStateException.class,
                () -> CircleThemePublishPolicy.assertPublishable(topic, Arrays.asList(
                        chapter("A1", "EXPERIENCE", 1), chapter("A2", "REST", 1))));
        assertEquals("圈层城市实例必须启用3—4个供给角色", tooFew.getMessage());

        IllegalStateException tooMany = assertThrows(IllegalStateException.class,
                () -> CircleThemePublishPolicy.assertPublishable(topic, Arrays.asList(
                        chapter("A1", "EXPERIENCE", 1), chapter("A2", "REST", 1),
                        chapter("A3", "EXPRESSION", 1), chapter("A4", "EXPERIENCE", 1),
                        chapter("A5", "REST", 1))));
        assertEquals("圈层城市实例必须启用3—4个供给角色", tooMany.getMessage());
    }

    @Test
    void circleInstanceRejectsDuplicateCandidateAndMissingPurpose() {
        CmsTopic topic = circleTopic();
        List<CmsTopicChapter> duplicate = Arrays.asList(
                chapter("A1", "EXPERIENCE", 1), chapter("A1", "REST", 1),
                chapter("A3", "EXPRESSION", 1));
        assertEquals("同一供给角色候选不能重复启用", assertThrows(IllegalStateException.class,
                () -> CircleThemePublishPolicy.assertPublishable(topic, duplicate)).getMessage());

        List<CmsTopicChapter> missingRole = Arrays.asList(
                chapter("A1", "EXPERIENCE", 1), chapter("A2", "EXPERIENCE", 1),
                chapter("A3", "REST", 1));
        assertEquals("圈层城市实例必须同时覆盖主体验、补给/休息、社交/表达",
                assertThrows(IllegalStateException.class,
                        () -> CircleThemePublishPolicy.assertPublishable(topic, missingRole)).getMessage());
    }

    @Test
    void disabledCandidatesDoNotCountAndThreeComplementaryCandidatesPass() {
        CmsTopic topic = circleTopic();
        List<CmsTopicChapter> chapters = Arrays.asList(
                chapter("A1", "EXPERIENCE", 1), chapter("A2", "REST", 1),
                chapter("A3", "EXPRESSION", 1), chapter("A4", "EXPERIENCE", 0),
                chapter("A5", "REST", 0));

        assertDoesNotThrow(() -> CircleThemePublishPolicy.assertPublishable(topic, chapters));
    }

    @Test
    void everyEnabledCandidateNeedsExactlyOneEffectiveSupplyFromADifferentMerchant() {
        CmsTopic topic = circleTopic();
        List<CmsTopicChapter> chapters = Arrays.asList(
                chapter("A1", "EXPERIENCE", 1), chapter("A2", "REST", 1),
                chapter("A3", "EXPRESSION", 1));
        assertThrows(IllegalStateException.class,
                () -> CircleThemePublishPolicy.assertSupplied(topic, chapters, Collections.emptyList()));

        List<MerchantCircleSupplyProfile> supplies = Arrays.asList(
                supply("A1", 11L), supply("A2", 12L), supply("A3", 13L));
        assertDoesNotThrow(() -> CircleThemePublishPolicy.assertSupplied(topic, chapters, supplies));

        supplies.get(2).setMerchantId(12L);
        assertThrows(IllegalStateException.class,
                () -> CircleThemePublishPolicy.assertSupplied(topic, chapters, supplies));
    }

    private static CmsTopic circleTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setCircleThemeCode("AFTER_WORK");
        return topic;
    }

    private static CmsTopicChapter chapter(String code, String role, int enabled) {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setCircleCandidateCode(code);
        chapter.setCircleRole(role);
        chapter.setCircleEnabled(enabled);
        return chapter;
    }

    private static MerchantCircleSupplyProfile supply(String code, Long merchantId) {
        MerchantCircleSupplyProfile supply = new MerchantCircleSupplyProfile();
        supply.setCandidateCode(code);
        supply.setMerchantId(merchantId);
        return supply;
    }
}
