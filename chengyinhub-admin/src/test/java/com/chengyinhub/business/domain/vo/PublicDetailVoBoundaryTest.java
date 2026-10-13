package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.ViewRegistration;
import com.chengyinhub.business.domain.ViewCollaborators;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.common.utils.bean.BeanUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.Arrays;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 公开详情必须是字段白名单，不能继承完整持久化实体。 */
class PublicDetailVoBoundaryTest {

    @Test
    void activityAndTopicDetailsAreIndependentWhitelists() {
        assertFalse(CmsActivity.class.isAssignableFrom(ActivityInfoVO.class));
        assertFalse(CmsTopic.class.isAssignableFrom(TopicInfoVO.class));
        assertFalse(CmsTopicNode.class.isAssignableFrom(TopicNodeVO.class));
        assertFalse(hasMethod(ActivityInfoVO.class, "getCheckStatus"));
        assertFalse(hasMethod(ActivityInfoVO.class, "getIsSettlement"));
        assertFalse(hasMethod(TopicInfoVO.class, "getCheckStatus"));
        assertFalse(hasMethod(TopicInfoVO.class, "getFeePlatformRate"));
        assertFalse(hasMethod(TopicNodeVO.class, "getCheckinCode"));
        assertFalse(hasMethod(PublicActivityListVO.class, "getSalesAmout"));
        assertFalse(hasMethod(PublicActivityListVO.class, "getCheckStatus"));
        assertFalse(hasMethod(PublicTopicListVO.class, "getFeePlatformRate"));
        assertFalse(hasMethod(PublicTopicListVO.class, "getCheckStatus"));
        assertFalse(hasMethod(PublicTopicListVO.class, "getIsSettlement"));
        assertFalse(hasMethod(PublicCollaboratorVO.class, "getMemberPhone"));
        assertFalse(hasMethod(PublicMemberSummaryVO.class, "getPhone"));
        assertFalse(hasMethod(PublicMerchantRegistrationVO.class, "getQuestionAnswer"));
        assertFalse(hasMethod(PublicMerchantRegistrationVO.class, "getCorrectAnswer"));
        assertFalse(hasMethod(PublicMerchantSummaryVO.class, "getPhone"));
    }

    @Test
    void topicMerchantWindowRemainsAvailableToExistingConsumer() throws Exception {
        Date start = new Date(1_700_000_000_000L);
        Date end = new Date(1_700_086_400_000L);
        CmsTopic topic = new CmsTopic();
        topic.setMerchantSignUpStartDate(start);
        topic.setMerchantSignUpEndDate(end);

        TopicInfoVO out = new TopicInfoVO();
        BeanUtils.copyBeanProp(out, topic);

        assertEquals(start, out.getMerchantSignUpStartDate());
        assertEquals(end, out.getMerchantSignUpEndDate());
        String json = new ObjectMapper().writeValueAsString(out);
        assertTrue(json.contains("merchantSignUpStartDate"));
        assertTrue(json.contains("merchantSignUpEndDate"));
    }

    @Test
    void activityTeamConfigRemainsAvailableToSignupConsumer() throws Exception {
        CmsActivity activity = new CmsActivity();
        activity.setTeamMode(2);
        activity.setTeamMaxMembers(3);
        ActivityInfoVO out = new ActivityInfoVO();
        BeanUtils.copyBeanProp(out, activity);

        assertEquals(2, out.getTeamMode());
        assertEquals(3, out.getTeamMaxMembers());
        String json = new ObjectMapper().writeValueAsString(out);
        assertTrue(json.contains("\"teamMode\":2"));
        assertTrue(json.contains("\"teamMaxMembers\":3"));
    }

    @Test
    void publicTicketsExposeOnlySocialRegistrationSummary() throws Exception {
        ViewRegistration registration = new ViewRegistration();
        registration.setId(99L);
        registration.setMemberId(88L);
        registration.setAvatar("avatar.png");
        registration.setNickname("玩家");
        registration.setPhone("13800000000");
        registration.setEmail("secret@example.com");
        registration.setRegistrationNo("REG-SECRET");
        registration.setTransactionId("WX-SECRET");
        registration.setVerificationCode("VERIFY-SECRET");

        OmsTicket ticket = new OmsTicket();
        ticket.setId(7L);
        ticket.setName("公开票");
        ticket.setMemberId(123L);
        ticket.setOwnerId(456L);
        PublicTicketVO out = PublicTicketVO.from(ticket, Collections.singletonList(registration));
        String json = new ObjectMapper().writeValueAsString(out);

        assertTrue(json.contains("\"memberId\":88"));
        assertTrue(json.contains("\"nickname\":\"玩家\""));
        assertFalse(json.contains("phone"));
        assertFalse(json.contains("email"));
        assertFalse(json.contains("registrationNo"));
        assertFalse(json.contains("transactionId"));
        assertFalse(json.contains("verificationCode"));
        assertFalse(json.contains("ownerId"));
    }

    @Test
    void publicTopicTicketsExposeRegistrationCountWithoutParticipantIdentity() throws Exception {
        ViewRegistration registration = new ViewRegistration();
        registration.setId(99L);
        registration.setMemberId(88L);
        registration.setAvatar("avatar.png");
        registration.setNickname("玩家");

        OmsTicket ticket = new OmsTicket();
        ticket.setId(7L);
        PublicTicketVO out = PublicTicketVO.fromWithoutRegistrationIdentities(
                ticket, Collections.singletonList(registration));
        String json = new ObjectMapper().writeValueAsString(out);

        assertEquals(Integer.valueOf(1), out.getRegistrationCount());
        assertTrue(out.getCmsRegistrationList().isEmpty());
        assertFalse(json.contains("avatar.png"));
        assertFalse(json.contains("\"memberId\":88"));
    }

    @Test
    void nestedPublicSummariesDoNotSerializePiiOrGovernanceFields() throws Exception {
        ViewCollaborators collaborator = new ViewCollaborators();
        collaborator.setMemberId(8L);
        collaborator.setMemberNickname("领队");
        collaborator.setMemberPhone("13900000000");

        MmsMerchant merchant = new MmsMerchant();
        merchant.setName("商家");
        merchant.setBusinessTime("09:00-18:00");
        merchant.setPhone("13700000000");
        CmsRegistrationMerchant registration = new CmsRegistrationMerchant();
        registration.setMemberId(9L);
        registration.setEmail("merchant-secret@example.com");
        registration.setQuestionAnswer("答案");
        registration.setCorrectAnswer("A");
        registration.setMmsMerchant(merchant);

        UmsMember member = new UmsMember();
        member.setId(10L);
        member.setNickname("发布者");
        member.setAvatar("avatar.png");
        member.setPhone("13600000000");
        member.setPassword("bcrypt-secret");

        ObjectMapper mapper = new ObjectMapper();
        String collaboratorJson = mapper.writeValueAsString(PublicCollaboratorVO.from(collaborator));
        String merchantJson = mapper.writeValueAsString(PublicMerchantRegistrationVO.from(registration));
        String memberJson = mapper.writeValueAsString(PublicMemberSummaryVO.from(member));

        assertFalse(collaboratorJson.contains("memberPhone"));
        assertFalse(merchantJson.contains("email"));
        assertFalse(merchantJson.contains("questionAnswer"));
        assertFalse(merchantJson.contains("correctAnswer"));
        assertFalse(merchantJson.contains("phone"));
        assertFalse(memberJson.contains("phone"));
        assertFalse(memberJson.contains("password"));
    }

    @Test
    void publicCouponSummaryKeepsRewardCopyButDropsSecretsAndInactiveCoupons() throws Exception {
        SmsCoupon coupon = new SmsCoupon();
        coupon.setId(21L);
        coupon.setName("街区奖励券");
        coupon.setCouponType(1);
        coupon.setAmount(new BigDecimal("20.00"));
        coupon.setMinPoint(new BigDecimal("100.00"));
        coupon.setDescription("完成路线后可使用");
        // 门槛已拍板删除展示(2026-09-17):即使库里残留 min_point,公开投影也不得再下发。
        coupon.setCode("REDEEM-SECRET");
        coupon.setMemberId(88L);
        coupon.setPublishCount(1000L);
        coupon.setUseCount(12L);
        coupon.setReceiveCount(30L);
        coupon.setStatus(1);
        coupon.setDelFlag(0);

        String json = new ObjectMapper().writeValueAsString(PublicCouponSummaryVO.from(coupon));

        assertTrue(json.contains("\"id\":21"));
        assertTrue(json.contains("\"name\":\"街区奖励券\""));
        assertTrue(json.contains("\"couponType\":1"));
        assertTrue(json.contains("\"amount\":20.00"));
        assertFalse(json.contains("minPoint"), "「满X可用」门槛已拍板删展示,公开投影不得再下发");
        assertTrue(json.contains("完成路线后可使用"));
        assertFalse(json.contains("REDEEM-SECRET"));
        assertFalse(json.contains("memberId"));
        assertFalse(json.contains("publishCount"));
        assertFalse(json.contains("useCount"));
        assertFalse(json.contains("receiveCount"));
        assertFalse(json.contains("status"));
        assertFalse(json.contains("delFlag"));

        coupon.setStatus(2);
        assertEquals(null, PublicCouponSummaryVO.from(coupon));
        // 商家停发(4)同样不可再获得,公开奖励投影必须整块消失,不能给玩家看点不动的空壳
        coupon.setStatus(4);
        assertEquals(null, PublicCouponSummaryVO.from(coupon));
        coupon.setStatus(1);
        coupon.setDelFlag(2);
        assertEquals(null, PublicCouponSummaryVO.from(coupon));
    }

    private boolean hasMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).anyMatch(method -> name.equals(method.getName()));
    }
}
