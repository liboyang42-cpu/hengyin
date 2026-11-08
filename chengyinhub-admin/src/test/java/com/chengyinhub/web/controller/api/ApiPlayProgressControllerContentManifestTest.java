package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.service.play.PlayContentManifestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** GET /api/play/content-manifest 只是 HTTP 适配:鉴权主体来自既有边界,业务判定在 application。 */
class ApiPlayProgressControllerContentManifestTest {

    private ApiPlayProgressController controller;
    private PlayContentManifestApplication application;

    @BeforeEach
    void setUp() {
        controller = new ApiPlayProgressController();
        application = mock(PlayContentManifestApplication.class);
        ReflectionTestUtils.setField(controller, "playContentManifestApplication", application);
    }

    @Test
    void forwardsActorAndFiltersVerbatim() {
        AjaxResult expected = AjaxResult.success();
        when(application.manifest(9L, 77L, 21L, 900L)).thenReturn(expected);
        ApiPlayProgressController spied = spy(controller);
        doReturn(9L).when(spied).getAppUserId();

        AjaxResult result = spied.contentManifest(77L, 21L, 900L);

        assertSame(expected, result);
        verify(application).manifest(9L, 77L, 21L, 900L);
    }

    @Test
    void anonymousActorIsPassedAsZeroAndRejectedDownstream() {
        AjaxResult denied = AjaxResult.error("请先登录");
        when(application.manifest(0L, null, 21L, null)).thenReturn(denied);

        // 无登录态时 getAppUserId 抛异常,控制器既有 currentMemberId() 归一为 0。
        ApiPlayProgressController spied = spy(controller);
        doThrow(new RuntimeException("no session")).when(spied).getAppUserId();

        AjaxResult result = spied.contentManifest(null, 21L, null);

        assertSame(denied, result);
        verify(application).manifest(0L, null, 21L, null);
    }
}
