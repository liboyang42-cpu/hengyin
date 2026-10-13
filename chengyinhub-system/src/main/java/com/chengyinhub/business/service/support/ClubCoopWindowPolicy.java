package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.common.constant.RecruitmentWindowPolicy;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * F04 俱乐部合作「招商期」唯一判定:合作池列表、俱乐部申请、商家→俱乐部邀约及其接受共用。
 *
 * <p>原先池/申请只看 openClubPool + 在架 + 未锁价,邀约却走 {@link RecruitmentWindowPolicy}
 * (只认 lifecycle 1/2 + recruitDeadline)。免费 UGC 主题不走商业生命周期、永远停在 DRAFT ⇒
 * 俱乐部能申请、商家却永远发不出邀约。分叉点在「这个主题归不归商业生命周期管」:
 * 由 {@link CommercialTopicPolicy} 判;商业主题一律走招商写闸(未过立项审 = 池里看不见、也申请不了)。
 */
@Component
public class ClubCoopWindowPolicy {

    private final CommercialTopicPolicy commercialTopicPolicy;

    public ClubCoopWindowPolicy(CommercialTopicPolicy commercialTopicPolicy) {
        this.commercialTopicPolicy = commercialTopicPolicy;
    }

    /** 申请/列表口径;invite 非空时是邀约口径。 */
    public void assertOpen(CmsTopic topic, CoopInvite invite, Date now) {
        if (freeClubPoolTopic(topic)) {
            // 免费主题没有锁价/结算阶段,带报酬的合作条款永远结不了 ⇒ 要谈钱先走招商立项。
            if (invite != null && invite.getShareMode() != null && invite.getShareMode() != 0) {
                throw new IllegalStateException("免费主题的俱乐部合作不支持报酬条款,请先提交招商立项");
            }
            if (invite != null && invite.getDepositAmount() != null && invite.getDepositAmount().signum() != 0) {
                throw new IllegalStateException("免费主题的俱乐部合作不支持押金,请先提交招商立项");
            }
            return;
        }
        RecruitmentWindowPolicy.assertOpen(topic == null ? null : topic.getLifecycle(),
                topic == null ? null : topic.getStatus(), topic == null ? null : topic.getUserStatus(),
                topic == null ? null : topic.getRecruitDeadline(), now);
    }

    public boolean isOpen(CmsTopic topic, Date now) {
        try {
            assertOpen(topic, null, now);
            return true;
        } catch (IllegalStateException closed) {
            return false;
        }
    }

    /** 免费主题({@link CommercialTopicPolicy#isFreeTopic},与报名侧同一口径)+ 开放俱乐部池 + 审核上架。 */
    private boolean freeClubPoolTopic(CmsTopic topic) {
        return topic != null
                && Integer.valueOf(1).equals(topic.getOpenClubPool())
                && Integer.valueOf(1).equals(topic.getStatus())
                && Integer.valueOf(1).equals(topic.getUserStatus())
                && commercialTopicPolicy.isFreeTopic(topic);
    }
}
