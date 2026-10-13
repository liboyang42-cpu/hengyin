package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.dto.CmsTopicEditDTO;
import com.chengyinhub.business.service.impl.TopicAdminCommandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 受托范围内主题编辑的状态机与内容主体边界必须由服务命令持有，不能留在 Controller。 */
class TopicAdminCommandServiceImplTest {

    private ICmsTopicService topicService;
    private IProductRoleService productRoleService;
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    private ITopicAdminCommandService service;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        productRoleService = mock(IProductRoleService.class);
        topicOperationsScopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        when(topicOperationsScopeAuthorizer.canViewTopic(anyLong())).thenReturn(true);
        when(topicOperationsScopeAuthorizer.canEditTopic(anyLong())).thenReturn(true);
        service = new TopicAdminCommandServiceImpl(topicService, productRoleService, topicOperationsScopeAuthorizer);
    }

    @Test
    void writesOnlyWhitelistedFieldsForOfficialTopics() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(0));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        CmsTopicEditDTO request = new CmsTopicEditDTO();
        request.setId(1L);
        request.setName("正常改个名");
        request.setDescription("简介");
        request.setUserStatus(1);
        request.setMerchantStatus(1);

        assertEquals(1, service.editTopicWithinScope(request));

        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicService).updateCmsTopic(captor.capture());
        CmsTopic patch = captor.getValue();
        assertEquals("正常改个名", patch.getName());
        assertEquals("简介", patch.getDescription());
        assertNull(patch.getLifecycle(), "生命周期不允许经过普通编辑写入");
        assertNull(patch.getIsSettlement(), "结算态不允许经过普通编辑写入");
        assertNull(patch.getSelfPlayPrice(), "票价只能经定价流程写入");
        assertNull(patch.getUserStatus(), "旧可见性轴仅兼容反序列化，不能写入主题");
        assertNull(patch.getMerchantStatus(), "旧可见性轴仅兼容反序列化，不能写入主题");
    }

    @Test
    void roamReferralSwitchPersistsOnlyForAnAuthorizedOfficialTopic() {
        when(topicOperationsScopeAuthorizer.canEditTopic(1L)).thenReturn(true);
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        CmsTopicEditDTO request = new CmsTopicEditDTO();
        request.setId(1L);
        request.setRoamReferralEnabled(1);

        assertEquals(1, service.editTopicWithinScope(request));

        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicService).updateCmsTopic(captor.capture());
        assertEquals(1, captor.getValue().getRoamReferralEnabled());
        assertNull(captor.getValue().getLifecycle(), "逐期开关不能夹带生命周期迁移");
    }

    @Test
    void roamReferralSwitchRejectsReadOnlyScopeBeforeAnyReadOrWrite() {
        when(topicOperationsScopeAuthorizer.canViewTopic(9L)).thenReturn(true);
        when(topicOperationsScopeAuthorizer.canEditTopic(9L)).thenReturn(false);
        CmsTopicEditDTO request = new CmsTopicEditDTO();
        request.setId(9L);
        request.setRoamReferralEnabled(1);

        assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(request));

        verify(topicService, never()).selectCmsTopicById(anyLong());
        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void scopedClubAndMemberTopicsAllowOnlyTheRoamReferralSwitch() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(false);
        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);

        for (String publisherType : new String[] { "CLUB", "MEMBER" }) {
            when(productRoleService.activeContentSlot(1L)).thenReturn(contentSlot(publisherType));
            CmsTopicEditDTO switchOnly = new CmsTopicEditDTO();
            switchOnly.setId(1L);
            switchOnly.setRoamReferralEnabled(1);
            assertEquals(1, service.editTopicWithinScope(switchOnly));

            CmsTopicEditDTO mixed = new CmsTopicEditDTO();
            mixed.setId(1L);
            mixed.setRoamReferralEnabled(1);
            mixed.setName("越权改写第三方内容");
            assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(mixed));
        }

        verify(topicService, org.mockito.Mockito.times(2)).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void historicalTopicWithoutKnownContentOwnerCannotEnableRoamReferral() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(false);
        when(productRoleService.activeContentSlot(1L)).thenReturn(null);
        CmsTopicEditDTO request = new CmsTopicEditDTO();
        request.setId(1L);
        request.setRoamReferralEnabled(1);

        assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(request));
        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void rejectsHistoricalAndNonOfficialTopics() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(0));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.editTopicWithinScope(contentRequest()));

        assertEquals("非官方主题仅允许调整漫游导流开关；历史待归属主题请走归属流程", error.getMessage());
        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void lockedLineupAllowsOnlyContentCorrections() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        CmsTopicEditDTO dateChange = contentRequest();
        dateChange.setStartDate(new Date());

        assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(dateChange));
        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));

        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);
        assertEquals(1, service.editTopicWithinScope(contentRequest()));
        verify(topicService).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void runningTopicsAllowOnlyLowRiskContentCorrections() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(4));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);

        assertEquals(1, service.editTopicWithinScope(contentRequest()));

        CmsTopicEditDTO dateChange = contentRequest();
        dateChange.setEndDate(new Date());
        assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(dateChange));
    }

    @Test
    void settledAndClosedTopicsCannotUseGenericEdit() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(5));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(contentRequest()));

        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void legacyStatusAxesAloneCannotProduceAnUpdate() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(0));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        CmsTopicEditDTO request = new CmsTopicEditDTO();
        request.setId(1L);
        request.setCheckStatus(2);
        request.setReason("不合规");
        request.setUserStatus(1);

        assertThrows(IllegalArgumentException.class, () -> service.editTopicWithinScope(request));

        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }

    @Test
    void plazaRecommendationAllowsClubAndMemberButWritesOnlyRecommendation() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1);

        for (String publisherType : new String[] { "CLUB", "MEMBER" }) {
            when(productRoleService.activeContentSlot(1L)).thenReturn(contentSlot(publisherType));
            assertEquals(1, service.updatePlazaRecommendation(1L, 1));
        }

        ArgumentCaptor<CmsTopic> captor = ArgumentCaptor.forClass(CmsTopic.class);
        verify(topicService, org.mockito.Mockito.times(2)).updateCmsTopic(captor.capture());
        for (CmsTopic patch : captor.getAllValues()) {
            assertEquals(1L, patch.getId());
            assertEquals(1, patch.getIsRecommend());
            assertNull(patch.getName(), "推荐治理不能夹带用户内容改写");
            assertNull(patch.getDescription(), "推荐治理不能夹带用户内容改写");
            assertNull(patch.getSelfPlayPrice(), "推荐治理不能夹带价格改写");
            assertNull(patch.getLifecycle(), "推荐治理不能推进主题状态机");
        }
    }

    @Test
    void plazaRecommendationRejectsOfficialHistoricalAndInvalidValues() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(productRoleService.activeContentSlot(1L)).thenReturn(contentSlot("PLATFORM"));

        IllegalArgumentException officialError = assertThrows(IllegalArgumentException.class,
                () -> service.updatePlazaRecommendation(1L, 1));
        assertEquals("主题广场推荐只适用于俱乐部或会员发布主题", officialError.getMessage());

        when(productRoleService.activeContentSlot(1L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.updatePlazaRecommendation(1L, 1));
        assertThrows(IllegalArgumentException.class, () -> service.updatePlazaRecommendation(1L, 2));

        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }


    @Test
    void rejectsOutOfScopeCommandsBeforeAnyTopicReadOrWrite() {
        when(topicOperationsScopeAuthorizer.canViewTopic(9L)).thenReturn(true);
        when(topicOperationsScopeAuthorizer.canEditTopic(9L)).thenReturn(false);
        CmsTopicEditDTO request = contentRequest();
        request.setId(9L);

        IllegalArgumentException editError = assertThrows(IllegalArgumentException.class,
                () -> service.editTopicWithinScope(request));
        assertEquals("主题不存在或当前账号无操作范围", editError.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.updatePlazaRecommendation(9L, 1));
        assertThrows(IllegalArgumentException.class, () -> service.deleteOfficialTopics(new Long[]{9L}));

        verify(topicService, never()).selectCmsTopicById(anyLong());
        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
        verify(productRoleService, never()).isPlatformOwned(anyLong());
        verify(productRoleService, never()).activeContentSlot(anyLong());
    }

    @Test
    void deleteRejectsNonOfficialTopicsBeforeAnyPartialMutation() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(topicService.selectCmsTopicById(2L)).thenReturn(topic(3));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        when(productRoleService.isPlatformOwned(2L)).thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.deleteOfficialTopics(new Long[]{1L, 2L}));

        assertEquals("删除只适用于官方主题；广场主题请走治理流程", error.getMessage());
        verify(topicService, never()).updateCmsTopic(any(CmsTopic.class));
    }


    @Test
    void deleteFailsClosedWhenAnyWriteDoesNotAffectExactlyOneTopic() {
        when(topicService.selectCmsTopicById(1L)).thenReturn(topic(3));
        when(topicService.selectCmsTopicById(2L)).thenReturn(topic(3));
        when(productRoleService.isPlatformOwned(1L)).thenReturn(true);
        when(productRoleService.isPlatformOwned(2L)).thenReturn(true);
        when(topicService.updateCmsTopic(any(CmsTopic.class))).thenReturn(1, 0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.deleteOfficialTopics(new Long[]{1L, 2L}));

        assertEquals("主题状态已变化，请刷新后重试", error.getMessage());
        verify(topicService, org.mockito.Mockito.times(2)).updateCmsTopic(any(CmsTopic.class));
    }

    private static CmsTopic topic(int lifecycle) {
        CmsTopic topic = new CmsTopic();
        topic.setId(1L);
        topic.setLifecycle(lifecycle);
        return topic;
    }

    private static ProductRole contentSlot(String entityType) {
        ProductRole role = new ProductRole();
        role.setEntityType(entityType);
        return role;
    }

    private static CmsTopicEditDTO contentRequest() {
        CmsTopicEditDTO request = new CmsTopicEditDTO();
        request.setId(1L);
        request.setName("内容勘误");
        return request;
    }
}
