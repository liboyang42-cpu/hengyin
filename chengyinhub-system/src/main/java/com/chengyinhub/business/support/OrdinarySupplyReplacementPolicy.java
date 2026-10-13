package com.chengyinhub.business.support;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.common.constant.TermsMode;
import java.util.Objects;

/** 补位只接续原供给，不改原合同记录或提高原计酬。 */
public final class OrdinarySupplyReplacementPolicy {
    private OrdinarySupplyReplacementPolicy() { }

    public static void assertOpen(com.chengyinhub.business.domain.CmsTopic topic) {
        com.chengyinhub.business.domain.enums.TopicLifecycleState state =
                com.chengyinhub.business.domain.enums.TopicLifecycleState.fromCode(topic.getLifecycle());
        if (state != com.chengyinhub.business.domain.enums.TopicLifecycleState.RECRUITING
                && state != com.chengyinhub.business.domain.enums.TopicLifecycleState.PRICING
                && state != com.chengyinhub.business.domain.enums.TopicLifecycleState.SELLING
                && state != com.chengyinhub.business.domain.enums.TopicLifecycleState.RUNNING) {
            throw new IllegalStateException("当前阶段不能补位，请按取消或售后流程处理");
        }
        if (!Integer.valueOf(1).equals(topic.getStatus())
                || !Integer.valueOf(0).equals(topic.getUserStatus())
                || (topic.getCircleThemeCode() != null && !topic.getCircleThemeCode().trim().isEmpty())) {
            throw new IllegalStateException("普通主题须暂停报名后才能补位");
        }
    }

    public static void assertCompatible(MerchantChapterOffer original, MerchantChapterOffer replacement) {
        if (original == null || replacement == null
                || !Objects.equals(original.getTopicId(), replacement.getTopicId())
                || !Objects.equals(original.getChapterId(), replacement.getChapterId())
                || !Objects.equals(original.getTermsMode(), replacement.getTermsMode())
                || !sameFee(original, replacement)
                || !Objects.equals(original.getAllowedValidationMethods(), replacement.getAllowedValidationMethods())
                || !Objects.equals(original.getMaxNodeXp(), replacement.getMaxNodeXp())) {
            throw new IllegalStateException("补位须沿用原供给条款、计酬和玩法边界");
        }
        if ((original.getValidEnd() == null && replacement.getValidEnd() != null)
                || (original.getValidEnd() != null && replacement.getValidEnd() != null
                    && replacement.getValidEnd().before(original.getValidEnd()))) {
            throw new IllegalStateException("补位供给有效期不能短于原供给");
        }
        if (TermsMode.PERK.equals(original.getTermsMode())) {
            int remaining = Math.max(0, (original.getQuotaTotal() == null ? 0 : original.getQuotaTotal())
                    - (original.getQuotaUsed() == null ? 0 : original.getQuotaUsed()));
            if (replacement.getQuotaTotal() == null || replacement.getQuotaTotal() < remaining) {
                throw new IllegalStateException("补位接待份数不足以接续原供给剩余份数");
            }
        }
    }

    private static boolean sameFee(MerchantChapterOffer a, MerchantChapterOffer b) {
        return a.getPerHeadFee() == null ? b.getPerHeadFee() == null
                : b.getPerHeadFee() != null && a.getPerHeadFee().compareTo(b.getPerHeadFee()) == 0;
    }
}
