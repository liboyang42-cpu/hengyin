package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.impl.ProjectHomeReadServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第十二轮 k4(j2 N-01):已中标商家的带条款邀约进度随项目主页下发,招商弹窗据此给「发邀约」;
 * 没有合作单的站名给俱乐部「现在要做」。
 */
class ProjectHomeTermsInviteStateTest {

    private static final Long HOST = 10L;
    private static final Long SHOP = 20L;
    private static final Long TOPIC = 300L;

    private static CoopInvite invite(long id, Long to, int status, Integer type) {
        CoopInvite i = new CoopInvite();
        i.setId(id);
        i.setTopicId(TOPIC);
        i.setToType("merchant");
        i.setToId(to);
        i.setStatus(status);
        i.setInviteType(type);
        return i;
    }

    @Test
    void termsInvitePrefersAcceptedAndIgnoresLegacyNodeInvites() {
        CoopInvite legacy = invite(1, SHOP, 1, CoopInvite.TYPE_LEGACY_MERCHANT_NODE);
        CoopInvite pending = invite(2, SHOP, 0, 0);
        CoopInvite accepted = invite(3, SHOP, 1, 0);
        assertNull(ProjectHomeReadServiceImpl.termsInviteTo(Collections.singletonList(legacy), SHOP));
        assertSame(pending, ProjectHomeReadServiceImpl.termsInviteTo(Arrays.asList(legacy, pending), SHOP));
        assertSame(accepted, ProjectHomeReadServiceImpl.termsInviteTo(Arrays.asList(pending, accepted), SHOP));
        assertNull(ProjectHomeReadServiceImpl.termsInviteTo(Collections.singletonList(invite(4, 99L, 1, 0)), SHOP));
    }

    private ProjectHomeReadServiceImpl service(List<CoopInvite> invites) {
        ICmsTopicService topicSvc = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodeSvc = mock(ICmsTopicNodeService.class);
        ICmsRegistrationMerchantService regSvc = mock(ICmsRegistrationMerchantService.class);
        ICoopInviteService inviteSvc = mock(ICoopInviteService.class);
        CmsTopic topic = topic();
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);
        CmsRegistrationMerchant winner = new CmsRegistrationMerchant();
        winner.setId(14L);
        winner.setTopicId(TOPIC);
        winner.setMemberId(SHOP);
        winner.setNodeId(71L);
        winner.setStatus(1L);
        winner.setAuditStatus(1L);
        when(regSvc.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.singletonList(winner));
        CmsTopicNode node = new CmsTopicNode();
        node.setId(71L);
        node.setName("人民广场音乐喷泉");
        when(nodeSvc.selectCmsTopicNodeById(71L)).thenReturn(node);
        when(nodeSvc.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        when(inviteSvc.selectCoopInviteList(any())).thenReturn(invites);
        when(inviteSvc.selectReadTimes(any())).thenReturn(Collections.emptyMap());
        ICmsRegistrationService registrationSvc = mock(ICmsRegistrationService.class);
        when(registrationSvc.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        return new ProjectHomeReadServiceImpl(topicSvc, nodeSvc, regSvc, registrationSvc, inviteSvc,
                mock(IClubService.class), mock(IMmsMerchantService.class),
                mock(IMerchantRegistrationDetailReadService.class), mock(IMerchantChapterApplicationService.class));
    }

    private static CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setMemberId(HOST);
        t.setProductType(ProductType.ORIENTEERING);
        t.setLifecycle(1);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merchantRow(Map<String, Object> home) {
        Map<String, Object> host = (Map<String, Object>) home.get("host");
        return ((List<Map<String, Object>>) host.get("merchants")).get(0);
    }

    @Test
    void winnerWithoutInviteIsMarkedNoneAndListedAsUncontracted() {
        ProjectHomeReadServiceImpl svc = service(Collections.emptyList());
        Map<String, Object> row = merchantRow(svc.getProjectHome(HOST, TOPIC));
        assertEquals("none", row.get("inviteState"));
        assertFalse(row.containsKey("inviteId"));
        assertEquals(Collections.singletonList("人民广场音乐喷泉"), svc.uncontractedStationNames(topic()));
    }

    @Test
    void pendingInviteIsSentAndStillUncontracted() {
        ProjectHomeReadServiceImpl svc = service(Collections.singletonList(invite(9, SHOP, 0, 0)));
        Map<String, Object> row = merchantRow(svc.getProjectHome(HOST, TOPIC));
        assertEquals("sent", row.get("inviteState"));
        assertEquals(9L, row.get("inviteId"));
        assertEquals(Collections.singletonList("人民广场音乐喷泉"), svc.uncontractedStationNames(topic()));
    }

    @Test
    void acceptedInviteClearsTheHold() {
        ProjectHomeReadServiceImpl svc = service(Collections.singletonList(invite(9, SHOP, 1, 0)));
        Map<String, Object> row = merchantRow(svc.getProjectHome(HOST, TOPIC));
        assertEquals("accepted", row.get("inviteState"));
        assertTrue(svc.uncontractedStationNames(topic()).isEmpty());
        CmsTopic free = topic();
        free.setProductType(ProductType.FREE_EXPLORE);
        assertTrue(service(Collections.emptyList()).uncontractedStationNames(free).isEmpty(), "自由探索不走站点合作单闸");
    }
}
