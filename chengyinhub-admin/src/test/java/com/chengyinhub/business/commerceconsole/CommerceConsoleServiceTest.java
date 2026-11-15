package com.chengyinhub.business.commerceconsole;

import com.chengyinhub.business.commerceconsole.mapper.CommerceConsoleMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** T5 MON-16: commerce console reads and the two writes that only reduce selling. */
class CommerceConsoleServiceTest {
    CommerceConsoleMapper mapper = mock(CommerceConsoleMapper.class);
    OpsConsoleAudit audit = mock(OpsConsoleAudit.class);
    CommerceConsoleService service = new CommerceConsoleService(mapper, audit);

    @Test
    void limitsAndFiltersAreBounded() {
        service.products(null, null, 5000);
        verify(mapper).selectProducts(null, null, CommerceConsoleService.MAX_LIMIT);
        service.duesPlans(3L, "active", null);
        verify(mapper).selectDuesPlans(3L, "ACTIVE", 50);
        assertThrows(ServiceException.class, () -> service.products(9, null, null));
        assertThrows(ServiceException.class, () -> service.duesInvoices(null, "REFUNDED", null));
    }

    @Test
    void stopSaleNeedsAReasonAndAudits() {
        assertThrows(ServiceException.class, () -> service.stopProductSale(1L, 8L, " "));
        verifyNoInteractions(audit);
        when(mapper.selectProductForUpdate(8L)).thenReturn(Map.of("status", 1), Map.of("status", 2));
        when(mapper.stopProductSale(8L)).thenReturn(1);
        assertEquals(2, service.stopProductSale(1L, 8L, "price error").get("status"));
        verify(audit).record(eq(1L), eq("MARKETING_PRODUCT_STOP_SALE"), eq("MARKETING_PRODUCT"), eq(8L),
                any(), any(), eq("price error"));
    }

    @Test
    void onlyOnSaleProductsAndActivePlansChange() {
        when(mapper.selectProductForUpdate(8L)).thenReturn(Map.of("status", 0));
        when(mapper.stopProductSale(8L)).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.stopProductSale(1L, 8L, "x"));
        when(mapper.selectDuesPlanForUpdate(4L)).thenReturn(Map.of("status", "INACTIVE"));
        when(mapper.deactivateDuesPlan(4L, "ops")).thenReturn(0);
        assertThrows(ServiceException.class, () -> service.deactivateDuesPlan(1L, "ops", 4L, "x"));
        verifyNoInteractions(audit);
    }

    @Test
    void writesOnlyReduceSellingAndReadsSkipSensitiveColumns() throws Exception {
        for (Method m : CommerceConsoleMapper.class.getDeclaredMethods()) {
            Update u = m.getAnnotation(Update.class);
            if (u != null) {
                String sql = String.join(" ", u.value());
                assertTrue(sql.contains("status = 2") || sql.contains("status = 'INACTIVE'"), m.getName());
                assertFalse(sql.contains("price"), m.getName() + " must not change a price");
            }
            Select s = m.getAnnotation(Select.class);
            if (s != null) {
                String sql = String.join(" ", s.value()).toLowerCase();
                for (String col : new String[] {"snapshot_json", "quote_sign", "payment_fact", "benefits", "claim_code", "select *"}) {
                    assertFalse(sql.contains(col), m.getName() + " reads " + col);
                }
            }
        }
    }
}
