package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IDispatchService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 铃铛「收到的」报名候选聚合端点:
 * 只聚合当前登录人作为发布者的主题,越权主题不可见;空结果稳定;多主题按待处理优先聚合。
 */
class ApiCoopCandidatesReceivedTest {

    private static final Long PUBLISHER = 9L;
    private static final Long OTHER_PUBLISHER = 99L;

    private ApiCoopController controller;
    private ICmsTopicService cmsTopicService;
    private ICmsRegistrationMerchantService regService;
    private IMmsMerchantService merchantService;
    private IClubService clubService;
    private IDispatchService dispatchService;
    private IMerchantAccessService merchantAccessService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiCoopController());
        doReturn(PUBLISHER).when(controller).getAppUserId();

        cmsTopicService = mock(ICmsTopicService.class);
        regService = mock(ICmsRegistrationMerchantService.class);
        merchantService = mock(IMmsMerchantService.class);
        clubService = mock(IClubService.class);
        dispatchService = mock(IDispatchService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", regService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "dispatchService", dispatchService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);

        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Collections.emptyList());
        when(regService.selectCmsRegistrationMerchantList(any())).thenReturn(Collections.emptyList());
    }

    private static CmsTopic topic(Long id, Long owner, String name) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setMemberId(owner);
        t.setName(name);
        return t;
    }

    private static CmsRegistrationMerchant registration(Long id, Long topicId, Long auditStatus) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setId(id);
        r.setTopicId(topicId);
        r.setMemberId(400L + id);
        r.setAuditStatus(auditStatus);
        r.setAddressName("站点 " + id);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(AjaxResult result) {
        assertEquals(200, result.get("code"));
        return (List<Map<String, Object>>) ((Map<String, Object>) result.get("data")).get("rows");
    }

    private static boolean hasMore(AjaxResult result) {
        assertEquals(200, result.get("code"));
        return Boolean.TRUE.equals(((Map<String, Object>) result.get("data")).get("hasMore"));
    }

    @Test
    void aggregates_registrations_across_my_topics_with_topic_identity() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(java.util.Arrays.asList(
                topic(200L, PUBLISHER, "苏州河夜行"),
                topic(201L, PUBLISHER, "外滩晨跑")));
        Map<Long, List<CmsRegistrationMerchant>> byTopic = new HashMap<>();
        byTopic.put(200L, Collections.singletonList(registration(81L, 200L, 0L)));
        byTopic.put(201L, Collections.singletonList(registration(82L, 201L, 1L)));
        when(regService.selectCmsRegistrationMerchantList(any())).thenAnswer(invocation -> {
            CmsRegistrationMerchant probe = invocation.getArgument(0);
            return byTopic.getOrDefault(probe.getTopicId(), Collections.emptyList());
        });

        AjaxResult result = controller.candidatesReceived(new HashMap<>());
        List<Map<String, Object>> rows = rows(result);

        assertEquals(2, rows.size());
        assertEquals(201L, rows.get(0).get("topicId"), "新主题在前");
        assertEquals("外滩晨跑", rows.get(0).get("topicName"));
        assertEquals(200L, rows.get(1).get("topicId"));
        assertEquals("苏州河夜行", rows.get(1).get("topicName"));
        assertFalse(hasMore(result));
        // 归属闸:只用「我发布的主题」枚举,调用参数必须是 owner 且未删
        org.mockito.ArgumentCaptor<CmsTopic> captor = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicService).selectCmsTopicList(captor.capture());
        assertEquals(PUBLISHER, captor.getValue().getMemberId());
        assertEquals(0, captor.getValue().getDelFlag());
    }

    @Test
    void pending_registrations_surface_before_history_and_newer_first() {
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(topic(200L, PUBLISHER, "苏州河夜行")));
        when(regService.selectCmsRegistrationMerchantList(any())).thenReturn(java.util.Arrays.asList(
                registration(1L, 200L, 2L),
                registration(2L, 200L, 0L),
                registration(3L, 200L, 1L),
                registration(4L, 200L, 0L)));

        List<Map<String, Object>> rows = rows(controller.candidatesReceived(new HashMap<>()));

        assertEquals(4, rows.size());
        assertEquals(4L, rows.get(0).get("id"), "待处理里新报名在前");
        assertEquals(2L, rows.get(1).get("id"));
        assertEquals(3L, rows.get(2).get("id"), "已中标属于历史");
        assertEquals(1L, rows.get(3).get("id"), "已落选属于历史");
    }

    @Test
    void ownership_scope_comes_from_publisher_query_not_client_topic_id() {
        // 归属闸在查询里:SQL 只回 member_id=我 的主题,客户端夹带的 topicId 不参与枚举
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(topic(200L, PUBLISHER, "我的主题")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topicId", 300L);
        List<Map<String, Object>> rows = rows(controller.candidatesReceived(body));

        org.mockito.ArgumentCaptor<CmsTopic> captor = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicService).selectCmsTopicList(captor.capture());
        assertEquals(PUBLISHER, captor.getValue().getMemberId(), "只枚举登录人发布的主题");
        assertEquals(0, captor.getValue().getDelFlag());
        assertTrue(rows.isEmpty(), "没有报名就是空表,不因客户端夹带 topicId 而变化");
    }

    @Test
    void empty_result_is_stable() {
        AjaxResult result = controller.candidatesReceived(null);

        assertEquals(200, result.get("code"));
        assertNotNull(rows(result));
        assertTrue(rows(result).isEmpty());
        assertFalse(hasMore(result));
    }

    @Test
    void dissolved_parent_club_topic_is_not_actionable() {
        CmsTopic withClub = topic(200L, PUBLISHER, "俱乐部主题");
        withClub.setClubId(77L);
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class)))
                .thenReturn(Collections.singletonList(withClub));
        when(clubService.selectClubById(77L)).thenReturn(null);
        when(regService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(registration(81L, 200L, 0L)));

        assertTrue(rows(controller.candidatesReceived(new HashMap<>())).isEmpty(),
                "父俱乐部已解散的主题与 /candidates 同闸,不作为可操作候选");
        verify(regService, never()).selectCmsRegistrationMerchantList(any());
    }

    @Test
    void topic_cap_truncates_deterministically_and_flags_more() {
        List<CmsTopic> topics = new ArrayList<>();
        for (long i = 0; i < 201; i++) topics.add(topic(200L + i, PUBLISHER, "主题" + i));
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(topics);
        when(regService.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.singletonList(registration(1L, 200L, 0L)));

        AjaxResult result = controller.candidatesReceived(new HashMap<>());

        assertEquals(200, rows(result).size());
        assertTrue(hasMore(result));
    }

    @Test
    void merchant_scope_uses_project_owner_and_never_principal() {
        Long owner = 10L;
        when(merchantAccessService.require(PUBLISHER, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(PUBLISHER, 77L, owner, MerchantRoleCode.MERCHANT_MANAGER));
        when(cmsTopicService.selectCmsTopicList(any(CmsTopic.class))).thenReturn(Collections.emptyList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", "MERCHANT");
        AjaxResult result = controller.candidatesReceived(body);

        assertEquals(200, result.get("code"));
        org.mockito.ArgumentCaptor<CmsTopic> captor = org.mockito.ArgumentCaptor.forClass(CmsTopic.class);
        verify(cmsTopicService).selectCmsTopicList(captor.capture());
        assertEquals(owner, captor.getValue().getMemberId());
    }

    @Test
    void merchant_scope_without_permission_returns_backend_refusal() {
        when(merchantAccessService.require(PUBLISHER, MerchantPermission.PROJECT_MANAGE))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("没有项目管理权限", 403));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", "MERCHANT");
        AjaxResult result = controller.candidatesReceived(body);

        assertEquals(403, result.get("code"));
        verify(cmsTopicService, never()).selectCmsTopicList(any(CmsTopic.class));
    }

    @Test
    void rejectCandidateEndpointIsRetiredForPublishers() throws Exception {
        // 2026-09-17 用户拍板:候选池「婉拒报名」退役,主办方侧端点不存在;
        // 后台驳回仍走 dispatchService.rejectRegistration(另一个入口),不在本测试主张。
        assertFalse(java.util.Arrays.stream(ApiCoopController.class.getMethods())
                .anyMatch(method -> "rejectCandidate".equals(method.getName())),
                "主办方婉拒端点必须从 ApiCoopController 删除");
        verify(dispatchService, never()).rejectRegistration(any(), any());
    }
}
