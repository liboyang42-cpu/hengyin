package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.RegistrationRequestConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H2 自玩通行证会员级幂等。
 *
 * <p>病:主题自玩单走 /api/registration/create 但**不带 requestId**,后端
 * RegistrationOrderServiceImpl 对空 requestId 直接跳过 replay 探测,而 createSelfPlayPassInternal
 * 自身只有主题级配额闸、没有任何「这个会员是不是已经有一张了」的判断 ⇒ 连点两下 / 网络重试
 * 就是两张待支付通行证,付两次钱拿两份 90 天权益。
 *
 * <p>幂等点放在主题行锁之后:同主题建单已被 selectCmsTopicByIdForUpdate 串行化,
 * 「查已有 → 建新单」之间不会有第二个请求插进来。
 */
class SelfPlayPassIdempotencyTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long TOPIC_ID = 500L;

    @Test
    void repeatedCreateReusesPendingOrderInsteadOfStackingSecondPayOrder() {
        Fixture fixture = fixture();
        CmsRegistration pending = pendingPass(9001L);
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID))
                .thenReturn(Collections.singletonList(pending));

        CmsRegistration result = fixture.service.createSelfPlayPass(
                MEMBER_ID, TOPIC_ID, "张三", "18800000000");

        assertSame(pending, result, "重复下单必须回到同一张待支付通行证");
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any(CmsRegistration.class));
    }

    @Test
    void validPaidPassBlocksBuyingASecondOne() {
        Fixture fixture = fixture();
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID))
                .thenReturn(Collections.singletonList(paidPass(9002L)));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> fixture.service.createSelfPlayPass(MEMBER_ID, TOPIC_ID, "张三", "18800000000"));

        assertTrue(error.getMessage().contains("已持有"), error.getMessage());
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any(CmsRegistration.class));
    }

    @Test
    void paidPassWinsOverPendingOrderWhenBothExist() {
        Fixture fixture = fixture();
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID))
                .thenReturn(Arrays.asList(pendingPass(9003L), paidPass(9004L)));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> fixture.service.createSelfPlayPass(MEMBER_ID, TOPIC_ID, "张三", "18800000000"));

        assertTrue(error.getMessage().contains("已持有"), error.getMessage());
    }

    @Test
    void noLivePassStillCreatesNormalOrder() {
        // 绿配对:同一条路径在"这个会员没有任何在世通行证"时必须照常建单,
        // 否则上面的红只能证明"对谁都不建单"。
        Fixture fixture = fixture();
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID))
                .thenReturn(Collections.emptyList());

        CmsRegistration created = fixture.service.createSelfPlayPass(
                MEMBER_ID, TOPIC_ID, "张三", "18800000000");

        assertEquals(0, new BigDecimal("100.00").compareTo(created.getPayableAmount()));
        verify(fixture.registrationMapper, times(1)).insertCmsRegistration(any(CmsRegistration.class));
    }

    @Test
    void explicitDifferentRequestIdCannotReuseLegacyPendingPass() {
        Fixture fixture = fixture();
        CmsRegistration pending = pendingPass(9005L);
        pending.setRequestId("legacy-request");
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID))
                .thenReturn(Collections.singletonList(pending));

        RegistrationRequest request = selfPlayRequest();
        assertThrows(RegistrationRequestConflictException.class,
                () -> fixture.service.create(MEMBER_ID, request, "new-request", repeat('a')));

        verify(fixture.registrationMapper, never()).insertCmsRegistration(any(CmsRegistration.class));
    }

    @Test
    void explicitSameRequestIdAndFingerprintReusesPendingPass() {
        Fixture fixture = fixture();
        CmsRegistration pending = pendingPass(9006L);
        pending.setRequestId("same-request");
        pending.setRequestFingerprint(repeat('b'));
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID))
                .thenReturn(Collections.singletonList(pending));
        when(fixture.registrationMapper.bindRequestFingerprint(9006L, "same-request", repeat('b')))
                .thenReturn(0);
        when(fixture.registrationMapper.selectCmsRegistrationByIdForUpdate(9006L)).thenReturn(pending);

        java.util.List<CmsRegistration> result = fixture.service.create(
                MEMBER_ID, selfPlayRequest(), "same-request", repeat('b'));

        assertEquals(Collections.singletonList(pending), result);
        verify(fixture.registrationMapper, never()).insertCmsRegistration(any(CmsRegistration.class));
    }

    private RegistrationRequest selfPlayRequest() {
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(1);
        request.setOwnerId(TOPIC_ID);
        request.setRealName("张三");
        request.setPhone("18800000000");
        return request;
    }

    private String repeat(char value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            text.append(value);
        }
        return text.toString();
    }

    private CmsRegistration pendingPass(Long id) {
        CmsRegistration pending = new CmsRegistration();
        pending.setId(id);
        pending.setMemberId(MEMBER_ID);
        pending.setOwnerType(1L);
        pending.setOwnerId(TOPIC_ID);
        pending.setRegistrationStatus(1);
        pending.setPaymentStatus(0);
        pending.setPayableAmount(new BigDecimal("100.00"));
        pending.setPayExpireTime(new Date(System.currentTimeMillis() + 600_000L));
        return pending;
    }

    private CmsRegistration paidPass(Long id) {
        CmsRegistration paid = new CmsRegistration();
        paid.setId(id);
        paid.setMemberId(MEMBER_ID);
        paid.setOwnerType(1L);
        paid.setOwnerId(TOPIC_ID);
        paid.setRegistrationStatus(2);
        paid.setPaymentStatus(2);
        paid.setPayableAmount(new BigDecimal("100.00"));
        paid.setExpiresAt(new Date(System.currentTimeMillis() + 86_400_000L));
        return paid;
    }

    private Fixture fixture() {
        Fixture fixture = new Fixture();
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setStatus(1);
        topic.setUserStatus(1);
        topic.setDelFlag(0);
        topic.setLifecycle(3);
        topic.setSellingStartedAt(new Date(System.currentTimeMillis() - 60_000L));
        topic.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
        topic.setSelfPlay(1);
        topic.setSelfPlayPrice(new BigDecimal("100.00"));
        topic.setFeePlatformRate(new BigDecimal("0.1000"));
        when(fixture.topicMapper.selectCmsTopicByIdForUpdate(TOPIC_ID)).thenReturn(topic);
        when(fixture.productRoleService.isPlatformOwned(TOPIC_ID)).thenReturn(true);

        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        member.setPoint(0L);
        when(fixture.memberMapper.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        when(fixture.registrationMapper.insertCmsRegistration(any(CmsRegistration.class))).thenAnswer(i -> { ((CmsRegistration)i.getArgument(0)).setId(9007L); return 1; });

        fixture.service = new CmsRegistrationServiceImpl();
        ReflectionTestUtils.setField(fixture.service, "cmsRegistrationMapper", fixture.registrationMapper);
        ReflectionTestUtils.setField(fixture.service, "registrationSignupNotificationService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.RegistrationSignupNotificationService.class));
        ReflectionTestUtils.setField(fixture.service, "cmsTopicMapper", fixture.topicMapper);
        ReflectionTestUtils.setField(fixture.service, "umsMemberMapper", fixture.memberMapper);
        ReflectionTestUtils.setField(fixture.service, "productRoleService", fixture.productRoleService);
        return fixture;
    }

    private static class Fixture {
        final CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        final CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        final UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        final IProductRoleService productRoleService = mock(IProductRoleService.class);
        CmsRegistrationServiceImpl service;
    }
}
