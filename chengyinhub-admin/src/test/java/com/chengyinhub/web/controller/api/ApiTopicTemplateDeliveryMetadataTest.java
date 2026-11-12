package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ContentDeliveryMetadataVO;
import com.chengyinhub.business.domain.vo.TopicTemplateDetailVO;
import com.chengyinhub.business.domain.vo.TopicTemplateListItemVO;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.support.TopicDeliveryMetadataProjection;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BE-13 最小接线:交付元数据只挂在既有公共可见性通过之后的详情上,列表只补真实源语言。
 */
class ApiTopicTemplateDeliveryMetadataTest {

    private ApiTopicTemplateController controller;
    private ICmsTopicService cmsTopicService;
    private TopicDeliveryMetadataProjection projection;

    @BeforeEach
    void setUp() {
        controller = new ApiTopicTemplateController();
        cmsTopicService = mock(ICmsTopicService.class);
        projection = mock(TopicDeliveryMetadataProjection.class);
        ReflectionTestUtils.setField(controller, "cmsTopicService", cmsTopicService);
        ReflectionTestUtils.setField(controller, "capabilityService", mock(ICapabilityService.class));
        ReflectionTestUtils.setField(controller, "clubService", mock(IClubService.class));
        ReflectionTestUtils.setField(controller, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(controller, "mmsMerchantService", mock(IMmsMerchantService.class));
        ReflectionTestUtils.setField(controller, "merchantAccessService", mock(IMerchantAccessService.class));
        ReflectionTestUtils.setField(controller, "deliveryMetadataProjection", projection);
    }

    private ApiTopicTemplateController loggedInAs(long memberId) {
        ApiTopicTemplateController spied = spy(controller);
        doReturn(memberId).when(spied).getAppUserId();
        return spied;
    }

    private TopicTemplateDetailVO detail(boolean merchant, boolean publisher) {
        TopicTemplateDetailVO detail = new TopicTemplateDetailVO();
        detail.setId(42L);
        detail.setViewerIsMerchant(merchant);
        detail.setViewerIsPublisher(publisher);
        return detail;
    }

    @Test
    void detailAttachesDeliveryMetadataForPartnerViewer() {
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        when(cmsTopicService.buildPublicTopicTemplateDetail(eq(42L), eq(9L), eq(true), any()))
                .thenReturn(detail(true, false));
        when(cmsTopicService.selectCmsTopicById(42L)).thenReturn(topic);
        when(((ICapabilityService) ReflectionTestUtils.getField(controller, "capabilityService"))
                .hasCapability(9L, "merchant")).thenReturn(true);
        when(((IMmsMerchantService) ReflectionTestUtils.getField(controller, "mmsMerchantService"))
                .selectMmsMerchantByMemberId(9L)).thenReturn(new com.chengyinhub.business.domain.MmsMerchant());
        ContentDeliveryMetadataVO delivery = new ContentDeliveryMetadataVO();
        when(projection.build(topic, true)).thenReturn(delivery);

        AjaxResult result = loggedInAs(9L).info("42");

        assertSame(delivery, ((TopicTemplateDetailVO) result.get(AjaxResult.DATA_TAG)).getContentDelivery());
        verify(projection).build(topic, true);
    }

    @Test
    void publisherWhoIsNotMerchantStillGetsOwnDeliveryMetadata() {
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        when(cmsTopicService.buildPublicTopicTemplateDetail(anyLong(), anyLong(), anyBoolean(), any()))
                .thenReturn(detail(false, true));
        when(cmsTopicService.selectCmsTopicById(42L)).thenReturn(topic);
        when(projection.build(topic, true)).thenReturn(new ContentDeliveryMetadataVO());

        loggedInAs(9L).info("42");

        verify(projection).build(topic, true);
    }

    @Test
    void anonymousViewerNeverSeesPartnerOnlyRequirements() {
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        when(cmsTopicService.buildPublicTopicTemplateDetail(eq(42L), isNull(), eq(false), any()))
                .thenReturn(detail(false, false));
        when(cmsTopicService.selectCmsTopicById(42L)).thenReturn(topic);
        when(projection.build(topic, false)).thenReturn(new ContentDeliveryMetadataVO());

        controller.info("42");

        verify(projection).build(topic, false);
    }

    @Test
    void missingProjectionWiringKeepsShelfDetailWorking() {
        ReflectionTestUtils.setField(controller, "deliveryMetadataProjection", null);
        TopicTemplateDetailVO existing = detail(false, false);
        when(cmsTopicService.buildPublicTopicTemplateDetail(anyLong(), anyLong(), anyBoolean(), any()))
                .thenReturn(existing);

        AjaxResult result = loggedInAs(9L).info("42");

        assertSame(existing, result.get(AjaxResult.DATA_TAG));
        assertNull(existing.getContentDelivery());
    }

    @Test
    void missingTemplateNeverCallsDeliveryProjection() {
        when(cmsTopicService.buildPublicTopicTemplateDetail(anyLong(), anyLong(), anyBoolean(), any()))
                .thenReturn(null);

        AjaxResult result = loggedInAs(9L).info("42");

        assertEquals(404, result.get(AjaxResult.CODE_TAG));
        assertEquals("模板不存在", result.get(AjaxResult.MSG_TAG));
        assertFalse(result.containsKey(AjaxResult.DATA_TAG));
        verifyNoInteractions(projection);
    }

    @Test
    void listRowsCarryOnlyTheRealSourceLocale() {
        TopicTemplateListItemVO row = new TopicTemplateListItemVO();
        row.setId(42L);
        List<TopicTemplateListItemVO> rows = Collections.singletonList(row);
        when(cmsTopicService.buildTopicTemplateListVO(false)).thenReturn(rows);

        AjaxResult result = controller.list(null);

        assertEquals("zh-CN", row.getSourceLocale());
        assertSame(rows, result.get(AjaxResult.DATA_TAG));
        verify(projection, never()).build(any(), anyBoolean());
    }

    @Test
    void recommendedOnlyStillRoutesTheVerifiedFilter() {
        when(cmsTopicService.buildTopicTemplateListVO(true)).thenReturn(Collections.emptyList());

        controller.list("1");

        verify(cmsTopicService).buildTopicTemplateListVO(true);
        verify(cmsTopicService, never()).buildTopicTemplateListVO(false);
    }
}
