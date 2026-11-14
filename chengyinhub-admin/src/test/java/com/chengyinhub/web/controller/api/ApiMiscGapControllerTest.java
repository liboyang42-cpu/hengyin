package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.begapmisc.MiscMoneySwitch;
import com.chengyinhub.business.begapmisc.RegistrationOriginalEventService;
import com.chengyinhub.business.domain.dto.WithdrawalDTO;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** be-gap-misc single-row short names: aliases hand the app's fields to the long-path methods; money writes check the switch first. */
class ApiMiscGapControllerTest {
    ApiLoginController login;
    ApiAiNpcController npc;
    ApiImController im;
    ApiRegistrationController registration;
    ApiWithdrawalController withdrawal;
    JdbcTemplate jdbc;
    Validator validator;

    @BeforeEach
    void setUp() {
        login = mock(ApiLoginController.class);
        npc = mock(ApiAiNpcController.class);
        im = mock(ApiImController.class);
        registration = mock(ApiRegistrationController.class);
        withdrawal = mock(ApiWithdrawalController.class);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:misc_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE cms_registration (id BIGINT PRIMARY KEY, member_id BIGINT)");
        jdbc.execute("CREATE TABLE business_audit_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, request_id VARCHAR(64), target_type VARCHAR(32),"
                + " target_id BIGINT, action VARCHAR(64), after_json VARCHAR(2000), create_time TIMESTAMP)");
        jdbc.execute("INSERT INTO cms_registration VALUES (74, 9009), (75, 9004)");
        jdbc.execute("INSERT INTO business_audit_event (request_id, target_type, target_id, action, after_json, create_time) VALUES"
                + " ('rq-1', 'ORDER', 74, 'ORDER_CREATED', '{\"state\":1}', '2026-11-13 10:00:00'),"
                + " ('rq-2', 'ORDER', 74, 'ORDER_PAID', '{\"state\":2}', '2026-11-13 10:05:00'),"
                + " ('rq-3', 'ORDER', 75, 'ORDER_CREATED', '{}', '2026-11-13 10:00:00'),"
                + " ('rq-4', 'MERCHANT', 74, 'OTHER', '{}', '2026-11-13 10:00:00')");
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    ApiMiscGapController controller(boolean payments, boolean payout) {
        return new ApiMiscGapController(login, npc, im, registration, withdrawal, new RegistrationOriginalEventService(jdbc),
                new MiscMoneySwitch(payments, payout), validator) {
            @Override public Long getAppUserId() { return 9009L; }
        };
    }

    static HttpServletRequest jsonRequest(String body) {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/x");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }

    @Test
    void userSmsSendIsTheAnonymousSmsSendWithFormOrJsonPhone() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(login.smsSend(eq("13900000009"), any())).thenReturn(AjaxResult.success("验证码已发送"));
        assertEquals(200, controller(false, false).userSmsSend("13900000009", request).get(AjaxResult.CODE_TAG));
        HttpServletRequest json = jsonRequest("{\"phone\":\"13900000009\"}");
        controller(false, false).userSmsSend(null, json);
        verify(login).smsSend("13900000009", request);
        verify(login).smsSend("13900000009", json);
    }

    @Test
    void npcChatDeleteIsTheChatDataDeleteWithTheOptionalRequestId() {
        Map<String, String> body = Map.of("requestId", "npc-del-1");
        when(npc.deleteChatData(body)).thenReturn(AjaxResult.success(Map.of("deleted", 3)));
        assertEquals(Map.of("deleted", 3), controller(false, false).npcChatDelete(body).get(AjaxResult.DATA_TAG));
        controller(false, false).npcChatDelete(null);
        verify(npc).deleteChatData((Map<String, String>) null);
    }

    @Test
    void ugcBlockIsTheImBlockForFormOrJsonTargets() {
        when(im.block(any(), any())).thenReturn(AjaxResult.success("已拉黑"));
        controller(false, false).ugcBlock("9004", null, mock(HttpServletRequest.class));
        verify(im).block("9004", null);
        controller(false, false).ugcBlock(null, null, jsonRequest("{\"targetMemberId\":9005}"));
        verify(im).block("9005", null);
        controller(false, false).ugcBlock(null, null, jsonRequest("{\"peer_id\":\"9006\"}"));
        verify(im).block(null, "9006");
    }

    @Test
    void originalEventReturnsOnlyTheOwnersOrderAuditRowsOldestFirst() {
        AjaxResult mine = controller(false, false).originalEvent("74");
        assertEquals(200, mine.get(AjaxResult.CODE_TAG));
        List<?> rows = (List<?>) mine.get(AjaxResult.DATA_TAG);
        assertEquals(2, rows.size());
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertEquals("ORDER", first.get("targetType"));
        assertEquals(74L, first.get("targetId"));
        assertEquals("ORDER_CREATED", first.get("action"));
        assertEquals("rq-1", first.get("requestId"));
        assertEquals(404, controller(false, false).originalEvent("75").get(AjaxResult.CODE_TAG), "another member's order");
        assertEquals(404, controller(false, false).originalEvent("999").get(AjaxResult.CODE_TAG));
        assertEquals(400, controller(false, false).originalEvent("0x1").get(AjaxResult.CODE_TAG));
        jdbc.execute("INSERT INTO cms_registration VALUES (76, 9009)");
        assertEquals(List.of(), controller(false, false).originalEvent("76").get(AjaxResult.DATA_TAG), "no audit row, nothing invented");
    }

    @Test
    void payCreateIsFeatureClosedUntilThePaymentsSwitchIsOn() {
        AjaxResult closed = controller(false, true).payCreate("74", jsonRequest("{}"));
        assertEquals(403, closed.get(AjaxResult.CODE_TAG));
        assertEquals("FEATURE_CLOSED", closed.get("errorCode"));
        verifyNoInteractions(registration);
        when(registration.payApp("74")).thenReturn(AjaxResult.success("ok", Map.of("prepayId", "wx-test")));
        assertEquals(200, controller(true, false).payCreate(null, jsonRequest("{\"id\":74}")).get(AjaxResult.CODE_TAG));
        assertEquals("PAY_ORDER_REQUIRED", controller(true, false).payCreate("abc", null).get(AjaxResult.MSG_TAG));
        verify(registration, times(1)).payApp("74");
    }

    @Test
    void assetWithdrawNeedsBothSwitchesAndTheBeanConstraints() {
        WithdrawalDTO dto = new WithdrawalDTO();
        assertEquals("FEATURE_CLOSED", controller(true, false).assetWithdraw(dto).get("errorCode"));
        assertEquals("FEATURE_CLOSED", controller(false, true).assetWithdraw(dto).get("errorCode"));
        assertEquals(400, controller(true, true).assetWithdraw(dto).get(AjaxResult.CODE_TAG), "constraints run");
        assertEquals(400, controller(true, true).assetWithdraw(null).get(AjaxResult.CODE_TAG));
        verifyNoInteractions(withdrawal);
        dto.setRealname("张三");
        dto.setBankName("TEST 银行");
        dto.setBankAccount("6222000000000000");
        dto.setMobilephone("13900000009");
        dto.setWithdrawalAmount(new BigDecimal("1.00"));
        dto.setRequestId("misc-wd-1");
        dto.setChallengeId(55L);
        when(withdrawal.request(dto)).thenReturn(AjaxResult.success("提现申请成功，请等待审核", 1L));
        assertEquals(200, controller(true, true).assetWithdraw(dto).get(AjaxResult.CODE_TAG));
    }
}
