package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 守住主题生命周期的真实写入口，防止后续又退回散落 setLifecycle。 */
class TopicLifecycleWiringTest {

    private static final String TOPIC_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java";
    private static final String PRICING_SERVICE =
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PricingServiceImpl.java";

    @Test
    void recruitDeadlineUsesCentralTransitionAndNormalizedLineupInsteadOfEntityPatch() throws Exception {
        String source = WiringSource.read(TOPIC_SERVICE).replaceAll("\\s+", "");
        String method = between(source, "publicintprocessRecruitDeadlines()", "publicLongcopyCmsTopic(");

        assertTrue(method.contains("topicLifecycleService.transition(topic.getId(),TopicLifecycleState.RECRUITING,TopicLifecycleState.PRICING)"));
        assertTrue(method.contains("transitionRecruitingToDraft(topic.getId())"));
        assertFalse(method.contains("selectCoopInviteList("), "invite 接受回执不得再充当 AcceptedLineup");
        assertFalse(method.contains("setLifecycle("), "招募截止不得重新引入实体补丁式生命周期写入");
    }

    @Test
    void recruitDeadlineLocksAndTerminatesPendingBeforeReadingLineup() throws Exception {
        String source = WiringSource.read(TOPIC_SERVICE).replaceAll("\\s+", "");
        String method = between(source, "publicintprocessRecruitDeadlines()", "publicLongcopyCmsTopic(");

        int lock = method.indexOf("selectCmsTopicByIdForUpdate(topic.getId())");
        int terminate = method.indexOf("terminatePendingUnderTopicLock(topic.getId(),now)");
        int lineup = method.indexOf("coopOrderLineupSource.readEffectiveLineup(topic.getId())");
        int transition = method.indexOf("topicLifecycleService.transition(topic.getId(),TopicLifecycleState.RECRUITING,TopicLifecycleState.PRICING)");
        assertTrue(method.contains("transactionTemplate.execute("), "每个到期主题必须在独立事务内处理");
        assertTrue(lock >= 0 && lock < terminate && terminate < lineup && lineup < transition,
                "必须先锁主题行、终结 pending，再读取规范化阵容并迁移状态");
    }

    @Test
    void confirmPriceStopsAtPricingForExplicitCanonicalSubmission() throws Exception {
        String source = WiringSource.read(PRICING_SERVICE).replaceAll("\\s+", "");
        String method = between(source, "publicintconfirmPrice(", "privatestaticBigDecimalnz(");

        assertFalse(method.contains("submitSaleReview("), "锁价不得拿客户端或 live 参数自动提审");
        assertFalse(method.contains("finalizeForSale("), "锁价不得绕过 canonical15 显式提审直接开售");
        assertTrue(method.contains("canonical15含人工责任/授权事实"));
        assertFalse(method.contains("setLifecycle("), "终价确认不得重新引入实体补丁式生命周期写入");
    }

    @Test
    void topicCreationCannotWriteRecruitingOrdinalAroundCommercialProjectReview() throws Exception {
        String source = WiringSource.read(TOPIC_SERVICE).replaceAll("\\s+", "");
        String method = between(source, "publicLongcreateTopic(TopicCreateDTOdto,", "publicLongupdateTopicByOwner(");

        assertFalse(method.contains("topic.setLifecycle(1)"), "创建主链不得直接写 DRAFT→RECRUITING ordinal");
        assertTrue(method.contains("topicCommercialReviewService.submitProjectReview(topicId,memberId,TopicLifecycleState.RECRUITING)"),
                "章节/票落库后必须通过商业立项审 seam 决定直通或 PENDING_REVIEW");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start, "生命周期接线测试找不到目标方法");
        return source.substring(start, end);
    }
}
