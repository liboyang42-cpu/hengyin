package com.chengyinhub.business.longrun;

import com.alibaba.fastjson2.JSON;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.impl.RedemptionRewardClawbackService;
import com.chengyinhub.business.service.impl.TopicCompletionCouponRewardServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Source only; ordinary collaborator-boundary tests, not MySQL/transaction proof. */
class E04_07Test {
    RedemptionRewardClawbackService consumer;
    CmsTopicCompletionCouponRewardMapper rewards;
    SmsCouponHistoryMapper histories;
    UmsMemberMapper members;
    UmsMemberPointsDetailMapper points;
    GrowthMissionProgressMapper progress;
    TopicCompletionXpAwardMapper xp;
    BusinessAuditEventMapper audits;
    MemberGrowthMapper growth;

    @BeforeEach void setUp() {
        consumer = new RedemptionRewardClawbackService();
        rewards = mock(CmsTopicCompletionCouponRewardMapper.class);
        histories = mock(SmsCouponHistoryMapper.class);
        members = mock(UmsMemberMapper.class);
        points = mock(UmsMemberPointsDetailMapper.class);
        progress = mock(GrowthMissionProgressMapper.class);
        xp = mock(TopicCompletionXpAwardMapper.class);
        audits = mock(BusinessAuditEventMapper.class);
        ReflectionTestUtils.setField(consumer,"completionCouponRewardMapper",rewards);
        ReflectionTestUtils.setField(consumer,"smsCouponHistoryMapper",histories);
        ReflectionTestUtils.setField(consumer,"umsMemberMapper",members);
        ReflectionTestUtils.setField(consumer,"pointsDetailMapper",points);
        ReflectionTestUtils.setField(consumer,"growthMissionProgressMapper",progress);
        ReflectionTestUtils.setField(consumer,"topicCompletionXpAwardMapper",xp);
        ReflectionTestUtils.setField(consumer,"businessAuditEventMapper",audits);
        growth = mock(MemberGrowthMapper.class);
        ReflectionTestUtils.setField(consumer,"memberGrowthMapper",growth);
        when(rewards.selectCouponId(700L,501L)).thenReturn(30L);
        when(audits.insertBusinessAuditEvent(any())).thenReturn(1);
        when(progress.insertMissionProgress(any())).thenReturn(1);
    }

    @Test void missingOriginalIdentityOnlyAuditsAndKeepsCouponAndGrantUnchanged() {
        when(histories.selectCompletionRewardHistoryId(501L,700L)).thenReturn(null);
        reverse();
        assertEquals("UNKNOWN_IDENTITY_NOT_RECOVERED",couponAudit().get("result"));
        verify(histories,never()).selectSmsCouponHistoryByIdForUpdate(anyLong());
        noCouponMutation();
        verify(progress,never()).insertMissionProgress(any());
    }

    @Test void missingOriginalIdentityDoesNotBlockAuthorizedCompletionPointsAndXpClawback() {
        when(histories.selectCompletionRewardHistoryId(501L,700L)).thenReturn(null);
        UmsMemberPointsDetail grant = new UmsMemberPointsDetail();
        grant.setId(11L); grant.setChangePoints(5L);
        when(points.selectGrantForClawback(501L,13L,8001L)).thenReturn(grant);
        when(points.detachGrant(eq(11L),anyString())).thenReturn(1);
        when(members.selectPointsForUpdate(501L)).thenReturn(10L);
        when(members.deductPoints(501L,5)).thenReturn(1);
        when(points.insertUmsMemberPointsDetail(any())).thenReturn(1);
        when(xp.selectXpForUpdate(501L,700L)).thenReturn(3);
        // master 校验通关经验发放事实删除恰为 1 行，并按 earned_exp_id 冲回原笔。
        when(xp.deleteAward(501L,700L)).thenReturn(1);
        when(xp.selectEarnedExpId(501L,700L)).thenReturn(null); // 历史直发无原笔获得明细
        when(growth.selectExpForUpdate(501L)).thenReturn(8);
        when(growth.deductExp(501L,3)).thenReturn(1);
        reverse();
        verify(points).insertUmsMemberPointsDetail(argThat(p -> Long.valueOf(5).equals(p.getChangePoints())));
        verify(xp).deleteAward(501L,700L);
        verify(growth).deductExp(501L,3);
        verify(progress).insertMissionProgress(argThat(p -> RedemptionRewardClawbackService.MARKER_COMPLETION_XP.equals(p.getMissionCode())));
        verify(progress).insertMissionProgress(argThat(p -> RedemptionRewardClawbackService.MARKER_COMPLETION_POINTS.equals(p.getMissionCode())));
        verify(progress,never()).insertMissionProgress(argThat(p -> RedemptionRewardClawbackService.MARKER_COMPLETION_COUPON.equals(p.getMissionCode())));
        assertEquals("UNKNOWN_IDENTITY_NOT_RECOVERED",couponAudit().get("result"));
        noCouponMutation();
    }

    @Test void nonPositiveOriginalReceiptIsUnknownBeforeAnyHistoryLock() {
        when(histories.selectCompletionRewardHistoryId(501L,700L)).thenReturn(0L,-1L);
        reverse(); reverse();
        verify(histories,never()).selectSmsCouponHistoryByIdForUpdate(anyLong());
        noCouponMutation();
        verify(progress,never()).insertMissionProgress(any());
        verify(audits,times(2)).insertBusinessAuditEvent(argThat(a -> a.getAfterJson().contains("UNKNOWN_IDENTITY_NOT_RECOVERED")));
    }

    @Test void lockedHistoryMustStillMatchOriginalMemberTemplateAndLiveIdentity() {
        when(histories.selectCompletionRewardHistoryId(501L,700L)).thenReturn(41L);
        SmsCouponHistory wrongMember = history(30L,999L,0); 
        SmsCouponHistory wrongCoupon = history(31L,501L,0);
        SmsCouponHistory deleted = history(30L,501L,0); deleted.setDelFlag(1);
        SmsCouponHistory wrongId = history(30L,501L,0); wrongId.setId(42L);
        when(histories.selectSmsCouponHistoryByIdForUpdate(41L)).thenReturn(wrongMember,wrongCoupon,deleted,wrongId,null);
        for (int i=0;i<5;i++) { reverse(); }
        noCouponMutation();
        verify(progress,never()).insertMissionProgress(any());
        verify(audits,times(5)).insertBusinessAuditEvent(argThat(a -> a.getAfterJson().contains("UNKNOWN_IDENTITY_NOT_RECOVERED")));
    }

    @Test void usedOriginalCouponIsAuditedWithoutDeletingGrantOrFindingAReplacement() {
        when(histories.selectCompletionRewardHistoryId(501L,700L)).thenReturn(41L);
        when(histories.selectSmsCouponHistoryByIdForUpdate(41L)).thenReturn(history(30L,501L,1));
        when(histories.voidUnusedCoupon(eq(41L),anyString())).thenReturn(0);
        reverse();
        assertEquals("USED_NOT_RECOVERED",couponAudit().get("result"));
        verify(histories).selectCompletionRewardHistoryId(501L,700L);
        verify(histories).selectSmsCouponHistoryByIdForUpdate(41L);
        verify(rewards,never()).deleteGrant(anyLong(),anyLong());
        verify(progress,never()).insertMissionProgress(any());
    }

    @Test void producerBindsTheActualReceiptAndRejectsMissingReceiptOrFailedBinding() {
        TopicCompletionCouponRewardServiceImpl producer = new TopicCompletionCouponRewardServiceImpl();
        SmsCouponMapper coupons = mock(SmsCouponMapper.class);
        ISmsCouponHistoryService issuing = mock(ISmsCouponHistoryService.class);
        SmsCoupon coupon = new SmsCoupon(); coupon.setId(30L);
        ReflectionTestUtils.setField(producer,"rewardMapper",rewards);
        ReflectionTestUtils.setField(producer,"smsCouponMapper",coupons);
        ReflectionTestUtils.setField(producer,"smsCouponHistoryService",issuing);
        when(coupons.selectSmsCouponById(30L)).thenReturn(coupon);
        when(rewards.insertIgnore(700L,501L,30L)).thenReturn(1);
        when(issuing.grantCouponWithReceipt(501L,coupon)).thenReturn(41L,null,43L);
        when(rewards.bindHistoryId(700L,501L,30L,41L)).thenReturn(1);
        assertTrue(producer.grantOnce(700L,501L,30L));
        verify(rewards).bindHistoryId(700L,501L,30L,41L);
        assertThrows(IllegalStateException.class,() -> producer.grantOnce(700L,501L,30L));
        assertThrows(IllegalStateException.class,() -> producer.grantOnce(700L,501L,30L));
        verify(issuing,never()).grantCouponById(anyLong(),anyLong());
    }

    @Test void noCompletionGrantKeepsTheExistingNoRewardResultAndDoesNotInventACoupon() {
        when(rewards.selectCouponId(700L,501L)).thenReturn(null);
        reverse();
        assertNull(couponAudit());
        verifyNoInteractions(histories);
        verify(rewards,never()).deleteGrant(anyLong(),anyLong());
        verify(progress,never()).insertMissionProgress(any());
    }

    void reverse() {
        Redemption r = new Redemption(); r.setId(61L); r.setTopicId(700L); r.setRegistrationId(9001L);
        CmsRegistration registration = new CmsRegistration(); registration.setMemberId(501L); registration.setOwnerId(8001L);
        consumer.clawback(r,registration,false,true,"E04-07",1L);
    }
    Map<String,Object> couponAudit() {
        ArgumentCaptor<BusinessAuditEvent> cap = ArgumentCaptor.forClass(BusinessAuditEvent.class);
        verify(audits).insertBusinessAuditEvent(cap.capture());
        return JSON.parseObject(cap.getValue().getAfterJson()).getJSONObject("completionCoupon");
    }
    void noCouponMutation() {
        verify(histories,never()).voidUnusedCoupon(anyLong(),anyString());
        verify(rewards,never()).deleteGrant(anyLong(),anyLong());
    }
    SmsCouponHistory history(Long coupon,Long member,int state) {
        SmsCouponHistory h = new SmsCouponHistory(); h.setId(41L); h.setCouponId(coupon); h.setMemberId(member); h.setUseStatus(state); h.setDelFlag(0); return h;
    }
}
