package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M0-7 钱路径契约测试(服务层,纯 Mockito,与项目既有 *ServiceImplTest 风格一致,零外部依赖可入 CI)。
 *
 * 覆盖 M0 引入的两条钱/权路径:
 *  - M0-1 实扣报价一致:quoteRegistration 与 createRegistration 共用 computePrice,报价=实付;
 *    quoteSign 无状态、可复算、对入参变化敏感(陈旧报价可被 create 端 409 检出)。
 *  - M0-2 优惠券动态二维码 token:签名可复验、防篡改、防重放(60s TTL)。
 */
class MoneyPathContractTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long ACTIVITY_ID = 200L;
    private static final Long TICKET_ID = 300L;

    // ---------- 构造被测的报名 service(仅注入报价所需的 4 个 mapper) ----------
    private CmsRegistrationServiceImpl quoteService(long memberPoint) {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);

        CmsActivity activity = new CmsActivity();
        activity.setId(ACTIVITY_ID);
        activity.setStatus(1L);
        activity.setPublishStatus(1L);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY_ID)).thenReturn(activity);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(memberPoint);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);

        OmsTicket ticket = new OmsTicket();
        ticket.setId(TICKET_ID);
        ticket.setOwnerId(ACTIVITY_ID);
        ticket.setOwnerType(2L);
        ticket.setDelFlag(0);
        ticket.setRemainingInventory(9999L);
        ticket.setPrice(new BigDecimal("100.00"));
        when(ticketMapper.selectOmsTicketById(TICKET_ID)).thenReturn(ticket);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        return service;
    }

    private RegistrationQuoteResult quote(CmsRegistrationServiceImpl s, int usePoint) {
        return s.quoteRegistration(MEMBER_ID, 2, ACTIVITY_ID, TICKET_ID, null, usePoint);
    }

    // ---------- M0-1 报价一致 ----------

    @Test
    void quoteWithoutPointsChargesFullTicketPrice() {
        RegistrationQuoteResult q = quote(quoteService(0L), 0);
        assertEquals(0, new BigDecimal("100.00").compareTo(q.getPayAmount()));
        assertEquals(0, q.getPointsUsed().intValue());
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getPointsDeductYuan()));
        assertEquals(0, BigDecimal.ZERO.compareTo(q.getCouponDeductYuan())); // 券不抵扣报名支付
    }

    @Test
    void quoteWithAmplePointsIsCappedAtFiftyYuan() {
        // 5000 积分 * 0.02 = 100 元潜在抵扣,但封顶 50 元
        RegistrationQuoteResult q = quote(quoteService(5000L), 1);
        assertEquals(0, new BigDecimal("50.00").compareTo(q.getPayAmount()));
        assertEquals(0, new BigDecimal("50.00").compareTo(q.getPointsDeductYuan()));
        assertEquals(2500, q.getPointsUsed().intValue()); // 50 / 0.02
    }

    @Test
    void quoteWithFewPointsDeductsProportionally() {
        // 100 积分 * 0.02 = 2 元抵扣
        RegistrationQuoteResult q = quote(quoteService(100L), 1);
        assertEquals(0, new BigDecimal("98.00").compareTo(q.getPayAmount()));
        assertEquals(0, new BigDecimal("2.00").compareTo(q.getPointsDeductYuan()));
        assertEquals(100, q.getPointsUsed().intValue());
    }

    @Test
    void quoteSignIsDeterministicForSameInputs() {
        // 相同入参、相同状态 → 相同签名(create 端可复算比对)
        String a = quote(quoteService(100L), 1).getQuoteSign();
        String b = quote(quoteService(100L), 1).getQuoteSign();
        assertEquals(a, b);
        assertTrue(a != null && a.length() > 0);
    }

    @Test
    void quoteSignChangesWhenPointToggleChangesAmount() {
        // 切换积分开关 → 金额变 → 签名变(前端切开关即需重报价)
        String noPoint = quote(quoteService(5000L), 0).getQuoteSign();
        String withPoint = quote(quoteService(5000L), 1).getQuoteSign();
        assertNotEquals(noPoint, withPoint);
    }

    @Test
    void quoteSignChangesWhenPointBalanceChanges_stalenessDetected() {
        // 报价后积分余额变化 → 应付变 → 签名变 → create 端 409 可检出陈旧报价
        String rich = quote(quoteService(5000L), 1).getQuoteSign(); // 抵扣 50
        String poor = quote(quoteService(100L), 1).getQuoteSign();  // 抵扣 2
        assertNotEquals(rich, poor);
    }

    // ---------- M0-2 优惠券动态二维码 token ----------

    private SmsCouponServiceImpl couponService() {
        SmsCouponServiceImpl s = new SmsCouponServiceImpl(); // token 逻辑纯函数,无需 mapper
        ReflectionTestUtils.setField(s, "couponQrSecret", "test-coupon-qr-secret");
        return s;
    }

    @Test
    void couponQrTokenRoundTripsToSameHistoryId() {
        SmsCouponServiceImpl s = couponService();
        String token = s.generateCouponQrToken(123L);
        assertEquals(123L, s.verifyCouponQrToken(token).longValue());
    }

    @Test
    void couponQrTokenRejectsTamperedSignature() {
        SmsCouponServiceImpl s = couponService();
        String token = s.generateCouponQrToken(123L);
        // 篡改签名最后一位
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == '0' ? '1' : '0');
        assertNull(s.verifyCouponQrToken(tampered));
    }

    @Test
    void couponQrTokenRejectsMalformedInput() {
        SmsCouponServiceImpl s = couponService();
        assertNull(s.verifyCouponQrToken(null));
        assertNull(s.verifyCouponQrToken("abc"));
        assertNull(s.verifyCouponQrToken("123.456")); // 仅两段
    }

    @Test
    void couponQrTokenRejectsExpiredToken() {
        SmsCouponServiceImpl s = couponService();
        // 白盒构造:同一密钥的合法签名但 epoch 远超 TTL(90s)之前 → 过期
        long oldEpoch = System.currentTimeMillis() / 1000L - 200L;
        String expired = com.chengyinhub.common.utils.VerifyDynCodeUtil.issueCoupon(123L, oldEpoch, "test-coupon-qr-secret");
        assertNull(s.verifyCouponQrToken(expired));
    }

    // ---------- M2 自玩票:通行证购买 ----------

    @Test
    void createSelfPlayPassSetsPriceOwnerTypeAndNinetyDayExpiry() {
        com.chengyinhub.business.mapper.CmsTopicMapper topicMapper =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        com.chengyinhub.business.mapper.CmsRegistrationMapper regMapper =
                mock(com.chengyinhub.business.mapper.CmsRegistrationMapper.class);

        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(777L);   // 夹具原本没设 id;二清闸按 topic.getId() 查内容槽,不设就恒 null
        topic.setName("自玩主题");
        topic.setStatus(1);
        topic.setUserStatus(1); // 在架:购买闸要求 user_status=1(违规下架只写这一列)
        topic.setDelFlag(0);
        topic.setLifecycle(3);
        topic.setSellingStartedAt(new java.util.Date(System.currentTimeMillis() - 60_000L));
        topic.setSaleEndAt(new java.util.Date(System.currentTimeMillis() + 86_400_000L));
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("19.90"));
        topic.setFeePlatformRate(new BigDecimal("0.1000"));
        when(topicMapper.selectCmsTopicById(777L)).thenReturn(topic);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        when(memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(regMapper.insertCmsRegistration(org.mockito.ArgumentMatchers.any())).thenReturn(1);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regMapper);
        // [#22 二清闸] 本用例验的是自玩单的**资金契约**(价格/owner_type/90 天有效期),不是二清。
        // 但收费自玩单现在要过二清闸 ⇒ 不喂「平台自营」就会被拦在契约断言之前。
        // 喂 true 而不是绕开闸:让它走完真实路径,契约才是在真实条件下被验的。
        // (「自玩票款归第三方 ⇒ 拒」由 ErqingGateTest#selfPlayPass_rejected 专门守。)
        com.chengyinhub.business.service.IProductRoleService productRoleService =
                mock(com.chengyinhub.business.service.IProductRoleService.class);
        when(productRoleService.isPlatformOwned(777L)).thenReturn(true);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);

        com.chengyinhub.business.domain.CmsRegistration r =
                service.createSelfPlayPass(MEMBER_ID, 777L, "张三", "18800000000");

        assertEquals(1L, r.getOwnerType().longValue());   // 1=主题通行证
        assertEquals(777L, r.getTopicId().longValue());
        assertEquals(0, new BigDecimal("19.90").compareTo(r.getPayableAmount()));
        assertEquals(0, r.getPointUsed().longValue());     // 自玩不走积分
        // 有效期约 90 天后(容差 1 天)
        long days = (r.getExpiresAt().getTime() - System.currentTimeMillis()) / (24L * 3600 * 1000);
        assertTrue(days >= 89 && days <= 90, "expires ~90d, got " + days);
    }

    @Test
    void createSelfPlayPassRejectsNonSelfPlayTopic() {
        com.chengyinhub.business.mapper.CmsTopicMapper topicMapper =
                mock(com.chengyinhub.business.mapper.CmsTopicMapper.class);
        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setStatus(1);
        topic.setDelFlag(0);
        topic.setSelfPlay(0); // 未开放自玩
        when(topicMapper.selectCmsTopicById(778L)).thenReturn(topic);

        CmsRegistrationServiceImpl service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.createSelfPlayPass(MEMBER_ID, 778L, "张三", "18800000000"));
    }
}
