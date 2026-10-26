package com.chengyinhub.business.service.compliance;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.mapper.UserInvoiceMapper;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.impl.UserInvoiceServiceImpl;
import com.chengyinhub.business.service.invoice.InvoiceIssuer;
import com.chengyinhub.business.service.invoice.ManualInvoiceIssuer;
import com.chengyinhub.business.service.invoice.UserInvoiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** DOC2 F7 用户发票：抬头加密/掩码、归属、一单不两开、幂等重放。 */
class UserInvoiceServiceTest {
    private static final long ME = 7L;
    private static final String TAX = "91310000MA1FL0XX3K";

    private UserInvoiceMapper mapper;
    private PiiCryptoService crypto;
    private InvoiceIssuer issuer;
    private UserInvoiceServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(UserInvoiceMapper.class);
        crypto = mock(PiiCryptoService.class);
        when(crypto.isEnabled()).thenReturn(true);
        issuer = spy(new ManualInvoiceIssuer());
        service = new UserInvoiceServiceImpl(mapper, crypto, issuer);
    }

    // ── 抬头 ─────────────────────────────────────────────────────────

    @Test
    void companyTitleNeedsAValidTaxNoAndPersonalTitleRefusesOne() {
        assertCode(UserInvoiceException.TAX_NO_INVALID, () -> service.saveTitle(ME, title("COMPANY", "某公司", null)));
        assertCode(UserInvoiceException.TAX_NO_INVALID, () -> service.saveTitle(ME, title("COMPANY", "某公司", "12-34")));
        assertCode(UserInvoiceException.TAX_NO_INVALID, () -> service.saveTitle(ME, title("PERSONAL", "张三", TAX)));
        assertCode(UserInvoiceException.TITLE_TYPE_INVALID, () -> service.saveTitle(ME, title("VAT", "某公司", TAX)));
        assertCode(UserInvoiceException.TITLE_NAME_INVALID, () -> service.saveTitle(ME, title("PERSONAL", "  ", null)));
        verify(mapper, never()).insertTitle(any());
    }

    @Test
    void titleIsNotWrittenWithoutTheEncryptionKey() {
        when(crypto.isEnabled()).thenReturn(false);
        assertCode(UserInvoiceException.CRYPTO_UNAVAILABLE, () -> service.saveTitle(ME, title("COMPANY", "某公司", TAX)));
        verify(mapper, never()).insertTitle(any());
    }

    @Test
    void savedTitleReadsBackWithTheTaxNoMaskedNeverInFull() {
        when(mapper.insertTitle(any())).thenAnswer(inv -> {
            ((UserInvoiceTitle) inv.getArgument(0)).setId(31L);
            return 1;
        });
        UserInvoiceTitle stored = title("COMPANY", "某公司", TAX);
        stored.setId(31L);
        stored.setIsDefault(0);
        when(mapper.selectTitle(ME, 31L)).thenReturn(stored);

        Map<String, Object> view = service.saveTitle(ME, title("COMPANY", "某公司", TAX.toLowerCase()));

        ArgumentCaptor<UserInvoiceTitle> written = ArgumentCaptor.forClass(UserInvoiceTitle.class);
        verify(mapper).insertTitle(written.capture());
        assertEquals(ME, written.getValue().getMemberId(), "归属只取当前登录会员");
        assertEquals(TAX, written.getValue().getTaxNo(), "税号统一大写后落库（mapper 侧加密）");
        assertEquals("91******XX3K", view.get("taxNoMasked"));
        assertFalse(view.containsValue(TAX), "回读视图不得含完整税号");
        assertFalse(view.containsKey("taxNo"));
    }

    @Test
    void someoneElsesTitleCannotBeEditedOrDeleted() {
        UserInvoiceTitle input = title("PERSONAL", "张三", null);
        input.setId(99L);
        when(mapper.selectTitle(ME, 99L)).thenReturn(null);
        assertCode(UserInvoiceException.TITLE_NOT_FOUND, () -> service.saveTitle(ME, input));
        verify(mapper, never()).updateTitle(any());

        when(mapper.deleteTitle(ME, 99L)).thenReturn(0);
        assertCode(UserInvoiceException.TITLE_NOT_FOUND, () -> service.deleteTitle(ME, 99L));
    }

    @Test
    void titleCountIsCapped() {
        when(mapper.countTitles(ME)).thenReturn(20);
        assertCode(UserInvoiceException.TITLE_LIMIT, () -> service.saveTitle(ME, title("PERSONAL", "张三", null)));
    }

    // ── 申请 ─────────────────────────────────────────────────────────

    @Test
    void applicationMergesOwnedPaidOrdersAndSumsTheirCashAmounts() {
        stubTitle();
        when(mapper.selectInvoiceableRegistrationsForUpdate(ME, List.of(1L, 2L)))
                .thenReturn(List.of(order(1L, "10.50"), order(2L, "20.00")));
        when(mapper.insertApplication(any())).thenAnswer(inv -> {
            ((UserInvoiceApplication) inv.getArgument(0)).setId(500L);
            return 1;
        });

        Map<String, Object> view = service.apply(ME, "req-1", 31L, List.of(1L, 2L, 1L));

        ArgumentCaptor<UserInvoiceApplication> app = ArgumentCaptor.forClass(UserInvoiceApplication.class);
        verify(mapper).insertApplication(app.capture());
        assertEquals(new BigDecimal("30.50"), app.getValue().getTotalAmount());
        assertEquals(2, app.getValue().getOrderCount());
        assertEquals(ME, app.getValue().getMemberId());
        assertEquals(TAX, app.getValue().getTaxNo(), "申请单快照抬头（mapper 侧加密）");
        assertEquals("MANUAL", app.getValue().getProvider());
        assertEquals("SUBMITTED", app.getValue().getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserInvoiceOrderLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertOrderLines(lines.capture());
        assertEquals(2, lines.getValue().size());
        for (UserInvoiceOrderLine line : lines.getValue()) {
            assertEquals(500L, line.getApplicationId());
            assertEquals(ME, line.getMemberId());
            assertEquals("registration", line.getOrderSource());
        }
        verify(issuer).submit(any());
        assertEquals(false, view.get("replayed"));
        assertEquals("91******XX3K", view.get("taxNoMasked"));
        assertFalse(view.containsValue(TAX));
    }

    @Test
    void sameRequestIdReplaysTheSameApplicationWithoutWritingAgain() {
        UserInvoiceApplication existing = application(500L);
        when(mapper.selectApplicationByRequest(ME, "req-1")).thenReturn(existing);
        when(mapper.selectOrderLines(500L)).thenReturn(List.of(order(2L, "20.00"), order(1L, "10.50")));

        Map<String, Object> view = service.apply(ME, "req-1", 31L, List.of(1L, 2L));

        assertEquals(true, view.get("replayed"));
        assertEquals("500", view.get("id"));
        verify(mapper, never()).insertApplication(any());
        verify(mapper, never()).insertOrderLines(anyList());
        verify(issuer, never()).submit(any());
    }

    @Test
    void sameRequestIdWithADifferentOrderSetIsRejected() {
        when(mapper.selectApplicationByRequest(ME, "req-1")).thenReturn(application(500L));
        when(mapper.selectOrderLines(500L)).thenReturn(List.of(order(1L, "10.50")));

        assertCode(UserInvoiceException.REQUEST_REUSED, () -> service.apply(ME, "req-1", 31L, List.of(1L, 3L)));
        verify(mapper, never()).insertApplication(any());
    }

    @Test
    void anOrderThatIsAlreadyOccupiedCannotBeInvoicedAgain() {
        stubTitle();
        when(mapper.selectInvoiceableRegistrationsForUpdate(ME, List.of(1L, 2L))).thenReturn(List.of(order(2L, "20.00")));
        when(mapper.selectOccupiedOrderIds(ME, "registration", List.of(1L, 2L))).thenReturn(List.of(1L));

        assertCode(UserInvoiceException.ORDER_ALREADY_INVOICED, () -> service.apply(ME, "req-2", 31L, List.of(1L, 2L)));
        verify(mapper, never()).insertApplication(any());
    }

    @Test
    void someoneElsesOrUnpaidOrRefundedOrderIsNotEligible() {
        stubTitle();
        when(mapper.selectInvoiceableRegistrationsForUpdate(ME, List.of(8L))).thenReturn(List.of());
        when(mapper.selectOccupiedOrderIds(anyLong(), anyString(), anyList())).thenReturn(List.of());

        assertCode(UserInvoiceException.ORDER_NOT_ELIGIBLE, () -> service.apply(ME, "req-3", 31L, List.of(8L)));
        verify(mapper, never()).insertApplication(any());
    }

    @Test
    void concurrentDoubleInvoiceLosesOnTheUniqueKey() {
        stubTitle();
        when(mapper.selectInvoiceableRegistrationsForUpdate(ME, List.of(1L))).thenReturn(List.of(order(1L, "10.50")));
        when(mapper.insertOrderLines(anyList())).thenThrow(new DuplicateKeyException("uk_user_invoice_order_active"));

        assertCode(UserInvoiceException.ORDER_ALREADY_INVOICED, () -> service.apply(ME, "req-4", 31L, List.of(1L)));
        verify(issuer, never()).submit(any());
    }

    @Test
    void applicationNeedsOrdersAndATitleOfMyOwnAndTheKey() {
        assertCode(UserInvoiceException.ORDERS_REQUIRED, () -> service.apply(ME, "req-5", 31L, List.of()));
        assertCode(UserInvoiceException.ARGUMENT_INVALID, () -> service.apply(ME, "bad id!", 31L, List.of(1L)));
        Long[] many = new Long[51];
        for (int i = 0; i < many.length; i++) many[i] = (long) i + 1;
        assertCode(UserInvoiceException.ORDERS_TOO_MANY, () -> service.apply(ME, "req-6", 31L, List.of(many)));

        when(mapper.selectTitle(ME, 31L)).thenReturn(null);
        assertCode(UserInvoiceException.TITLE_NOT_FOUND, () -> service.apply(ME, "req-7", 31L, List.of(1L)));

        when(crypto.isEnabled()).thenReturn(false);
        assertCode(UserInvoiceException.CRYPTO_UNAVAILABLE, () -> service.apply(ME, "req-8", 31L, List.of(1L)));
        verify(mapper, never()).insertApplication(any());
    }

    // ── 撤回 / 人工处理 ───────────────────────────────────────────────

    @Test
    void cancelIsOwnerScopedAndReleasesTheOrders() {
        when(mapper.selectApplication(ME, 500L)).thenReturn(application(500L));
        when(mapper.cancelApplication(eq(ME), eq(500L), any())).thenReturn(1);

        service.cancel(ME, 500L);

        verify(mapper).releaseOrderLines(500L);

        when(mapper.selectApplication(ME, 600L)).thenReturn(null);
        assertCode(UserInvoiceException.NOT_FOUND, () -> service.cancel(ME, 600L));
    }

    @Test
    void anIssuedApplicationCannotBeCancelled() {
        when(mapper.selectApplication(ME, 500L)).thenReturn(application(500L));
        when(mapper.cancelApplication(eq(ME), eq(500L), any())).thenReturn(0);

        assertCode(UserInvoiceException.STATE_CONFLICT, () -> service.cancel(ME, 500L));
        verify(mapper, never()).releaseOrderLines(anyLong());
    }

    @Test
    void manualRejectReleasesOrdersButManualIssueKeepsThem() {
        when(mapper.markRejected(eq(500L), eq("抬头有误"), any())).thenReturn(1);
        service.markRejected(500L, "抬头有误");
        verify(mapper).releaseOrderLines(500L);

        when(mapper.markIssued(eq(501L), eq("24310000000012345678"), any())).thenReturn(1);
        service.markIssued(501L, "24310000000012345678");
        verify(mapper, never()).releaseOrderLines(501L);
    }

    @Test
    void manualIssuerNeverCallsOut() {
        InvoiceIssuer.Receipt receipt = new ManualInvoiceIssuer().submit(
                new InvoiceIssuer.Request("INV1", "PERSONAL", "张三", null, BigDecimal.ONE));
        assertFalse(receipt.accepted());
        assertNull(receipt.providerRef());
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private void stubTitle() {
        UserInvoiceTitle t = title("COMPANY", "某公司", TAX);
        t.setId(31L);
        t.setMemberId(ME);
        when(mapper.selectTitle(ME, 31L)).thenReturn(t);
    }

    private static UserInvoiceTitle title(String type, String name, String taxNo) {
        UserInvoiceTitle t = new UserInvoiceTitle();
        t.setTitleType(type);
        t.setTitleName(name);
        t.setTaxNo(taxNo);
        return t;
    }

    private static UserInvoiceOrderLine order(long id, String amount) {
        UserInvoiceOrderLine line = new UserInvoiceOrderLine();
        line.setOrderId(id);
        line.setOrderSource("registration");
        line.setMemberId(ME);
        line.setAmount(new BigDecimal(amount));
        return line;
    }

    private static UserInvoiceApplication application(long id) {
        UserInvoiceApplication app = new UserInvoiceApplication();
        app.setId(id);
        app.setMemberId(ME);
        app.setStatus("SUBMITTED");
        app.setTitleType("COMPANY");
        app.setTitleName("某公司");
        app.setTaxNo(TAX);
        return app;
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable call) {
        UserInvoiceException e = assertThrows(UserInvoiceException.class, call);
        assertEquals(code, e.getErrorCode(), e.getMessage());
    }
}
