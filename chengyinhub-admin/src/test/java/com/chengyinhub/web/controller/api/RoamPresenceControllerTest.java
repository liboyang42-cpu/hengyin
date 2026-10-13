package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.vo.NearbyRunnerVO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附近正在漫游的人:心跳与查询两个入口。
 *
 * 这一组测的全是**隐私闸**,不是功能:坐标必须截位、越界要拒、别人的会话写不进、
 * 百分比要夹住、回包里不许出现拍板范围以外的字段。功能坏了是 bug,这几条坏了是事故。
 */
class RoamPresenceControllerTest {

    private static class StubController extends ApiRoamController {
        final List<Runnable> auditTasks = new ArrayList<>();
        boolean rejectAudit;
        final com.chengyinhub.business.service.impl.RoamPresenceRevokeAuditService revokeAudits =
                mock(com.chengyinhub.business.service.impl.RoamPresenceRevokeAuditService.class);
        @Override Long uid() { return 9L; }
        @Override protected void enqueuePresenceAudit(Long memberId, Runnable audit) {
            if (rejectAudit) throw new java.util.concurrent.RejectedExecutionException("fixture scheduler shutdown");
            auditTasks.add(audit);
        }
    }

    private StubController controller(RoamMapper mapper) {
        StubController c = new StubController();
        ReflectionTestUtils.setField(c, "roamMapper", mapper);
        ReflectionTestUtils.setField(c,"locationConsent",mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class));
        ReflectionTestUtils.setField(c, "presenceRevokeAuditService", c.revokeAudits);
        ReflectionTestUtils.setField(c, "alarmService", mock(com.chengyinhub.business.service.impl.AlarmService.class));
        return c;
    }

    @Test
    void presence_truncates_coordinates_to_about_100m_before_it_ever_reaches_the_db() {
        RoamMapper mapper = mock(RoamMapper.class);
        when(mapper.updatePresence(anyLong(), anyLong(), any(), any(), any(), anyInt())).thenReturn(1);
        StubController c = controller(mapper);

        // 真实定位点小数点后 6 位;落库必须只剩 3 位
        AjaxResult r = c.presence("77", 31.2304567, 121.4737123, 42);
        assertEquals(200, r.get("code"));

        ArgumentCaptor<BigDecimal> lat = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> lng = ArgumentCaptor.forClass(BigDecimal.class);
        verify(mapper).updatePresence(anyLong(), anyLong(), lat.capture(), lng.capture(), any(Date.class), anyInt());
        assertEquals(new BigDecimal("31.230"), lat.getValue());
        assertEquals(new BigDecimal("121.474"), lng.getValue());
        // ★负控:原始精度一旦漏过去,跟着一个人走就成立了
        assertNotEquals(new BigDecimal("31.2304567"), lat.getValue());
    }

    @Test
    void presence_clamps_client_supplied_percent_and_refuses_out_of_range_coordinates() {
        RoamMapper mapper = mock(RoamMapper.class);
        when(mapper.updatePresence(anyLong(), anyLong(), any(), any(), any(), anyInt())).thenReturn(1);
        StubController c = controller(mapper);

        c.presence("77", 31.23, 121.47, 9999);
        ArgumentCaptor<Integer> pct = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).updatePresence(anyLong(), anyLong(), any(), any(), any(Date.class), pct.capture());
        assertEquals(99, pct.getValue(), "探索度会显示给陌生人,客户端写多少都要夹住");

        RoamMapper m2 = mock(RoamMapper.class);
        StubController c2 = controller(m2);
        assertEquals(500, c2.presence("77", 999.0, 121.47, 0).get("code"));
        assertEquals(500, c2.presence("77", null, null, 0).get("code"));
        assertEquals(500, c2.presence("0", 31.23, 121.47, 0).get("code"), "没有会话就不算在走,这里不懒建");
        verify(m2, never()).updatePresence(anyLong(), anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void presence_reports_the_same_refusal_whether_the_session_is_someone_elses_or_already_finished() {
        RoamMapper mapper = mock(RoamMapper.class);
        // where 里带 member_id 与 status=0 ⇒ 别人的、已结算的都命中 0 行
        when(mapper.updatePresence(anyLong(), anyLong(), any(), any(), any(), anyInt())).thenReturn(0);
        StubController c = controller(mapper);
        AjaxResult r = c.presence("77", 31.23, 121.47, 10);
        assertEquals(500, r.get("code"));
        // 口径故意粗:分得越细,越能拿它探别人的会话 id
        assertEquals("会话不存在", r.get("msg"));
    }

    private void activeSharing(RoamMapper mapper) {
        com.chengyinhub.business.domain.RoamSession own = new com.chengyinhub.business.domain.RoamSession();
        own.setMemberId(9L);
        own.setStatus(0);
        own.setPresenceVisible(true);
        own.setLastLat(new BigDecimal("31.230"));
        own.setLastLng(new BigDecimal("121.474"));
        when(mapper.selectActivePresence(eq(9L), any(Date.class))).thenReturn(own);
    }

    @Test
    void privateOrInactiveCallerCannotReadOthers() {
        RoamMapper mapper = mock(RoamMapper.class);
        AjaxResult result = controller(mapper).nearbyRunners(31.23, 121.47, 3000);
        assertEquals(200, result.get("code"));
        assertTrue(((List<?>) result.get("data")).isEmpty());
        verify(mapper, never()).selectNearbyRunners(anyLong(), any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    void requestedCoordinatesCannotMoveTheQueryAwayFromStoredOwnPosition() {
        RoamMapper mapper = mock(RoamMapper.class);
        activeSharing(mapper);
        controller(mapper).nearbyRunners(1.0, 2.0, 3000);
        verify(mapper).selectNearbyRunners(eq(9L), eq(31.230), eq(121.474), eq(3000),
                any(Date.class), any(Date.class), anyInt());
    }

    @Test
    void enablingRequiresSeparateConsentAndOwnedActiveSession() {
        RoamMapper mapper = mock(RoamMapper.class);
        StubController c = controller(mapper);
        assertEquals(500, c.changePresenceVisibility("77", true, "").get("code"));
        assertEquals(500, c.changePresenceVisibility("77", true, "roam-presence-v1").get("code"));
        verify(mapper, never()).updatePresenceVisibility(any(), anyLong(), anyBoolean());
    }

    @Test
    void sharingCanOpenOnlyAfterSeparateConsentActuallyPersists() {
        RoamMapper mapper = mock(RoamMapper.class);
        com.chengyinhub.business.service.IConsentRecordService consents = mock(com.chengyinhub.business.service.IConsentRecordService.class);
        com.chengyinhub.business.domain.RoamSession own = new com.chengyinhub.business.domain.RoamSession();
        own.setId(77L); own.setMemberId(9L); own.setStatus(0);
        when(mapper.selectOwnedSessionForUpdate(77L, 9L)).thenReturn(own);
        StubController controller = controller(mapper);
        ReflectionTestUtils.setField(controller, "consentRecordService", consents);
        when(consents.record(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> controller.changePresenceVisibility("77", true, "roam-presence-v1"));
        verify(mapper, never()).updatePresenceVisibility(any(), anyLong(), eq(true));
        when(consents.record(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(mapper.updatePresenceVisibility(77L, 9L, true)).thenReturn(1);
        assertEquals(true, controller.changePresenceVisibility("77", true, "roam-presence-v1").get("data"));
        verify(mapper).updatePresenceVisibility(77L, 9L, true);
    }

    @Test
    void revokeAuditStartsAfterCloseCommitAndItsFailuresCannotReopenSharing() {
        RoamMapper mapper = mock(RoamMapper.class);
        java.util.concurrent.atomic.AtomicBoolean sharing = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(mapper.updatePresenceVisibility(isNull(), eq(9L), eq(false))).thenAnswer(call -> { sharing.set(false); return 1; });
        StubController controller = controller(mapper);
        when(controller.revokeAudits.deliver(anyString())).thenReturn(false);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            AjaxResult closed = controller.changePresenceVisibility(null, false, null);
            assertFalse(sharing.get());
            assertEquals("PENDING", closed.get("auditStatus"));
            ArgumentCaptor<String> request = ArgumentCaptor.forClass(String.class);
            verify(controller.revokeAudits).remember(eq(9L), request.capture(), any(Date.class));
            verify(controller.revokeAudits, never()).deliver(anyString());
            assertTrue(controller.auditTasks.isEmpty());
            for (org.springframework.transaction.support.TransactionSynchronization callback :
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) {
                assertDoesNotThrow(callback::afterCommit);
            }
            assertEquals(1, controller.auditTasks.size());
            assertDoesNotThrow(controller.auditTasks.get(0)::run);
            verify(controller.revokeAudits).deliver(request.getValue());
            assertEquals("PENDING", closed.get("auditStatus"));
            assertFalse(sharing.get());
            verify(mapper, never()).updatePresenceVisibility(any(), anyLong(), eq(true));
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test
    void rejectedAuditSchedulingDoesNotThrowFromCommitOrReopenSharingAndRaisesAlarm() {
        RoamMapper mapper = mock(RoamMapper.class);
        StubController controller = controller(mapper);
        controller.rejectAudit = true;
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            AjaxResult closed = controller.changePresenceVisibility(null, false, null);
            assertEquals(false, closed.get("data"));
            verify(mapper).updatePresenceVisibility(isNull(), eq(9L), eq(false));
            for (org.springframework.transaction.support.TransactionSynchronization callback :
                    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations())
                assertDoesNotThrow(callback::afterCommit);
            assertEquals("PENDING", closed.get("auditStatus"));
            assertTrue(controller.auditTasks.isEmpty());
            verify(mapper, never()).updatePresenceVisibility(any(), anyLong(), eq(true));
            verify((com.chengyinhub.business.service.impl.AlarmService) ReflectionTestUtils.getField(controller, "alarmService"))
                    .alarm(eq("位置分享撤回记录失败"), anyString());
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test
    void closingClearsAllOwnSessionsAndHeartbeatCannotRestoreSharing() {
        RoamMapper mapper = mock(RoamMapper.class);
        com.chengyinhub.business.service.IConsentRecordService consents =
                mock(com.chengyinhub.business.service.IConsentRecordService.class);
        StubController c = controller(mapper);
        ReflectionTestUtils.setField(c, "consentRecordService", consents);
        when(consents.record(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        AjaxResult closed = c.changePresenceVisibility(null, false, null);
        assertEquals(false, closed.get("data"));
        assertEquals("PENDING", closed.get("auditStatus"));
        c.auditTasks.get(0).run();
        assertEquals("PENDING", closed.get("auditStatus"), "async audit must not mutate the returned response");
        verify(mapper).updatePresenceVisibility(isNull(), eq(9L), eq(false));
        when(mapper.updatePresence(anyLong(), anyLong(), any(), any(), any(), anyInt())).thenReturn(1);
        c.presence("77", 31.23, 121.47, 12);
        verify(mapper, never()).updatePresenceVisibility(any(), anyLong(), eq(true));
        verify(c.revokeAudits).remember(eq(9L), anyString(), any(Date.class));
        verify(c.revokeAudits).deliver(anyString());
    }

    @Test
    void nearby_runners_returns_only_the_six_agreed_fields_and_never_an_identity_leak() {
        RoamMapper mapper = mock(RoamMapper.class);
        activeSharing(mapper);
        NearbyRunnerVO v = new NearbyRunnerVO();
        v.setMemberId(12L);
        v.setNickname("小鱼");
        v.setAvatar("a.png");
        v.setLat(new BigDecimal("31.230"));
        v.setLng(new BigDecimal("121.474"));
        v.setExplorePct(9);
        v.setShops(12);
        v.setElapsedSec(2300L);
        when(mapper.selectNearbyRunners(anyLong(), any(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(new ArrayList<>(Arrays.asList(v)));
        when(mapper.selectRunnerShops(any(), anyInt())).thenReturn(new ArrayList<>());

        AjaxResult r = controller(mapper).nearbyRunners(31.23, 121.47, 3000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("data");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(12L, row.get("memberId"));
        assertEquals(new BigDecimal("31.23"), row.get("lat"));
        assertEquals(new BigDecimal("121.47"), row.get("lng"));
        assertEquals("小鱼", row.get("nickname"));
        assertEquals(9, row.get("explorePct"));
        assertEquals(12, row.get("shops"));
        assertEquals(2300L, row.get("elapsedSec"));
        // ★负控:2026-09-10 拍板的字段范围是六样 + 坐标 + memberId,多一个都不许有
        assertEquals(9, row.size(), "回包字段数变了就重读这条:它是发给陌生人的");
        for (String leak : new String[]{"phone", "mobile", "realName", "idCard", "sessionId", "openId"}) {
            assertFalse(row.containsKey(leak), leak + " 不许出现在发给陌生人的回包里");
        }
    }

    @Test
    void nearby_runners_caps_the_radius_and_skips_the_shop_query_when_nobody_is_around() {
        RoamMapper mapper = mock(RoamMapper.class);
        when(mapper.selectNearbyRunners(anyLong(), any(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(new ArrayList<NearbyRunnerVO>());
        StubController c = controller(mapper);
        activeSharing(mapper);

        c.nearbyRunners(31.23, 121.47, 999999);
        ArgumentCaptor<Integer> radius = ArgumentCaptor.forClass(Integer.class);
        verify(mapper).selectNearbyRunners(anyLong(), any(), any(), radius.capture(), any(), any(), anyInt());
        assertEquals(3000, radius.getValue(), "不接受客户端要一个全城范围");

        // 空列表要在进 SQL 前挡住:`in ()` 是语法错,会 500
        verify(mapper, never()).selectRunnerShops(any(), anyInt());
    }
}
