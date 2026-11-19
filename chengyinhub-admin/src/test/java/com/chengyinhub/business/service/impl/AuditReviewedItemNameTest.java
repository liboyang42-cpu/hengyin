package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MC-035:活动 / 玩法模板的审核通知点名是哪一条,读不到时仍写「内容」。 */
class AuditReviewedItemNameTest {

    private AuditTaskServiceImpl service;
    private CmsActivityMapper activities;
    private CmsMemberTemplateMapper templates;
    private CmsTemplateLibraryMapper libraries;

    @BeforeEach
    void setUp() {
        service = new AuditTaskServiceImpl();
        activities = mock(CmsActivityMapper.class);
        templates = mock(CmsMemberTemplateMapper.class);
        libraries = mock(CmsTemplateLibraryMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        ReflectionTestUtils.setField(service, "templateMapper", templates);
        ReflectionTestUtils.setField(service, "libraryMapper", libraries);
    }

    private AuditTask task(String bizType) {
        AuditTask t = new AuditTask();
        t.setId(1L);
        t.setBizType(bizType);
        t.setBizId(9L);
        return t;
    }

    @Test
    void activityAndTemplateNamesAreUsed() {
        CmsActivity a = new CmsActivity();
        a.setName("周末夜跑");
        when(activities.selectCmsActivityById(9L)).thenReturn(a);
        assertEquals("活动「周末夜跑」", service.reviewedItemName(task(AuditTask.BIZ_ACTIVITY), "内容"));

        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setTitle("寻宝答题");
        when(templates.selectCmsMemberTemplateById(9L)).thenReturn(t);
        assertEquals("玩法模板「寻宝答题」", service.reviewedItemName(task(AuditTask.BIZ_TEMPLATE), "内容"));

        CmsTemplateLibrary l = new CmsTemplateLibrary();
        l.setTitle("库模板");
        when(libraries.selectCmsTemplateLibraryById(9L)).thenReturn(l);
        assertEquals("玩法模板「库模板」", service.reviewedItemName(task(AuditTask.BIZ_TEMPLATE_LIBRARY), "内容"));
    }

    @Test
    void missingOrFailingLookupFallsBackToGenericWord() {
        when(activities.selectCmsActivityById(9L)).thenReturn(null);
        assertEquals("内容", service.reviewedItemName(task(AuditTask.BIZ_ACTIVITY), "内容"));
        when(templates.selectCmsMemberTemplateById(9L)).thenThrow(new RuntimeException("db"));
        assertEquals("内容", service.reviewedItemName(task(AuditTask.BIZ_TEMPLATE), "内容"));
        assertEquals("内容", service.reviewedItemName(task(AuditTask.BIZ_SQUARE), "内容"));
    }
}
