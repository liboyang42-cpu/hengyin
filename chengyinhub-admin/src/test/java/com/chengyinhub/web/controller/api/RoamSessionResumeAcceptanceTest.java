package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.business.service.support.RoamRecoveryService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收 SC-ROAM-RESUME(服务端):开始 → 退出小程序 → 重新打开 → 继续,始终是同一个会话。
 * 同一 clientSessionKey 的开局重试不新建会话;恢复读只读不写;续写落在原会话;开始时间不被改写
 * (时长按 startedAt→finishedAt 连续计)。暂停/恢复在服务端没有独立状态,属客户端行为;结算归另一条线。
 */
class RoamSessionResumeAcceptanceTest {

    private static final Long MEMBER = 77L;
    private static final String KEY = "c0ffee".repeat(5) + "ab";

    private RoamMapper roamMapper;
    private ApiRoamController controller;
    private RoamSession session;

    @BeforeEach
    void setUp() {
        roamMapper = mock(RoamMapper.class);
        controller = spy(new ApiRoamController());
        doReturn(MEMBER).when(controller).getAppUserId();
        ReflectionTestUtils.setField(controller, "roamMapper", roamMapper);
        ReflectionTestUtils.setField(controller, "locationConsent", mock(RoamLocationConsentService.class));
        ReflectionTestUtils.setField(controller, "roamRecoveryService", new RoamRecoveryService(roamMapper));

        session = new RoamSession();
        session.setId(9L);
        session.setMemberId(MEMBER);
        session.setClientSessionKey(KEY);
        session.setStatus(0);
        session.setStartedAt(new Date(System.currentTimeMillis() - 30 * 60_000L));
        when(roamMapper.selectOwnedSessionByKeyForUpdate(MEMBER, KEY)).thenReturn(session);
        when(roamMapper.selectOwnedSessionForUpdate(9L, MEMBER)).thenReturn(session);
        when(roamMapper.selectOwnedRecoverySession(MEMBER, 9L, null)).thenReturn(session);
        when(roamMapper.batchInsertTile(eq(MEMBER), eq(9L), anyList(), any(Date.class))).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(AjaxResult r) {
        assertEquals(200, r.get(AjaxResult.CODE_TAG), String.valueOf(r.get(AjaxResult.MSG_TAG)));
        return (Map<String, Object>) r.get(AjaxResult.DATA_TAG);
    }

    @Test
    void startRetryReopenAndContinueStayOnOneSession() {
        Date startedAt = session.getStartedAt();

        // 开始(首包丢失后重试同一 key)⇒ 同一会话
        assertEquals("9", data(controller.reveal("0", "wtw3sjq", KEY)).get("sessionId"));
        assertEquals("9", data(controller.reveal("0", "wtw3sjr", KEY)).get("sessionId"));
        verify(roamMapper, never()).insertSession(any());

        // 退出后重新打开:恢复读拿回同一个进行中的会话
        Map<String, Object> recovered = data(controller.session("9", null));
        assertEquals("ACTIVE", recovered.get("state"));
        assertEquals("9", recovered.get("sessionId"));

        // 继续:写在原会话上
        assertEquals("9", data(controller.reveal("9", "wtw3sjs", KEY)).get("sessionId"));
        verify(roamMapper, times(3)).batchInsertTile(eq(MEMBER), eq(9L), anyList(), any(Date.class));

        // 全程没有结算、没有改写会话行(开始时间不变 ⇒ 时长连续)
        assertEquals(startedAt, session.getStartedAt());
        for (Invocation call : mockingDetails(roamMapper).getInvocations()) {
            String name = call.getMethod().getName();
            assertFalse(name.startsWith("update") || name.startsWith("delete"), "恢复链路不得改写会话:" + name);
        }
    }
}
