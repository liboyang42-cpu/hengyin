package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.vo.TemplateHomeVO;
import com.chengyinhub.business.service.impl.TemplatePublishServiceImpl;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模板广场首页是公开读端点，五个板块都不得下发答案、付费提示或未公开玩法配置。 */
class TemplateHomeDataPublicProjectionTest {

    private ICmsTemplateLibraryService libraryService;
    private ISysCategoryService categoryService;
    private TemplatePublishServiceImpl service;

    @BeforeEach
    void setUp() {
        libraryService = mock(ICmsTemplateLibraryService.class);
        categoryService = mock(ISysCategoryService.class);
        service = new TemplatePublishServiceImpl();
        ReflectionTestUtils.setField(service, "templateLibraryService", libraryService);
        ReflectionTestUtils.setField(service, "categoryService", categoryService);
    }

    @Test
    void publicHomeStripsSecretsFromEveryShelfAndKeepsVisibilityFilters() {
        CmsTemplateLibrary source = new CmsTemplateLibrary();
        source.setId(401L);
        source.setTitle("城市暗号");
        source.setUseNum(9);
        source.setQuestionAnswer("北纬31");
        source.setCorrectAnswer("B");
        source.setHint1("付费提示1");
        source.setHint2("付费提示2");
        source.setAnswerReveal("公布解析");
        source.setAdvancedConfigJson("{\"schemaVersion\":1,\"blindTaste\":{\"enabled\":true,\"title\":\"盲品\",\"answerKey\":\"B\"}}");
        when(libraryService.selectCmsTemplateLibraryList(any()))
                .thenReturn(Collections.singletonList(source));
        SysCategory category = categoryWithAuditMetadata();
        when(categoryService.selectSysCategoryList(any()))
                .thenReturn(Collections.singletonList(category));

        TemplateHomeVO home = service.getHomeData();

        ArgumentCaptor<CmsTemplateLibrary> query = ArgumentCaptor.forClass(CmsTemplateLibrary.class);
        verify(libraryService).selectCmsTemplateLibraryList(query.capture());
        assertEquals(0, query.getValue().getDelFlag());
        assertEquals(1L, query.getValue().getStatus());
        assertEquals(1L, query.getValue().getPublishStatus());
        assertEquals(1, home.getTotal());

        List<List<CmsTemplateLibrary>> shelves = Arrays.asList(
                home.getBannerList(), home.getRecommendList(), home.getMustPlayList(),
                home.getHotList(), home.getLatestList());
        for (List<CmsTemplateLibrary> shelf : shelves) {
            assertFalse(shelf.isEmpty());
            CmsTemplateLibrary item = shelf.get(0);
            assertEquals("城市暗号", item.getTitle());
            assertNull(item.getQuestionAnswer());
            assertNull(item.getCorrectAnswer());
            assertNull(item.getHint1());
            assertNull(item.getHint2());
            assertNull(item.getAnswerReveal());
            assertTrue(item.getAdvancedConfigJson().contains("\"blindTaste\""));
            assertFalse(item.getAdvancedConfigJson().contains("answerKey"));
        }

        SysCategory publicCategory = home.getCategoryList().get(0);
        assertEquals(4L, publicCategory.getType());
        assertEquals("城市探索", publicCategory.getCategoryName());
        assertNull(publicCategory.getCreateBy());
        assertNull(publicCategory.getCreateTime());
        assertNull(publicCategory.getUpdateBy());
        assertNull(publicCategory.getUpdateTime());
        assertNull(publicCategory.getRemark());
    }

    private SysCategory categoryWithAuditMetadata() {
        SysCategory category = new SysCategory();
        category.setId(4L);
        category.setType(4L);
        category.setCategoryName("城市探索");
        category.setCreateBy("internal-admin");
        category.setCreateTime(new Date());
        category.setUpdateBy("auditor");
        category.setUpdateTime(new Date());
        category.setRemark("内部备注");
        return category;
    }
}
