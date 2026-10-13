package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MemberCapability;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.TopicTemplateChapterVO;
import com.chengyinhub.business.domain.vo.TopicTemplateDetailVO;
import com.chengyinhub.business.domain.vo.TopicTemplateRecruitVO;
import com.chengyinhub.business.service.ICapabilityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商家发现页只可读取服务端过滤后的自由探索章节，不能从普通主题详情猜容量或品类。 */
class ApiTopicMerchantRecruitmentControllerTest {

    private ApiTopicController controller;
    private ICmsTopicService topicService;
    private ICapabilityService capabilityService;
    private IMmsMerchantService merchantService;
    private IMerchantAccessService merchantAccessService;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        capabilityService = mock(ICapabilityService.class);
        merchantService = mock(IMmsMerchantService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        controller = spy(new ApiTopicController());
        doReturn(7L).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "cmsTopicService", topicService);
        ReflectionTestUtils.setField(controller, "capabilityService", capabilityService);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchantService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
    }

    @Test
    void merchant_receives_only_server_eligible_chapters() {
        when(capabilityService.hasCapability(7L, MemberCapability.CAP_MERCHANT)).thenReturn(true);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setCategoryId(18);
        when(merchantService.selectMmsMerchantByMemberId(7L)).thenReturn(merchant);

        TopicTemplateDetailVO detail = new TopicTemplateDetailVO();
        detail.setProductType(ProductType.FREE_EXPLORE);
        detail.setChapters(Arrays.asList(chapter(1L, true), chapter(2L, false), chapter(3L, null)));
        when(topicService.buildTopicTemplateDetail(42L, true, 18)).thenReturn(detail);

        AjaxResult result = controller.merchantRecruitmentChapters("42", null);

        assertEquals(200, result.get("code"));
        List<TopicTemplateChapterVO> chapters = (List<TopicTemplateChapterVO>) result.get("data");
        assertEquals(1, chapters.size(), "不匹配、已满或关闭的章节不能泄给商家发现端点");
        assertEquals(1L, chapters.get(0).getId());
        verify(topicService).buildTopicTemplateDetail(42L, true, 18);
    }

    private TopicTemplateChapterVO chapter(Long id, Boolean open) {
        TopicTemplateChapterVO chapter = new TopicTemplateChapterVO();
        chapter.setId(id);
        if (open != null) {
            TopicTemplateRecruitVO recruit = new TopicTemplateRecruitVO();
            recruit.setIsOpen(open);
            chapter.setRecruitStatus(recruit);
        }
        return chapter;
    }

    @Test
    void non_merchant_cannot_probe_chapter_recruitment() {
        when(capabilityService.hasCapability(7L, MemberCapability.CAP_MERCHANT)).thenReturn(false);

        AjaxResult result = controller.merchantRecruitmentChapters("42", null);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("商家"));
        verify(topicService, never()).buildTopicTemplateDetail(any(), eq(true), any());
    }

    @Test
    void city_orienteering_never_exposes_chapter_application_list() {
        when(capabilityService.hasCapability(7L, MemberCapability.CAP_MERCHANT)).thenReturn(true);
        MmsMerchant merchant = new MmsMerchant();
        merchant.setCategoryId(18);
        when(merchantService.selectMmsMerchantByMemberId(7L)).thenReturn(merchant);
        TopicTemplateDetailVO detail = new TopicTemplateDetailVO();
        detail.setProductType(ProductType.ORIENTEERING);
        when(topicService.buildTopicTemplateDetail(42L, true, 18)).thenReturn(detail);

        AjaxResult result = controller.merchantRecruitmentChapters("42", null);

        assertEquals(500, result.get("code"));
        assertTrue(String.valueOf(result.get("msg")).contains("自由探索"));
    }

    @Test
    void managerScopeReadsOwnerMerchantCategory() {
        when(merchantAccessService.require(7L, MerchantPermission.COOP_MANAGE)).thenReturn(
                new MerchantAccessContext(7L, 77L, 900L, MerchantRoleCode.MERCHANT_MANAGER));
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(77L);
        merchant.setMemberId(900L);
        merchant.setCategoryId(18);
        when(merchantService.selectMmsMerchantById(77L)).thenReturn(merchant);
        TopicTemplateDetailVO detail = new TopicTemplateDetailVO();
        detail.setProductType(ProductType.FREE_EXPLORE);
        detail.setChapters(Arrays.asList(chapter(1L, true)));
        when(topicService.buildTopicTemplateDetail(42L, true, 18)).thenReturn(detail);

        AjaxResult result = controller.merchantRecruitmentChapters("42", "MERCHANT");

        assertEquals(200, result.get("code"));
        verify(merchantService).selectMmsMerchantById(77L);
        verify(capabilityService, never()).hasCapability(any(), any());
    }
}
