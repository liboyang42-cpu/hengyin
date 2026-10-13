package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ISystemNotificationIntentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原首次建单事务内固化通知，消息投递与重试归B；不可按replay现态重造payload。 */
@Service
public class RegistrationSignupNotificationService {
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private ISystemNotificationIntentService notificationIntentService;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordIntent(Long memberId, CmsRegistration registration) {
        if (registration == null || registration.getId() == null || memberId == null
                || !memberId.equals(registration.getMemberId())) {
            throw new IllegalArgumentException("报名通知原身份不匹配");
        }
        Long topicId = null;
        if (Long.valueOf(2L).equals(registration.getOwnerType())) {
            CmsActivity activity = activityService.selectCmsActivityById(registration.getOwnerId());
            topicId = activity == null ? null : activity.getTopicId();
        }
        Long intentId = notificationIntentService.enqueue(memberId, ImMessage.MSG_CARD, "报名成功",
                RegistrationCheckoutServiceImpl.signupNoticeExtra(topicId), "registration-signup:" + registration.getId());
        if (intentId == null) throw new IllegalStateException("报名通知意图写入失败");
    }
}
