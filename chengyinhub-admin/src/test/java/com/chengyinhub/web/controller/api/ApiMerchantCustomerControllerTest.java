package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.dto.MerchantCustomerQuery;
import com.chengyinhub.business.service.IProjectPlayerReadService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户名册接口的 **controller 层**契约。
 *
 * ⚠️ 这里只该测 controller 真正负责的两件事:①商家维度锁死在登录态;②把查询参数
 * 原样交给 service、把结果原样交回去。筛选/分页/聚合都在
 * ProjectPlayerReadService 里(AGENTS.md:「Controller 薄、Service 写业务」),
 * 那些行为由 MerchantCustomerRosterTest 覆盖 —— 在这里 mock 掉 service 再断言
 * 分页结果,测的是一个已经不存在的实现。
 */
class ApiMerchantCustomerControllerTest {

    private static final long PRINCIPAL_MEMBER_ID = 9001L;
    private static final long OWNER_MEMBER_ID = 9001L;
    private static final long MERCHANT_ROW_ID = 11L;

    private static Map<String, Object> roster(List<Map<String, Object>> rows) {
        Map<String, Object> out = new HashMap<>();
        out.put("rows", rows);
        out.put("total", rows.size());
        out.put("summary", new HashMap<String, Object>());
        out.put("segmentCounts", new HashMap<String, Object>());
        return out;
    }

    private ApiMerchantController controllerWith(IProjectPlayerReadService service) {
        ApiMerchantController controller = new LoggedInMerchantController();
        ReflectionTestUtils.setField(controller, "projectPlayerReadService", service);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.CRM_READ))
                .thenReturn(new MerchantAccessContext(PRINCIPAL_MEMBER_ID, MERCHANT_ROW_ID,
                        OWNER_MEMBER_ID, MerchantRoleCode.MERCHANT_OWNER));
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);
        return controller;
    }

    @Test
    void rosterIsAlwaysScopedToLoggedInMerchant() {
        IProjectPlayerReadService service = mock(IProjectPlayerReadService.class);
        when(service.listMerchantCustomers(eq(OWNER_MEMBER_ID), any(), eq(true)))
                .thenReturn(roster(new ArrayList<>()));

        AjaxResult result = controllerWith(service).customers(new MerchantCustomerQuery());

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        // 商家维度只能来自登录态。这条链路带联系方式,一旦能被请求体顶掉,
        // 泄的就不是「自己客户」而是「别家客户」。
        verify(service).listMerchantCustomers(eq(OWNER_MEMBER_ID), any(), eq(true));
        verify(service, never()).listMerchantCustomers(
                org.mockito.ArgumentMatchers.longThat(id -> id != OWNER_MEMBER_ID), any(), anyBoolean());
    }

    @Test
    void queryIsHandedToTheServiceUntouched() {
        IProjectPlayerReadService service = mock(IProjectPlayerReadService.class);
        when(service.listMerchantCustomers(eq(OWNER_MEMBER_ID), any(), eq(true)))
                .thenReturn(roster(new ArrayList<>()));

        MerchantCustomerQuery query = new MerchantCustomerQuery();
        query.setKeyword("  张三  ");
        query.setSegment("pending");
        query.setPageNum(3);
        controllerWith(service).customers(query);

        // controller 不许在这儿先 safeKeyword() 一道再传 —— 归一化口径必须只有一处,
        // 两边各归一各的迟早会漂。
        ArgumentCaptor<MerchantCustomerQuery> captor = ArgumentCaptor.forClass(MerchantCustomerQuery.class);
        verify(service).listMerchantCustomers(eq(OWNER_MEMBER_ID), captor.capture(), eq(true));
        assertSame(query, captor.getValue());
        assertEquals("张三", captor.getValue().safeKeyword());
        assertEquals("pending", captor.getValue().safeSegment());
    }

    @Test
    void nullBodyStillReachesTheServiceAsAUsableQuery() {
        IProjectPlayerReadService service = mock(IProjectPlayerReadService.class);
        when(service.listMerchantCustomers(eq(OWNER_MEMBER_ID), any(), eq(true)))
                .thenReturn(roster(new ArrayList<>()));

        // 请求体可以整个缺席(@RequestBody(required = false)),不能因此 NPE
        AjaxResult result = controllerWith(service).customers(null);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(service).listMerchantCustomers(eq(OWNER_MEMBER_ID), any(), eq(true));
    }

    @Test
    void serviceResultIsPassedThroughWithoutReshaping() {
        IProjectPlayerReadService service = mock(IProjectPlayerReadService.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("memberId", 91L);
        row.put("name", "张三");
        row.put("tier", "pending");
        rows.add(row);
        Map<String, Object> served = roster(rows);
        when(service.listMerchantCustomers(eq(OWNER_MEMBER_ID), any(), eq(true))).thenReturn(served);

        AjaxResult result = controllerWith(service).customers(new MerchantCustomerQuery());

        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        // 分段计数与 total 都得原样带出去;controller 少拷一个 key,前端的分段就全变 0
        assertSame(served, data);
        assertEquals(1, data.get("total"));
        assertEquals("pending", ((Map<?, ?>) ((List<?>) data.get("rows")).get(0)).get("tier"));
    }

    @Test
    void disabledMerchantCannotReachRosterService() {
        IProjectPlayerReadService service = mock(IProjectPlayerReadService.class);
        ApiMerchantController controller = controllerWith(service);
        IMerchantAccessService accessService = mock(IMerchantAccessService.class);
        when(accessService.require(PRINCIPAL_MEMBER_ID, MerchantPermission.CRM_READ))
                .thenThrow(new ServiceException("商家身份无效或权限不足", 403));
        ReflectionTestUtils.setField(controller, "merchantAccessService", accessService);

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.customers(new MerchantCustomerQuery()));

        assertEquals(403, error.getCode());
        verify(service, never()).listMerchantCustomers(any(), any(), anyBoolean());
    }

    private static class LoggedInMerchantController extends ApiMerchantController {
        @Override
        public Long getAppUserId() {
            return PRINCIPAL_MEMBER_ID;
        }
    }
}
