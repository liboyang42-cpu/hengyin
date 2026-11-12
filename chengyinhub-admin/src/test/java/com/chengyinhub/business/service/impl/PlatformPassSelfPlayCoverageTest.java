package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.business.service.IProductRoleService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * l-pass：通行证覆盖的自玩票走原建单链路但金额为 ¥0（随后由既有零元单完成路径置已付），
 * 到期不越过通行证本段，且同事务记一笔领取；未覆盖（含总开关关闭）时金额与原来逐字一致。
 */
class PlatformPassSelfPlayCoverageTest {

    private static final Long MEMBER_ID = 88L;
    private static final Long TOPIC_ID = 500L;

    @Test
    void coveredOfficialTopicCreatesZeroAmountPassAndRecordsClaim() {
        Fixture fixture = fixture();
        PlatformPassService pass = mock(PlatformPassService.class);
        Date validTo = new Date(System.currentTimeMillis() + 5L * 86_400_000L);
        PlatformPassService.Coverage coverage = new PlatformPassService.Coverage(77L, validTo);
        when(pass.coverage(eq(MEMBER_ID), eq(TOPIC_ID), any(Date.class))).thenReturn(coverage);
        ReflectionTestUtils.setField(fixture.service, "platformPassService", pass);
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID)).thenReturn(Collections.emptyList());

        CmsRegistration created = fixture.service.createSelfPlayPass(MEMBER_ID, TOPIC_ID, "张三", "18800000000");

        assertEquals(0, BigDecimal.ZERO.compareTo(created.getPayableAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getWechatPaymentAmount()));
        assertEquals(0, new BigDecimal("100.00").compareTo(created.getOriginalAmount()), "原价照实记录");
        assertEquals(Integer.valueOf(0), created.getPaymentType());
        assertFalse(created.getExpiresAt().after(validTo), "¥0 自玩票不越过通行证本段到期");
        verify(pass).recordClaim(coverage, MEMBER_ID, TOPIC_ID, 9007L);
    }

    @Test
    void uncoveredMemberKeepsOriginalPriceAndNoClaim() {
        Fixture fixture = fixture();
        PlatformPassService pass = mock(PlatformPassService.class);
        when(pass.coverage(anyLong(), anyLong(), any(Date.class))).thenReturn(null);
        ReflectionTestUtils.setField(fixture.service, "platformPassService", pass);
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID)).thenReturn(Collections.emptyList());

        CmsRegistration created = fixture.service.createSelfPlayPass(MEMBER_ID, TOPIC_ID, "张三", "18800000000");

        assertEquals(0, new BigDecimal("100.00").compareTo(created.getPayableAmount()));
        verify(pass, never()).recordClaim(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void missingPlatformPassBeanKeepsOriginalPrice() {
        Fixture fixture = fixture();
        when(fixture.registrationMapper.selectLiveSelfPlayPasses(TOPIC_ID, MEMBER_ID)).thenReturn(Collections.emptyList());
        CmsRegistration created = fixture.service.createSelfPlayPass(MEMBER_ID, TOPIC_ID, "张三", "18800000000");
        assertEquals(0, new BigDecimal("100.00").compareTo(created.getPayableAmount()));
        assertTrue(created.getExpiresAt() != null);
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
