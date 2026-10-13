package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.common.constant.CoopTermsAxes;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** W2 合同三两轴映射、快照判据与闸1边界负控。 */
class W2TermsAxesContractTest {

    @Test
    void publishedPendingReviewCanRecruitWithoutOpeningUnpublishedOrClosedStages() {
        Date now = new Date();
        Date deadline = new Date(now.getTime() + 10000);
        RecruitmentWindowPolicy.assertOpen(7, 1, 1, deadline, now);
        assertThrows(IllegalStateException.class, () -> RecruitmentWindowPolicy.assertOpen(7, 0, 1, deadline, now));
        assertThrows(IllegalStateException.class, () -> RecruitmentWindowPolicy.assertOpen(7, 1, 0, deadline, now));
        assertThrows(IllegalStateException.class, () -> RecruitmentWindowPolicy.assertOpen(7, 1, 1, now, now));
        assertThrows(IllegalStateException.class, () -> RecruitmentWindowPolicy.assertOpen(7, 1, 1, null, now));
        for (int state : new int[] {0, 3, 4, 5, 6, 999}) {
            assertThrows(IllegalStateException.class, () -> RecruitmentWindowPolicy.assertOpen(state, 1, 1, deadline, now));
        }
    }

    @Test
    void commonRecruitmentWindowCodesStayLockedToLifecycleTruth() {
        assertEquals(TopicLifecycleState.RECRUITING.getCode(),
                RecruitmentWindowPolicy.RECRUITING_LIFECYCLE);
        assertEquals(TopicLifecycleState.PRICING.getCode(),
                RecruitmentWindowPolicy.PRICING_LIFECYCLE);
        for (TopicLifecycleState state : TopicLifecycleState.values()) {
            if (state == TopicLifecycleState.RECRUITING || state == TopicLifecycleState.PRICING) continue;
            assertThrows(IllegalStateException.class,
                    () -> RecruitmentWindowPolicy.assertOpen(state.getCode(), future(), new Date()));
        }
        assertThrows(IllegalStateException.class,
                () -> RecruitmentWindowPolicy.assertOpen(999, future(), new Date()));
    }

    @Test
    void chapterTermsMapToFrozenPair() {
        assertPair(TermsMode.TRAFFIC, "NONE", "NONE");
        assertPair(TermsMode.PERK, "NONE", "PERK");
        assertPair(TermsMode.REVSHARE, "FIXED_PER_FULFILLMENT", "NONE");
    }

    @Test
    void orderShareModeUsesSnapshotAsOnlyPerk判据() {
        CoopTermsAxes.Value noSnapshot = CoopTermsAxes.fromOrder(0, "[]");
        assertEquals("NONE", noSnapshot.getCompensationMode());
        assertEquals("NONE", noSnapshot.getBenefitMode(),
                "shareMode=0 无冻结权益快照不得被页面语境映射成 PERK");

        CoopTermsAxes.Value valid = CoopTermsAxes.fromOrder(0,
                "[{\"retailValue\":88,\"quota\":20}]");
        assertEquals("PERK", valid.getBenefitMode(), "只有有效冻结权益快照才允许进入 PERK");
        assertEquals("SALES_PERCENT", CoopTermsAxes.fromOrder(1, null).getCompensationMode());
        assertEquals("FIXED_PER_FULFILLMENT", CoopTermsAxes.fromOrder(2, null).getCompensationMode());
    }

    @Test
    void malformedPerkSnapshotFailsClosed() {
        assertFalse(CoopTermsAxes.isValidPerksSnapshot("not-json"));
        assertFalse(CoopTermsAxes.isValidPerksSnapshot("[{\"retailValue\":88,\"quota\":0}]"));
        assertFalse(CoopTermsAxes.isValidPerksSnapshot("[{\"quota\":20}]"));
    }

    @Test
    void legacyOrderWithoutContractSnapshotIsNotInferredAtReadTime() {
        CoopOrder legacy = new CoopOrder();
        legacy.setShareMode(1);
        legacy.setPerksSnapshot("[{\"retailValue\":88,\"quota\":20}]");
        assertNull(legacy.getCompensationMode(), "缺合同快照的存量合作单必须进入人工修复清单");
        assertNull(legacy.getBenefitMode(), "缺合同快照的存量合作单不得按旧字段运行时推断");
    }

    @Test
    void recruitmentGateRejectsSelling() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RecruitmentWindowPolicy.assertOpen(3, future(), new Date()));
        assertEquals("招商写入已关闭：主题不在招商期", error.getMessage());
    }

    @Test
    void recruitmentGateRejectsDraftPendingAndUnknown() {
        for (Integer lifecycle : new Integer[]{0, 7, null, 99}) {
            assertThrows(IllegalStateException.class,
                    () -> RecruitmentWindowPolicy.assertOpen(lifecycle, future(), new Date()));
        }
    }

    @Test
    void recruitmentGateRejectsMissingDeadline() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RecruitmentWindowPolicy.assertOpen(1, null, new Date()));
        assertEquals("招商写入已关闭：recruitDeadline 未配置", error.getMessage());
    }

    @Test
    void recruitmentGateRejectsEqualDeadline() {
        Date now = new Date();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RecruitmentWindowPolicy.assertOpen(1, now, now));
        assertEquals("招商写入已关闭：now >= recruitDeadline", error.getMessage());
    }

    @Test
    void recruitmentGateAllowsBeforeDeadline() {
        Date now = new Date();
        RecruitmentWindowPolicy.assertOpen(1, new Date(now.getTime() + 1_000), now);
    }

    private Date future() { return new Date(System.currentTimeMillis() + 60_000); }

    private void assertPair(String termsMode, String compensation, String benefit) {
        CoopTermsAxes.Value value = CoopTermsAxes.fromTermsMode(termsMode);
        assertEquals(compensation, value.getCompensationMode());
        assertEquals(benefit, value.getBenefitMode());
    }
}
