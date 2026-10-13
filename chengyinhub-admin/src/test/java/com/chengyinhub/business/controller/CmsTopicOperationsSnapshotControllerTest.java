package com.chengyinhub.business.controller;

import com.chengyinhub.business.domain.dto.TopicOperationsSnapshotDTO;
import com.chengyinhub.business.service.ITopicOperationsSnapshotService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.business.CmsTopicController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0 统一待办端点只能返回白名单快照，不混入领域实体或通用写接口。 */
class CmsTopicOperationsSnapshotControllerTest {

    @Test
    void operationsReturnsTheSafeSnapshotForAnExistingTopic() {
        ITopicOperationsSnapshotService service = mock(ITopicOperationsSnapshotService.class);
        TopicOperationsSnapshotDTO snapshot = new TopicOperationsSnapshotDTO();
        when(service.getSnapshot(71L)).thenReturn(snapshot);

        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsSnapshotService", service);

        AjaxResult result = controller.operations(71L);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertSame(snapshot, result.get(AjaxResult.DATA_TAG));
        verify(service).getSnapshot(71L);
    }

    @Test
    void operationsDoesNotRevealWhetherAHiddenTopicExists() {
        ITopicOperationsSnapshotService service = mock(ITopicOperationsSnapshotService.class);
        when(service.getSnapshot(72L)).thenReturn(null);

        CmsTopicController controller = new CmsTopicController();
        ReflectionTestUtils.setField(controller, "topicOperationsSnapshotService", service);

        AjaxResult result = controller.operations(72L);

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("主题不存在或当前账号无查看范围", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void operationsRequiresTopicReadPermissionAndKeepsTheRouteReadOnly() throws Exception {
        Method method = CmsTopicController.class.getMethod("operations", Long.class);

        assertEquals("@ss.hasPermi('business:topic:query')", method.getAnnotation(PreAuthorize.class).value());
        assertEquals("/{id}/operations", method.getAnnotation(GetMapping.class).value()[0]);
    }
}
