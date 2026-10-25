package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.CoopPartnerVO;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B5 合作方身份 + B6 邀约节点(2026-08-05)。
 *
 * <p><b>B5 洞</b>:{@code /api/coop/list} 出参只有 to_type/to_id 这类内部标识,没有对方名称
 * ⇒ 项目页合作区只能渲染成「俱乐部 · 合作中」,商家看不出到底是谁在跟他合作。
 * <b>闸</b>:名称恒下发;负责人 + 电话按方案 §3.8 分两种闸 ——
 * sent(我发出的)= 主办方视角,闸是 {@code topic.memberId == 我},<b>没有 status 条件</b>;
 * received(我收到的)= 承接方视角,闸是邀约 {@code status=1}(已接受)。
 *
 * <p><b>H1</b>:{@code inviteType=2}(商家邀商家承接节点)已撤销。新建与历史处理入口都必须
 * fail-closed；历史行只允许列表读取，不能再接受、拒绝或取消。
 *
 * <p>★ 每条断言都指名被拦的具体那道闸(错误文案 / 被摘掉的具体字段),不只断言「拒了」:
 * 本方法相邻多道闸(登录 / 受邀方 / 主题归属 / 条款)都会返回同型 500,互相接得住。
 */
class ApiCoopControllerPartnerNodeTest {

    private static final Long ME = 500L;
    private static final Long PEER = 88L;
    private static final Long TOPIC = 200L;
    private static final Long NODE = 3001L;
    private static final Long CLUB = 7001L;
    private static final Long INVITE_ID = 61L;

    private ICoopInviteService coopInviteService;
    private ICmsTopicService cmsTopicService;
    private IClubService clubService;
    private IMmsMerchantService mmsMerchantService;
    private IImService imService;
    private ApiCoopController c;

    @BeforeEach
    void setUp() {
        coopInviteService = mock(ICoopInviteService.class);
        cmsTopicService = mock(ICmsTopicService.class);
        clubService = mock(IClubService.class);
        mmsMerchantService = mock(IMmsMerchantService.class);
        imService = mock(IImService.class);

        c = spy(new ApiCoopController());
        doReturn(ME).when(c).getAppUserId();
        ReflectionTestUtils.setField(c, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(c, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(c, "clubService", clubService);
        ReflectionTestUtils.setField(c, "mmsMerchantService", mmsMerchantService);
        ReflectionTestUtils.setField(c, "imService", imService);
        ReflectionTestUtils.setField(c, "coopClubApplyService",
                mock(com.chengyinhub.business.service.ICoopClubApplyService.class));
        ReflectionTestUtils.setField(c, "notifyService",
                mock(com.chengyinhub.business.service.INotifyService.class));
        ReflectionTestUtils.setField(c, "umsMemberService",
                mock(com.chengyinhub.business.service.IUmsMemberService.class));
        com.chengyinhub.business.access.merchant.IMerchantAccessService merchantAccess =
                mock(com.chengyinhub.business.access.merchant.IMerchantAccessService.class);
        when(merchantAccess.require(ME,
                com.chengyinhub.business.access.merchant.MerchantPermission.COOP_MANAGE))
                .thenReturn(new com.chengyinhub.business.access.merchant.MerchantAccessContext(
                        ME, 9001L, ME,
                        com.chengyinhub.business.access.merchant.MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(c, "merchantAccessService", merchantAccess);

        CmsTopic mine = new CmsTopic();
        mine.setId(TOPIC);
        mine.setMemberId(ME);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(mine);
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenReturn(Collections.<CoopInvite>emptyList());
        when(clubService.selectClubsByMemberId(any())).thenReturn(Collections.<Club>emptyList());
    }

    private static String msg(AjaxResult r) {
        return String.valueOf(r.get("msg"));
    }

    private void givenClub(Long clubId, String name, String leader, String phone) {
        Club club = new Club();
        club.setId(clubId);
        club.setName(name);
        club.setLeaderName(leader);
        club.setPhone(phone);
        when(clubService.selectClubById(clubId)).thenReturn(club);
    }

    /** 我发出的一条邀约(from=我),用于 /list 的 sent 分支。 */
    private CoopInvite sentInviteToClub(Integer status) {
        CoopInvite inv = new CoopInvite();
        inv.setId(INVITE_ID);
        inv.setFromId(ME);
        inv.setToType("club");
        inv.setToId(CLUB);
        inv.setTopicId(TOPIC);
        inv.setStatus(status);
        return inv;
    }

    @SuppressWarnings("unchecked")
    private CoopPartnerVO listSentPartner(CoopInvite inv) {
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenReturn(Collections.singletonList(inv));
        AjaxResult r = c.list(null);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<CoopInvite> sent = (List<CoopInvite>) data.get("sent");
        return sent.get(0).getPartner();
    }

    /** 我收到的一条邀约(from=对方,to=我):走 /list 的 received 分支。 */
    private CoopInvite receivedInviteFromPeer(Integer status) {
        CoopInvite inv = new CoopInvite();
        inv.setId(INVITE_ID);
        inv.setFromId(PEER);
        inv.setToType("merchant");
        inv.setToId(ME);
        inv.setTopicId(TOPIC);
        inv.setStatus(status);
        return inv;
    }

    @SuppressWarnings("unchecked")
    private CoopPartnerVO listReceivedPartner(CoopInvite inv) {
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenReturn(Collections.singletonList(inv));
        AjaxResult r = c.list(null);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<CoopInvite> received = (List<CoopInvite>) data.get("received");
        return received.get(0).getPartner();
    }

    private void givenPeerMerchant(String name, String phone) {
        MmsMerchant m = new MmsMerchant();
        m.setName(name);
        m.setPhone(phone);
        when(mmsMerchantService.selectMmsMerchantByMemberId(PEER)).thenReturn(m);
    }

    // ------------------------ B5 名称恒给 / 联系方式按 §3.8 分两种闸 ------------------------
    // ★ 两行的闸不同,别拿第一行那条套全场:
    //   sent(我发出的)= 主办方视角,闸 = topic.memberId == 我,**没有 status 条件**;
    //   received(我收到的)= 承接方视角,闸 = 邀约 status=1 已接受。

    /** 已接受:双向都给名称 + 负责人 + 电话。 */
    /**
     * 官方邀约写的是同一张 coop_invite(from_type=official),而 official_publish_whitelist 当前配的
     * 100092 本身就是商家 ⇒ 只按 from_id 查 sent,会把本该只出现在 /api/official/invites 的官方邀约
     * 混进普通合作列表。两个界面是分开的(官方那边按 FROM_OFFICIAL 正向过滤),这里必须剔除。
     */
    @Test
    void list_sentExcludesOfficialInvites() {
        givenClub(CLUB, "夜跑俱乐部", "张三", "13800000000");
        CoopInvite normal = sentInviteToClub(1);
        CoopInvite official = sentInviteToClub(1);
        official.setFromType(CoopInvite.FROM_OFFICIAL);
        when(coopInviteService.selectCoopInviteListReadOnly(any()))
                .thenReturn(java.util.Arrays.asList(normal, official));

        AjaxResult r = c.list(null);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<CoopInvite> sent = (List<CoopInvite>) data.get("sent");

        assertEquals(1, sent.size(), "官方邀约不该出现在普通合作列表的 sent 里");
        assertTrue(!CoopInvite.FROM_OFFICIAL.equals(sent.get(0).getFromType()),
                "留下的必须是非官方那条");
    }

    @Test
    void list_sentExcludesDissolvedClubInviteButRetainsDatabaseHistory() {
        CoopInvite archived = sentInviteToClub(0);
        when(coopInviteService.selectCoopInviteListReadOnly(any()))
                .thenReturn(Collections.singletonList(archived));
        when(clubService.selectClubById(CLUB)).thenReturn(null);

        AjaxResult r = c.list(null);
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        List<CoopInvite> sent = (List<CoopInvite>) data.get("sent");

        assertEquals(0, sent.size(), "已解散俱乐部邀约不能成为普通合作列表幽灵项");
    }

    @Test
    void list_receivedExcludesInviteFromDissolvedClubTopic() {
        CoopInvite received = receivedInviteFromPeer(0);
        CmsTopic archivedTopic = new CmsTopic();
        archivedTopic.setId(TOPIC);
        archivedTopic.setClubId(77L);
        when(cmsTopicService.selectCmsTopicById(TOPIC)).thenReturn(archivedTopic);
        when(clubService.selectClubById(77L)).thenReturn(null);
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenReturn(Collections.singletonList(received));

        AjaxResult result = c.list(null);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        List<CoopInvite> rows = (List<CoopInvite>) data.get("received");

        assertEquals(0, rows.size(), "已解散俱乐部主题发来的邀约不能留在收到列表");
    }

    /** 商家档案读取抛异常时降级为昵称档,但不得把整个请求带崩(fail-closed 且有日志)。 */
    @Test
    void list_merchantLookupThrows_degradesToNickWithoutContact() {
        CoopInvite inv = sentInviteToClub(1);
        inv.setToType("merchant");
        inv.setToId(777L);
        when(mmsMerchantService.selectMmsMerchantByMemberId(777L))
                .thenThrow(new RuntimeException("db down"));

        CoopPartnerVO p = listSentPartner(inv);

        // 不抛出、不 500;拿不到商家档案就没有联系方式可给
        assertTrue(p == null || p.getPhone() == null,
                "商家档案读不到时不得凭空给出联系方式");
    }

    @Test
    void list_acceptedInvite_exposesPartnerNameAndContact() {
        givenClub(CLUB, "夜跑俱乐部", "张三", "13800000000");

        CoopPartnerVO p = listSentPartner(sentInviteToClub(1));

        assertNotNull(p, "已接受的合作必须能看出对方是谁");
        assertEquals("夜跑俱乐部", p.getName());
        assertEquals("张三", p.getLeaderName(), "已接受时负责人须下发");
        assertEquals("13800000000", p.getPhone(), "已接受时电话须下发");
    }

    /**
     * §3.8 第二行:我发出的邀约 = 主办方视角,<b>挂起中(status=0)照样给联系方式</b>。
     * /invite 已强制「只能为自己发布的主题发起合作」⇒ sent 恒满足 topic.memberId == 我;
     * §3.4「招募中 → 名单(名称 + 电话)」也要求这一条。
     */
    @Test
    void list_sentPendingInvite_stillExposesContactForHost() {
        givenClub(CLUB, "夜跑俱乐部", "张三", "13800000000");

        CoopPartnerVO p = listSentPartner(sentInviteToClub(0));

        assertEquals("夜跑俱乐部", p.getName());
        assertEquals("张三", p.getLeaderName(), "主办方在招募中就该拿到负责人(§3.8 第二行无 status 条件)");
        assertEquals("13800000000", p.getPhone(), "主办方在招募中就该拿到电话");
    }

    /**
     * §3.8 第一行:我收到的、还没接受(status=0)—— 名称照给,负责人 + 电话必须摘掉。
     * ★ 断言的是「被摘掉的具体字段」而不是「partner 为空」—— 后者分不清是脱敏闸生效
     * 还是整块装配挂了;名称仍在,才证明拦的是联系方式这道闸。
     * ★ 这条是 sent 侧放开后最该锁住的一条:放开一侧的同时必须证明另一侧还锁着。
     */
    @Test
    void list_receivedPendingInvite_keepsNameButStripsContact() {
        givenPeerMerchant("发起方商家", "13922222222");

        CoopPartnerVO p = listReceivedPartner(receivedInviteFromPeer(0));

        assertNotNull(p, "未接受也要能看出是谁邀的我(名称是可公开信息)");
        assertEquals("发起方商家", p.getName(), "名称不受闸");
        assertNull(p.getLeaderName(), "收到的邀约未接受前不得下发负责人");
        assertNull(p.getPhone(), "收到的邀约未接受前不得下发电话");
    }

    /** 我收到的、已拒绝(status=2)同样不给 —— 闸是「== 1」而不是「!= 0」。 */
    @Test
    void list_receivedRejectedInvite_stripsContact() {
        givenPeerMerchant("发起方商家", "13922222222");

        CoopPartnerVO p = listReceivedPartner(receivedInviteFromPeer(2));

        assertEquals("发起方商家", p.getName());
        assertNull(p.getPhone(), "已拒绝的合作不得下发电话");
    }

    /** 我收到的、已接受(status=1):给电话 —— 别把闸修成恒拒。 */
    @Test
    void list_receivedAcceptedInvite_exposesContact() {
        givenPeerMerchant("发起方商家", "13922222222");

        CoopPartnerVO p = listReceivedPartner(receivedInviteFromPeer(1));

        assertEquals("13922222222", p.getPhone(), "已接受的合作方电话须下发");
    }

    /** 受邀方是商家(to_type=merchant,to_id 即会员 id):取商家档案的店名 + 电话。 */
    @Test
    void list_acceptedMerchantInvite_exposesMerchantName() {
        MmsMerchant m = new MmsMerchant();
        m.setName("城南咖啡");
        m.setPhone("13911111111");
        when(mmsMerchantService.selectMmsMerchantByMemberId(PEER)).thenReturn(m);
        CoopInvite inv = sentInviteToClub(1);
        inv.setToType("merchant");
        inv.setToId(PEER);

        CoopPartnerVO p = listSentPartner(inv);

        assertEquals("城南咖啡", p.getName());
        assertEquals("13911111111", p.getPhone());
    }

    /** 我收到的邀约:对方是发起方,不是 to_id(to_id 是我自己)。 */
    @Test
    void list_receivedInvite_partnerIsInviter() {
        givenPeerMerchant("发起方商家", "13922222222");

        CoopPartnerVO p = listReceivedPartner(receivedInviteFromPeer(1));

        assertEquals("发起方商家", p.getName(), "收到的邀约里的合作方应是发起方,不是我自己");
    }

    // ---------------------------------------------- H1 type2 只读兼容闸

    private CoopInvite type2Invite(Long nodeId) {
        CoopInvite b = new CoopInvite();
        b.setInviteType(2);
        b.setToType("merchant");
        b.setToId(PEER);
        b.setTopicId(TOPIC);
        b.setNodeId(nodeId);
        return b;
    }

    @Test
    void invite_type2_rejectedBeforeAnyPersistence() {
        AjaxResult r = c.invite(type2Invite(NODE));

        assertEquals(500, r.get("code"));
        assertTrue(msg(r).contains("邀请类型不合法"), "错误文案须指明 type2 已关闭,实际:" + msg(r));
        verify(coopInviteService, never()).insertCoopInvite(any());
    }

    @Test
    void invite_type0_and_type1_stillPass() {
        CoopInvite type0 = type2Invite(NODE);
        type0.setInviteType(0);
        CoopInvite type1 = type2Invite(NODE);
        type1.setInviteType(1);
        type1.setToType("club");
        type1.setToId(CLUB);

        AjaxResult type0Result = c.invite(type0);
        AjaxResult type1Result = c.invite(type1);

        assertEquals(200, type0Result.get("code"), "type0 实际:" + msg(type0Result));
        assertEquals(200, type1Result.get("code"), "type1 实际:" + msg(type1Result));
        verify(coopInviteService).insertCoopInvite(type0);
        verify(coopInviteService).insertCoopInvite(type1);
        assertNull(type0.getNodeId(), "type0 不得携带历史 type2 的 node_id");
        assertNull(type1.getNodeId(), "type1 不得携带历史 type2 的 node_id");
    }

    @Test
    void invite_rebuildsForgedFromTypeFromLoginPath() {
        CoopInvite body = type2Invite(NODE);
        body.setInviteType(0);
        body.setFromType(CoopInvite.FROM_OFFICIAL);

        AjaxResult r = c.invite(body);

        assertEquals(200, r.get("code"), "普通邀约不应因客户端伪造 fromType 改走官方路径");
        verify(coopInviteService).insertCoopInvite(body);
        assertEquals("talent", body.getFromType(), "普通 Controller 必须无条件按登录态重建 fromType");
    }

    /** 非 type2 客户端塞的 node_id 无语义,须清空,别让脏值进库。 */
    @Test
    void invite_type1_dropsClientNodeId() {
        CoopInvite body = type2Invite(NODE);
        body.setInviteType(1);
        body.setToType("club");
        body.setToId(CLUB);

        AjaxResult r = c.invite(body);

        assertEquals(200, r.get("code"), "实际:" + msg(r));
        assertNull(body.getNodeId(), "非 type2 不得保留客户端传的 node_id");
    }

    // ---------------------------------------------- 历史 type2 不得再写状态

    @Test
    void handle_legacyType2_isReadOnly() {
        CoopInvite stored = type2Invite(NODE);
        stored.setId(INVITE_ID);
        stored.setFromId(PEER);
        stored.setToId(ME);        // 我是受邀方
        stored.setStatus(0);
        when(coopInviteService.selectCoopInviteById(INVITE_ID)).thenReturn(stored);
        CoopInvite req = new CoopInvite();
        req.setId(INVITE_ID);
        req.setStatus(1);

        AjaxResult r = c.handle(req);

        assertEquals(500, r.get("code"), "实际:" + msg(r));
        assertTrue(msg(r).contains("仅供查看"), "历史 type2 必须明确只读,实际:" + msg(r));
        verify(coopInviteService, never()).acceptInviteWithLock(any(), any());
        verify(coopInviteService, never()).acceptInviteWithLock(any(), any(), any());
        verify(coopInviteService, never()).updateCoopInvite(any());
    }

    @Test
    void contact_legacyType2_doesNotCreateConversation() {
        CoopInvite stored = type2Invite(NODE);
        stored.setId(INVITE_ID);
        stored.setFromId(ME);
        stored.setStatus(1);
        when(coopInviteService.selectCoopInviteById(INVITE_ID)).thenReturn(stored);
        CoopInvite req = new CoopInvite();
        req.setId(INVITE_ID);

        AjaxResult r = c.contact(req);

        assertEquals(500, r.get("code"), "实际:" + msg(r));
        assertTrue(msg(r).contains("仅供查看"), "历史 type2 联系入口必须明确只读,实际:" + msg(r));
        verify(imService, never()).startSingle(any(), any());
    }

    // 发件箱「取消合作」置灰按后端真实锁价态:termsFrozen 与取消闸同一判据,不是前端写死
    @Test
    @SuppressWarnings("unchecked")
    void listMarksTermsFrozenFromTopicLifecycleAndFillsTopicSummary() {
        CmsTopic topic = cmsTopicService.selectCmsTopicById(TOPIC);
        topic.setName("周末夜跑");
        topic.setImgUrl("https://img/c.jpg");
        topic.setLifecycle(1); // 招募中:可取消
        CoopInvite inv = sentInviteToClub(1);
        givenClub(CLUB, "夜跑团", "张", "13800000000");
        when(coopInviteService.selectCoopInviteListReadOnly(any())).thenReturn(Collections.singletonList(inv));

        List<CoopInvite> sent = (List<CoopInvite>) ((Map<String, Object>) c.list(null).get("data")).get("sent");
        assertEquals(Boolean.FALSE, sent.get(0).getTermsFrozen());
        assertEquals("周末夜跑", sent.get(0).getTopicName());
        assertEquals("https://img/c.jpg", sent.get(0).getTopicImgUrl());

        topic.setLifecycle(3); // 售票中:已锁价
        sent = (List<CoopInvite>) ((Map<String, Object>) c.list(null).get("data")).get("sent");
        assertEquals(Boolean.TRUE, sent.get(0).getTermsFrozen());
    }
}
