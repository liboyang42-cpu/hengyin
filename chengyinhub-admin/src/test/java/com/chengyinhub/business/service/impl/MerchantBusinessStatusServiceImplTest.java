package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍板 2026-09-16 #10:营业状态门禁的归属解析与生效商家判据。
 *
 * <p>钉四件事:①主题单按发布者;②场次单优先创建者、创建者不是生效商家才回落主题发布者;
 * ③未生效商家(未过审/停用/删除)不在此闸拦;④不是商家一律不拦。
 */
class MerchantBusinessStatusServiceImplTest {

    private static final long TOPIC_ID = 100L;
    private static final long ACTIVITY_ID = 500L;
    private static final long MERCHANT_MEMBER = 800L;

    private ICmsTopicService topicService;
    private ICmsActivityService activityService;
    private IMmsMerchantService mmsMerchantService;
    private MerchantBusinessStatusServiceImpl service;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        activityService = mock(ICmsActivityService.class);
        mmsMerchantService = mock(IMmsMerchantService.class);
        service = new MerchantBusinessStatusServiceImpl();
        ReflectionTestUtils.setField(service, "topicService", topicService);
        ReflectionTestUtils.setField(service, "activityService", activityService);
        ReflectionTestUtils.setField(service, "mmsMerchantService", mmsMerchantService);
    }

    private CmsTopic topic(Long memberId) {
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(memberId);
        return topic;
    }

    private CmsActivity activity(Long memberId, Long topicId) {
        CmsActivity activity = new CmsActivity();
        activity.setMemberId(memberId);
        activity.setTopicId(topicId);
        return activity;
    }

    private MmsMerchant merchant(Long status, Long accountStatus, Integer businessStatus) {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setStatus(status);
        merchant.setAccountStatus(accountStatus);
        merchant.setBusinessStatus(businessStatus);
        merchant.setDelFlag(0);
        return merchant;
    }

    @Test
    void topicOwnedByClosedMerchantIsClosed() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(MERCHANT_MEMBER));
        when(mmsMerchantService.selectMmsMerchantByMemberId(MERCHANT_MEMBER))
                .thenReturn(merchant(1L, 1L, 0));

        assertTrue(service.isTargetMerchantClosed(1, TOPIC_ID));
    }

    @Test
    void topicOwnedByOpenMerchantIsNotClosed() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(MERCHANT_MEMBER));
        when(mmsMerchantService.selectMmsMerchantByMemberId(MERCHANT_MEMBER))
                .thenReturn(merchant(1L, 1L, 1));

        assertFalse(service.isTargetMerchantClosed(1, TOPIC_ID));
    }

    @Test
    void topicOwnedByPlayerIsNotBlockedByThisGate() {
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(9001L));
        when(mmsMerchantService.selectMmsMerchantByMemberId(9001L)).thenReturn(null);

        assertFalse(service.isTargetMerchantClosed(1, TOPIC_ID));
    }

    @Test
    void activityCreatedByClosedMerchantIsClosed() {
        when(activityService.selectCmsActivityById(ACTIVITY_ID))
                .thenReturn(activity(MERCHANT_MEMBER, TOPIC_ID));
        when(mmsMerchantService.selectMmsMerchantByMemberId(MERCHANT_MEMBER))
                .thenReturn(merchant(1L, 1L, 0));

        assertTrue(service.isTargetMerchantClosed(2, ACTIVITY_ID));
    }

    @Test
    void activityFallsBackToTopicOwnerWhenCreatorIsNotAnActiveMerchant() {
        // 场景:俱乐部(非商家)开的场次,主题发布者是商家 ⇒ 商家闭店也要挡。
        when(activityService.selectCmsActivityById(ACTIVITY_ID))
                .thenReturn(activity(9100L, TOPIC_ID));
        when(mmsMerchantService.selectMmsMerchantByMemberId(9100L)).thenReturn(null);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(MERCHANT_MEMBER));
        when(mmsMerchantService.selectMmsMerchantByMemberId(MERCHANT_MEMBER))
                .thenReturn(merchant(1L, 1L, 0));

        assertTrue(service.isTargetMerchantClosed(2, ACTIVITY_ID));
    }

    @Test
    void activityWhoseCreatorAndTopicOwnerArePlayersIsNotBlocked() {
        when(activityService.selectCmsActivityById(ACTIVITY_ID))
                .thenReturn(activity(9100L, TOPIC_ID));
        when(mmsMerchantService.selectMmsMerchantByMemberId(9100L)).thenReturn(null);
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(9200L));
        when(mmsMerchantService.selectMmsMerchantByMemberId(9200L)).thenReturn(null);

        assertFalse(service.isTargetMerchantClosed(2, ACTIVITY_ID));
    }

    @Test
    void inactiveMerchantRowsDoNotTripThisGate() {
        // 未过审(0)/ 账号停用(2)的商家不归本闸管 —— 各自资格闸先拦,不在售前营业闸重复。
        when(topicService.selectCmsTopicById(TOPIC_ID)).thenReturn(topic(MERCHANT_MEMBER));
        when(mmsMerchantService.selectMmsMerchantByMemberId(MERCHANT_MEMBER))
                .thenReturn(merchant(0L, 1L, 0));

        assertFalse(service.isTargetMerchantClosed(1, TOPIC_ID));

        when(mmsMerchantService.selectMmsMerchantByMemberId(MERCHANT_MEMBER))
                .thenReturn(merchant(1L, 2L, 0));
        assertFalse(service.isTargetMerchantClosed(1, TOPIC_ID));
    }

    @Test
    void unknownOwnerTypeOrMissingIdIsNeverBlocked() {
        assertFalse(service.isTargetMerchantClosed(null, TOPIC_ID));
        assertFalse(service.isTargetMerchantClosed(3, TOPIC_ID));
        assertFalse(service.isTargetMerchantClosed(1, null));
        assertFalse(service.isTargetMerchantClosed(1, 0L));
        verify(topicService, never()).selectCmsTopicById(anyLong());
    }
}
