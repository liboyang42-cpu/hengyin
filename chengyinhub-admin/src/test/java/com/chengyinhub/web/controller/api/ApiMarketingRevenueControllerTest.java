package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocation;
import com.chengyinhub.business.domain.marketing.MarketingRevenueAllocationLine;
import com.chengyinhub.business.exception.MarketingRevenueException;
import com.chengyinhub.business.service.IMarketingRevenueAllocationService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 创作者收益接口契约：未登录如实 401；非参与人 403；平台分录不下发给个人。
 */
class ApiMarketingRevenueControllerTest {

    private static final long MEMBER = 9L;

    private IMarketingRevenueAllocationService service;
    private ApiMarketingRevenueController controller;

    @BeforeEach
    void setUp() {
        service = mock(IMarketingRevenueAllocationService.class);
        controller = spy(new ApiMarketingRevenueController(service));
    }

    @Test
    void unauthenticatedCallerGetsUnauthorizedAndNeverReachesService() {
        doThrow(new ServiceException("获取APP用户信息异常", HttpStatus.UNAUTHORIZED))
                .when(controller).getAppUserId();

        ServiceException mine = assertThrows(ServiceException.class,
                () -> controller.myAuthorAllocations());
        ServiceException order = assertThrows(ServiceException.class,
                () -> controller.orderAllocation(1, 555L));

        assertEquals(Integer.valueOf(HttpStatus.UNAUTHORIZED), mine.getCode());
        assertEquals(Integer.valueOf(HttpStatus.UNAUTHORIZED), order.getCode());
        verify(service, never()).listByAuthor(anyLong());
        verify(service, never()).getByOrder(anyInt(), anyLong());
    }

    @Test
    void missingPrincipalFailsInsteadOfBecomingAFakeIdentity() {
        org.mockito.Mockito.doReturn((Long) null).when(controller).getAppUserId();

        ServiceException error = assertThrows(ServiceException.class,
                () -> controller.myAuthorAllocations());
        assertEquals(Integer.valueOf(HttpStatus.UNAUTHORIZED), error.getCode());
    }

    @Test
    void nonParticipantCannotReadSomeoneElsesAllocation() {
        doReturn(MEMBER).when(controller).getAppUserId();
        MarketingRevenueAllocation row = allocation(88L, 1234L, 4321L);
        when(service.getByOrder(1, 555L)).thenReturn(row);
        MarketingRevenueAllocationLine authorLine = line(MarketingRevenueAllocationLine.ROLE_AUTHOR, 1234L, 4670L);
        when(service.linesOf(88L)).thenReturn(List.of(authorLine));

        MarketingRevenueException error = assertThrows(MarketingRevenueException.class,
                () -> controller.orderAllocation(1, 555L));
        assertEquals("NOT_PARTICIPANT", error.getReasonCode());
        assertEquals(403, error.getHttpStatus());
    }

    @Test
    void participantCanReadAndPlatformLineIsHidden() {
        doReturn(MEMBER).when(controller).getAppUserId();
        MarketingRevenueAllocation row = allocation(88L, MEMBER, null);
        when(service.getByOrder(1, 555L)).thenReturn(row);
        when(service.linesOf(88L)).thenReturn(List.of(
                line(MarketingRevenueAllocationLine.ROLE_AUTHOR, MEMBER, 4670L),
                line(MarketingRevenueAllocationLine.ROLE_REFERRER, MEMBER, 1868L),
                line(MarketingRevenueAllocationLine.ROLE_PLATFORM, null, 2802L)));

        AjaxResult result = controller.orderAllocation(1, 555L);

        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) result.get("data");
        assertNotNull(view);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) view.get("lines");
        assertEquals(2, lines.size(), "平台分录不下发给个人");
        for (Map<String, Object> item : lines) {
            assertFalse(MarketingRevenueAllocationLine.ROLE_PLATFORM.equals(item.get("role")));
        }
        assertEquals("accrued", view.get("statusText"));
    }

    @Test
    void myAuthorAllocationsExcludesOtherRecipientsAndUnattributedLines() {
        doReturn(MEMBER).when(controller).getAppUserId();
        when(service.listByAuthor(MEMBER)).thenReturn(List.of(allocation(88L, MEMBER, 9999L)));
        when(service.linesOf(88L)).thenReturn(List.of(
                line(MarketingRevenueAllocationLine.ROLE_AUTHOR, MEMBER, 4670L),
                line(MarketingRevenueAllocationLine.ROLE_REFERRER, 9999L, 90000L),
                line(MarketingRevenueAllocationLine.ROLE_AUTHOR, null, 1234L),
                line(MarketingRevenueAllocationLine.ROLE_PLATFORM, MEMBER, 2802L)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) controller.myAuthorAllocations().get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) rows.get(0).get("lines");
        assertEquals(1, lines.size(), "其他收款人和未知收款人不能冒充本人收入");
        assertEquals(MarketingRevenueAllocationLine.ROLE_AUTHOR, lines.get(0).get("role"));
        assertEquals(4670L, lines.get(0).get("amountFen"));
    }

    @Test
    void sharerOrderSeesOnlyTheSharersOwnAllocationLine() {
        doReturn(MEMBER).when(controller).getAppUserId();
        when(service.getByOrder(1, 555L)).thenReturn(allocation(88L, 9999L, MEMBER));
        when(service.linesOf(88L)).thenReturn(List.of(
                line(MarketingRevenueAllocationLine.ROLE_AUTHOR, 9999L, 4670L),
                line(MarketingRevenueAllocationLine.ROLE_REFERRER, MEMBER, 1868L),
                line(MarketingRevenueAllocationLine.ROLE_PLATFORM, null, 2802L)));

        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) controller.orderAllocation(1, 555L).get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) view.get("lines");
        assertEquals(1, lines.size());
        assertEquals(MarketingRevenueAllocationLine.ROLE_REFERRER, lines.get(0).get("role"));
        assertEquals(1868L, lines.get(0).get("amountFen"));
    }

    @Test
    void missingAllocationReturnsEmptyDataNotAnError() {
        doReturn(MEMBER).when(controller).getAppUserId();
        when(service.getByOrder(1, 999L)).thenReturn(null);

        AjaxResult result = controller.orderAllocation(1, 999L);

        assertEquals(Integer.valueOf(200), result.get("code"));
        assertNull(result.get("data"));
    }

    @Test
    void myAuthorAllocationsProjectsOwnRows() {
        doReturn(MEMBER).when(controller).getAppUserId();
        when(service.listByAuthor(MEMBER)).thenReturn(List.of(allocation(88L, MEMBER, null)));
        when(service.linesOf(88L)).thenReturn(List.of(
                line(MarketingRevenueAllocationLine.ROLE_AUTHOR, MEMBER, 4670L)));

        AjaxResult result = controller.myAuthorAllocations();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("data");
        assertEquals(1, rows.size());
        assertEquals(88L, rows.get(0).get("allocationId"));
        assertTrue(rows.get(0).containsKey("settleableAt"));
    }

    @Test
    void rejectionIsReturnedWithStableReasonCode() {
        AjaxResult result = controller.handleRevenueRejection(
                MarketingRevenueException.conflict("ACCRUAL_CONFLICT", "该订单已按不同参数应计"));

        assertEquals(Integer.valueOf(409), result.get("code"));
        assertEquals("ACCRUAL_CONFLICT", result.get("reasonCode"));
    }

    private MarketingRevenueAllocation allocation(long id, Long author, Long sharer) {
        MarketingRevenueAllocation row = new MarketingRevenueAllocation();
        row.setId(id);
        row.setOrderType(1);
        row.setOrderId(555L);
        row.setScenario(1);
        row.setCurrency("CNY");
        row.setStatus(MarketingRevenueAllocation.STATUS_ACCRUED);
        row.setAuthorMemberId(author);
        row.setSharerMemberId(sharer);
        row.setReversedFen(0L);
        row.setRuleVersion(2);
        return row;
    }

    private MarketingRevenueAllocationLine line(String role, Long recipient, long amountFen) {
        MarketingRevenueAllocationLine line = new MarketingRevenueAllocationLine();
        line.setEntryType(MarketingRevenueAllocationLine.ENTRY_ACCRUE);
        line.setLineRole(role);
        line.setRecipientMemberId(recipient);
        line.setAmountFen(amountFen);
        line.setRate(new BigDecimal("0.50"));
        line.setSourceFact("registration:555");
        return line;
    }
}
