package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UserInvoiceApplication;
import com.chengyinhub.business.domain.UserInvoiceOrderLine;
import com.chengyinhub.business.domain.UserInvoiceTitle;
import com.chengyinhub.business.service.IUserInvoiceService;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
import com.chengyinhub.web.controller.api.ApiUserInvoiceController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserInvoiceWireContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void titleApplicationAndOrderProjectionsKeepFullLongStringsAndOriginalRequest() throws Exception {
        UserInvoiceTitle title = new UserInvoiceTitle(); title.setId(Long.MAX_VALUE);
        assertEquals(Long.toString(Long.MAX_VALUE), json.readTree(json.writeValueAsBytes(UserInvoiceServiceImpl.titleView(title))).get("id").textValue());
        UserInvoiceOrderLine order = new UserInvoiceOrderLine(); order.setOrderId(Long.MAX_VALUE);
        UserInvoiceApplication app = new UserInvoiceApplication(); app.setId(Long.MAX_VALUE);
        app.setRequestId("invoice-original-1"); app.setOrders(List.of(order));
        var view = json.readTree(json.writeValueAsBytes(UserInvoiceServiceImpl.applicationView(app)));
        assertEquals(Long.toString(Long.MAX_VALUE), view.get("id").textValue());
        assertEquals("invoice-original-1", view.get("requestId").textValue());
        assertEquals(Long.toString(Long.MAX_VALUE), view.get("orders").get(0).get("orderId").textValue());
    }

    @Test
    void eligibleOrderJsonKeepsFullLongString() throws Exception {
        UserInvoiceOrderLine order = new UserInvoiceOrderLine(); order.setOrderId(Long.MAX_VALUE);
        assertEquals(Long.toString(Long.MAX_VALUE), json.readTree(json.writeValueAsBytes(order)).get("orderId").textValue());
    }

    private MockMvc mvc(IUserInvoiceService service) {
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        when(switches.userInvoiceOn()).thenReturn(true);
        ApiUserInvoiceController controller = new ApiUserInvoiceController(service, switches) {
            @Override public Long getAppUserId() { return 42L; }
        };
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void httpApplyPassesCompleteLongIdsAndAuthenticatedPrincipal() throws Exception {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        mvc(service).perform(post("/api/invoice/apply").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("requestId", "wire-1", "titleId", Long.toString(Long.MAX_VALUE),
                        "orderIds", List.of(Long.toString(Long.MAX_VALUE)))))).andExpect(status().isOk());
        verify(service).apply(42L, "wire-1", Long.MAX_VALUE, List.of(Long.MAX_VALUE));
    }

    @Test
    void originalRequestHttpUsesTokenOwnerNotBodyOwner() throws Exception {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        when(service.applicationByRequest(42L, "original-request")).thenReturn(Map.of("id", Long.toString(Long.MAX_VALUE)));
        String result = mvc(service).perform(post("/api/invoice/request").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("requestId", "original-request", "memberId", 99L))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(Long.toString(Long.MAX_VALUE), json.readTree(result).get("data").get("id").textValue());
        verify(service).applicationByRequest(42L, "original-request");
        verify(service, never()).applicationByRequest(eq(99L), any());
    }

    @Test
    void originalRequestHttpPreservesFeatureAndLoginGuards() throws Exception {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        ComplianceFeatureSwitches switches = mock(ComplianceFeatureSwitches.class);
        ApiUserInvoiceController controller = new ApiUserInvoiceController(service, switches) {
            @Override public Long getAppUserId() { return null; }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String off = mvc.perform(post("/api/invoice/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"original\"}")).andReturn().getResponse().getContentAsString();
        assertEquals("INVOICE_FEATURE_OFF", json.readTree(off).get("errorCode").textValue());
        when(switches.userInvoiceOn()).thenReturn(true);
        String anonymous = mvc.perform(post("/api/invoice/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"original\"}")).andReturn().getResponse().getContentAsString();
        assertNotEquals(200, json.readTree(anonymous).get("code").intValue());
        verifyNoInteractions(service);
    }

    @Test
    void titleSaveRequestIsOptInAndBothReceiptRoutesUseTokenOwner() throws Exception {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        MockMvc mvc = mvc(service);
        Map<String, Object> body = Map.of("memberId", 99L, "requestId", "save-original", "titleType", "PERSONAL", "titleName", "Name");
        mvc.perform(post("/api/invoice/titles/save-request").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk());
        verify(service).saveTitleWithRequest(eq(42L), eq("save-original"), argThat(input -> "Name".equals(input.getTitleName())));
        verify(service, never()).saveTitle(anyLong(), any());
        mvc.perform(post("/api/invoice/titles/request").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("memberId", 99L, "requestId", "save-original")))).andExpect(status().isOk());
        verify(service).titleSaveReceipt(42L, "save-original");
        mvc.perform(post("/api/invoice/titles/save").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("titleType", "PERSONAL", "titleName", "Legacy")))).andExpect(status().isOk());
        verify(service).saveTitle(eq(42L), argThat(input -> "Legacy".equals(input.getTitleName())));
    }

    @Test
    void malformedTitleUpdateIdCannotBecomeAnIdempotentCreate() throws Exception {
        IUserInvoiceService service = mock(IUserInvoiceService.class);
        String response = mvc(service).perform(post("/api/invoice/titles/save-request").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("requestId", "invalid-target", "id", "9223372036854775808",
                        "titleType", "PERSONAL", "titleName", "Name"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals("INVOICE_ARGUMENT_INVALID", json.readTree(response).get("errorCode").textValue());
        verifyNoInteractions(service);
    }

    @Test
    void httpRejectsOverflowFractionExponentNegativeAndStructuredIdsBeforeServiceValidation() throws Exception {
        IUserInvoiceService service = mock(IUserInvoiceService.class); MockMvc mvc = mvc(service);
        List<Object> invalid = List.of("9223372036854775808", "-1", "0", "1e3", "1.5", 1.5, true, Map.of("id", 1));
        for (Object value : invalid) {
            mvc.perform(post("/api/invoice/detail").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("id", value)))).andExpect(status().isOk());
        }
        verify(service, times(invalid.size())).applicationDetail(42L, null);
    }
}
