package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubPostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 发动态与解散共用 club 行锁，并在锁内复核活跃成员身份。 */
class ClubPostServiceImplLifecycleTest {

    private static final Long CLUB_ID = 73L;
    private static final Long MEMBER_ID = 42L;

    private ClubPostServiceImpl service;
    private ClubMapper clubMapper;
    private ClubMemberMapper clubMemberMapper;
    private ClubPostMapper clubPostMapper;

    @BeforeEach
    void setUp() {
        service = new ClubPostServiceImpl();
        clubMapper = mock(ClubMapper.class);
        clubMemberMapper = mock(ClubMemberMapper.class);
        clubPostMapper = mock(ClubPostMapper.class);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubMemberMapper", clubMemberMapper);
        ReflectionTestUtils.setField(service, "clubPostMapper", clubPostMapper);
    }

    @Test
    void dissolvedClubIsRejectedBeforeMembershipReadOrInsert() {
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.insertClubPostInActiveClub(post(), MEMBER_ID));

        assertEquals("俱乐部不存在或已解散", error.getMessage());
        verify(clubMemberMapper, never()).selectByClubAndMember(any(), any());
        verify(clubPostMapper, never()).insertClubPost(any());
    }

    @Test
    void activeOwnerLocksClubBeforeInsertWithoutMemberRow() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(MEMBER_ID);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        when(clubPostMapper.insertClubPost(any())).thenReturn(1);
        ClubPost post = post();

        assertEquals(1, service.insertClubPostInActiveClub(post, MEMBER_ID));

        org.mockito.InOrder order = inOrder(clubMapper, clubPostMapper);
        order.verify(clubMapper).selectClubByIdForUpdate(CLUB_ID);
        order.verify(clubPostMapper).insertClubPost(post);
        verify(clubMemberMapper, never()).selectByClubAndMember(any(), any());
        assertNotNull(post.getCreateTime());
    }

    @Test
    void outsiderIsRejectedByActiveMembershipGateBeforeInsert() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(99L);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.insertClubPostInActiveClub(post(), MEMBER_ID));

        assertEquals("加入俱乐部后才能发动态", error.getMessage());
        verify(clubPostMapper, never()).insertClubPost(any());
    }

    @Test
    void activeMemberIsRecheckedAfterLockThenInserted() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(99L);
        ClubMember membership = new ClubMember();
        membership.setClubId(CLUB_ID);
        membership.setMemberId(MEMBER_ID);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        when(clubMemberMapper.selectByClubAndMember(CLUB_ID, MEMBER_ID)).thenReturn(membership);
        when(clubPostMapper.insertClubPost(any())).thenReturn(1);
        ClubPost post = post();

        assertEquals(1, service.insertClubPostInActiveClub(post, MEMBER_ID));

        org.mockito.InOrder order = inOrder(clubMapper, clubMemberMapper, clubPostMapper);
        order.verify(clubMapper).selectClubByIdForUpdate(CLUB_ID);
        order.verify(clubMemberMapper).selectByClubAndMember(CLUB_ID, MEMBER_ID);
        order.verify(clubPostMapper).insertClubPost(post);
    }

    @Test
    void contentAtThreeHundredCharactersStillPublishes() {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(MEMBER_ID);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        when(clubPostMapper.insertClubPost(any())).thenReturn(1);
        ClubPost post = post();
        post.setContent(new String(new char[300]).replace('\0', '文'));
        assertEquals(1, service.insertClubPostInActiveClub(post, MEMBER_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "images", "blank"})
    void bothCreatePathsRejectInvalidContentBeforePersistence(String invalid) {
        Club club = new Club();
        club.setId(CLUB_ID);
        club.setMemberId(MEMBER_ID);
        when(clubMapper.selectClubByIdForUpdate(CLUB_ID)).thenReturn(club);
        ClubPost post = post();
        if ("content".equals(invalid)) post.setContent(new String(new char[301]).replace('\0', '文'));
        if ("images".equals(invalid)) post.setImages(new String(new char[2001]).replace('\0', 'a'));
        if ("blank".equals(invalid)) { post.setContent("  "); post.setImages("  "); }

        assertThrows(ServiceException.class, () -> service.insertClubPost(post));
        assertThrows(ServiceException.class, () -> service.insertClubPostInActiveClub(post, MEMBER_ID));
        verify(clubPostMapper, never()).insertClubPost(any());
    }

    @Test
    void activeClubInsertMethodOwnsTheTransaction() throws Exception {
        Method method = ClubPostServiceImpl.class.getMethod(
                "insertClubPostInActiveClub", ClubPost.class, Long.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertNotNull(annotation, "FOR UPDATE 与 insert 必须由同一个 service 事务包住");
        assertEquals(1, annotation.rollbackFor().length);
        assertEquals(Exception.class, annotation.rollbackFor()[0]);
    }

    private ClubPost post() {
        ClubPost post = new ClubPost();
        post.setClubId(CLUB_ID);
        post.setAuthorMemberId(MEMBER_ID);
        post.setContent("内容");
        post.setType(0);
        return post;
    }
}
