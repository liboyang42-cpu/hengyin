package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CircleExploreAnswer;
import com.chengyinhub.business.domain.CircleExploreRecord;
import com.chengyinhub.business.domain.CircleExploreSession;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.CircleExploreCardVO;
import com.chengyinhub.business.mapper.CircleExploreMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.impl.CircleExploreServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 玩家会话只写自助记录，不得绕过有效供给或成员归属。 */
class CircleExploreServiceTest {
    private CircleExploreMapper exploreMapper;
    private CmsTopicMapper topicMapper;
    private ICircleThemeSupplyService supplyService;
    private IUmsMemberService memberService;
    private ICmsRegistrationService registrationService;
    private CircleExploreServiceImpl service;

    @BeforeEach
    void setUp() {
        exploreMapper = mock(CircleExploreMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        supplyService = mock(ICircleThemeSupplyService.class);
        memberService = mock(IUmsMemberService.class);
        registrationService = mock(ICmsRegistrationService.class);
        service = new CircleExploreServiceImpl();
        ReflectionTestUtils.setField(service, "exploreMapper", exploreMapper);
        ReflectionTestUtils.setField(service, "topicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "supplyService", supplyService);
        ReflectionTestUtils.setField(service, "memberService", memberService);
        ReflectionTestUtils.setField(service, "registrationService", registrationService);
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.singletonList(validPass()));
    }

    @Test
    void createAndJoinRequireAValidThemePassForEachMember() {
        when(registrationService.selectCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(Collections.emptyList());
        when(supplyService.listPublicSupplies(9L)).thenReturn(
                Collections.singletonList(new MerchantCircleSupplyProfile()));

        assertThrows(IllegalStateException.class, () -> service.create(9L, 100L));

        CircleExploreSession session = activeSession("FITNESS");
        session.setInviteCode("ABCD1234");
        when(exploreMapper.selectActiveSessionByInvite("ABCD1234")).thenReturn(session);
        assertThrows(IllegalStateException.class, () -> service.join("ABCD1234", 200L));

        verify(exploreMapper, never()).insertSession(any());
        verify(exploreMapper, never()).insertMember(55L, 200L);
    }

    @Test
    void zeroMemberIdIsNotALoggedInMember() {
        assertThrows(IllegalStateException.class, () -> service.create(9L, 0L));
        verify(registrationService, never()).selectCmsRegistrationList(any(CmsRegistration.class));
    }

    @Test
    void dateSessionRejectsAThirdMemberAndLocksBeforeCounting() {
        CircleExploreSession session = activeSession("DATE");
        session.setInviteCode("ABCD1234");
        when(exploreMapper.selectActiveSessionByInvite("ABCD1234")).thenReturn(session);
        when(exploreMapper.countSessionMembers(55L)).thenReturn(2);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("DATE"));
        MerchantCircleSupplyProfile supply = new MerchantCircleSupplyProfile();
        supply.setMaxPeople(2);
        supply.setSplitBookingAllowed(0);
        when(supplyService.listPublicSupplies(9L)).thenReturn(Collections.singletonList(supply));

        assertThrows(IllegalStateException.class, () -> service.join("ABCD1234", 300L));
        verify(exploreMapper, never()).insertMember(55L, 300L);
    }

    @Test
    void existingMemberCanReopenAFullSharedSessionIdempotently() {
        CircleExploreSession session = activeSession("DATE");
        session.setInviteCode("ABCD1234");
        when(exploreMapper.selectActiveSessionByInvite("ABCD1234")).thenReturn(session);
        when(exploreMapper.countMember(55L, 200L)).thenReturn(1);
        when(exploreMapper.countSessionMembers(55L)).thenReturn(2);

        assertEquals(session, service.join("ABCD1234", 200L));

        verify(exploreMapper, never()).insertMember(55L, 200L);
        verify(exploreMapper, never()).countSessionMembers(55L);
    }

    @Test
    void createRejectsAHiddenInstanceWithFewerThanThreeEffectiveMerchants() {
        when(supplyService.listPublicSupplies(9L)).thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class, () -> service.create(9L, 100L));

        verify(exploreMapper, never()).insertSession(any());
    }

    @Test
    void createAddsOwnerAsTheFirstSessionMember() {
        when(supplyService.listPublicSupplies(9L)).thenReturn(
                Collections.singletonList(new MerchantCircleSupplyProfile()));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((CircleExploreSession) invocation.getArgument(0)).setId(55L);
            return 1;
        }).when(exploreMapper).insertSession(any());
        when(exploreMapper.insertMember(55L, 100L)).thenReturn(1);

        CircleExploreSession session = service.create(9L, 100L);

        assertEquals(Long.valueOf(55L), session.getId());
        assertEquals(Long.valueOf(100L), session.getOwnerMemberId());
        verify(exploreMapper).insertMember(55L, 100L);
    }

    @Test
    void createRetriesWithAFreshInviteCodeWhenTheCodeCollides() {
        when(supplyService.listPublicSupplies(9L)).thenReturn(
                Collections.singletonList(new MerchantCircleSupplyProfile()));
        java.util.List<String> triedCodes = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            CircleExploreSession attempt = invocation.getArgument(0);
            triedCodes.add(attempt.getInviteCode());
            if (triedCodes.size() == 1) throw new org.springframework.dao.DuplicateKeyException("uk_circle_session_invite");
            attempt.setId(56L);
            return 1;
        }).when(exploreMapper).insertSession(any());

        CircleExploreSession session = service.create(9L, 100L);

        assertEquals(Long.valueOf(56L), session.getId());
        assertEquals(2, triedCodes.size(), "撞码后必须换码重试一次");
        assertTrue(!triedCodes.get(0).equals(triedCodes.get(1)), "重试必须换一个新邀请码");
        verify(exploreMapper).insertMember(56L, 100L);
    }

    @Test
    void createStopsAfterThreeInviteCodeCollisionsWithoutAddingAMember() {
        when(supplyService.listPublicSupplies(9L)).thenReturn(
                Collections.singletonList(new MerchantCircleSupplyProfile()));
        when(exploreMapper.insertSession(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_circle_session_invite"));

        assertThrows(IllegalStateException.class, () -> service.create(9L, 100L));

        verify(exploreMapper, org.mockito.Mockito.times(3)).insertSession(any());
        verify(exploreMapper, never()).insertMember(any(), any());
    }

    @Test
    void nightSessionRejectsAMemberWithoutAConfirmedAdultBirthday() {
        when(supplyService.listPublicSupplies(9L)).thenReturn(
                Collections.singletonList(new MerchantCircleSupplyProfile()));
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("NIGHT"));
        UmsMember member = new UmsMember();
        member.setId(100L);
        when(memberService.selectUmsMemberById(100L)).thenReturn(member);

        assertThrows(IllegalStateException.class, () -> service.create(9L, 100L));

        verify(exploreMapper, never()).insertSession(any());
    }

    @Test
    void alcoholNightSupplyUsesItsLocalLegalDrinkingAgeAtEntry() {
        MerchantCircleSupplyProfile alcohol = new MerchantCircleSupplyProfile();
        alcohol.setId(88L);
        alcohol.setOfferId(800L);
        alcohol.setCurrent(1);
        alcohol.setAlcoholInvolved(1);
        alcohol.setLegalDrinkingAge(21);
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(alcohol));
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("NIGHT"));
        UmsMember member = new UmsMember();
        member.setId(100L);
        java.util.Calendar birthday = java.util.Calendar.getInstance();
        birthday.add(java.util.Calendar.YEAR, -20);
        member.setBirthday(birthday.getTime());
        when(memberService.selectUmsMemberById(100L)).thenReturn(member);

        assertThrows(IllegalStateException.class, () -> service.create(9L, 100L));

        verify(exploreMapper, never()).insertSession(any());
    }

    @Test
    void selfRecordRequiresMembershipAndAnEffectiveOfferOnTheSameTopic() {
        CircleExploreSession session = activeSession("SOLO");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.record(55L, 800L, "我自己记下的体验", 100L));
        verify(exploreMapper, never()).insertRecord(any());

        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        MerchantCircleSupplyProfile other = new MerchantCircleSupplyProfile();
        other.setId(89L);
        other.setOfferId(900L);
        other.setCurrent(1);
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(other));
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("SOLO"));
        assertThrows(IllegalStateException.class,
                () -> service.record(55L, 800L, "我自己记下的体验", 100L));
        verify(exploreMapper, never()).insertRecord(any());
    }

    @Test
    void crossDayNightRecordRechecksAChangedLegalDrinkingAge() {
        CircleExploreSession session = activeSession("NIGHT");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("NIGHT"));
        MerchantCircleSupplyProfile alcohol = new MerchantCircleSupplyProfile();
        alcohol.setId(88L);
        alcohol.setOfferId(800L);
        alcohol.setCurrent(1);
        alcohol.setAlcoholInvolved(1);
        alcohol.setLegalDrinkingAge(21);
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(alcohol));
        UmsMember member = new UmsMember();
        java.util.Calendar birthday = java.util.Calendar.getInstance();
        birthday.add(java.util.Calendar.YEAR, -20);
        member.setBirthday(birthday.getTime());
        when(memberService.selectUmsMemberById(100L)).thenReturn(member);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.record(55L, 800L, "跨天后再记录", 100L));

        assertTrue(error.getMessage().contains("参与年龄"));
        verify(memberService).selectUmsMemberById(100L);
        verify(exploreMapper, never()).insertRecord(any());
    }

    @Test
    void selfRecordStoresOnlyTheLoggedInMemberAndSelectedEffectiveOffer() {
        CircleExploreSession session = activeSession("SOLO");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("SOLO"));
        MerchantCircleSupplyProfile supply = new MerchantCircleSupplyProfile();
        supply.setId(88L);
        supply.setOfferId(800L);
        supply.setCurrent(1);
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(supply));
        when(exploreMapper.insertRecord(any())).thenReturn(1);

        service.record(55L, 800L, "  这是我的私人记录  ", 100L);

        ArgumentCaptor<CircleExploreRecord> record = ArgumentCaptor.forClass(CircleExploreRecord.class);
        verify(exploreMapper).insertRecord(record.capture());
        assertEquals(Long.valueOf(55L), record.getValue().getSessionId());
        assertEquals(Long.valueOf(100L), record.getValue().getMemberId());
        assertEquals(Long.valueOf(800L), record.getValue().getOfferId());
        assertEquals(Long.valueOf(88L), record.getValue().getSupplyProfileId());
        assertEquals("这是我的私人记录", record.getValue().getNote());
    }

    @Test
    void cardReportsCompletionWithoutWritingStateFromAGetRequest() {
        CircleExploreSession session = activeSession("AFTER_WORK");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(2);
        when(exploreMapper.countSessionMembers(55L)).thenReturn(2);
        when(exploreMapper.countAnswersByStage(55L, "PRE_STATE")).thenReturn(2);
        when(exploreMapper.countAnswersByStage(55L, "POST_STATE")).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("AFTER_WORK"));
        when(exploreMapper.selectRecords(55L)).thenReturn(Collections.<CircleExploreRecord>emptyList());
        when(exploreMapper.selectAnswers(55L)).thenReturn(Collections.<CircleExploreAnswer>emptyList());

        CircleExploreCardVO incomplete = service.card(55L, 100L);
        assertTrue(!incomplete.isCompleted());
        verify(exploreMapper, never()).completeSession(55L);

        when(exploreMapper.countAnswersByStage(55L, "POST_STATE")).thenReturn(2);
        CircleExploreCardVO completed = service.card(55L, 100L);
        assertTrue(completed.isCompleted());
        verify(exploreMapper, never()).completeSession(55L);
    }

    @Test
    void parentChildMemoryNeedsSeparateAdultAndChildFavorites() {
        CircleExploreSession session = activeSession("PARENT_CHILD");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(2);
        when(exploreMapper.countSessionMembers(55L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("PARENT_CHILD"));
        when(exploreMapper.countAnswersByStage(55L, "ADULT_FAVORITE")).thenReturn(1);
        when(exploreMapper.countAnswersByStage(55L, "CHILD_FAVORITE")).thenReturn(0);
        when(exploreMapper.selectRecords(55L)).thenReturn(Collections.<CircleExploreRecord>emptyList());
        when(exploreMapper.selectAnswers(55L)).thenReturn(Collections.<CircleExploreAnswer>emptyList());

        assertTrue(!service.card(55L, 100L).isCompleted());

        when(exploreMapper.countAnswersByStage(55L, "CHILD_FAVORITE")).thenReturn(1);
        assertTrue(service.card(55L, 100L).isCompleted());
        verify(exploreMapper, never()).completeSession(55L);
    }

    @Test
    void dateAndFriendsNeedAtLeastTwoMembersBeforeCompletion() {
        CircleExploreSession session = activeSession("DATE");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(2);
        when(exploreMapper.countSessionMembers(55L)).thenReturn(1, 2);
        when(exploreMapper.countAnswersByStage(55L, "QUESTION_CARD")).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("DATE"));
        when(exploreMapper.selectRecords(55L)).thenReturn(Collections.<CircleExploreRecord>emptyList());
        when(exploreMapper.selectAnswers(55L)).thenReturn(Collections.<CircleExploreAnswer>emptyList());

        assertTrue(!service.card(55L, 100L).isCompleted());
        assertTrue(service.card(55L, 100L).isCompleted());
    }

    @Test
    void cardDoesNotExposeOtherMembersIdentityOrPrivateNotes() {
        CircleExploreSession session = activeSession("SOLO");
        CircleExploreRecord mine = new CircleExploreRecord();
        mine.setMemberId(100L);
        mine.setNote("我的记录");
        CircleExploreRecord other = new CircleExploreRecord();
        other.setMemberId(200L);
        other.setNote("别人的私人记录");
        CircleExploreAnswer answer = new CircleExploreAnswer();
        answer.setMemberId(200L);
        answer.setAnswerStage("DECISION");
        answer.setAnswerValue("别人的决定");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("SOLO"));
        when(exploreMapper.countAnswersByStage(55L, "DECISION")).thenReturn(1);
        when(exploreMapper.selectRecords(55L)).thenReturn(Arrays.asList(mine, other));
        when(exploreMapper.selectAnswers(55L)).thenReturn(Collections.singletonList(answer));

        CircleExploreCardVO card = service.card(55L, 100L);

        assertEquals("我的记录", card.getRecords().get(0).getNote());
        assertEquals(null, card.getRecords().get(0).getMemberId());
        assertEquals(null, card.getRecords().get(1).getNote());
        assertEquals(null, card.getRecords().get(1).getMemberId());
        assertTrue(card.getAnswers().isEmpty());
    }

    @Test
    void fixedChoiceThemesRejectInventedInteractionValues() {
        CircleExploreSession session = activeSession("NIGHT");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("NIGHT"));

        assertThrows(IllegalStateException.class,
                () -> service.answer(55L, "KEYWORD", "加班", 100L));
        verify(exploreMapper, never()).upsertAnswer(any());
    }

    @Test
    void primaryCandidateAnswersMustReferenceAnEnabledOffer() {
        CircleExploreSession session = activeSession("FITNESS");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("FITNESS"));
        MerchantCircleSupplyProfile enabled = new MerchantCircleSupplyProfile();
        enabled.setCandidateCode("F2");
        MerchantCircleSupplyProfile second = new MerchantCircleSupplyProfile();
        second.setCandidateCode("F3");
        MerchantCircleSupplyProfile third = new MerchantCircleSupplyProfile();
        third.setCandidateCode("F4");
        when(supplyService.listPublicSupplies(9L)).thenReturn(Arrays.asList(enabled, second, third));

        assertThrows(IllegalStateException.class,
                () -> service.answer(55L, "PRE_CHOICE", "F8", 100L));
        verify(exploreMapper, never()).upsertAnswer(any());
    }

    @Test
    void afterInteractionCannotBeSubmittedBeforeTwoMerchantRecords() {
        CircleExploreSession session = activeSession("FRIENDS");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("FRIENDS"));

        assertThrows(IllegalStateException.class,
                () -> service.answer(55L, "NEXT_PICK", "P4", 100L));
        verify(exploreMapper, never()).upsertAnswer(any());
    }

    @Test
    void hiddenInstanceRejectsNewRecordsAndAnswers() {
        CircleExploreSession session = activeSession("FITNESS");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("FITNESS"));
        when(supplyService.listPublicSupplies(9L)).thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class,
                () -> service.record(55L, 800L, "不应写入", 100L));
        assertThrows(IllegalStateException.class,
                () -> service.answer(55L, "PRE_CHOICE", "F1", 100L));

        verify(exploreMapper, never()).insertRecord(any());
        verify(exploreMapper, never()).upsertAnswer(any());
    }

    @Test
    void departureChoiceLocksWhenRecordingStartsAndIsRequiredBeforeFirstRecord() {
        CircleExploreSession session = activeSession("FITNESS");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("FITNESS"));
        MerchantCircleSupplyProfile supply = new MerchantCircleSupplyProfile();
        supply.setId(88L);
        supply.setOfferId(800L);
        supply.setCurrent(1);
        supply.setCandidateCode("F1");
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(supply));
        when(exploreMapper.countSessionMembers(55L)).thenReturn(1);
        when(exploreMapper.countAnswersByStage(55L, "PRE_CHOICE")).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.record(55L, 800L, "尚未出发", 100L));

        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(1);
        assertThrows(IllegalStateException.class,
                () -> service.answer(55L, "PRE_CHOICE", "F1", 100L));
        verify(exploreMapper, never()).insertRecord(any());
        verify(exploreMapper, never()).upsertAnswer(any());
    }

    @Test
    void joiningSecondMemberDoesNotAcceptAStaleOnePersonDateChoiceAsComplete() {
        CircleExploreSession session = activeSession("DATE");
        session.setInviteCode("ABCD1234");
        when(exploreMapper.selectActiveSessionByInvite("ABCD1234")).thenReturn(session);
        when(exploreMapper.countMember(55L, 200L)).thenReturn(0);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("DATE"));
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(new MerchantCircleSupplyProfile()));
        when(exploreMapper.countSessionMembers(55L)).thenReturn(1, 2);
        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(2);
        when(exploreMapper.countAnswersByStage(55L, "QUESTION_CARD")).thenReturn(1);

        service.join("ABCD1234", 200L);

        verify(exploreMapper).insertMember(55L, 200L);
        verify(exploreMapper, never()).completeSession(55L);
    }

    @Test
    void dateQuestionRequiresBothMembersBeforeItCanCompleteTheSession() {
        CircleExploreSession session = activeSession("DATE");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("DATE"));
        when(supplyService.listPublicSupplies(9L)).thenReturn(openSupplies(new MerchantCircleSupplyProfile()));
        when(exploreMapper.countSessionMembers(55L)).thenReturn(1, 2, 2);
        when(exploreMapper.countDistinctRecordedMerchants(55L)).thenReturn(2);
        when(exploreMapper.countAnswersByStage(55L, "QUESTION_CARD")).thenReturn(1);

        assertThrows(IllegalStateException.class,
                () -> service.answer(55L, "QUESTION_CARD", "Q1", 100L));

        service.answer(55L, "QUESTION_CARD", "Q1", 100L);

        verify(exploreMapper).upsertAnswer(any());
        verify(exploreMapper).completeSession(55L);
    }

    @Test
    void primaryThemeMemoryAnswersAreSharedWithoutMemberIdentity() {
        CircleExploreSession session = activeSession("MIDLIFE");
        CircleExploreAnswer answer = new CircleExploreAnswer();
        answer.setMemberId(200L);
        answer.setAnswerStage("SELF_MOMENT");
        answer.setAnswerValue("朋友笑起来时");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("MIDLIFE"));
        when(exploreMapper.selectAnswers(55L)).thenReturn(Collections.singletonList(answer));

        CircleExploreCardVO card = service.card(55L, 100L);

        assertEquals(1, card.getAnswers().size());
        assertEquals("朋友笑起来时", card.getAnswers().get(0).getAnswerValue());
        assertEquals("同行1", card.getAnswers().get(0).getMemberLabel());
        assertEquals(null, card.getAnswers().get(0).getMemberId());
    }

    @Test
    void anonymousCompanionNumberDoesNotCountTheCurrentMember() {
        CircleExploreSession session = activeSession("MIDLIFE");
        CircleExploreAnswer mine = new CircleExploreAnswer();
        mine.setMemberId(100L);
        mine.setAnswerStage("SELF_MOMENT");
        mine.setAnswerValue("我的时刻");
        CircleExploreAnswer other = new CircleExploreAnswer();
        other.setMemberId(200L);
        other.setAnswerStage("SELF_MOMENT");
        other.setAnswerValue("朋友的时刻");
        when(exploreMapper.selectSessionById(55L)).thenReturn(session);
        when(exploreMapper.countMember(55L, 100L)).thenReturn(1);
        when(topicMapper.selectCmsTopicById(9L)).thenReturn(topic("MIDLIFE"));
        when(exploreMapper.selectAnswers(55L)).thenReturn(Arrays.asList(mine, other));

        CircleExploreCardVO card = service.card(55L, 100L);

        assertEquals("我", card.getAnswers().get(0).getMemberLabel());
        assertEquals("同行1", card.getAnswers().get(1).getMemberLabel());
    }

    private CircleExploreSession activeSession(String ignoredTheme) {
        CircleExploreSession session = new CircleExploreSession();
        session.setId(55L);
        session.setTopicId(9L);
        session.setOwnerMemberId(100L);
        session.setStatus("ACTIVE");
        return session;
    }

    private CmsTopic topic(String themeCode) {
        CmsTopic topic = new CmsTopic();
        topic.setId(9L);
        topic.setCircleThemeCode(themeCode);
        return topic;
    }

    private java.util.List<MerchantCircleSupplyProfile> openSupplies(
            MerchantCircleSupplyProfile target) {
        MerchantCircleSupplyProfile second = new MerchantCircleSupplyProfile();
        second.setId(89L);
        second.setOfferId(801L);
        second.setCurrent(1);
        MerchantCircleSupplyProfile third = new MerchantCircleSupplyProfile();
        third.setId(90L);
        third.setOfferId(802L);
        third.setCurrent(1);
        return Arrays.asList(target, second, third);
    }

    private CmsRegistration validPass() {
        CmsRegistration pass = new CmsRegistration();
        pass.setMemberId(100L);
        pass.setOwnerType(1L);
        pass.setOwnerId(9L);
        pass.setRegistrationStatus(2);
        pass.setExpiresAt(new java.util.Date(System.currentTimeMillis() + 60_000L));
        return pass;
    }
}
