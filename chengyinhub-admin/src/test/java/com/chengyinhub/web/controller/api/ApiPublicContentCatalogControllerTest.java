package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.vo.PublicCatalogFilterOptionsVO;
import com.chengyinhub.business.service.ICityDiscoveryCatalogService;
import com.chengyinhub.business.service.IPublicContentCatalogService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M7 公开目录控制器的入参合同：非法枚举在分页之前就被拒、错误码透传、分页线程变量不残留。
 *
 * <p>真库分页/公开闸见 PublicContentCatalogMyBatisIT；这里只固定 HTTP 层的边界行为
 * (纯单测，不构造 servlet 请求，因此不覆盖 startPage 生效路径)。
 */
class ApiPublicContentCatalogControllerTest {

    private IPublicContentCatalogService catalogService;
    private ICityDiscoveryCatalogService cityCatalogService;
    private ApiPublicContentCatalogController controller;

    @BeforeEach
    void setUp() {
        catalogService = mock(IPublicContentCatalogService.class);
        cityCatalogService = mock(ICityDiscoveryCatalogService.class);
        controller = new ApiPublicContentCatalogController();
        ReflectionTestUtils.setField(controller, "catalogService", catalogService);
        ReflectionTestUtils.setField(controller, "cityCatalogService", cityCatalogService);
    }

    @AfterEach
    void clearPaging() {
        PageHelper.clearPage();
    }

    @Test
    void invalidFilterIsRejectedBeforePagingAndNothingIsQueried() {
        doThrow(new ServiceException("product_type 枚举无效", 400)).when(catalogService).validate(any(PublicCatalogQuery.class));

        AjaxResult result = controller.list(null, null, null, 9, null, null, null, null, null, null, null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verify(catalogService, never()).list(any(PublicCatalogQuery.class));
        assertNull(PageHelper.getLocalPage(), "validation failure must not leave paging state");
    }

    @Test
    void mapEndpointUsesTheSameValidationGate() {
        doThrow(new ServiceException("sort 枚举无效", 400)).when(catalogService).validate(any(PublicCatalogQuery.class));

        AjaxResult result = controller.map(null, null, null, null, null, null, null, null, null, null, "SIDEWAYS");

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verify(catalogService, never()).mapPoints(any(PublicCatalogQuery.class));
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void malformedIdIsRejectedWithoutTouchingTheService() {
        AjaxResult result = controller.info("abc", null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(catalogService);
    }

    @Test
    void missingIdIsRejectedWithoutTouchingTheService() {
        AjaxResult result = controller.info("  ", null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(catalogService);
    }

    @Test
    void unpublicTopicIsReportedAsNotFound() {
        when(catalogService.detail(9L, "en")).thenThrow(new ServiceException("作品不存在或未公开", 404));

        AjaxResult result = controller.info("9", "en");

        assertEquals(404, result.get(AjaxResult.CODE_TAG));
    }

    @Test
    void malformedIdsListIsRejectedWithoutQuerying() {
        AjaxResult result = controller.byIds("1,abc,3", null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verify(catalogService, never()).byIds(anyList(), any());
    }

    @Test
    void oversizedIdBatchIsRejectedByServiceContract() {
        when(catalogService.byIds(anyList(), any())).thenThrow(new ServiceException("topicIds 一次最多 100 个", 400));

        AjaxResult result = controller.byIds("1,2,3", null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verify(catalogService).byIds(Arrays.asList(1L, 2L, 3L), null);
    }

    @Test
    void filterOptionsAreReturnedAsIs() {
        PublicCatalogFilterOptionsVO options = new PublicCatalogFilterOptionsVO();
        options.setDeliveryModes(Arrays.asList("SELF_PLAY"));
        options.setGroupEligibleAvailable(false);
        when(cityCatalogService.filterOptions()).thenReturn(options);

        AjaxResult result = controller.filters();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertNotNull(result.get(AjaxResult.DATA_TAG));
        assertEquals(options, result.get(AjaxResult.DATA_TAG));
    }
}
