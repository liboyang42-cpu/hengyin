package com.chengyinhub.business.appgaps;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectStateRequestsTest {
    static Map<String, Object> row() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bizType", "template"); r.put("id", 12L); r.put("title", "夜游"); r.put("state", "running"); r.put("publishStatus", "online");
        return r;
    }

    @Test void commandMirrorsAppValidation() {
        String h = "a".repeat(64);
        assertNotNull(ProjectStateRequests.command(Map.of("originalPath", "api/template/updateLibraryStatus", "resourceId", 12, "baselineHash", h,
                "expectedOnline", true, "fields", Map.of("template_id", "12", "publish_status", "0"))));
        assertNull(ProjectStateRequests.command(Map.of("originalPath", "api/template/updateLibraryStatus", "resourceId", 12, "baselineHash", h,
                "expectedOnline", true, "fields", Map.of("template_id", "12", "publish_status", "1"))), "目标状态必须与 expectedOnline 相反");
        assertNull(ProjectStateRequests.command(Map.of("originalPath", "api/topic/delete", "resourceId", 12, "baselineHash", h,
                "expectedOnline", false, "fields", Map.of("id", "13"))));
        assertNotNull(ProjectStateRequests.command(Map.of("originalPath", "api/topic/update_user_status", "resourceId", 3, "baselineHash", h,
                "expectedOnline", false, "fields", Map.of("id", "3", "expectedUserStatus", "0"))));
    }

    @Test void contextUsesListRowAndStableBaseline() {
        ProjectStateRequests.Projects port = mock(ProjectStateRequests.Projects.class);
        when(port.row("template", 12L)).thenReturn(row());
        Map<String, Object> c = new ProjectStateRequests(mock(AppOriginalRequestLedger.class)).context(Map.of("kind", "template", "resourceId", 12), port);
        assertEquals("夜游", c.get("title")); assertEquals(true, c.get("online"));
        assertEquals(ProjectStateRequests.baseline(row()), c.get("baselineHash"));
        Map<String, Object> reordered = new TreeMap<>(row());
        assertEquals(ProjectStateRequests.baseline(row()), ProjectStateRequests.baseline(reordered), "摘要与键顺序无关");
    }

    @SuppressWarnings("unchecked")
    @Test void submitRefusesStaleBaselineAndMapsTemplateToggle() {
        AppOriginalRequestLedger ledger = mock(AppOriginalRequestLedger.class);
        when(ledger.run(anyString(), anyLong(), anyString(), anyString(), anyString(), any(Supplier.class))).thenAnswer(a -> {
            try { return new AppOriginalRequestLedger.Fact("COMMITTED", a.getArgument(2), a.getArgument(3), a.getArgument(4), ((Supplier<Map<String, Object>>) a.getArgument(5)).get(), null, null, false); }
            catch (com.chengyinhub.common.exception.ServiceException e) { return new AppOriginalRequestLedger.Fact("REJECTED", a.getArgument(2), a.getArgument(3), a.getArgument(4), null, e.getCode(), e.getMessage(), false); }
        });
        ProjectStateRequests.Projects port = mock(ProjectStateRequests.Projects.class);
        when(port.row("template", 12L)).thenReturn(row());
        when(port.write(eq("api/template/updateLibraryStatus"), anyMap())).thenReturn(AjaxResult.success("下架成功"));
        String good = "{\"baselineHash\":\"" + ProjectStateRequests.baseline(row()) + "\",\"expectedOnline\":true,\"fields\":{\"publish_status\":\"0\",\"template_id\":\"12\"},\"originalPath\":\"api/template/updateLibraryStatus\",\"resourceId\":12}";
        Map<String, Object> ok = new ProjectStateRequests(ledger).submit(9, AppOriginalEnvelopeTest.submit(ProjectStateRequests.PROTOCOL, good, 40), port);
        assertEquals("COMMITTED", ok.get("state")); assertEquals(true, ((Map<?, ?>) ok.get("originalResult")).get("applied"));
        verify(port).write("api/template/updateLibraryStatus", Map.of("template_id", "12", "status", "3"));
        String stale = good.replace(ProjectStateRequests.baseline(row()), "b".repeat(64));
        Map<String, Object> rejected = new ProjectStateRequests(ledger).submit(9, AppOriginalEnvelopeTest.submit(ProjectStateRequests.PROTOCOL, stale, 40), port);
        assertEquals("REJECTED", rejected.get("state")); assertEquals(409, ((Map<?, ?>) rejected.get("originalResult")).get("code"));
        verify(port, times(1)).write(anyString(), anyMap());
    }
}
