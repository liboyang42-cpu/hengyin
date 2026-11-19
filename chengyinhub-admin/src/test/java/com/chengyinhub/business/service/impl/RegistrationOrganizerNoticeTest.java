package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.OrganizerMoneyNotice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MC-034:买票成功告诉主办方「有新报名」;买家即主办方不发;查不到/出错不影响支付。 */
class RegistrationOrganizerNoticeTest {

    private CmsRegistrationServiceImpl service;
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private OrganizerMoneyNotice notice;

    @BeforeEach
    void setUp() {
        service = new CmsRegistrationServiceImpl();
        activities = mock(CmsActivityMapper.class);
        topics = mock(CmsTopicMapper.class);
        notice = mock(OrganizerMoneyNotice.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topics);
        ReflectionTestUtils.setField(service, "organizerMoneyNotice", notice);
    }

    private CmsRegistration reg(long ownerType, long buyer) {
        CmsRegistration r = new CmsRegistration();
        r.setId(77L);
        r.setOwnerType(ownerType);
        r.setOwnerId(5L);
        r.setMemberId(buyer);
        r.setActivityTitle("旧标题");
        return r;
    }

    @Test
    void activityPurchaseNotifiesActivityCreator() {
        CmsActivity a = new CmsActivity();
        a.setMemberId(9L);
        a.setName("周末夜跑");
        when(activities.selectCmsActivityById(5L)).thenReturn(a);
        service.notifyOrganizerOfPurchase(reg(2L, 100L));
        verify(notice).ticketPurchased(9L, "ACTIVITY", 5L, 77L, "周末夜跑");
    }

    @Test
    void topicPurchaseNotifiesTopicOwnerButNotSelfPurchase() {
        CmsTopic t = new CmsTopic();
        t.setMemberId(9L);
        t.setName("城市定向");
        when(topics.selectCmsTopicById(5L)).thenReturn(t);
        service.notifyOrganizerOfPurchase(reg(1L, 100L));
        verify(notice).ticketPurchased(9L, "TOPIC", 5L, 77L, "城市定向");

        OrganizerMoneyNotice other = mock(OrganizerMoneyNotice.class);
        ReflectionTestUtils.setField(service, "organizerMoneyNotice", other);
        service.notifyOrganizerOfPurchase(reg(1L, 9L));
        verify(other, never()).ticketPurchased(any(), any(), any(), any(), any());
    }

    @Test
    void lookupFailureNeverEscapes() {
        when(activities.selectCmsActivityById(5L)).thenThrow(new RuntimeException("db"));
        assertDoesNotThrow(() -> service.notifyOrganizerOfPurchase(reg(2L, 100L)));
        verify(notice, never()).ticketPurchased(any(), any(), any(), any(), any());
    }
}
