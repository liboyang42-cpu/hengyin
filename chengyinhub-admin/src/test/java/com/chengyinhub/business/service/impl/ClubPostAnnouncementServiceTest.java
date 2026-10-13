package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRevision;
import com.chengyinhub.business.domain.dto.ClubPostUpdateResult;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostRevisionMapper;
import com.chengyinhub.business.service.IClubPostMediaReviewService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubPostAnnouncementServiceTest {
    private static final Long CLUB_ID = 73L;
    private static final Long POST_ID = 81L;
    private static final Long OWNER_ID = 1L;
    private static final Long ACTOR_ID = 42L;

    private ClubPostServiceImpl service;
    private ClubPostMapper postMapper;
    private ClubPostRevisionMapper revisionMapper;
    private IClubAccessService accessService;
    private BusinessAuditEventMapper auditMapper;
    private ClubMapper clubMapper;
    private ClubMemberMapper memberMapper;
    private IClubPostMediaReviewService mediaReviewService;

    @BeforeEach
    void setUp() {
        service = new ClubPostServiceImpl();
        postMapper = mock(ClubPostMapper.class);
        revisionMapper = mock(ClubPostRevisionMapper.class);
        accessService = mock(IClubAccessService.class);
        auditMapper = mock(BusinessAuditEventMapper.class);
        clubMapper = mock(ClubMapper.class);
        memberMapper = mock(ClubMemberMapper.class);
        mediaReviewService = mock(IClubPostMediaReviewService.class);
        ReflectionTestUtils.setField(service, "clubPostMapper", postMapper);
        ReflectionTestUtils.setField(service, "clubPostRevisionMapper", revisionMapper);
        ReflectionTestUtils.setField(service, "clubAccessService", accessService);
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", auditMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "clubPostMediaReviewService", mediaReviewService);
    }

    @Test
    void authorEditArchivesPreviousSnapshotThenCasUpdatesAndAudits() {
        ClubPost locked = post(0, 2, ACTOR_ID, 0);
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(locked);
        when(accessService.resolve(ACTOR_ID, CLUB_ID, null)).thenReturn(access(ClubRoleCode.CLUB_MEMBER));
        when(revisionMapper.insertClubPostRevision(any())).thenReturn(1);
        when(postMapper.updateContentCas(eq(POST_ID), eq("新正文"), eq("a.jpg"), eq(2), any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(locked);

        service.updatePost(ACTOR_ID, POST_ID, " 新正文 ", "a.jpg", 2, "edit-1");

        ArgumentCaptor<ClubPostRevision> revision = ArgumentCaptor.forClass(ClubPostRevision.class);
        verify(revisionMapper).insertClubPostRevision(revision.capture());
        assertEquals("旧正文", revision.getValue().getContent());
        assertEquals(2, revision.getValue().getSnapshotVersion());
        assertEquals(ACTOR_ID, revision.getValue().getEditorMemberId());
        ArgumentCaptor<BusinessAuditEvent> audit = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(auditMapper).insertBusinessAuditEvent(audit.capture());
        assertEquals("CLUB_POST_UPDATE", audit.getValue().getAction());
        assertEquals("edit-1", audit.getValue().getRequestId());
        verify(mediaReviewService).enqueueUpdatedMedia(POST_ID, 3, ACTOR_ID, "a.jpg");
    }

    @Test
    void exactUpdateReplayReturnsSuccessButSameRequestWithChangedPayloadConflicts() {
        ClubPost before = post(0, 2, ACTOR_ID, 0);
        ClubPost after = post(0, 3, ACTOR_ID, 0);
        after.setContent("新正文");
        after.setImages("a.jpg");
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(before, after, after);
        when(accessService.resolve(ACTOR_ID, CLUB_ID, null)).thenReturn(access(ClubRoleCode.CLUB_MEMBER));
        when(revisionMapper.insertClubPostRevision(any())).thenReturn(1);
        when(postMapper.updateContentCas(eq(POST_ID), eq("新正文"), eq("a.jpg"), eq(2), any())).thenReturn(1);
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        when(auditMapper.selectByDomainActionRequestForUpdate(
                "CLUB", CLUB_ID, "CLUB_POST_UPDATE", "edit-replay-1"))
                .thenAnswer(call -> receipt.get());
        when(auditMapper.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(after);

        ClubPostUpdateResult first = service.updatePost(ACTOR_ID, POST_ID, "新正文", "a.jpg", 2, "edit-replay-1");
        ClubPostUpdateResult replay = service.updatePost(ACTOR_ID, POST_ID, "新正文", "a.jpg", 2, "edit-replay-1");
        ServiceException tampered = assertThrows(ServiceException.class,
                () -> service.updatePost(ACTOR_ID, POST_ID, "篡改正文", "a.jpg", 2, "edit-replay-1"));

        assertEquals(3, first.getPost().getVersion());
        assertEquals(3, replay.getPost().getVersion());
        org.junit.jupiter.api.Assertions.assertTrue(first.isMutated());
        org.junit.jupiter.api.Assertions.assertFalse(replay.isMutated());
        assertEquals(HttpStatus.CONFLICT, tampered.getCode());
        verify(revisionMapper, times(1)).insertClubPostRevision(any());
        verify(postMapper, times(1)).updateContentCas(eq(POST_ID), eq("新正文"), eq("a.jpg"), eq(2), any());
        verify(auditMapper, times(1)).insertBusinessAuditEvent(any());
        verify(mediaReviewService, times(1)).enqueueUpdatedMedia(POST_ID, 3, ACTOR_ID, "a.jpg");
        org.junit.jupiter.api.Assertions.assertTrue(receipt.get().getReason().startsWith("requestFingerprint="));
    }

    @Test
    void contentOperatorMayEditAnnouncementButCannotRewriteMemberPost() {
        ClubAccessContext operator = access(ClubRoleCode.CLUB_OPERATOR);
        ClubPost announcement = post(2, 0, OWNER_ID, 0);
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(announcement);
        when(accessService.resolve(ACTOR_ID, CLUB_ID, null)).thenReturn(operator);
        when(revisionMapper.insertClubPostRevision(any())).thenReturn(1);
        when(postMapper.updateContentCas(eq(POST_ID), eq("新公告"), eq(""), eq(0), any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(announcement);

        service.updatePost(ACTOR_ID, POST_ID, "新公告", "", 0, "announcement-edit");
        verify(postMapper).updateContentCas(eq(POST_ID), eq("新公告"), eq(""), eq(0), any());

        ClubPost memberPost = post(0, 0, OWNER_ID, 0);
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(memberPost);
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updatePost(ACTOR_ID, POST_ID, "篡改", "", 0, "bad"));
        assertEquals(HttpStatus.FORBIDDEN, error.getCode());
    }

    @Test
    void staleOrCrossClubEditFailsBeforeRevisionWrite() {
        ClubPost locked = post(0, 4, ACTOR_ID, 0);
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(locked);
        when(accessService.resolve(ACTOR_ID, CLUB_ID, null)).thenReturn(access(ClubRoleCode.CLUB_MEMBER));
        ServiceException stale = assertThrows(ServiceException.class,
                () -> service.updatePost(ACTOR_ID, POST_ID, "新正文", "", 3, "stale"));
        assertEquals(HttpStatus.CONFLICT, stale.getCode());
        verify(revisionMapper, never()).insertClubPostRevision(any());

        when(accessService.resolve(ACTOR_ID, CLUB_ID, null)).thenReturn(
                new ClubAccessContext(ACTOR_ID, 999L, OWNER_ID, null,
                        new LinkedHashSet<>(Collections.singletonList(ClubRoleCode.CLUB_MEMBER))));
        ServiceException crossClub = assertThrows(ServiceException.class,
                () -> service.updatePost(ACTOR_ID, POST_ID, "新正文", "", 4, "cross"));
        assertEquals(HttpStatus.FORBIDDEN, crossClub.getCode());
    }

    @Test
    void exactPinReplayReturnsAuthoritativeRowAndChangedPayloadConflicts() {
        ClubPost announcement = post(2, 3, OWNER_ID, 0);
        ClubPost pinned = post(2, 4, OWNER_ID, 1);
        ClubAccessContext operator = access(ClubRoleCode.CLUB_OPERATOR);
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(announcement, pinned, pinned);
        when(accessService.require(ACTOR_ID, CLUB_ID, null, ClubPermission.CONTENT_MANAGE)).thenReturn(operator);
        when(postMapper.updatePinnedCas(eq(POST_ID), eq(1), any(), eq(3), any())).thenReturn(1);
        AtomicReference<BusinessAuditEvent> receipt = new AtomicReference<>();
        when(auditMapper.selectByDomainActionRequestForUpdate(
                eq("CLUB"), eq(CLUB_ID), any(), eq("pin-replay-1")))
                .thenAnswer(call -> {
                    BusinessAuditEvent stored = receipt.get();
                    return stored != null && call.getArgument(2).equals(stored.getAction()) ? stored : null;
                });
        when(auditMapper.insertBusinessAuditEvent(any())).thenAnswer(call -> {
            receipt.set(call.getArgument(0, BusinessAuditEvent.class));
            return 1;
        });
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(pinned);

        ClubPost first = service.setPinned(ACTOR_ID, POST_ID, true, 3, "pin-replay-1");
        ClubPost replay = service.setPinned(ACTOR_ID, POST_ID, true, 3, "pin-replay-1");
        ServiceException changedPayload = assertThrows(ServiceException.class,
                () -> service.setPinned(ACTOR_ID, POST_ID, false, 3, "pin-replay-1"));

        assertEquals(Integer.valueOf(4), first.getVersion());
        assertEquals(Integer.valueOf(4), replay.getVersion());
        assertEquals(HttpStatus.CONFLICT, changedPayload.getCode());
        verify(postMapper, times(1)).updatePinnedCas(eq(POST_ID), eq(1), any(), eq(3), any());
        verify(auditMapper, times(1)).insertBusinessAuditEvent(any());
        org.junit.jupiter.api.Assertions.assertTrue(receipt.get().getReason().startsWith("requestFingerprint="));
    }

    @Test
    void ordinaryPostCannotBePinnedAndAnnouncementCreateRequiresContentPermission() {
        ClubPost ordinary = post(0, 0, ACTOR_ID, 0);
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(ordinary);
        when(accessService.require(ACTOR_ID, CLUB_ID, null, ClubPermission.CONTENT_MANAGE))
                .thenReturn(access(ClubRoleCode.CLUB_OPERATOR));
        ServiceException notAnnouncement = assertThrows(ServiceException.class,
                () -> service.setPinned(ACTOR_ID, POST_ID, true, 0, "bad-pin"));
        assertEquals(HttpStatus.BAD_REQUEST, notAnnouncement.getCode());

        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(OWNER_ID);
        ClubMember membership = new ClubMember();
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        when(memberMapper.selectByClubAndMember(CLUB_ID, ACTOR_ID)).thenReturn(membership);
        when(postMapper.insertClubPost(any())).thenReturn(1);
        ClubPost announcement = post(2, 0, ACTOR_ID, 0);
        announcement.setId(null);
        when(accessService.require(ACTOR_ID, CLUB_ID, null, ClubPermission.CONTENT_MANAGE))
                .thenThrow(new ServiceException("权限不足", HttpStatus.FORBIDDEN));
        ServiceException denied = assertThrows(ServiceException.class,
                () -> service.insertClubPostInActiveClub(announcement, ACTOR_ID));
        assertEquals(HttpStatus.FORBIDDEN, denied.getCode());
        verify(postMapper, never()).insertClubPost(announcement);
    }

    @Test
    void createWithImagesCommitsVersionZeroOutboxInThePostTransaction() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(ACTOR_ID);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        when(postMapper.insertClubPost(any())).thenAnswer(invocation -> {
            ClubPost inserted = invocation.getArgument(0);
            inserted.setId(POST_ID);
            return 1;
        });
        ClubPost created = post(0, 0, ACTOR_ID, 0);
        created.setId(null);
        created.setImages("https://cdn.example.test/club/new.jpg");

        assertEquals(1, service.insertClubPostInActiveClub(created, ACTOR_ID));

        verify(mediaReviewService).enqueueUpdatedMedia(
                POST_ID, 0, ACTOR_ID, "https://cdn.example.test/club/new.jpg");
    }

    @Test
    void addingOneImageDoesNotRequeueTheRetainedImageAtANewVersion() {
        ClubPost locked = post(0, 2, ACTOR_ID, 0);
        locked.setImages("https://cdn.example.test/club/retained.jpg");
        when(postMapper.selectClubPostByIdForUpdate(POST_ID)).thenReturn(locked);
        when(accessService.resolve(ACTOR_ID, CLUB_ID, null)).thenReturn(access(ClubRoleCode.CLUB_MEMBER));
        when(revisionMapper.insertClubPostRevision(any())).thenReturn(1);
        when(postMapper.updateContentCas(eq(POST_ID), eq("旧正文"),
                eq("https://cdn.example.test/club/retained.jpg;https://cdn.example.test/club/new.jpg"),
                eq(2), any())).thenReturn(1);
        when(auditMapper.insertBusinessAuditEvent(any())).thenReturn(1);
        ClubPost updated = post(0, 3, ACTOR_ID, 0);
        updated.setImages("https://cdn.example.test/club/retained.jpg;https://cdn.example.test/club/new.jpg");
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(updated);

        service.updatePost(ACTOR_ID, POST_ID, "旧正文",
                "https://cdn.example.test/club/retained.jpg;https://cdn.example.test/club/new.jpg",
                2, "add-one-image");

        verify(mediaReviewService).enqueueUpdatedMedia(
                POST_ID, 3, ACTOR_ID, "https://cdn.example.test/club/new.jpg");
    }

    @Test
    void dissolvedClubHidesPreviouslyPublicRevisionHistory() {
        ClubPost active = post(0, 2, ACTOR_ID, 0);
        when(postMapper.selectClubPostById(POST_ID)).thenReturn(active);
        when(clubMapper.selectClubById(CLUB_ID)).thenReturn(null);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.listRevisionHistory(POST_ID));

        assertEquals(HttpStatus.BAD_REQUEST, error.getCode());
        verify(revisionMapper, never()).selectByPostId(POST_ID);
    }

    private ClubAccessContext access(String role) {
        return new ClubAccessContext(ACTOR_ID, CLUB_ID, OWNER_ID, null,
                new LinkedHashSet<>(Collections.singletonList(role)));
    }

    private ClubPost post(int type, int version, Long authorId, int pinned) {
        ClubPost post = new ClubPost();
        post.setId(POST_ID);
        post.setClubId(CLUB_ID);
        post.setAuthorMemberId(authorId);
        post.setContent("旧正文");
        post.setImages("");
        post.setType(type);
        post.setVersion(version);
        post.setIsPinned(pinned);
        post.setDelFlag(0);
        return post;
    }
}
