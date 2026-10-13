package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IShopDayMerchantRosterService;
import com.chengyinhub.business.service.IProjectPlayerReadService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.impl.ProjectPlayerReadServiceImpl;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 玩家名单的联系方式可见范围。
 *
 * 判据是「谁组织、谁负责联系」：有俱乐部带团时不得绕过组织方直接联系玩家；
 * 没有俱乐部时，主办方与承接商家再按经营权限和玩家当前版本单独同意展示电话。
 *
 * 掩码已去掉:给一个可复制的完整电话、同时把名字打码,是隐私剧场。
 * 真正的闸是「电话给不给」这个二元判断。
 *
 * club_id 挂靠和已接受的俱乐部邀约都是组织关系，任一成立都必须 fail-closed。
 */
class ProjectPlayerContactVisibilityTest {

    private static final Long HOST = 10L;
    private static final Long MERCHANT = 20L;
    private static final Long STRANGER = 99L;
    private static final Long TOPIC = 300L;

    private ICmsTopicService topicSvc;
    private ICmsRegistrationService regSvc;
    private ICmsRegistrationMerchantService regMerchantSvc;
    private ICoopInviteService inviteSvc;
    private ProjectPlayerReadServiceImpl service;
    private ConsentRecordMapper consentMapper;

    @BeforeEach
    void setUp() {
        topicSvc = mock(ICmsTopicService.class);
        regSvc = mock(ICmsRegistrationService.class);
        regMerchantSvc = mock(ICmsRegistrationMerchantService.class);
        inviteSvc = mock(ICoopInviteService.class);
        IUmsMemberService memberSvc = mock(IUmsMemberService.class);
        service = new ProjectPlayerReadServiceImpl(topicSvc, regSvc, regMerchantSvc, inviteSvc, memberSvc);
        consentMapper = mock(ConsentRecordMapper.class);
        ConsentRecordServiceImpl consentService = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(consentService, "consentRecordMapper", consentMapper);
        ComplianceProperties properties = new ComplianceProperties();
        Map<String, String> versions = new HashMap<>();
        versions.put("activity_host_data_sharing", "v2.1");
        properties.setDocVersions(versions);
        ReflectionTestUtils.setField(service, "consentRecordService", consentService);
        ReflectionTestUtils.setField(service, "complianceProperties", properties);
        ConsentRecord agreed = new ConsentRecord();
        agreed.setDocVersion("v2.1");
        agreed.setEventType("AGREE");
        when(consentMapper.selectLatest(anyLong(),
                org.mockito.ArgumentMatchers.eq("activity_host_data_sharing"),
                org.mockito.ArgumentMatchers.eq("activity_signup"))).thenReturn(agreed);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setMemberId(HOST);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);

        UmsMember m = new UmsMember();
        m.setNickname("城瘾玩家");
        when(memberSvc.selectUmsMemberById(anyLong())).thenReturn(m);

        when(inviteSvc.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
        when(regMerchantSvc.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.<CmsRegistrationMerchant>emptyList());
        // 已支付与已退款是两次独立查询,stub 必须按 paymentStatus 分流 ——
        // 一律返回同一批人会让两次查询叠加成双份,汇总数直接翻倍。
        when(regSvc.selectCmsRegistrationList(any())).thenAnswer(invocation -> {
            CmsRegistration probe = invocation.getArgument(0);
            if (Integer.valueOf(4).equals(probe.getPaymentStatus())) {
                return Arrays.asList(reg(3L, "李四", "13800000003", false, 4));
            }
            return Arrays.asList(
                    reg(1L, "张明", "13800000001", true),
                    reg(2L, "王小强", "13800000002", false));
        });
    }

    private CmsRegistration reg(Long id, String name, String phone, boolean verified) {
        return reg(id, name, phone, verified, 2);
    }

    private CmsRegistration reg(Long id, String name, String phone, boolean verified, int paymentStatus) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(500L + id);
        r.setRealName(name);
        r.setPhone(phone);
        r.setRegistrationNo("A02" + id);
        r.setPaymentStatus(paymentStatus);
        r.setVerificationStatus(verified ? 1 : 0);
        if (verified) r.setVerificationTime(new Date());
        return r;
    }

    private void givenOrganizerClub() {
        CoopInvite invite = new CoopInvite();
        invite.setToType("club");
        invite.setStatus(1);
        when(inviteSvc.selectCoopInviteList(any())).thenAnswer(invocation -> {
            CoopInvite probe = invocation.getArgument(0);
            boolean acceptedClubInvite = TOPIC.equals(probe.getTopicId())
                    && "club".equals(probe.getToType())
                    && Integer.valueOf(1).equals(probe.getStatus());
            return acceptedClubInvite
                    ? Collections.singletonList(invite)
                    : Collections.<CoopInvite>emptyList();
        });
    }

    private void givenIAmMerchant() {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setMemberId(MERCHANT);
        r.setTopicId(TOPIC);
        when(regMerchantSvc.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant probe = invocation.getArgument(0);
            return Long.valueOf(1L).equals(probe.getAuditStatus())
                    ? Collections.singletonList(r) : Collections.emptyList();
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("rows");
    }

    @Test
    void twoArgumentOverloadFailsClosedForSensitiveContacts() {
        Map<String, Object> out = service.listPlayers(HOST, TOPIC);

        assertEquals(Boolean.FALSE, out.get("contactVisible"));
        assertNull(rows(out).get(0).get("phone"));
        assertFalse(String.valueOf(out).contains("13800000001"));
    }

    /** 没有俱乐部:主办方自办,给真名和电话 —— 他就是履约方,要打电话催没到的人。 */
    @Test
    @Timeout(5)
    void hostWithoutClub_seesNameAndPhone() {
        Map<String, Object> out = service.listPlayers(HOST, TOPIC, true);

        assertEquals(Boolean.TRUE, out.get("contactVisible"));
        assertNull(out.get("contactHint"), "能看的时候不该还挂一句解释");
        assertEquals("张明", rows(out).get(0).get("name"));
        assertEquals("13800000001", rows(out).get(0).get("phone"));
    }

    @Test
    void freeExploreUsesOnlyShopDayMerchantNarrowProjection() {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(2);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);
        IShopDayMerchantRosterService narrow = mock(IShopDayMerchantRosterService.class);
        Map<String, Object> expected = new HashMap<>();
        expected.put("rows", Collections.emptyList());
        when(narrow.list(MERCHANT, TOPIC, true)).thenReturn(expected);
        ReflectionTestUtils.setField(service, "shopDayMerchantRosterService", narrow);

        assertEquals(expected, service.listPlayers(MERCHANT, TOPIC, true));
        verify(narrow).list(MERCHANT, TOPIC, true);
    }

    /** 有俱乐部带团：主办方也不能绕过接收俱乐部直接取得玩家电话。 */
    @Test
    @Timeout(5)
    void hostWithAcceptedClubInvite_phoneWithheld() {
        givenOrganizerClub();

        Map<String, Object> out = service.listPlayers(HOST, TOPIC, true);

        assertEquals(Boolean.FALSE, out.get("contactVisible"));
        assertNull(rows(out).get(0).get("phone"));
        assertEquals("张明", rows(out).get(0).get("name"), "名字不打码");
        assertEquals("王小强", rows(out).get(1).get("name"), "名字不打码");
        assertTrue(String.valueOf(out.get("contactHint")).contains("俱乐部"));
        assertFalse(String.valueOf(out).contains("13800000001"));
    }

    /**
     * 没有俱乐部时,承接商家也给电话 —— 判据是「谁组织、谁负责联系」,
     * 没有组织方时谁履约谁就得能联系上人(2026-08-05 用户二次拍板)。
     */
    @Test
    @Timeout(5)
    void joinMerchantWithoutClub_seesPhone() {
        givenIAmMerchant();

        Map<String, Object> out = service.listPlayers(MERCHANT, TOPIC, true);

        assertEquals(Boolean.TRUE, out.get("contactVisible"));
        assertEquals("13800000001", rows(out).get(0).get("phone"));
        assertNotNull(rows(out).get(0).get("ticketNo"), "票号照给,核销要用");
    }

    @Test
    @Timeout(5)
    void missingCurrentConsentWithholdsThatPlayersPhone() {
        when(consentMapper.selectLatest(501L, "activity_host_data_sharing", "activity_signup"))
                .thenReturn(null);
        ApiProjectController controller = spy(new ApiProjectController());
        doReturn(HOST).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "projectPlayerReadService", service);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", TOPIC);

        AjaxResult response = controller.players(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) response.get(AjaxResult.DATA_TAG);

        assertNull(rows(out).get(0).get("phone"), "缺当前版本单独同意不得下发手机号");
        assertFalse(String.valueOf(out).contains("13800000001"));
        verify(consentMapper).selectLatest(501L, "activity_host_data_sharing", "activity_signup");
    }

    @Test
    void checkinOperatorUsesMerchantOwnerButNeverReceivesPhone() {
        long operator = 21L;
        IProjectPlayerReadService readService = mock(IProjectPlayerReadService.class);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(operator, MerchantPermission.VERIFY_RECORD_READ))
                .thenReturn(new MerchantAccessContext(
                        operator, 11L, MERCHANT, MerchantRoleCode.MERCHANT_CHECKIN));
        when(readService.listPlayers(MERCHANT, TOPIC, false)).thenReturn(Collections.emptyMap());
        ApiProjectController controller = spy(new ApiProjectController());
        doReturn(operator).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "projectPlayerReadService", readService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", TOPIC);
        body.put("scope", "MERCHANT");

        AjaxResult response = controller.players(body);

        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        verify(readService).listPlayers(MERCHANT, TOPIC, false);
    }

    @Test
    void disabledMerchantTopicOwnerCannotUseHostFastPathToReadPhones() {
        IProjectPlayerReadService readService = mock(IProjectPlayerReadService.class);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        IMmsMerchantService merchantService = mock(IMmsMerchantService.class);
        MmsMerchant disabled = new MmsMerchant();
        disabled.setId(11L);
        disabled.setMemberId(HOST);
        disabled.setStatus(1L);
        disabled.setAccountStatus(2L);
        disabled.setDelFlag(0);
        when(merchantService.selectMmsMerchantByMemberId(HOST)).thenReturn(disabled);
        when(accessService.require(HOST, MerchantPermission.VERIFY_RECORD_READ))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException(
                        "商家身份无效或权限不足", 403));
        ApiProjectController controller = spy(new ApiProjectController());
        doReturn(HOST).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "projectPlayerReadService", readService);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicSvc);
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        Map<String, Object> body = new HashMap<>();
        body.put("topicId", TOPIC);

        AjaxResult response = controller.players(body);

        assertEquals(403, response.get(AjaxResult.CODE_TAG));
        verify(accessService).require(HOST, MerchantPermission.VERIFY_RECORD_READ);
        org.mockito.Mockito.verifyNoInteractions(readService);
    }

    /** 承接商家同样不能绕过带团俱乐部读取玩家电话。 */
    @Test
    @Timeout(5)
    void joinMerchantWithAcceptedClubInvite_phoneWithheld() {
        givenIAmMerchant();
        givenOrganizerClub();

        Map<String, Object> out = service.listPlayers(MERCHANT, TOPIC, true);

        assertEquals(Boolean.FALSE, out.get("contactVisible"));
        assertNull(rows(out).get(1).get("phone"));
        assertFalse(String.valueOf(out).contains("13800000002"));
    }

    /**
     * ★俱乐部自己发的主题:靠 cms_topic.club_id 挂靠,没有任何邀约。
     * 只查邀约会漏掉俱乐部自办主题，导致承接商家绕过组织方读取玩家电话。
     */
    @Test
    @Timeout(5)
    void clubOwnedTopic_phoneWithheldEvenWithoutInvite() {
        CmsTopic clubTopic = new CmsTopic();
        clubTopic.setId(TOPIC);
        clubTopic.setMemberId(HOST);
        clubTopic.setClubId(77L);          // 俱乐部自办:挂靠,无邀约
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(clubTopic);

        Map<String, Object> out = service.listPlayers(HOST, TOPIC, true);

        assertEquals(Boolean.FALSE, out.get("contactVisible"), "俱乐部自办同样不给玩家电话");
        assertNull(rows(out).get(0).get("phone"));
        assertFalse(String.valueOf(out).contains("13800000001"));
    }

    /** 负控的另一半:clubId=0(哨兵)不算挂靠,否则存量 0 会把所有主题判成有俱乐部。 */
    @Test
    @Timeout(5)
    void clubIdZeroSentinel_notTreatedAsOrganizer() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(HOST);
        t.setClubId(0L);
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(t);

        assertEquals(Boolean.TRUE, service.listPlayers(HOST, TOPIC, true).get("contactVisible"));
    }

    /** 既不是发布者也没承接 = 外人,整个名单都不给。 */
    @Test
    @Timeout(5)
    void stranger_rejected() {
        assertThrows(RuntimeException.class, () -> service.listPlayers(STRANGER, TOPIC, true));
    }

    /**
     * 已支付与已退款都要查。★ 原先只查已支付,退款的玩家从名单里凭空消失 ——
     * 对商家来说「昨天还在、今天没了」和「退款了」是两回事,前者只会让人以为自己看错了。
     */
    @Test
    @Timeout(5)
    void paidAndRefundedRegistrationsQueried() {
        service.listPlayers(HOST, TOPIC, true);

        org.mockito.ArgumentCaptor<CmsRegistration> cap =
                org.mockito.ArgumentCaptor.forClass(CmsRegistration.class);
        org.mockito.Mockito.verify(regSvc, org.mockito.Mockito.times(2))
                .selectCmsRegistrationList(cap.capture());
        java.util.List<Integer> statuses = new java.util.ArrayList<>();
        for (CmsRegistration probe : cap.getAllValues()) {
            statuses.add(probe.getPaymentStatus());
            assertEquals(TOPIC, probe.getOwnerId());
        }
        assertTrue(statuses.contains(2), "要查已支付");
        assertTrue(statuses.contains(4), "要查已退款");
        assertEquals(2, statuses.size(), "只查这两档,别把未支付也捞进来");
    }

    /** 汇总口径:谁来了、谁还没来、谁退了。退款不进 paid/pending,但单独有数。 */
    @Test
    @Timeout(5)
    void summaryCountsArrivedAndPending() {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) service.listPlayers(HOST, TOPIC, true).get("summary");

        assertEquals(2, s.get("paidCount"), "退款的人不该把「卖了多少」撑大");
        assertEquals(1, s.get("arrivedCount"));
        assertEquals(1, s.get("pendingCount"));
        assertEquals(1, s.get("refundedCount"));
    }

    /** 退款那一行留在名单里,状态是 refunded,且不算「到店」。 */
    @Test
    @Timeout(5)
    void refundedRowStaysVisibleWithItsOwnState() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) service.listPlayers(HOST, TOPIC, true).get("rows");

        Map<String, Object> refunded = null;
        for (Map<String, Object> row : rows) {
            if ("refunded".equals(row.get("state"))) refunded = row;
        }
        assertNotNull(refunded, "退款的玩家不许从名单里消失");
        assertEquals(Boolean.FALSE, refunded.get("arrived"));
        assertEquals(3, rows.size());
    }
}
