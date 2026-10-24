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
        ReflectionTestUtils.setField(task, "cmsRegistrationService", service);
        return task;
    }

    @Test
    void run_closesEveryExpiredOrderPerId() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnverifiedIds()).thenReturn(Arrays.asList(101L, 102L, 103L));
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
        when(service.listExpiredUnverifiedIds()).thenReturn(Arrays.asList(201L, 202L));
        when(service.autoVerifyExpiredOne(201L)).thenThrow(new RuntimeException("模拟单笔异常"));
        when(service.autoVerifyExpiredOne(202L)).thenReturn(true);

        task(service).run(); // 不向上抛:Quartz 只记本轮失败,后面的单必须继续收

        verify(service).autoVerifyExpiredOne(202L);
    }

    @Test
    void run_emptyScan_touchesNothing() {
        ICmsRegistrationService service = mock(ICmsRegistrationService.class);
        when(service.listExpiredUnverifiedIds()).thenReturn(Collections.emptyList());

        task(service).run();

        verify(service, never()).autoVerifyExpiredOne(org.mockito.ArgumentMatchers.anyLong());
    }


    @Test
    void run_firstTwoHundredFailuresStillReachOrder201() {
        java.util.List<Long> candidates = java.util.stream.LongStream.rangeClosed(1, 201)
                .boxed().collect(java.util.stream.Collectors.toList());
        java.util.List<Long> attempted = new java.util.ArrayList<>();
        java.util.List<Long> cursors = new java.util.ArrayList<>();
        RuntimeException permanentFailure = new IllegalStateException("persistent first-page failure");
        permanentFailure.setStackTrace(new StackTraceElement[0]);
        ICmsRegistrationService service = mock(ICmsRegistrationService.class, invocation -> {
            String method = invocation.getMethod().getName();
            // Both the legacy scan and its cursor successor model the same eligible rows and 200-row cap.
            if ("listExpiredUnverifiedIds".equals(method) || "listExpiredUnverifiedIdsAfter".equals(method)) {
                long after = invocation.getArguments().length == 0 ? 0L
                        : ((Number) invocation.getArgument(0)).longValue();
                cursors.add(after);
                assertTrue(cursors.size() <= 4, "scan must advance instead of retrying the same page");
                return candidates.stream().filter(id -> id > after).limit(200)
                        .collect(java.util.stream.Collectors.toList());
            }
            if ("autoVerifyExpiredOne".equals(method)) {
                long id = ((Number) invocation.getArgument(0)).longValue();
                attempted.add(id);
                if (id <= 200) throw permanentFailure;
                return true;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        task(service).run();

        assertTrue(attempted.contains(201L), "first-page failures must not starve order 201");
        assertEquals(candidates, attempted, "each candidate must be attempted once in ascending order");
        assertEquals(Arrays.asList(0L, 200L, 201L), cursors, "advance past failed IDs and terminate on empty page");
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
        // PR #8 回归:探店日票(purchase_kind=3)不进自动收口,扫描与条件置位两处都必须排除
        assertTrue(scan.contains("r.purchase_kind is null or r.purchase_kind &lt;&gt; 3"), "扫描必须排除探店日票");
        assertTrue(cas.contains("purchase_kind is null or purchase_kind &lt;&gt; 3"), "置位必须排除探店日票");
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
