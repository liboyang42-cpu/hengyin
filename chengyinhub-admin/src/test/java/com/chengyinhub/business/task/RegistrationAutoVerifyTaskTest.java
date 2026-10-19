package com.chengyinhub.business.task;

import com.chengyinhub.business.service.ICmsRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RUN-01 到期自动核销任务:逐单独立收口、单笔失败不熔断整轮;SQL 判据不许漂出口径。 */
class RegistrationAutoVerifyTaskTest {

    private RegistrationAutoVerifyTask task(ICmsRegistrationService service) {
        RegistrationAutoVerifyTask task = new RegistrationAutoVerifyTask();
        when(service.registrationScanUpperBound()).thenReturn(Long.MAX_VALUE);
        ReflectionTestUtils.setField(task, "cmsRegistrationService", service);
        return task;
    }

    @Test
    void run_closesEveryExpiredOrderPerId() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnverifiedIds(0L)).thenReturn(Arrays.asList(101L, 102L, 103L));
        when(service.autoVerifyExpiredOne(101L)).thenReturn(true);
        when(service.autoVerifyExpiredOne(102L)).thenReturn(false); // 竞态已被商家真核销,跳过
        when(service.autoVerifyExpiredOne(103L)).thenReturn(true);

        task(service).run();

        verify(service).autoVerifyExpiredOne(101L);
        verify(service).autoVerifyExpiredOne(102L);
        verify(service).autoVerifyExpiredOne(103L);
    }

    @Test
    void run_singleFailureDoesNotAbortRemainingIds() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnverifiedIds(0L)).thenReturn(Arrays.asList(201L, 202L));
        when(service.autoVerifyExpiredOne(201L)).thenThrow(new RuntimeException("模拟单笔异常"));
        when(service.autoVerifyExpiredOne(202L)).thenReturn(true);

        task(service).run(); // 不向上抛:Quartz 只记本轮失败,后面的单必须继续收

        verify(service).autoVerifyExpiredOne(202L);
    }

    @Test
    void run_emptyScan_touchesNothing() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnverifiedIds(0L)).thenReturn(Collections.emptyList());

        task(service).run();

        verify(service, never()).autoVerifyExpiredOne(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void boundedCircleGetsPastFailedPrefixAndNewArrivalsCannotPostponeRetry() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        RegistrationAutoVerifyTask task = task(service);
        when(service.registrationScanUpperBound()).thenReturn(201L, 401L);
        when(service.listExpiredUnverifiedIds(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            long after = call.getArgument(0);
            return java.util.stream.LongStream.rangeClosed(after + 1, after + 200)
                    .boxed().collect(java.util.stream.Collectors.toList());
        });
        when(service.autoVerifyExpiredOne(org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        task.run();
        verify(service, never()).autoVerifyExpiredOne(201L);
        task.run();
        verify(service).autoVerifyExpiredOne(201L);
        verify(service, never()).autoVerifyExpiredOne(202L);
        task.run();
        verify(service, org.mockito.Mockito.times(2)).autoVerifyExpiredOne(1L);
        verify(service, org.mockito.Mockito.times(2)).registrationScanUpperBound();
    }

    @Test
    void removalDoesNotSkipNextPageAndRestartOnlyRevisitsUnresolvedOrders() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        java.util.List<Long> pending = new java.util.ArrayList<>();
        for (long id = 1; id <= 201; id++) pending.add(id);
        when(service.listExpiredUnverifiedIds(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            long after = call.getArgument(0);
            return pending.stream().filter(id -> id > after).limit(200).collect(java.util.stream.Collectors.toList());
        });
        when(service.autoVerifyExpiredOne(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> pending.remove((Long) call.getArgument(0)));
        RegistrationAutoVerifyTask first = task(service);
        first.run();
        assertEquals(Collections.singletonList(201L), pending);
        task(service).run();
        assertTrue(pending.isEmpty());
        verify(service, org.mockito.Mockito.times(201)).autoVerifyExpiredOne(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void malformedPageDoesNotRepeatTheSameTicket() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnverifiedIds(0L)).thenReturn(Arrays.asList(1L, 1L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> task(service).run());
        verify(service).autoVerifyExpiredOne(1L);
    }

    @Test
    void mapperStatements_keepScopeAndGuards() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system",
                "src/main/resources/mapper/business/CmsRegistrationMapper.xml")), StandardCharsets.UTF_8);
        String scan = statement(xml, "selectExpiredUnverifiedIds");
        String cas = statement(xml, "autoVerifyExpiredSession");

        // 范围只收口场次票(主题票结算写入在核销动作里,不带台账的自动置位会锁死商家的钱)
        assertTrue(scan.contains("r.owner_type = 2"), "扫描必须钉死 owner_type=2");
        assertTrue(cas.contains("owner_type = 2"), "置位必须钉死 owner_type=2");
        // 退款在途与缺锚点两条闸在扫描和置位语句里都必须在(判据下推进当前读,不许先 select 再判)
        assertTrue(scan.contains("refund_application") && cas.contains("refund_application"),
                "在途退款排除必须同时存在于扫描与条件置位");
        assertTrue(scan.contains("status in (0, 1)") && cas.contains("status in (0, 1)"));
        assertTrue(scan.contains("a.end_date is not null") && cas.contains("a.end_date is not null"),
                "缺锚点必须 fail-closed 不自动收口");
        // 自动收口不伪造核销人:verifier_id 只许商家扫码路径写
        assertTrue(cas.contains("verification_status = 1"), "置位语义就是已核销");
        assertEquals(-1, cas.indexOf("verifier_id"), "autoVerifyExpiredSession 不许写 verifier_id");
        assertTrue(cas.contains("payment_status = 2") && cas.contains("registration_status = 2"));
    }

    /** 取 <select|update id=...> 语句体(含注释),避免全文件 contains 误判到别的语句上。 */
    private String statement(String xml, String id) {
        int begin = xml.indexOf("id=\"" + id + "\"");
        assertTrue(begin >= 0, "找不到语句 " + id);
        int open = xml.lastIndexOf('<', begin);
        String tag = xml.substring(open + 1).split("[\\s>]")[0];
        int close = xml.indexOf("</" + tag + ">", begin);
        assertTrue(close > begin);
        return xml.substring(begin, close);
    }
}
