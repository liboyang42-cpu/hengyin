package com.chengyinhub.web.service.registration;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.vo.ExploreCompletionVO;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.web.controller.api.ApiRegistrationController;
import com.chengyinhub.web.controller.api.RegistrationQueryTestFixtures;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual application/HTTP adapter, with leaf readers mocked. No Java/MySQL execution claim. */
class RegistrationQueryApplicationTest {
    private RegistrationQueryApplication application(ICmsRegistrationService registrations) {
        RegistrationQueryApplication application = new RegistrationQueryApplication();
        ReflectionTestUtils.setField(application, "registrationService", registrations);
        return application;
    }

    @Test void allSixApplicationEntriesRejectAnonymousBeforeAnyReader() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        RegistrationQueryApplication app = application(registrations);
        for (AjaxResult response : new AjaxResult[] { app.getRegistrationDetail(0L, "1"), app.exploreCompletion(0L, "1"),
                app.getJoinRegistrationDetail(0L, "99"), app.countByDate(0L, "1", "1"),
                app.myRegistrationList(0L, "3", "0", null), app.myJoined(0L) }) {
            assertEquals(500, response.get("code")); assertEquals("请先登录", response.get("msg"));
            assertFalse(response.containsKey("data"));
        }
        verifyNoInteractions(registrations);
    }

    @Test void ownedDetailCannotReadForeignRegistrationOrAnyEnrichment() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        CmsRegistration foreign = new CmsRegistration(); foreign.setId(1L); foreign.setMemberId(8L);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(foreign);
        RegistrationQueryApplication app = application(registrations);
        ICmsTopicService topics = mock(ICmsTopicService.class); IRefundService refunds = mock(IRefundService.class);
        ReflectionTestUtils.setField(app, "topicService", topics); ReflectionTestUtils.setField(app, "refundService", refunds);
        AjaxResult response = app.getRegistrationDetail(7L, "1");
        assertEquals(500, response.get("code")); assertEquals("无权查看该报名信息", response.get("msg"));
        verifyNoInteractions(topics, refunds); assertNull(foreign.getCmsTopic()); assertNull(foreign.getRefundApplication());
    }

    @Test void ignoredJoinMemberParameterCannotReplaceTheActor() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        AjaxResult response = application(registrations).getJoinRegistrationDetail(7L, "9999");
        assertEquals(200, response.get("code")); assertNull(response.get("data"));
        ArgumentCaptor<CmsRegistration> query = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations).selectCmsRegistrationList(query.capture());
        assertEquals(Long.valueOf(7L), query.getValue().getMemberId());
        assertEquals(Integer.valueOf(2), query.getValue().getRegistrationStatus());
    }

    @Test void completionScopeUsesOwnedOriginalRegistrationAndExistingActivityBeforeTheReadPort() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        CmsRegistration row = new CmsRegistration(); row.setId(1L); row.setMemberId(7L); row.setOwnerId(20L);
        row.setPurchaseKind(PurchaseKind.EXPLORE_PASS);
        when(registrations.selectCmsRegistrationById(1L)).thenReturn(row);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        CmsActivity activity = new CmsActivity(); activity.setId(20L); activity.setTopicId(30L);
        when(activities.selectCmsActivityById(20L)).thenReturn(activity);
        IExploreCompletionReadService completion = mock(IExploreCompletionReadService.class);
        ExploreCompletionVO projection = new ExploreCompletionVO(); when(completion.read(1L, 7L, 30L, 20L)).thenReturn(projection);
        RegistrationQueryApplication app = application(registrations);
        ReflectionTestUtils.setField(app, "activityService", activities); ReflectionTestUtils.setField(app, "exploreCompletionReadService", completion);
        AjaxResult response = app.exploreCompletion(7L, "1");
        assertSame(projection, response.get("data"));
        org.mockito.InOrder order = inOrder(registrations, activities, completion);
        order.verify(registrations).selectCmsRegistrationById(1L); order.verify(activities).selectCmsActivityById(20L);
        order.verify(completion).read(1L, 7L, 30L, 20L); order.verifyNoMoreInteractions();
    }

    @Test void originalDetailAndCompletionExceptionBoundariesRemainDifferent() {
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IllegalStateException storage = new IllegalStateException("fixture reader unavailable");
        when(registrations.selectCmsRegistrationById(1L)).thenThrow(storage);
        RegistrationQueryApplication app = application(registrations);
        AjaxResult detail = app.getRegistrationDetail(7L, "1");
        assertEquals(500, detail.get("code")); assertEquals("fixture reader unavailable", detail.get("msg"));
        assertSame(storage, assertThrowsExactly(IllegalStateException.class, () -> app.exploreCompletion(7L, "1")));
    }

    @Test void rawListRemainsOnTheHttpThreadAndOnlyTheControllerCreatesTheOriginalPageEnvelope() {
        PageHelper.clearPage();
        ICmsRegistrationService registrations = mock(ICmsRegistrationService.class);
        IRefundService refunds = mock(IRefundService.class);
        Page<CmsRegistration> rows = new Page<>(1, 20); rows.setTotal(37);
        CmsRegistration row = new CmsRegistration(); row.setId(1L); row.setMemberId(7L); row.setOwnerType(2L);
        row.setOwnerId(20L); row.setRegistrationStatus(1); rows.add(row);
        Thread httpThread = Thread.currentThread();
        int[] calls = {0};
        boolean[] outerPagination = {false};
        when(registrations.selectCmsRegistrationList(any())).thenAnswer(call -> {
            assertSame(httpThread, Thread.currentThread());
            calls[0]++;
            if (!outerPagination[0]) assertNull(PageHelper.getLocalPage());
            else {
                Page<?> requested = PageHelper.getLocalPage();
                assertNotNull(requested); assertEquals(1, requested.getPageNum()); assertEquals(10, requested.getPageSize());
                // The real PageHelper interceptor clears this after the Mapper read; this mock must do the same.
                PageHelper.clearPage();
            }
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return rows;
        });
        when(refunds.selectRefundList(any())).thenReturn(Collections.emptyList());
        ApiRegistrationController controller = new ApiRegistrationController() { @Override public Long getAppUserId() { return 7L; } };
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrations);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refunds);
        RegistrationQueryApplication app = RegistrationQueryTestFixtures.application(controller);
        try {
            assertSame(rows, app.myRegistrationList(7L, "3", "0", null).get("data"));
            // The original controller intentionally does not enable startPage; wrapping is its only added effect.
            AjaxResult response = controller.myRegistrationList("3", "0", null);
            assertEquals(200, response.get("code")); TableDataInfo page = (TableDataInfo) response.get("data");
            assertSame(rows, page.getRows()); assertEquals(37L, page.getTotal()); assertEquals(200, page.getCode());
            assertEquals("查询成功", page.getMsg()); assertNull(PageHelper.getLocalPage());
            // Preserve the positive PageHelper assertions for a real pre-existing HTTP-thread page context.
            outerPagination[0] = true;
            PageHelper.startPage(1, 10);
            AjaxResult pagedResponse = controller.myRegistrationList("3", "0", null);
            assertEquals(200, pagedResponse.get("code"));
            TableDataInfo paged = (TableDataInfo) pagedResponse.get("data");
            assertSame(rows, paged.getRows()); assertEquals(37L, paged.getTotal()); assertEquals(200, paged.getCode());
            assertEquals("查询成功", paged.getMsg()); assertNull(PageHelper.getLocalPage());
            assertEquals(3, calls[0]);
        } finally {
            PageHelper.clearPage();
        }
        assertNull(RegistrationQueryApplication.class.getAnnotation(Transactional.class));
        for (java.lang.reflect.Method method : RegistrationQueryApplication.class.getDeclaredMethods())
            assertNull(method.getAnnotation(Transactional.class), method.getName());
    }
}
