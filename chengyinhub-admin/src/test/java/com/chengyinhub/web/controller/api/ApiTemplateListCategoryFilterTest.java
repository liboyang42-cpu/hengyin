package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.common.utils.PageUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.mock.web.MockPart;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/template/list 的分类筛选**参数名口径**。
 *
 * <p>这不是「筛选逻辑对不对」的测试，是「客户端发的那个名字后端认不认」的测试：
 * Spring 按形参名绑定表单字段，形参改名 / 客户端改名都会让筛选静默失效
 * （零报错、零告警，表现是「点了分类没反应」）。所以这里一律用 MockMvc 走真实
 * 参数绑定，不直接调 controller 方法——直接调方法会绕过 bug 本身。
 */
class ApiTemplateListCategoryFilterTest {

    private final ICmsTemplateLibraryService templateLibraryService = mock(ICmsTemplateLibraryService.class);

    private MockMvc mvc() {
        when(templateLibraryService.selectCmsTemplateLibraryList(any()))
                .thenReturn(Collections.<CmsTemplateLibrary>emptyList());
        ApiTemplateController controller = new ApiTemplateController();
        ReflectionTestUtils.setField(controller, "templateLibraryService", templateLibraryService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** startPage() 往 ThreadLocal 塞了分页参数，没有真 SQL 消费它，必须手动清掉别污染同线程后续用例。 */
    @AfterEach
    void clearPage() {
        PageUtils.clearPage();
    }

    private CmsTemplateLibrary captureProbe() {
        ArgumentCaptor<CmsTemplateLibrary> probe = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(templateLibraryService).selectCmsTemplateLibraryList(probe.capture());
        return probe.getValue();
    }

    /** 两个客户端（App template_api.dart / 小程序 search 页）发的都是驼峰。 */
    @Test
    void camelCaseCategoryId_filtersTheList() throws Exception {
        mvc().perform(post("/api/template/list").param("categoryId", "7"))
                .andExpect(status().isOk());

        assertEquals("7", captureProbe().getActivityCategoryids());
    }

    /** 蛇形是 @Parameters 里写明的口径，任何时候都不能掉。 */
    @Test
    void snakeCaseCategoryId_filtersTheList() throws Exception {
        mvc().perform(post("/api/template/list").param("category_id", "7"))
                .andExpect(status().isOk());

        assertEquals("7", captureProbe().getActivityCategoryids());
    }

    /** 两个都传且不一致时必须有确定的赢家，别看运气。文档口径 category_id 优先。 */
    @Test
    void bothSpellings_snakeCaseWins() throws Exception {
        mvc().perform(post("/api/template/list")
                        .param("category_id", "7")
                        .param("categoryId", "9"))
                .andExpect(status().isOk());

        assertEquals("7", captureProbe().getActivityCategoryids());
    }

    /**
     * App 发的是 multipart/form-data(dio FormData)，不是 url-encoded —— 两种传输方式
     * 走 Spring 里不同的解析分支，所以这条单独钉一次真实线格式，别只测好写的那种。
     */
    @Test
    void camelCaseCategoryId_asMultipartFormData_filtersTheList() throws Exception {
        mvc().perform(multipart("/api/template/list")
                        .part(new MockPart("categoryId", "7".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());

        assertEquals("7", captureProbe().getActivityCategoryids());
    }

    /** 不传分类 = 不筛选，返回全部。 */
    @Test
    void noCategory_doesNotFilter() throws Exception {
        mvc().perform(post("/api/template/list")).andExpect(status().isOk());

        assertNull(captureProbe().getActivityCategoryids());
    }
}
