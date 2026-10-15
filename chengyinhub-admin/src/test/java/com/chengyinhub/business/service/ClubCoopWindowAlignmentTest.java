package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopClubApplyMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.CoopClubApplyServiceImpl;
import com.chengyinhub.business.service.impl.CoopInviteServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F04:俱乐部合作池「能看见/能申请」与商家「能发邀约」必须同一个招商期口径。
 *
 * <p>990051 形态:商家发布的免费城市定向,openClubPool=1、审核上架、lifecycle=0(纯 UGC 不走商业
 * 生命周期)。修复前池和申请放行,/api/coop/invite 却被 RecruitmentWindowPolicy 以「不在招商期」拒。
 */
class ClubCoopWindowAlignmentTest {

    private static final Long TOPIC = 990051L;
    private static final Long MERCHANT = 300L;
    private static final Long CLUB = 77L;
    private static final Long LEADER = 42L;

    private CmsTopicMapper topicMapper;
    private CoopInviteMapper inviteMapper;
    private ClubMapper clubMapper;
    private CommercialTopicPolicy commercialTopicPolicy;
    private CoopInviteServiceImpl inviteService;
    private CoopClubApplyServiceImpl applyService;

    @BeforeEach
    void setUp() throws Exception {
        topicMapper = mock(CmsTopicMapper.class);
        inviteMapper = mock(CoopInviteMapper.class);
        clubMapper = mock(ClubMapper.class);
        commercialTopicPolicy = mock(CommercialTopicPolicy.class);
        when(commercialTopicPolicy.isFreeTopic(any())).thenCallRealMethod();
        MmsMerchantMapper merchantMapper = mock(MmsMerchantMapper.class);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setMemberId(MERCHANT);
        merchant.setStatus(1L);
        merchant.setAccountStatus(1L);
        merchant.setDelFlag(0);
        when(merchantMapper.selectMmsMerchantByMemberIdForUpdate(MERCHANT)).thenReturn(merchant);
        when(merchantMapper.selectMmsMerchantByMemberId(MERCHANT)).thenReturn(merchant);
        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(LEADER);
        club.setStatus(1L);
        when(clubMapper.selectClubById(CLUB)).thenReturn(club);
        when(clubMapper.selectClubByIdForUpdate(CLUB)).thenReturn(club);
        when(clubMapper.selectClubsByMemberId(LEADER)).thenReturn(Collections.singletonList(club));
        when(inviteMapper.selectCoopInviteList(any(CoopInvite.class))).thenReturn(Collections.emptyList());

        Object windowPolicy = windowPolicy();
        inviteService = new CoopInviteServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(inviteService, "commercialReviewService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.ITopicCommercialReviewService.class));
        ReflectionTestUtils.setField(inviteService, "coopInviteMapper", inviteMapper);
        ReflectionTestUtils.setField(inviteService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(inviteService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(inviteService, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(inviteService, "coopClubApplyService", mock(ICoopClubApplyService.class));
        injectIfPresent(inviteService, "clubCoopWindowPolicy", windowPolicy);

        applyService = new CoopClubApplyServiceImpl();
        ReflectionTestUtils.setField(applyService, "coopClubApplyMapper", mock(CoopClubApplyMapper.class));
        ReflectionTestUtils.setField(applyService, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(applyService, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(applyService, "coopInviteMapper", inviteMapper);
        injectIfPresent(applyService, "clubCoopWindowPolicy", windowPolicy);
    }

    /** 修复前 ClubCoopWindowPolicy 不存在;反射构造保证 RED 是业务断言红,不是编译红。 */
    private Object windowPolicy() throws Exception {
        try {
            Class<?> type = Class.forName("com.chengyinhub.business.service.support.ClubCoopWindowPolicy");
            return type.getConstructor(CommercialTopicPolicy.class).newInstance(commercialTopicPolicy);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    private static void injectIfPresent(Object target, String field, Object value) {
        if (value != null && ReflectionUtils.findField(target.getClass(), field) != null) {
            ReflectionTestUtils.setField(target, field, value);
        }
    }

    private CmsTopic givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation evaluation) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(MERCHANT);
        topic.setOpenClubPool(1);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setLifecycle(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
        when(commercialTopicPolicy.evaluate(TOPIC)).thenReturn(evaluation);
        return topic;
    }

    private static CoopInvite merchantToClub() {
        CoopInvite invite = new CoopInvite();
        invite.setInviteType(1);
        invite.setFromType("merchant");
        invite.setFromId(MERCHANT);
        invite.setToType("club");
        invite.setToId(CLUB);
        invite.setTopicId(TOPIC);
        invite.setStatus(0);
        return invite;
    }

    @Test
    void freeUgcPoolTopic_clubCanApply_andMerchantInviteIsAccepted() {
        givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation.ugc());
        applyService.apply(TOPIC, LEADER, CLUB, "想承接");
        when(inviteMapper.insertCoopInvite(any(CoopInvite.class))).thenReturn(1);

        assertEquals(1, inviteService.insertCoopInvite(merchantToClub()),
                "池里申请得了,商家就必须发得出邀约");
    }

    /** 负控:商业主题(收费票)未过立项审仍停在 DRAFT —— 申请和邀约都必须拒,不能只放行一头。 */
    @Test
    void commercialPoolTopic_notRecruiting_applyAndInviteBothRejected() {
        givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation.commercial("paidTicket", 1L));

        IllegalStateException invite = assertThrows(IllegalStateException.class,
                () -> inviteService.insertCoopInvite(merchantToClub()));
        assertTrue(invite.getMessage().contains("不在招商期"), invite.getMessage());
        IllegalStateException apply = assertThrows(IllegalStateException.class,
                () -> applyService.apply(TOPIC, LEADER, CLUB, "想承接"));
        assertTrue(apply.getMessage().contains("不在招商期"), "池/申请与邀约同口径:" + apply.getMessage());
        verify(inviteMapper, never()).insertCoopInvite(any());
    }

    /** 负控:免费主题不能借俱乐部邀约带出报酬条款(没有锁价/结算阶段,钱永远结不了)。 */
    @Test
    void freeUgcPoolTopic_paidTermsRejected() {
        givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation.ugc());
        CoopInvite paid = merchantToClub();
        paid.setShareMode(2);
        paid.setFixedFee(new BigDecimal("100"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> inviteService.insertCoopInvite(paid));
        assertTrue(error.getMessage().contains("报酬条款"), error.getMessage());
        verify(inviteMapper, never()).insertCoopInvite(any());
    }

    /** W3f:免费窗口下不带分成但带押金,同样是没有结算阶段承接的钱 ⇒ 拒。 */
    @Test
    void freeUgcPoolTopic_depositRejected() {
        givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation.ugc());
        CoopInvite deposit = merchantToClub();
        deposit.setDepositAmount(new BigDecimal("50"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> inviteService.insertCoopInvite(deposit));
        assertTrue(error.getMessage().contains("押金"), error.getMessage());
        verify(inviteMapper, never()).insertCoopInvite(any());
    }

    @Test
    void publishedPendingReviewWithDeadlineCanSendClubInvite() {
        CmsTopic topic = givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation.ugc());
        topic.setLifecycle(7);
        topic.setRecruitDeadline(new java.util.Date(System.currentTimeMillis() + 60000));
        inviteService.insertCoopInvite(merchantToClub());
        verify(inviteMapper).insertCoopInvite(any());
    }

    /** W3f 口径统一:立项待审不是免费态 —— 与报名侧同一口径(CommercialTopicPolicy.isFreeTopic)。 */
    @Test
    void pendingReviewPoolTopic_isNotFreeWindow() {
        givenFreeMerchantTopic(CommercialTopicPolicy.Evaluation.ugc()).setLifecycle(7);

        IllegalStateException apply = assertThrows(IllegalStateException.class,
                () -> applyService.apply(TOPIC, LEADER, CLUB, "想承接"));
        assertTrue(apply.getMessage().contains("recruitDeadline 未配置"), apply.getMessage());
    }
}
