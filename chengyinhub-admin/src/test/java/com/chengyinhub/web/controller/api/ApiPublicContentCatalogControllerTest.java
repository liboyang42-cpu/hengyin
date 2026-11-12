package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.PublicCatalogQuery;
import com.chengyinhub.business.domain.dto.PublicContentCatalogRow;
import com.chengyinhub.business.domain.vo.PublicContentCatalogDetailVO;
import com.chengyinhub.business.mapper.PublicContentCatalogMapper;
import com.chengyinhub.business.service.impl.PublicContentCatalogServiceImpl;
import com.chengyinhub.business.domain.vo.PublicCatalogFilterOptionsVO;
import com.chengyinhub.business.service.ICityDiscoveryCatalogService;
import com.chengyinhub.business.service.IPublicContentCatalogService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.vo.PublicContentCatalogItemVO;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * (纯单测；目录成功路径使用 MockHttpServletRequest，不连接数据库)。
 */
class ApiPublicContentCatalogControllerTest {

    private IPublicContentCatalogService catalogService;
    private ICityDiscoveryCatalogService cityCatalogService;
    private ApiPublicContentCatalogController controller;
    private PlatformPassService platformPassService;

    @BeforeEach
    void setUp() {
        catalogService = mock(IPublicContentCatalogService.class);
        cityCatalogService = mock(ICityDiscoveryCatalogService.class);
        controller = new ApiPublicContentCatalogController();
        ReflectionTestUtils.setField(controller, "catalogService", catalogService);
        ReflectionTestUtils.setField(controller, "cityCatalogService", cityCatalogService);
        platformPassService = mock(PlatformPassService.class);
        ReflectionTestUtils.setField(controller, "platformPassService", platformPassService);
    }

    @AfterEach
    void clearPaging() {
        PageHelper.clearPage();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCatalogRemainsReadableWithoutPassEntitlement() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        PublicContentCatalogItemVO item = card(7L, true, "ON_SALE");
        when(catalogService.list(any())).thenReturn(List.of(item));
        when(platformPassService.coveredTopics(isNull(), eq(List.of(7L)), any())).thenReturn(Set.of());

        AjaxResult result = controller.list(null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(false, item.isPassCovered());
        verify(platformPassService).coveredTopics(isNull(), eq(List.of(7L)), any());
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void catalogBadgeRequiresPerTopicCoverageAndAnAvailableSelfPlayEntry() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        AppUser appUser = mock(AppUser.class);
        when(appUser.getUserId()).thenReturn(42L);
        LoginAppUser user = new LoginAppUser(appUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        PublicContentCatalogItemVO covered = card(7L, true, "ON_SALE");
        PublicContentCatalogItemVO uncovered = card(8L, true, "ON_SALE");
        PublicContentCatalogItemVO noSelfPlay = card(9L, false, "ON_SALE");
        PublicContentCatalogItemVO closed = card(10L, true, "SALE_ENDED");
        when(catalogService.list(any())).thenReturn(List.of(covered, uncovered, noSelfPlay, closed));
        when(platformPassService.coveredTopics(eq(42L), eq(List.of(7L, 8L, 9L, 10L)), any()))
                .thenReturn(Set.of(7L, 9L, 10L));

        AjaxResult result = controller.list(null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(true, covered.isPassCovered());
        assertEquals(false, uncovered.isPassCovered());
        assertEquals(false, noSelfPlay.isPassCovered());
        assertEquals(false, closed.isPassCovered());
    }

    private static PublicContentCatalogItemVO card(long id, boolean selfPlay, String saleState) {
        PublicContentCatalogItemVO item = new PublicContentCatalogItemVO();
        item.setId(id);
        item.setName("公开主题" + id);
        item.setDeliveryModes(selfPlay ? List.of("SELF_PLAY") : List.of("CLUB_HOSTED"));
        item.setSaleState(saleState);
        return item;
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
    void infoReturns200WhenAChapterNameIsNull() {
        // 真实服务 + 映射器桩：章节 name 为 NULL 时映射器给出 null 行，/info 仍应 200(f-rehearsal 冒烟曾 500)。
        PublicContentCatalogMapper mapper = mock(PublicContentCatalogMapper.class);
        PublicContentCatalogServiceImpl realService = new PublicContentCatalogServiceImpl();
        ReflectionTestUtils.setField(realService, "catalogMapper", mapper);
        ReflectionTestUtils.setField(controller, "catalogService", realService);
        PublicContentCatalogRow row = new PublicContentCatalogRow();
        row.setId(7L);
        row.setCityCode("310100");
        row.setName("A");
        row.setLifecycle(3);
        row.setSaleEndAt(new Date(System.currentTimeMillis() + 86_400_000L));
        when(mapper.selectCatalogItemById(eq(7L), any())).thenReturn(row);
        when(mapper.selectChapterNames(eq(7L))).thenReturn(Collections.<Map<String, Object>>singletonList(null));
        when(mapper.selectPreviewStops(eq(7L), anyInt())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectChapterCounts(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectTicketAggregates(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectSaleRevisions(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectApprovedLocales(anyList())).thenReturn(new ArrayList<Map<String, Object>>());
        when(mapper.selectCityNames(anyList())).thenReturn(new ArrayList<Map<String, Object>>());

        AjaxResult result = controller.info("7", null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        PublicContentCatalogDetailVO detail = (PublicContentCatalogDetailVO) result.get(AjaxResult.DATA_TAG);
        assertEquals(Collections.singletonList("未命名章节"), detail.getChapterNames());
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
