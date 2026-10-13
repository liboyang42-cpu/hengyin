package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.PlayerRunSessionMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 第二轮拍板 22:跨设备续玩的服务端会话。只测作用域/身份/边界,「能不能继续」由 /api/play/nodes 权威态把门。 */
class ApiPlayRunSessionControllerTest {

    private static class AsMember extends ApiPlayRunSessionController {
        private final long id;
        AsMember(long id) { this.id = id; }
        @Override Long uid() { return id; }
    }

    private PlayerRunSessionMapper mapper = mock(PlayerRunSessionMapper.class);
    private ICmsActivityService activities = mock(ICmsActivityService.class);

    private ApiPlayRunSessionController as(long memberId) {
        ApiPlayRunSessionController c = new AsMember(memberId);
        ReflectionTestUtils.setField(c, "runSessionMapper", mapper);
        ReflectionTestUtils.setField(c, "cmsActivityService", activities);
        return c;
    }

    @Test
    void save_activity_scope_takes_topic_from_the_activity_not_the_client() {
        CmsActivity activity = new CmsActivity();
        activity.setTopicId(31L);
        when(activities.selectCmsActivityById(88L)).thenReturn(activity);
        assertEquals(200, as(9).save(88L, 999L, 42, 1000L).get("code"));
        verify(mapper).upsertPaused(9L, 88L, 31L, 42, 1000L);
    }

    @Test
    void save_self_play_scope_uses_zero_activity() {
        assertEquals(200, as(9).save(null, 31L, 0, 1000L).get("code"));
        verify(mapper).upsertPaused(9L, 0L, 31L, 0, 1000L);
        verifyNoInteractions(activities);
    }

    @Test
    void save_rejects_login_bounds_and_unknown_scope_without_writing() {
        assertEquals(500, as(0).save(null, 31L, 42, 1000L).get("code"), "未登录");
        assertEquals(500, as(9).save(null, 31L, -1, 1000L).get("code"), "负用时");
        assertEquals(500, as(9).save(null, 31L, ApiPlayRunSessionController.MAX_PAUSED_SECONDS + 1, 1000L).get("code"), "超一周是脏数据");
        assertEquals(500, as(9).save(null, 31L, 42, null).get("code"));
        assertEquals(500, as(9).save(null, null, 42, 1000L).get("code"), "无作用域");
        assertEquals(500, as(9).save(77L, 31L, 42, 1000L).get("code"), "活动不存在");
        verify(mapper, never()).upsertPaused(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void read_clear_and_list_are_scoped_to_the_caller() {
        when(mapper.selectContinuableByMember(9L, ApiPlayRunSessionController.LIST_LIMIT)).thenReturn(Collections.emptyList());
        assertEquals(200, as(9).read(88L, null).get("code"));
        verify(mapper).selectScope(9L, 88L, null);
        assertEquals(200, as(9).clear(null, 31L, 5000L).get("code"));
        verify(mapper).markEnded(9L, 0L, 31L, 5000L);
        assertEquals(200, as(9).list().get("code"));
        verify(mapper).selectContinuableByMember(9L, ApiPlayRunSessionController.LIST_LIMIT);

        assertEquals(500, as(0).read(88L, null).get("code"));
        assertEquals(500, as(0).clear(88L, null, 5000L).get("code"));
        assertEquals(500, as(9).clear(88L, null, null).get("code"), "墓碑必须带结束时刻,否则另一台设备无从比新旧");
        assertEquals(500, as(0).list().get("code"));
        assertEquals(500, as(9).clear(null, null, 5000L).get("code"));
        verify(mapper, times(1)).markEnded(any(), any(), any(), any());
    }
}
