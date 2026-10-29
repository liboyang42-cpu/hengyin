package com.chengyinhub.business.task;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IWechatPayService;
import com.chengyinhub.business.service.impl.AlarmService;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * §5-3:bean 名与方法名必须与 sys_job 注册的 invoke_target 一字不差 —— Quartz 找不到就是
 * 每 10 分钟静默 NoSuchMethodException,任务等于不存在(predictVoidExpiredTask 的老教训)。
 * 迁移文件与依赖声明也在这里钉住,防止「代码合了、注册 SQL 没写」的静默漏配。
 */
class OrderClosePayingReconcileTaskContractTest {

    private static final String MIGRATION = "migration_registration_paying_reconcile_job_20260915.sql";

    @Test
    void bean名与注册SQL和依赖声明一致() throws Exception {
        String bean = OrderCloseTask.class.getAnnotation(Component.class).value();
        assertEquals("orderCloseTask", bean);
        OrderCloseTask.class.getMethod("reconcileExpiredPaying");

        String sql = readFirstExisting(
                Paths.get("sql", MIGRATION),
                Paths.get("chengyinhub-admin", "sql", MIGRATION));
        assertTrue(sql.contains("'" + bean + ".reconcileExpiredPaying()'"),
                "迁移里必须注册 " + bean + ".reconcileExpiredPaying()");
        // P2-PT-4:DELETE+INSERT 重跑会换 job_id,已激活的旧 JobKey 仍在内存 Scheduler 里 ⇒ 两个触发器并跑。
        assertTrue(sql.contains("WHERE NOT EXISTS"),
                "注册必须幂等且不换 job_id(INSERT ... WHERE NOT EXISTS)");
        assertFalse(sql.toUpperCase().contains("DELETE FROM SYS_JOB"),
                "禁止 DELETE+INSERT 重注册:重跑会换 job_id");
        assertTrue(sql.contains("-- runner: assert=scalar-true"),
                "注册必须带回读断言,否则迁移跑没跑只有人肉知道");
        assertTrue(sql.contains("post-deploy"), "任务注册必须在 jar 部署后执行");

        String dependencies = readFirstExisting(
                Paths.get("..", "ci", "migration-dependencies.json"),
                Paths.get("ci", "migration-dependencies.json"));
        String migrationId = MIGRATION.substring("migration_".length(), MIGRATION.length() - ".sql".length());
        assertTrue(dependencies.contains("\"" + migrationId + "\""),
                "新增迁移必须在 ci/migration-dependencies.json 有条目");
    }

    @Test
    void failedSecondPageKeepsAttemptedPayingPrefixUntilQueryRecovers() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        OrderCloseTask task = new OrderCloseTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", service);
        ReflectionTestUtils.setField(task, "wechatPayService", wechat);
        ReflectionTestUtils.setField(task, "alarmService", mock(AlarmService.class));
        when(wechat.isReady()).thenReturn(true);
        List<Long> firstPage = LongStream.rangeClosed(1L, 200L).boxed().collect(Collectors.toList());
        when(service.listExpiredPayingIds(0L)).thenReturn(firstPage);
        when(service.listExpiredPayingIds(200L))
                .thenThrow(new IllegalStateException("second page unavailable"))
                .thenThrow(new IllegalStateException("second page unavailable"))
                .thenReturn(Collections.singletonList(201L));
        when(service.listExpiredPayingIds(201L)).thenReturn(Collections.emptyList());
        when(service.selectCmsRegistrationById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            CmsRegistration registration = new CmsRegistration();
            registration.setId(id);
            registration.setRegistrationNo("PAYING-" + id);
            registration.setPaymentStatus(1);
            registration.setRegistrationStatus(1);
            return registration;
        });
        when(wechat.queryOrderStatus(anyString())).thenAnswer(call -> {
            String orderNo = call.getArgument(0);
            if (!"PAYING-201".equals(orderNo)) throw new IllegalStateException("provider unavailable");
            Map<String, String> result = new HashMap<>();
            result.put("out_trade_no", orderNo);
            result.put("wx_out_trade_no", orderNo);
            result.put("trade_state", "CLOSED");
            return result;
        });
        when(service.settleClosedPayingOne(argThat(r -> Long.valueOf(201L).equals(r.getId())), eq("PAYING-201"), anyString(), anyString(), eq(4))).thenReturn(true);

        assertThrows(IllegalStateException.class, task::reconcileExpiredPaying);
        assertEquals(200L, ReflectionTestUtils.getField(task, "reconcileCursor"));
        assertThrows(IllegalStateException.class, task::reconcileExpiredPaying);
        assertEquals(200L, ReflectionTestUtils.getField(task, "reconcileCursor"));
        verify(service, never()).settleClosedPayingOne(any(), anyString(), anyString(), anyString(), anyInt());

        task.reconcileExpiredPaying();

        verify(service, times(1)).listExpiredPayingIds(0L);
        verify(service, times(3)).listExpiredPayingIds(200L);
        for (long id = 1L; id <= 201L; id++) {
            verify(wechat, times(1)).queryOrderStatus("PAYING-" + id);
        }
        verify(service, times(1)).settleClosedPayingOne(argThat(r -> Long.valueOf(201L).equals(r.getId())), eq("PAYING-201"),
                eq(com.chengyinhub.common.constant.EntitlementInvalidReason.ORGANIZER_CANCEL),
                eq("payment-reconcile-task"), eq(4));
        verify(service, never()).handleWechatPayCallback(anyMap());
        verify(wechat, never()).closeOrder(anyString());
        assertEquals(0L, ReflectionTestUtils.getField(task, "reconcileCursor"));
    }

    @Test
    void refusedOriginalClosedObservationCannotFallBackToAnUnguardedRelease() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        IWechatPayService wechat = mock(IWechatPayService.class);
        OrderCloseTask task = new OrderCloseTask();
        ReflectionTestUtils.setField(task, "cmsRegistrationService", service);
        ReflectionTestUtils.setField(task, "wechatPayService", wechat);
        ReflectionTestUtils.setField(task, "alarmService", mock(AlarmService.class));
        CmsRegistration original = new CmsRegistration(); original.setId(1L);
        original.setRegistrationNo("REG1"); original.setPaymentStatus(1); original.setRegistrationStatus(1);
        when(wechat.isReady()).thenReturn(true);
        when(service.listExpiredPayingIds(0L)).thenReturn(List.of(1L));
        when(service.listExpiredPayingIds(1L)).thenReturn(List.of());
        when(service.selectCmsRegistrationById(1L)).thenReturn(original);
        when(wechat.queryOrderStatus("REG1")).thenReturn(Map.of("out_trade_no", "REG1", "wx_out_trade_no", "WX-ORIGINAL", "trade_state", "CLOSED"));
        when(service.settleClosedPayingOne(original, "WX-ORIGINAL",
                com.chengyinhub.common.constant.EntitlementInvalidReason.ORGANIZER_CANCEL, "payment-reconcile-task", 4))
                .thenReturn(false);

        task.reconcileExpiredPaying();

        verify(service).settleClosedPayingOne(original, "WX-ORIGINAL",
                com.chengyinhub.common.constant.EntitlementInvalidReason.ORGANIZER_CANCEL, "payment-reconcile-task", 4);
        verify(service, never()).settlePayingOne(anyLong(), anyString(), anyString(), anyInt());
        verify(service, never()).handleWechatPayCallback(anyMap());
        verify(wechat, never()).lockPaymentAttemptForUpdate(anyString(), anyString());
        verify(wechat, never()).lockClosablePaymentAttemptForUpdate(anyString(), anyString());
        verify(wechat, never()).closeOrder(anyString());
    }

    private String readFirstExisting(Path... candidates) throws Exception {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到文件: " + candidates[0]);
    }
}
