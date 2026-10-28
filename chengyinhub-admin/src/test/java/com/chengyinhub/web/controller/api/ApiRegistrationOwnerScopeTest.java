package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * /api/registration/list 与 /my-joined 的归属口径:查询条件里的 member_id 是唯一的 owner 谓词,
 * Mapper 对 memberId 用 &lt;if test="memberId != null"&gt;,null 会让 WHERE 整条消失 → 列出全站报名。
 * 所以登录态里拿到 null / ≤0 的 member 必须当未登录处理,一个查询都不许发出。
 */
class ApiRegistrationOwnerScopeTest {

    private final ICmsRegistrationService registrationService = mock(ICmsRegistrationService.class);
    private final IRefundService refundService = mock(IRefundService.class);

    private ApiRegistrationController controllerAs(Long memberId) {
        ApiRegistrationController controller = spy(new ApiRegistrationController());
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "registrationService", registrationService);
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "topicService", mock(ICmsTopicService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "activityService", mock(ICmsActivityService.class));
        ReflectionTestUtils.setField(RegistrationQueryTestFixtures.application(controller), "refundService", refundService);
        when(registrationService.selectCmsRegistrationList(any())).thenReturn(new ArrayList<>());
        when(refundService.selectRefundList(any())).thenReturn(new ArrayList<>());
        doReturn(memberId).when(controller).getAppUserId();
        return controller;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void list_非法member一律按未登录拒且不发查询(Long memberId) {
        AjaxResult res = RegistrationQueryTestFixtures.wire(controllerAs(memberId)).myRegistrationList("3", null, null);

        assertEquals(500, res.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", res.get(AjaxResult.MSG_TAG));
        assertFalse(res.containsKey(AjaxResult.DATA_TAG));
        verifyNoInteractions(registrationService, refundService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void myJoined_非法member一律按未登录拒且不发查询(Long memberId) {
        AjaxResult res = RegistrationQueryTestFixtures.wire(controllerAs(memberId)).myJoined();

        assertEquals(500, res.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", res.get(AjaxResult.MSG_TAG));
        assertFalse(res.containsKey(AjaxResult.DATA_TAG));
        verifyNoInteractions(registrationService, refundService);
    }

    @Test
    void list_合法member只查本人() {
        AjaxResult res = RegistrationQueryTestFixtures.wire(controllerAs(7L)).myRegistrationList("3", null, null);

        assertEquals(200, res.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<CmsRegistration> query = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationService).selectCmsRegistrationList(query.capture());
        assertEquals(7L, query.getValue().getMemberId());
    }

    @Test
    void myJoined_合法member两类查询都只查本人() {
        AjaxResult res = RegistrationQueryTestFixtures.wire(controllerAs(7L)).myJoined();

        assertEquals(200, res.get(AjaxResult.CODE_TAG));
        ArgumentCaptor<CmsRegistration> query = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrationService, times(2)).selectCmsRegistrationList(query.capture());
        for (CmsRegistration q : query.getAllValues()) {
            assertEquals(7L, q.getMemberId());
        }
    }
}
