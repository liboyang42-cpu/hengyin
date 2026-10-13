package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PureContentExploreServiceTest {
    @Mock CmsTopicMapper topics;
    @Mock CmsTopicChapterMapper chapters;
    @Mock CmsTopicNodeMapper nodes;
    @Mock MerchantChapterOfferMapper offers;
    @Mock CmsRegistrationMapper registrations;
    @Mock CmsActivityMapper activities;
    @Mock PlayerNodeProgressMapper progress;
    PureContentExploreService service;
    CmsTopic topic;
    CmsTopicChapter chapter;
    CmsTopicNode game;
    CmsRegistration order;

    @BeforeEach void setup() {
        MockitoAnnotations.openMocks(this);
        service = new PureContentExploreService();
        Object[][] fields = {{"topicMapper",topics},{"chapterMapper",chapters},{"nodeMapper",nodes},
                {"offerMapper",offers},{"registrationMapper",registrations},{"activityMapper",activities},
                {"progressMapper",progress}};
        for (Object[] field : fields) ReflectionTestUtils.setField(service,(String)field[0],field[1]);
        topic = new CmsTopic(); topic.setId(10L); topic.setProductType(2);
        chapter = new CmsTopicChapter(); chapter.setId(20L); chapter.setTopicId(10L);
        chapter.setRequired(1); chapter.setRecruitEnabled(0); chapter.setTermsMode("TRAFFIC");
        game = new CmsTopicNode(); game.setId(30L); game.setChapterId(20L); game.setTemplateId(40L);
        order = new CmsRegistration(); order.setMemberId(50L); order.setOwnerType(2L); order.setOwnerId(60L);
        order.setPaymentStatus(2); order.setRegistrationStatus(2); order.setPurchaseKind(3);
        CmsActivity activity = new CmsActivity(); activity.setId(60L); activity.setTopicId(10L);
        when(topics.selectCmsTopicById(10L)).thenReturn(topic);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(chapter));
        when(chapters.selectRequiredByTopic(10L)).thenReturn(Arrays.asList(chapter));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(game));
        when(registrations.selectCmsRegistrationById(70L)).thenReturn(order);
        when(activities.selectCmsActivityById(60L)).thenReturn(activity);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Collections.emptyList());
    }

    @Test void noMerchantIsEligibleButAnEmptyRequiredChapterIsNot() {
        assertTrue(service.isPureContent(10L));
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        assertFalse(service.isPureContent(10L));
    }
    @Test void exitedMerchantSupplyCannotBecomePureContent() {
        when(offers.countAllByChapter(20L)).thenReturn(1);
        assertFalse(service.isPureContent(10L));
    }
    @Test void recruitingMerchantNodesPerksAndSpecialCompletionRemainMerchantFlows() {
        chapter.setRecruitEnabled(1); assertFalse(service.isPureContent(10L));
        chapter.setRecruitEnabled(0); chapter.setTermsMode("PERK"); assertFalse(service.isPureContent(10L));
        chapter.setTermsMode("TRAFFIC"); game.setMerchantMemberId(80L); assertFalse(service.isPureContent(10L));
        game.setMerchantMemberId(null); topic.setCompleteRuleJson("{\"distinctMerchantRoles\":4}");
        assertFalse(service.isPureContent(10L));
    }
    @Test void onlyActualCompletedGameplayCounts() {
        assertFalse(service.isCompleted(70L,10L));
        PlayerNodeProgress proof = new PlayerNodeProgress(); proof.setNodeId(30L); proof.setStatus(0);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(proof));
        assertFalse(service.isCompleted(70L,10L));
        proof.setStatus(1); assertTrue(service.isCompleted(70L,10L));
        org.mockito.ArgumentCaptor<PlayerNodeProgress> scope = org.mockito.ArgumentCaptor.forClass(PlayerNodeProgress.class);
        verify(progress,atLeastOnce()).selectPlayerNodeProgressList(scope.capture());
        assertEquals(50L,scope.getValue().getMemberId()); assertEquals(60L,scope.getValue().getActivityId());
        assertEquals(10L,scope.getValue().getTopicId());
    }
    @Test void allGamesWithinRequiredChapterMustFinishWhileOptionalChapterDoesNotBlock() {
        CmsTopicNode second = new CmsTopicNode(); second.setId(31L); second.setChapterId(20L); second.setTemplateId(40L);
        CmsTopicNode optional = new CmsTopicNode(); optional.setId(32L); optional.setChapterId(21L); optional.setTemplateId(40L);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(game,second,optional));
        PlayerNodeProgress first = new PlayerNodeProgress(); first.setNodeId(30L); first.setStatus(1);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(first));
        assertFalse(service.isCompleted(70L,10L));
        PlayerNodeProgress last = new PlayerNodeProgress(); last.setNodeId(31L); last.setStatus(1);
        when(progress.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(first,last));
        assertTrue(service.isCompleted(70L,10L));
    }
    @Test void cancelledOrUnpaidOrderCannotComplete() {
        order.setRegistrationStatus(3); assertFalse(service.isCompleted(70L,10L));
        order.setRegistrationStatus(2); order.setPaymentStatus(1); assertFalse(service.isCompleted(70L,10L));
        verifyNoInteractions(progress);
    }
    @Test void anotherEditionCannotContributeProgress() {
        CmsActivity other = new CmsActivity(); other.setTopicId(11L);
        when(activities.selectCmsActivityById(60L)).thenReturn(other);
        assertFalse(service.isCompleted(70L,10L)); verifyNoInteractions(progress);
    }
}
