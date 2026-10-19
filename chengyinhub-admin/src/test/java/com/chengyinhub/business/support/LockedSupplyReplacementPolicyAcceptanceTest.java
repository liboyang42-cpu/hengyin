package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.common.constant.TermsMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SC-EXIT-REPLACE-LOCKED 验收:锁价后商家退出,补位供给只能<b>接续</b>原供给 ——
 * 冻结的计酬、条款模式、玩法边界(核销方式 / 节点经验上限)、有效期、PERK 剩余份数都不得被削弱或抬高。
 */
class LockedSupplyReplacementPolicyAcceptanceTest {

    private static final String TERMS_MISMATCH = "补位须沿用原供给条款、计酬和玩法边界";
    private static final long DAY = 24L * 3600 * 1000;
    private static final Date ORIGINAL_END = new Date(System.currentTimeMillis() + 30 * DAY);

    private static MerchantChapterOffer original(String mode) {
        MerchantChapterOffer o = new MerchantChapterOffer();
        o.setId(800L);
        o.setTopicId(100L);
        o.setChapterId(9L);
        o.setTermsMode(mode);
        o.setPerHeadFee(new BigDecimal("12.50"));
        o.setAllowedValidationMethods("1,2");
        o.setMaxNodeXp(30);
        o.setValidEnd(ORIGINAL_END);
        o.setQuotaTotal(50);
        o.setQuotaUsed(20);
        return o;
    }

    /** 与原供给完全兼容的补位(费用写法不同但数值相等)。 */
    private static MerchantChapterOffer compatible(MerchantChapterOffer o) {
        MerchantChapterOffer r = new MerchantChapterOffer();
        r.setId(801L);
        r.setTopicId(o.getTopicId());
        r.setChapterId(o.getChapterId());
        r.setTermsMode(o.getTermsMode());
        r.setPerHeadFee(new BigDecimal("12.5"));
        r.setAllowedValidationMethods(o.getAllowedValidationMethods());
        r.setMaxNodeXp(o.getMaxNodeXp());
        r.setValidEnd(new Date(ORIGINAL_END.getTime() + DAY));
        r.setQuotaTotal(30);   // = 原剩余 50-20
        return r;
    }

    private static void assertRefused(MerchantChapterOffer o, MerchantChapterOffer r, String msg) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OrdinarySupplyReplacementPolicy.assertCompatible(o, r));
        assertEquals(msg, ex.getMessage());
    }

    @Test
    void compatibleReplacementPasses() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        assertDoesNotThrow(() -> OrdinarySupplyReplacementPolicy.assertCompatible(o, compatible(o)));
        MerchantChapterOffer traffic = original(TermsMode.TRAFFIC);
        MerchantChapterOffer sameEnd = compatible(traffic);
        sameEnd.setValidEnd(ORIGINAL_END);
        sameEnd.setQuotaTotal(0);   // 非 PERK 不看份数
        assertDoesNotThrow(() -> OrdinarySupplyReplacementPolicy.assertCompatible(traffic, sameEnd));
    }

    @Test
    void frozenPriceCannotChange() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        MerchantChapterOffer higher = compatible(o);
        higher.setPerHeadFee(new BigDecimal("13.00"));
        assertRefused(o, higher, TERMS_MISMATCH);
        MerchantChapterOffer lower = compatible(o);
        lower.setPerHeadFee(new BigDecimal("1.00"));
        assertRefused(o, lower, TERMS_MISMATCH);
        MerchantChapterOffer missing = compatible(o);
        missing.setPerHeadFee(null);
        assertRefused(o, missing, TERMS_MISMATCH);
    }

    @Test
    void termsModeCannotChange() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        MerchantChapterOffer r = compatible(o);
        r.setTermsMode(TermsMode.REVSHARE);
        assertRefused(o, r, TERMS_MISMATCH);
    }

    @Test
    void playBoundaryCannotChange() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        MerchantChapterOffer methods = compatible(o);
        methods.setAllowedValidationMethods("1,2,3");
        assertRefused(o, methods, TERMS_MISMATCH);
        MerchantChapterOffer xp = compatible(o);
        xp.setMaxNodeXp(31);
        assertRefused(o, xp, TERMS_MISMATCH);
    }

    @Test
    void replacementMustTargetSameTopicAndChapter() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        MerchantChapterOffer otherChapter = compatible(o);
        otherChapter.setChapterId(10L);
        assertRefused(o, otherChapter, TERMS_MISMATCH);
        MerchantChapterOffer otherTopic = compatible(o);
        otherTopic.setTopicId(101L);
        assertRefused(o, otherTopic, TERMS_MISMATCH);
        assertRefused(o, null, TERMS_MISMATCH);
    }

    @Test
    void shorterValidityIsRefused() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        MerchantChapterOffer shorter = compatible(o);
        shorter.setValidEnd(new Date(ORIGINAL_END.getTime() - DAY));
        assertRefused(o, shorter, "补位供给有效期不能短于原供给");
        MerchantChapterOffer openEnded = original(TermsMode.PERK);
        openEnded.setValidEnd(null);
        MerchantChapterOffer bounded = compatible(openEnded);
        assertRefused(openEnded, bounded, "补位供给有效期不能短于原供给");
    }

    @Test
    void perkQuotaBelowRemainingIsRefused() {
        MerchantChapterOffer o = original(TermsMode.PERK);
        MerchantChapterOffer r = compatible(o);
        r.setQuotaTotal(29);
        assertRefused(o, r, "补位接待份数不足以接续原供给剩余份数");
        r.setQuotaTotal(null);
        assertRefused(o, r, "补位接待份数不足以接续原供给剩余份数");
    }

    @Test
    void replacementOnlyWhileSalesArePaused() {
        CmsTopic paused = new CmsTopic();
        paused.setLifecycle(TopicLifecycleState.SELLING.getCode());
        paused.setStatus(1);
        paused.setUserStatus(0);
        assertDoesNotThrow(() -> OrdinarySupplyReplacementPolicy.assertOpen(paused));

        CmsTopic selling = new CmsTopic();
        selling.setLifecycle(TopicLifecycleState.SELLING.getCode());
        selling.setStatus(1);
        selling.setUserStatus(1);
        assertEquals("普通主题须暂停报名后才能补位",
                assertThrows(IllegalStateException.class,
                        () -> OrdinarySupplyReplacementPolicy.assertOpen(selling)).getMessage());

        CmsTopic settling = new CmsTopic();
        settling.setLifecycle(TopicLifecycleState.SETTLING.getCode());
        settling.setStatus(1);
        settling.setUserStatus(0);
        assertEquals("当前阶段不能补位，请按取消或售后流程处理",
                assertThrows(IllegalStateException.class,
                        () -> OrdinarySupplyReplacementPolicy.assertOpen(settling)).getMessage());
    }
}
