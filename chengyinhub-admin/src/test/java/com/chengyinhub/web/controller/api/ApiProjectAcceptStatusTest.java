package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * P0-4 承接三态:我的项目卡必须带 acceptStatus(P0-4 验收:每张项目卡显示当前承接状态)。
 *
 * 商家/个人主题(clubId=null,对手方=俱乐部):clubAccepted 俱乐部已承接 / clubOpen 俱乐部未承接 / selfRun 自行承办。
 * 俱乐部主题(clubId!=null,对手方=商家):merchantAccepted 商家已承接 / merchantOpen 商家未承接 / selfRun 自行承办。
 * 「已承接」判据 = coop_invite 该主题存在 status=1 且 to_type 为对手方 —— 邀约/开放报名两条路殊途同归。
 */
class ApiProjectAcceptStatusTest {

    private static final long ME = 42L;

    private ApiProjectController controller;
    private ICmsTopicService cmsTopicService;
    private ICoopInviteService coopInviteService;

    private final List<CoopInvite> acceptedInvites = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cmsTopicService = mock(ICmsTopicService.class);
        coopInviteService = mock(ICoopInviteService.class);
        ICmsActivityService actService = mock(ICmsActivityService.class);
        ICmsRegistrationMerchantService regService = mock(ICmsRegistrationMerchantService.class);
        IMerchantAccessService merchantAccessService = mock(IMerchantAccessService.class);

        controller = spy(new ApiProjectController());
        doReturn(ME).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "cmsActivityService", actService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", regService);
        ReflectionTestUtils.setField(controller, "coopInviteService", coopInviteService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);

        when(merchantAccessService.resolve(ME)).thenReturn(null);
        when(cmsTopicService.selectDraftTopicIds(anyLong())).thenReturn(Collections.emptyList());
        when(actService.selectCmsActivityList(any())).thenReturn(Collections.emptyList());
        when(regService.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.emptyList());
        when(coopInviteService.selectCoopInviteList(any(CoopInvite.class))).thenAnswer(inv -> {
            CoopInvite q = inv.getArgument(0);
            List<CoopInvite> out = new ArrayList<>();
            for (CoopInvite i : acceptedInvites) {
                List<?> topicIds = (List<?>) q.getParams().get("topicIds");
                if ((q.getTopicId() != null && q.getTopicId().equals(i.getTopicId())
                        || topicIds != null && topicIds.contains(i.getTopicId()))
                        && (q.getStatus() == null || q.getStatus().equals(i.getStatus()))) {
                    out.add(i);
                }
            }
            return out;
        });
    }

    private CmsTopic myTopic(Long id, Long clubId, int openClubPool, int merchantStatus) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setMemberId(ME);
        t.setClubId(clubId);
        t.setOpenClubPool(openClubPool);
        t.setMerchantStatus(merchantStatus);
        t.setStatus(1);
        t.setUserStatus(1);
        t.setName("主题" + id);
        return t;
    }

    private void accepted(Long topicId, String toType) {
        CoopInvite i = new CoopInvite();
        i.setTopicId(topicId);
        i.setToType(toType);
        i.setStatus(1);
        acceptedInvites.add(i);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> row(Long topicId) {
        AjaxResult r = controller.my(null, null, null, 1, 50, null);
        assertEquals(200, r.get("code"));
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        for (Map<String, Object> m : (List<Map<String, Object>>) data.get("rows")) {
            if (topicId.equals(m.get("id")) && "topic".equals(m.get("bizType"))) return m;
        }
        throw new AssertionError("row not found: " + topicId);
    }

    @Test
    void personal_topic_open_without_club_coop_is_club_open() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(1L, null, 1, 0)));
        Map<String, Object> m = row(1L);
        assertEquals("clubOpen", m.get("acceptStatus"));
        assertEquals("俱乐部未承接", m.get("acceptStatusText"));
    }

    @Test
    void personal_topic_with_accepted_club_coop_is_club_accepted() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(1L, null, 1, 0)));
        accepted(1L, "club");
        Map<String, Object> m = row(1L);
        assertEquals("clubAccepted", m.get("acceptStatus"));
        assertEquals("俱乐部已承接", m.get("acceptStatusText"));
    }

    @Test
    void personal_topic_closed_is_self_run() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(1L, null, 0, 0)));
        Map<String, Object> m = row(1L);
        assertEquals("selfRun", m.get("acceptStatus"));
        assertEquals("自行承办", m.get("acceptStatusText"));
    }

    @Test
    void invite_path_acceptance_counts_even_when_pool_closed() {
        // 商家没开开放报名、走定向邀约成交 → 一样是「俱乐部已承接」
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(1L, null, 0, 0)));
        accepted(1L, "club");
        assertEquals("clubAccepted", row(1L).get("acceptStatus"));
    }

    @Test
    void club_topic_open_merchant_pool_without_coop_is_merchant_open() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(2L, 7L, 0, 1)));
        Map<String, Object> m = row(2L);
        assertEquals("merchantOpen", m.get("acceptStatus"));
        assertEquals("商家未承接", m.get("acceptStatusText"));
    }

    @Test
    void club_topic_with_accepted_merchant_coop_is_merchant_accepted() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(2L, 7L, 0, 0)));
        accepted(2L, "merchant");
        Map<String, Object> m = row(2L);
        assertEquals("merchantAccepted", m.get("acceptStatus"));
        assertEquals("商家已承接", m.get("acceptStatusText"));
    }

    @Test
    void club_topic_closed_is_self_run() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(2L, 7L, 0, 0)));
        Map<String, Object> m = row(2L);
        assertEquals("selfRun", m.get("acceptStatus"));
        assertEquals("自行承办", m.get("acceptStatusText"));
    }

    @Test
    void mixed_topics_derive_independently() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Arrays.asList(
                myTopic(1L, null, 1, 0), myTopic(2L, 7L, 0, 1)));
        accepted(2L, "merchant");
        assertEquals("clubOpen", row(1L).get("acceptStatus"));
        assertEquals("merchantAccepted", row(2L).get("acceptStatus"));
    }

    @Test
    void accepted_merchant_coop_does_not_flip_personal_topic_club_state() {
        // 个人主题上有商家合作(type0/2)≠ 俱乐部承接
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(1L, null, 1, 0)));
        accepted(1L, "merchant");
        assertEquals("clubOpen", row(1L).get("acceptStatus"));
    }

    @Test
    void pending_invite_does_not_count_as_accepted() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(myTopic(1L, null, 1, 0)));
        CoopInvite pending = new CoopInvite();
        pending.setTopicId(1L);
        pending.setToType("club");
        pending.setStatus(0);
        acceptedInvites.add(pending);
        assertEquals("clubOpen", row(1L).get("acceptStatus"));
    }

    @Test
    void acceptedInviteBatchIsLimitedToVisibleOwnedTopics() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Arrays.asList(
                myTopic(1L, null, 1, 0), myTopic(2L, 7L, 0, 1)));
        accepted(2L, "merchant");
        accepted(999L, "club");
        assertEquals("clubOpen", row(1L).get("acceptStatus"));
        org.mockito.ArgumentCaptor<CoopInvite> query = org.mockito.ArgumentCaptor.forClass(CoopInvite.class);
        verify(coopInviteService).selectCoopInviteList(query.capture());
        assertEquals(Arrays.asList(1L, 2L), query.getValue().getParams().get("topicIds"));
        assertEquals(1, query.getValue().getStatus());
    }

    @Test
    void inviteBatchSqlPreservesLegacyFilterAndEmptyScopeCannotReadAll() throws Exception {
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.getTypeAliasRegistry().registerAlias("CoopInvite", CoopInvite.class);
        String resource = "mapper/business/CoopInviteMapper.xml";
        try (java.io.InputStream in = org.apache.ibatis.io.Resources.getResourceAsStream(resource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        org.apache.ibatis.mapping.MappedStatement statement = config.getMappedStatement(
                "com.chengyinhub.business.mapper.CoopInviteMapper.selectCoopInviteList");
        CoopInvite query = new CoopInvite();
        query.setStatus(1);
        query.getParams().put("topicIds", Arrays.asList(1L, 2L));
        org.apache.ibatis.mapping.BoundSql bound = statement.getBoundSql(query);
        String sql = bound.getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("del_flag = 0"));
        assertTrue(sql.contains("topic_id in"));
        assertTrue(sql.contains("status = ?"));
        assertEquals(3, bound.getParameterMappings().size());
        query.getParams().put("topicIds", Collections.emptyList());
        assertTrue(statement.getBoundSql(query).getSql().contains("and 1 = 0"));
        query.getParams().remove("topicIds");
        query.setTopicId(1L);
        sql = statement.getBoundSql(query).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("topic_id = ?"));
        assertFalse(sql.contains("topic_id in"));
    }
}
