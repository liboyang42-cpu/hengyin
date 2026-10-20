package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventParty;
import com.chengyinhub.business.domain.UmsCreativeSquare;
import com.chengyinhub.business.domain.community.CommunityPost;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IUmsCreativeSquareService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOLLOWUP-C-1:内容安全因图片下架内容时,作者在系统消息里得知是哪一条、为什么。
 *
 * <p>修复前各类型只改状态 + 打日志,作者毫无感知。修复后:本次回调真正把内容从公开态下架时发一条
 * 「内容已下架」系统卡片(点名内容 + 「图片未通过内容安全检测」);已下架的重复回调不再发。</p>
 */
class ContentSafetyImageTakedownNoticeTest {
    private static final long ID = 31L;
    private static final long AUTHOR = 9L;

    private WxContentSecurityServiceImpl service;
    private IImService im;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new WxContentSecurityServiceImpl();
        im = mock(IImService.class);
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(im);
        when(provider.getObject()).thenReturn(im);
        ReflectionTestUtils.setField(service, "imServiceProvider", provider);
    }

    private boolean risky(int bizType) {
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(service, "processMappedMediaCheckResult",
                bizType + ":" + ID, "trace-" + bizType, true));
    }

    private String noticeTo(long member) {
        ArgumentCaptor<String> extra = ArgumentCaptor.forClass(String.class);
        verify(im).sendSystemInNestedTransaction(eq(member), eq(ImMessage.MSG_CARD), eq("内容已下架"), extra.capture());
        assertTrue(extra.getValue().contains("图片未通过内容安全检测"), extra.getValue());
        return extra.getValue();
    }

    private void noNotice() {
        verify(im, never()).sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void legacySquareTakedownTellsTheAuthorWhichPostAndWhy() {
        IUmsCreativeSquareService squares = mock(IUmsCreativeSquareService.class);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squares);
        UmsCreativeSquare live = new UmsCreativeSquare();
        live.setId(ID);
        live.setMemberId(AUTHOR);
        live.setStatus(1);
        live.setContents("周末夜跑打卡路线");
        when(squares.selectUmsCreativeSquareById(ID)).thenReturn(live);
        when(squares.updateUmsCreativeSquare(any())).thenReturn(1);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_SQUARE));

        String card = noticeTo(AUTHOR);
        assertTrue(card.contains("广场帖「周末夜跑打卡路线」"), card);
        assertTrue(card.contains("\"bizType\":\"SQUARE\""), card);
        assertTrue(card.contains("IMAGE_RISK"), card);
    }

    @Test
    void squareAlreadyTakenDownIsNotNotifiedAgain() {
        IUmsCreativeSquareService squares = mock(IUmsCreativeSquareService.class);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squares);
        UmsCreativeSquare down = new UmsCreativeSquare();
        down.setId(ID);
        down.setMemberId(AUTHOR);
        down.setStatus(2);
        when(squares.selectUmsCreativeSquareById(ID)).thenReturn(down);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_SQUARE));

        noNotice();
    }

    @Test
    void topicTakedownNotifiesTheOrganizerOncePerTakedown() {
        ICmsTopicService topics = mock(ICmsTopicService.class);
        ReflectionTestUtils.setField(service, "cmsTopicService", topics);
        CmsTopic live = new CmsTopic();
        live.setId(ID);
        live.setMemberId(AUTHOR);
        live.setName("城市夜光寻宝");
        live.setUserStatus(1);
        live.setStatus(1);
        CmsTopic down = new CmsTopic();
        down.setId(ID);
        down.setMemberId(AUTHOR);
        down.setUserStatus(0);
        down.setStatus(1);
        down.setCheckStatus(2);
        when(topics.selectCmsTopicById(ID)).thenReturn(live, down);
        when(topics.updateCmsTopic(any())).thenReturn(1);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_TOPIC));
        // 同一主题第二张图的违规回调:已下架已留置,不再通知
        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_TOPIC));

        assertTrue(noticeTo(AUTHOR).contains("主题「城市夜光寻宝」"));
    }

    @Test
    void activityTakedownNotifiesTheCreator() {
        ICmsActivityService activities = mock(ICmsActivityService.class);
        ReflectionTestUtils.setField(service, "cmsActivityService", activities);
        CmsActivity live = new CmsActivity();
        live.setId(ID);
        live.setMemberId(AUTHOR);
        live.setName("周六徒步");
        live.setPublishStatus(1L);
        when(activities.selectCmsActivityById(ID)).thenReturn(live);
        when(activities.updateCmsActivity(any())).thenReturn(1);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_ACTIVITY));

        assertTrue(noticeTo(AUTHOR).contains("活动「周六徒步」"));
    }

    @Test
    void clubPostTakedownNotifiesTheAuthor() {
        IClubPostService posts = mock(IClubPostService.class);
        ReflectionTestUtils.setField(service, "clubPostService", posts);
        ClubPost live = new ClubPost();
        live.setId(ID);
        live.setAuthorMemberId(AUTHOR);
        live.setContent("今晚集合");
        live.setDelFlag(0);
        ClubPost removed = new ClubPost();
        removed.setId(ID);
        removed.setAuthorMemberId(AUTHOR);
        removed.setDelFlag(2);
        when(posts.selectClubPostByIdForUpdate(ID)).thenReturn(live, removed);
        // 软删 SQL 无 del_flag 条件,驱动按 found rows 计数:已删的再删也回 1
        when(posts.deleteClubPostById(ID)).thenReturn(1);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_CLUB_POST));
        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_CLUB_POST));

        assertTrue(noticeTo(AUTHOR).contains("俱乐部动态「今晚集合」"));
    }

    @Test
    void templateTakedownNotifiesTheAuthorOnceForAllProjections() {
        CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        CmsTemplateLibraryMapper libraries = mock(CmsTemplateLibraryMapper.class);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", templates);
        ReflectionTestUtils.setField(service, "cmsTemplateLibraryMapper", libraries);
        ReflectionTestUtils.setField(service, "auditTaskMapper", mock(AuditTaskMapper.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<IAuditTaskService> audits = mock(ObjectProvider.class);
        when(audits.getObject()).thenReturn(mock(IAuditTaskService.class));
        ReflectionTestUtils.setField(service, "auditTaskServiceProvider", audits);
        CmsMemberTemplate template = new CmsMemberTemplate();
        template.setId(ID);
        template.setMemberId(AUTHOR);
        template.setTitle("拍照打卡玩法");
        when(templates.selectCmsMemberTemplateById(ID)).thenReturn(template);
        when(libraries.selectByMemberTemplateForUpdate(ID)).thenReturn(java.util.Arrays.asList(listed(41L), listed(42L)));
        when(libraries.updateCmsTemplateLibrary(any())).thenReturn(1);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_TEMPLATE));

        assertTrue(noticeTo(AUTHOR).contains("玩法模板「拍照打卡玩法」"));
    }

    @Test
    void memberPublishedOfficialEventNotifiesThePublisherButAdminCreatedDoesNot() {
        OfficialEventMapper events = mock(OfficialEventMapper.class);
        ReflectionTestUtils.setField(service, "officialEventMapper", events);
        OfficialEvent memberEvent = officialEvent(OfficialEventParty.IDENTITY_UMS_MEMBER);
        when(events.selectOfficialEventById(ID)).thenReturn(memberEvent);
        when(events.updateOfficialEvent(any())).thenReturn(1);

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT));
        assertTrue(noticeTo(AUTHOR).contains("官方活动「城市灯光节」"));

        // 后台账号创建:sender_account 是后台 userId,不能当会员 id 发系统消息
        im = mock(IImService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(im);
        ReflectionTestUtils.setField(service, "imServiceProvider", provider);
        when(events.selectOfficialEventById(ID)).thenReturn(officialEvent(null));

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_OFFICIAL_EVENT));
        noNotice();
    }

    @Test
    void noticeFailureDoesNotBlockTheTakedown() {
        IUmsCreativeSquareService squares = mock(IUmsCreativeSquareService.class);
        ReflectionTestUtils.setField(service, "umsCreativeSquareService", squares);
        UmsCreativeSquare live = new UmsCreativeSquare();
        live.setId(ID);
        live.setMemberId(AUTHOR);
        live.setStatus(1);
        when(squares.selectUmsCreativeSquareById(ID)).thenReturn(live);
        when(squares.updateUmsCreativeSquare(any())).thenReturn(1);
        when(im.sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("im down"));

        assertTrue(risky(IWxContentSecurityService.BIZ_TYPE_SQUARE));
        verify(squares).updateUmsCreativeSquare(any());
    }

    @Test
    void communityPostHiddenByImageNotifiesTheAuthorOnlyOnTheTransition() {
        CommunityPostMapper mapper = mock(CommunityPostMapper.class);
        IAuditTaskService audits = mock(IAuditTaskService.class);
        CommunityPostServiceImpl posts = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(),
                mock(IContentSafetyGate.class), audits, mock(CommunityTrustService.class),
                mock(ICommunityMediaReceiptService.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<IImService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(im);
        posts.setImServiceProvider(provider);
        CommunityPost live = new CommunityPost();
        live.setId(ID);
        live.setAuthorId(AUTHOR);
        live.setLifecycle("PUBLISHED");
        live.setVersion(4);
        live.setTitle("江边日落");
        CommunityPost hidden = new CommunityPost();
        hidden.setId(ID);
        hidden.setAuthorId(AUTHOR);
        hidden.setLifecycle("HIDDEN");
        hidden.setVersion(5);
        when(mapper.selectByIdForUpdate(ID)).thenReturn(live, hidden);
        when(mapper.transitionCas(eq(ID), eq("PUBLISHED"), eq("HIDDEN"), eq(4), any(), any(), anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(1);
        when(mapper.selectMediaForSafety(ID)).thenReturn(Collections.emptyList());
        when(mapper.insertOutbox(anyString(), anyLong(), anyString(), anyString(), any())).thenReturn(1);

        assertTrue(posts.applyRiskyMediaResult(ID));
        assertTrue(posts.applyRiskyMediaResult(ID));

        String card = noticeTo(AUTHOR);
        assertTrue(card.contains("帖文「江边日落」"), card);
        assertTrue(card.contains("\"bizType\":\"" + AuditTask.BIZ_COMMUNITY_POST + "\""), card);
        verify(im, times(1)).sendSystemInNestedTransaction(anyLong(), anyInt(), anyString(), anyString());
    }

    private static CmsTemplateLibrary listed(long id) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(id);
        row.setMemberTemplateId(ID);
        row.setDelFlag(0);
        row.setStatus(1L);
        row.setPublishStatus(1L);
        return row;
    }

    private static OfficialEvent officialEvent(String identitySource) {
        OfficialEvent event = new OfficialEvent();
        event.setId(ID);
        event.setTitle("城市灯光节");
        event.setStatus(OfficialEvent.ST_SIGNUP);
        event.setSenderAccount(AUTHOR);
        event.setCreatorIdentitySource(identitySource);
        return event;
    }
}
