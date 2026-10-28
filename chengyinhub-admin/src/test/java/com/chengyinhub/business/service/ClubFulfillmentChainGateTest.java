package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.service.impl.GroupCodeServiceImpl;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.business.service.impl.TeamGroupServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 履约这一段的两道闸:清退退款、团码签发。
 *
 * <p>两条都因为「只认主题(owner_type=1 / 承接合作单)」而对**俱乐部自己开的场次**恒不成立 ——
 * 一个是必然失败的按钮,一个是查不出真因的权限错误。这里钉的是它们现在认得第二档。
 */
class ClubFulfillmentChainGateTest {

    /** 清退退款:场次报名(owner_type=2)由承接俱乐部主理人发起,授权闸必须放行。 */
    @Test
    void ownerRefundAcceptsSessionRegistrationFromClubOwner() {
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(1L);
        reg.setOwnerType(2L);          // 场次报名 —— 俱乐部办团卖的恒是这一档
        reg.setOwnerId(50L);
        reg.setPaymentStatus(0);       // 故意未支付:让它卡在下游,证明授权闸已经放行
        when(registrationMapper.selectCmsRegistrationById(1L)).thenReturn(reg);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(1L)).thenReturn(reg);

        CmsActivity session = new CmsActivity();
        session.setId(50L);
        session.setTopicId(70L);
        session.setClubId(9L);
        when(activityMapper.selectCmsActivityById(50L)).thenReturn(session);

        CmsTopic topic = new CmsTopic();
        topic.setId(70L);
        topic.setMemberId(999L);       // 主题是别人的(合作承接场景)
        when(topicMapper.selectCmsTopicById(70L)).thenReturn(topic);

        Club club = new Club();
        club.setId(9L);
        club.setMemberId(7L);          // 调用者是承接俱乐部的主理人
        club.setDelFlag(0);
        when(clubMapper.selectClubById(9L)).thenReturn(club);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistrationByOwner(1L, 7L));
        assertEquals("仅已支付报名可退款", e.getMessage(),
                "★应当卡在下游的支付状态上;还报「仅主题报名支持主理人退款」就说明那道闸没开");
    }

    /** 同一条闸的负向:既不是主题作者也不是承接俱乐部主理人,照样拒。 */
    @Test
    void ownerRefundStillRejectsAnUnrelatedCaller() {
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(2L);
        reg.setOwnerType(2L);
        reg.setOwnerId(51L);
        when(registrationMapper.selectCmsRegistrationById(2L)).thenReturn(reg);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(2L)).thenReturn(reg);
        CmsActivity session = new CmsActivity();
        session.setId(51L);
        session.setTopicId(71L);
        session.setClubId(9L);
        when(activityMapper.selectCmsActivityById(51L)).thenReturn(session);
        CmsTopic topic = new CmsTopic();
        topic.setMemberId(999L);
        when(topicMapper.selectCmsTopicById(71L)).thenReturn(topic);
        Club club = new Club();
        club.setMemberId(7L);
        club.setDelFlag(0);
        when(clubMapper.selectClubById(9L)).thenReturn(club);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistrationByOwner(2L, 12345L));
        assertEquals("无权操作:只有主题作者或承接俱乐部主理人可以清退", e.getMessage());
    }

    /**
     * P2-RF-2(2026-09-15 拍板):主理人清退是主办方主动退款,不受玩家自助退款窗口限制。
     * 集合时间已过去 48h(玩家自助按「集合前 24h」必拒),清退仍要走到退款链路。
     */
    @Test
    void ownerRefundIgnoresPlayerRefundWindowForPastSession() {
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "perkLedgerService",
                mock(com.chengyinhub.business.service.IPerkLedgerService.class));

        CmsRegistration reg = new CmsRegistration();
        reg.setId(11L);
        reg.setOwnerType(2L);
        reg.setOwnerId(50L);
        reg.setPaymentStatus(2);
        reg.setVerificationStatus(0);
        reg.setTicketId(null);
        reg.setParentOrderNo(null);
        reg.setPointUsed(null);
        // 非现金:下单落库即明确为 0(null 是未确认快照,退款链 409);只做标记/返积分,不触微信
        reg.setWechatPaymentAmount(java.math.BigDecimal.ZERO);
        com.chengyinhub.business.service.IRefundService refundService = mock(com.chengyinhub.business.service.IRefundService.class);
        ReflectionTestUtils.setField(service, "refundService", refundService);
        when(registrationMapper.selectCmsRegistrationById(11L)).thenReturn(reg);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(11L)).thenReturn(reg);
        when(registrationMapper.claimRegistrationRefunded(anyLong(), anyString(), any())).thenReturn(1);

        CmsActivity session = new CmsActivity();
        session.setId(50L);
        session.setTopicId(70L);
        session.setClubId(9L);
        session.setStartDate(new Date(System.currentTimeMillis() - 48L * 3600 * 1000));
        when(activityMapper.selectCmsActivityById(50L)).thenReturn(session);

        CmsTopic topic = new CmsTopic();
        topic.setId(70L);
        topic.setMemberId(7L);              // 调用者 = 主题作者,授权闸放行
        when(topicMapper.selectCmsTopicById(70L)).thenReturn(topic);

        assertEquals(1, service.cancelPaidRegistrationByOwner(11L, 7L),
                "集合时间早已过去不该拦住主办清退");
        verify(registrationMapper).claimRegistrationRefunded(anyLong(), anyString(), any());
    }

    /** 窗口豁免不等于核销豁免:已核销报名仍按 RefundPolicy 的唯一判定拒绝清退。 */
    @Test
    void ownerRefundStillRejectsVerifiedRegistration() {
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "firstReviewPointsClawback",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.impl.FirstReviewPointsClawbackService.class));
        CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrationMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);

        CmsRegistration reg = new CmsRegistration();
        reg.setId(12L);
        reg.setOwnerType(2L);
        reg.setOwnerId(50L);
        reg.setPaymentStatus(2);
        reg.setVerificationStatus(1);       // 已核销
        when(registrationMapper.selectCmsRegistrationById(12L)).thenReturn(reg);
        when(registrationMapper.selectCmsRegistrationByIdForUpdate(12L)).thenReturn(reg);

        CmsActivity session = new CmsActivity();
        session.setId(50L);
        session.setTopicId(70L);
        session.setClubId(9L);
        when(activityMapper.selectCmsActivityById(50L)).thenReturn(session);
        CmsTopic topic = new CmsTopic();
        topic.setId(70L);
        topic.setMemberId(7L);
        when(topicMapper.selectCmsTopicById(70L)).thenReturn(topic);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.cancelPaidRegistrationByOwner(12L, 7L));
        assertEquals("已核销报名不可退款", e.getMessage());
    }

    /** 团码签发:主题本来就挂在这个俱乐部名下时,不该再要一张「承接自己」的合作单。 */
    @Test
    void groupCodeIssuesForAClubRunningItsOwnTopic() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        IClubAccessService accessService = mock(IClubAccessService.class);
        CoopOrderMapper coopOrderMapper = mock(CoopOrderMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        GroupCodeServiceImpl service = new GroupCodeServiceImpl(
                activityService, accessService, coopOrderMapper,
                mock(com.chengyinhub.business.mapper.CoopGroupVerifyMapper.class),
                inlineQrcode(), "test-secret");
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);

        CmsActivity session = new CmsActivity();
        session.setId(60L);
        session.setTopicId(80L);
        session.setClubId(9L);
        session.setDelFlag(0);
        session.setStatus(1L);
        session.setPublishStatus(1L);
        when(activityService.selectCmsActivityById(60L)).thenReturn(session);
        // 自办:没有任何 to_type='club' 的合作单
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(80L))
                .thenReturn(Collections.<CoopOrder>emptyList());
        CmsTopic own = new CmsTopic();
        own.setId(80L);
        own.setClubId(9L);             // 主题就是这个俱乐部的
        when(topicMapper.selectCmsTopicById(80L)).thenReturn(own);

        AjaxResult result = service.issue(60L, 7L);

        assertNotEquals("您没有该场次的团码核销权限", result.get("msg"),
                "★自办场次出不了码,错误文案还写成权限问题,查半天查不到真因");
        assertEquals(200, result.get("code"));
    }

    /** 负向:主题不属于本俱乐部、又没有承接合作单,仍然拒。 */
    @Test
    void groupCodeStillRejectsAClubWithNeitherOwnershipNorCoopOrder() {
        ICmsActivityService activityService = mock(ICmsActivityService.class);
        CoopOrderMapper coopOrderMapper = mock(CoopOrderMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        GroupCodeServiceImpl service = new GroupCodeServiceImpl(
                activityService, mock(IClubAccessService.class), coopOrderMapper,
                mock(com.chengyinhub.business.mapper.CoopGroupVerifyMapper.class),
                inlineQrcode(), "test-secret");
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);

        CmsActivity session = new CmsActivity();
        session.setId(61L);
        session.setTopicId(81L);
        session.setClubId(9L);
        session.setDelFlag(0);
        session.setStatus(1L);
        session.setPublishStatus(1L);
        when(activityService.selectCmsActivityById(61L)).thenReturn(session);
        when(coopOrderMapper.selectEffectiveOrdersByTopicId(81L))
                .thenReturn(Collections.<CoopOrder>emptyList());
        CmsTopic foreign = new CmsTopic();
        foreign.setClubId(123L);       // 别人的主题
        when(topicMapper.selectCmsTopicById(81L)).thenReturn(foreign);

        AjaxResult result = service.issue(61L, 7L);

        assertEquals("您没有该场次的团码核销权限", result.get("msg"));
    }

    /** 团码使用真实内联PNG渲染，不依赖对象存储。 */
    private static IQrcodeService inlineQrcode() {
        return new QrcodeServiceImpl();
    }
}
