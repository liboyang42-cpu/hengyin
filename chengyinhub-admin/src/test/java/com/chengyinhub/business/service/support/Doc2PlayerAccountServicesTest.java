package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.MyAppealRow;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.MemberAccountMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 文档二 L8:联系主办 / 联系商家(B6 / B7)、我的申诉(C1)、账号信誉(X7)。 */
class Doc2PlayerAccountServicesTest {

    private final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    private final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
    private final CmsActivityMapper activities = mock(CmsActivityMapper.class);
    private final ClubMapper clubs = mock(ClubMapper.class);
    private final MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
    private final PlayContactService contacts = new PlayContactService(topics, nodes, activities, clubs, merchants);

    private CmsTopic topic(Long id, Long clubId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(id);
        topic.setClubId(clubId);
        when(topics.selectCmsTopicById(id)).thenReturn(topic);
        return topic;
    }

    private Club club(Long id, String name, String phone, Long status) {
        Club club = new Club();
        club.setId(id);
        club.setName(name);
        club.setPhone(phone);
        club.setStatus(status);
        when(clubs.selectClubById(id)).thenReturn(club);
        return club;
    }

    @Test
    void clubThemeAndEventUseClubPhone() {
        topic(1L, 50L);
        club(50L, "城南徒步俱乐部", " 13800000000 ", 1L);
        Map<String, Object> contact = contacts.forTopic(1L, 999L);
        assertEquals("club", contact.get("kind"));
        assertEquals("城南徒步俱乐部", contact.get("name"));
        assertEquals("13800000000", contact.get("phone"));
        CmsActivity activity = new CmsActivity();
        activity.setClubId(50L);
        when(activities.selectCmsActivityById(8L)).thenReturn(activity);
        assertEquals("club", contacts.forActivity(8L).get("kind"));
    }

    @Test
    void freeExplorationUsesCurrentStationMerchant() {
        topic(2L, null);
        CmsTopicNode node = new CmsTopicNode();
        node.setTopicId(2L);
        node.setMerchantMemberId(70L);
        when(nodes.selectCmsTopicNodeById(5L)).thenReturn(node);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setName("示例咖啡");
        merchant.setPhone("021-0000000");
        merchant.setStatus(1L);
        when(merchants.selectMmsMerchantByMemberId(70L)).thenReturn(merchant);
        Map<String, Object> contact = contacts.forTopic(2L, 5L);
        assertEquals("merchant", contact.get("kind"));
        assertEquals("示例咖啡", contact.get("name"));
        assertNull(contacts.forTopic(2L, null));
        // 节点不属于该主题
        CmsTopicNode foreign = new CmsTopicNode();
        foreign.setTopicId(3L);
        foreign.setMerchantMemberId(70L);
        when(nodes.selectCmsTopicNodeById(6L)).thenReturn(foreign);
        assertNull(contacts.forTopic(2L, 6L));
        merchant.setStatus(0L);
        assertNull(contacts.forTopic(2L, 5L));
    }

    @Test
    void missingPhoneOrInactiveClubHidesRow() {
        topic(1L, 50L);
        club(50L, "城南徒步俱乐部", "  ", 1L);
        assertNull(contacts.forTopic(1L, null));
        club(50L, "城南徒步俱乐部", "13800000000", 0L);
        assertNull(contacts.forTopic(1L, null));
        Club deleted = club(50L, "城南徒步俱乐部", "13800000000", 1L);
        deleted.setDelFlag(2);
        assertNull(contacts.forTopic(1L, null));
        assertNull(contacts.forTopic(404L, null));
        assertNull(contacts.forActivity(null));
    }

    @Test
    void myAppealsMapsStatusesAndFilters() {
        MemberAccountMapper mapper = mock(MemberAccountMapper.class);
        MemberAccountService service = new MemberAccountService(mapper);
        MyAppealRow dispute = new MyAppealRow();
        dispute.setKind("dispute");
        dispute.setId(12L);
        dispute.setStatus(0);
        dispute.setTopicName("城南旧事 · 夜行定向");
        dispute.setCreateTime(new Date());
        MyAppealRow appeal = new MyAppealRow();
        appeal.setKind("appeal");
        appeal.setStatus(2);
        appeal.setNodeId(3L);
        appeal.setResolutionNote("计分记录与现场一致");
        when(mapper.selectMyAppeals(eq(9L), isNull(), eq(0), eq(20))).thenReturn(Arrays.asList(dispute, appeal));
        when(mapper.countMyAppeals(eq(9L), isNull())).thenReturn(2);
        Map<String, Object> page = service.myAppeals(9L, null, null, null);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        assertEquals("open", rows.get(0).get("status"));
        assertEquals("rejected", rows.get(1).get("status"));
        assertEquals("计分记录与现场一致", rows.get(1).get("resolutionNote"));
        assertEquals(2, page.get("total"));
        service.myAppeals(9L, "closed", 3, 500);
        verify(mapper).selectMyAppeals(9L, Boolean.FALSE, 100, 50);
        service.myAppeals(9L, "open", 1, 10);
        verify(mapper).selectMyAppeals(9L, Boolean.TRUE, 0, 10);
        assertEquals("accepted", MemberAccountService.status(1));
        assertThrows(ServiceException.class, () -> service.myAppeals(9L, "all", 1, 10));
    }

    @Test
    void reputationIsNormalOrRestrictedOnly() {
        MemberAccountMapper mapper = mock(MemberAccountMapper.class);
        MemberAccountService service = new MemberAccountService(mapper);
        when(mapper.countEffectiveBans(eq(9L), any(Date.class))).thenReturn(0);
        assertEquals("normal", service.reputation(9L).get("status"));
        when(mapper.countEffectiveBans(eq(9L), any(Date.class))).thenReturn(2);
        Map<String, Object> restricted = service.reputation(9L);
        assertEquals("restricted", restricted.get("status"));
        assertEquals(1, restricted.size());
    }
}
