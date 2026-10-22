package com.chengyinhub.business.service.impl;

import java.util.Arrays;
import java.util.Map;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberProfileVersion;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberProfileVersionMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #25:资料版本 + 头像审核回执只描述能证明的事。 */
class ProfileReviewServiceTest {
    private static final Long MEMBER = 42L;
    private static final String RAW_AVATAR = "https://img.example.test/a.png";

    private ProfileReviewService service;
    private UmsMemberProfileVersionMapper versions;
    private WxContentCallbackInboxMapper inbox;
    private UmsMemberMapper members;

    @BeforeEach
    void setUp() {
        service = new ProfileReviewService();
        versions = mock(UmsMemberProfileVersionMapper.class);
        inbox = mock(WxContentCallbackInboxMapper.class);
        members = mock(UmsMemberMapper.class);
        ReflectionTestUtils.setField(service, "versionMapper", versions);
        ReflectionTestUtils.setField(service, "inboxMapper", inbox);
        ReflectionTestUtils.setField(service, "umsMemberMapper", members);
    }

    private static UmsMemberProfileVersion version(int no, String ref, String hash) {
        UmsMemberProfileVersion v = new UmsMemberProfileVersion();
        v.setMemberId(MEMBER);
        v.setVersionNo(no);
        v.setAvatarRef(ref);
        v.setAvatarMediaHash(hash);
        v.setChangedFields("avatar");
        return v;
    }

    private static WxContentCallbackInbox row(Integer verdict, int state) {
        WxContentCallbackInbox r = new WxContentCallbackInbox();
        r.setVerdict(verdict);
        r.setState(state);
        return r;
    }

    private void currentAvatar(String ref) {
        UmsMember m = new UmsMember();
        m.setAvatar(ref);
        when(members.selectUmsMemberById(MEMBER)).thenReturn(m);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> avatarOf(Map<String, Object> receipt) {
        return (Map<String, Object>) receipt.get("avatar");
    }

    @Test
    void recordHashesTheSubmittedUrlAndStoresNoText() {
        when(versions.selectLatest(MEMBER)).thenReturn(version(3, "media-asset:9", null));
        Integer no = service.recordMemberSubmission(MEMBER, Arrays.asList("name", "avatar"), "media-asset:9", " " + RAW_AVATAR + " ");
        assertEquals(Integer.valueOf(3), no);
        ArgumentCaptor<UmsMemberProfileVersion> saved = ArgumentCaptor.forClass(UmsMemberProfileVersion.class);
        verify(versions).insertNextVersion(saved.capture());
        assertEquals("name,avatar", saved.getValue().getChangedFields());
        assertEquals("media-asset:9", saved.getValue().getAvatarRef());
        assertEquals(WxContentCallbackInboxServiceImpl.sha256Hex(RAW_AVATAR), saved.getValue().getAvatarMediaHash());
    }

    @Test
    void textOnlySubmissionHasNoAvatarAndConcurrentVersionRetriesOnce() {
        when(versions.insertNextVersion(any())).thenThrow(new DuplicateKeyException("dup")).thenReturn(1);
        when(versions.selectLatest(MEMBER)).thenReturn(version(5, null, null));
        assertEquals(Integer.valueOf(5), service.recordMemberSubmission(MEMBER, Arrays.asList("workAddress"), "", null));
        ArgumentCaptor<UmsMemberProfileVersion> saved = ArgumentCaptor.forClass(UmsMemberProfileVersion.class);
        verify(versions, times(2)).insertNextVersion(saved.capture());
        assertNull(saved.getValue().getAvatarRef());
        assertNull(saved.getValue().getAvatarMediaHash());
    }

    @Test
    void missingTableNeverBreaksTheSaveItReceipts() {
        when(versions.insertNextVersion(any())).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "x", new java.sql.SQLException()));
        assertNull(service.recordMemberSubmission(MEMBER, Arrays.asList("name"), null, null));
        when(versions.selectLatest(MEMBER)).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "x", new java.sql.SQLException()));
        assertEquals(false, service.memberReceipt(MEMBER).get("available"));
    }

    @Test
    void avatarStatesComeFromTheCallbackInbox() {
        String hash = WxContentCallbackInboxServiceImpl.sha256Hex(RAW_AVATAR);
        when(versions.selectLatest(MEMBER)).thenReturn(version(4, null, null));
        when(versions.selectLatestAvatarVersion(MEMBER)).thenReturn(version(3, "media-asset:9", hash));
        currentAvatar("media-asset:9");
        int bizType = IWxContentSecurityService.BIZ_TYPE_MEMBER_PROFILE;

        when(inbox.selectLatestByTargetMedia(bizType, MEMBER, hash)).thenReturn(null);
        assertEquals("NOT_SUBMITTED", avatarOf(service.memberReceipt(MEMBER)).get("state"));
        when(inbox.selectLatestByTargetMedia(bizType, MEMBER, hash)).thenReturn(row(null, WxContentCallbackInbox.STATE_WAITING));
        Map<String, Object> pending = service.memberReceipt(MEMBER);
        assertEquals("PENDING", avatarOf(pending).get("state"));
        assertEquals(true, avatarOf(pending).get("live"));
        assertEquals(4, pending.get("currentVersion"));
        when(inbox.selectLatestByTargetMedia(bizType, MEMBER, hash)).thenReturn(row(0, WxContentCallbackInbox.STATE_RETRYABLE));
        assertEquals("PENDING", avatarOf(service.memberReceipt(MEMBER)).get("state"), "通过但处置未收尾,不提前说通过");
        when(inbox.selectLatestByTargetMedia(bizType, MEMBER, hash)).thenReturn(row(0, WxContentCallbackInbox.STATE_MANUAL_REVIEW));
        assertEquals("PENDING", avatarOf(service.memberReceipt(MEMBER)).get("state"));
        when(inbox.selectLatestByTargetMedia(bizType, MEMBER, hash)).thenReturn(row(0, WxContentCallbackInbox.STATE_DONE));
        assertEquals("APPROVED", avatarOf(service.memberReceipt(MEMBER)).get("state"));
        when(inbox.selectLatestByTargetMedia(bizType, MEMBER, hash)).thenReturn(row(1, WxContentCallbackInbox.STATE_DONE));
        currentAvatar(null);
        Map<String, Object> rejected = service.memberReceipt(MEMBER);
        assertEquals("REJECTED", avatarOf(rejected).get("state"));
        assertEquals(false, avatarOf(rejected).get("live"));
    }

    @Test
    void noAvatarEverSubmittedHasNoAvatarReceipt() {
        when(versions.selectLatest(MEMBER)).thenReturn(version(1, null, null));
        currentAvatar("");
        Map<String, Object> receipt = service.memberReceipt(MEMBER);
        assertNull(receipt.get("avatar"));
        verify(inbox, never()).selectLatestByTargetMedia(anyInt(), anyLong(), anyString());
    }

    @Test
    void merchantNpcPendingIsNotVisibleAndPresetNeedsNoMediaReview() {
        NpcProfile npc = new NpcProfile();
        npc.setAuditStatus(0);
        npc.setEnabled(1);
        npc.setAvatar("px1:p01");
        Map<String, Object> pending = service.merchantNpcReceipt(7L, npc);
        assertEquals("PENDING", pending.get("review"));
        assertEquals(false, pending.get("playerVisible"));
        assertEquals("NOT_REQUIRED", pending.get("avatarReview"));

        npc.setAuditStatus(1);
        npc.setAvatar(RAW_AVATAR);
        npc.setId(91L);
        when(inbox.selectLatestByTargetMedia(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, 91L,
                WxContentCallbackInboxServiceImpl.sha256Hex(RAW_AVATAR))).thenReturn(row(null, WxContentCallbackInbox.STATE_WAITING));
        Map<String, Object> live = service.merchantNpcReceipt(7L, npc);
        assertEquals("APPROVED", live.get("review"));
        assertEquals(true, live.get("playerVisible"));
        assertEquals("PENDING", live.get("avatarReview"));

        npc.setAuditStatus(2);
        npc.setAuditReason("人设含广告");
        Map<String, Object> rejected = service.merchantNpcReceipt(7L, npc);
        assertEquals("REJECTED", rejected.get("review"));
        assertEquals("人设含广告", rejected.get("reviewReason"));
        assertEquals(false, service.merchantNpcReceipt(7L, null).get("hasProfile"));
    }

    /**
     * Batch-2 S1:回执必须读送检路径真正写入的目标 —— (BIZ_TYPE_MERCHANT_NPC=18, npc_profile.id),
     * 不是 (MERCHANT_PROFILE, merchantId)。走 未送检 → 送检待回调 → 回调通过 / 回调违规 全程。
     */
    @Test
    void merchantNpcAvatarReceiptFollowsTheNpcSubmissionThroughPendingToApproved() {
        NpcProfile npc = new NpcProfile();
        npc.setId(91L);
        npc.setAuditStatus(1);
        npc.setEnabled(1);
        npc.setAvatar(" " + RAW_AVATAR + " ");
        String hash = WxContentCallbackInboxServiceImpl.sha256Hex(RAW_AVATAR);
        // 商家 logo 恰好是同一张图且已过审:这条是 (6, merchantId),不能被门店分身头像回执读到
        when(inbox.selectLatestByTargetMedia(IWxContentSecurityService.BIZ_TYPE_MERCHANT_PROFILE, 7L, hash))
                .thenReturn(row(0, WxContentCallbackInbox.STATE_DONE));

        assertEquals("NOT_SUBMITTED", service.merchantNpcReceipt(7L, npc).get("avatarReview"), "还没送检");

        when(inbox.selectLatestByTargetMedia(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, 91L, hash))
                .thenReturn(row(null, WxContentCallbackInbox.STATE_WAITING))
                .thenReturn(row(0, WxContentCallbackInbox.STATE_DONE))
                .thenReturn(row(1, WxContentCallbackInbox.STATE_DONE));
        assertEquals("PENDING", service.merchantNpcReceipt(7L, npc).get("avatarReview"), "已送检、等回调");
        assertEquals("APPROVED", service.merchantNpcReceipt(7L, npc).get("avatarReview"), "回调通过");
        npc.setAuditStatus(2);
        Map<String, Object> rejected = service.merchantNpcReceipt(7L, npc);
        assertEquals("REJECTED", rejected.get("avatarReview"), "回调违规时回执如实显示驳回");
        assertEquals(false, rejected.get("playerVisible"));
        verify(inbox, never()).selectLatestByTargetMedia(IWxContentSecurityService.BIZ_TYPE_MERCHANT_PROFILE, 7L, hash);
    }
}
