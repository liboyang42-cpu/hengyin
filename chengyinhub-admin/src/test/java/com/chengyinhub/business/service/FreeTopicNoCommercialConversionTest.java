package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 9-15 拍板:免费主题不能转成商业主题(只能发起人重新发起)。
 *
 * <p>判据用真实 {@link CommercialTopicPolicy}(只桩 mapper):已发布(is_draft≠1)+ 统一免费口径(DRAFT、无商业事实)
 * = 受保护。用户侧三个能加商业事实的写口必须拒:章节开招募、派生活动加收费票;主题编辑重建见 TopicUpdateByOwnerTest。
 */
class FreeTopicNoCommercialConversionTest {

    private static final Long TOPIC = 990049L;
    private static final Long CHAPTER = 55L;
    private static final Long ACTIVITY = 66L;
    private static final Long OWNER = 9L;

    private CmsTopicMapper topicMapper;
    private OmsTicketMapper ticketMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CommercialTopicPolicy policy;
    private CmsTopic topic;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        MerchantChapterOfferMapper offerMapper = mock(MerchantChapterOfferMapper.class);
        policy = new CommercialTopicPolicy(ticketMapper, chapterMapper, offerMapper, topicMapper);

        topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(OWNER);
        topic.setLifecycle(0);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
    }

    // ---------- 判据 ----------

    @Test
    void publishedFreeTopicIsProtected_draftOrCommercialTrackIsNot() {
        assertTrue(policy.isConversionProtected(topic));

        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(1);
        assertFalse(policy.isConversionProtected(topic), "草稿编辑期允许补成商业(发布前)");

        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
        topic.setLifecycle(7);
        assertFalse(policy.isConversionProtected(topic), "立项待审已在商业轨道,不是免费主题");
    }

    @Test
    void writeThatAddsCommercialFactToProtectedTopicIsRejected() {
        OmsTicket paid = new OmsTicket();
        paid.setId(1L);
        paid.setDelFlag(0);
        paid.setPrice(new BigDecimal("10"));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(paid));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> policy.assertNotConvertedToCommercial(TOPIC, true));
        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, error.getMessage());
        policy.assertNotConvertedToCommercial(TOPIC, false); // 写前本就不受保护 ⇒ 放行
    }

    // ---------- F:/api/topic/chapter/recruit ----------

    @Test
    void chapterRecruitOnProtectedFreeTopicIsRejected() {
        CmsTopicChapterServiceImpl service = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", policy);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);

        // 非归属人不在此闸暴露主题状态,交给 owner-scoped SQL 返回 0
        when(chapterMapper.setRecruitEnabledIfOwned(CHAPTER, 777L, 1)).thenReturn(0);
        assertEquals(0, service.setChapterRecruitAsOwner(CHAPTER, 777L, 1));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.setChapterRecruitAsOwner(CHAPTER, OWNER, 1));
        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, error.getMessage());
        verify(chapterMapper, never()).setRecruitEnabledIfOwned(CHAPTER, OWNER, 1);

        // 负控:关闭招募不是转商业,照常写
        when(chapterMapper.setRecruitEnabledIfOwned(CHAPTER, OWNER, 0)).thenReturn(1);
        assertEquals(1, service.setChapterRecruitAsOwner(CHAPTER, OWNER, 0));
    }

    // ---------- D:/api/activity/update ----------

    private CmsActivityServiceImpl activityService(CmsActivityMapper activityMapper) {
        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", policy);
        return service;
    }

    private CmsActivityPublishRequest activityUpdate(BigDecimal price, Long ticketId) {
        ActivityTicketRequest ticket = new ActivityTicketRequest();
        ticket.setId(ticketId);
        ticket.setName("入场");
        ticket.setPrice(price);
        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setId(ACTIVITY);
        request.setName("派生活动");
        request.setTickets(Arrays.asList(ticket));
        return request;
    }

    @Test
    void derivedActivityPaidTicketOnProtectedFreeTopicIsRejected() {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        activity.setMemberId(OWNER);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activityMapper.updateCmsActivityByOwner(any())).thenReturn(1);
        OmsTicket freeLive = new OmsTicket();
        freeLive.setId(3L);
        freeLive.setOwnerType(2L);
        freeLive.setOwnerId(ACTIVITY);
        freeLive.setDelFlag(0);
        freeLive.setPrice(BigDecimal.ZERO);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(freeLive));
        CmsActivityServiceImpl service = activityService(activityMapper);

        // ServiceException 才会被 ApiActivityController 原样回给用户(其余异常变「系统繁忙」)。
        com.chengyinhub.common.exception.ServiceException error = assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.updateActivityByOwner(activityUpdate(new BigDecimal("10"), 3L), OWNER, "me"));
        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, error.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(any());
        verify(ticketMapper, never()).insertOmsTicket(any());
    }

    /** 负控:原本就收费的活动票保持原价,不是「0→收费」,不在此闸。 */
    @Test
    void alreadyPaidActivityTicketKeepingItsPriceIsNotAConversion() {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY);
        activity.setTopicId(TOPIC);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(activity);
        when(activityMapper.updateCmsActivityByOwner(any())).thenReturn(1);
        OmsTicket paidLive = new OmsTicket();
        paidLive.setId(3L);
        paidLive.setDelFlag(0);
        paidLive.setPrice(new BigDecimal("10"));
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class))).thenReturn(Collections.singletonList(paidLive));
        CmsActivityServiceImpl service = activityService(activityMapper);

        RuntimeException error = null;
        try {
            service.updateActivityByOwner(activityUpdate(new BigDecimal("10"), 3L), OWNER, "me");
        } catch (RuntimeException e) {
            error = e;
        }
        assertTrue(error == null || !CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED.equals(error.getMessage()),
                "保持原价不是转商业:" + (error == null ? "" : error.getMessage()));
    }
}
