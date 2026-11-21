package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.compliance.MemberAgeAttestationService;
import com.chengyinhub.common.core.domain.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1b SEC-14:年龄确认接口。 */
class ApiAgeGateControllerTest {

    private final MemberAgeAttestationService service = mock(MemberAgeAttestationService.class);

    private ApiAgeGateController controller(Long memberId) {
        return new ApiAgeGateController(service) {
            @Override
            Long currentMemberId() {
                return memberId;
            }
        };
    }

    @Test
    void under_18_answer_returns_plain_message_and_error_code() {
        when(service.confirm(eq(5L), eq(false), any(), any())).thenThrow(new MemberAgeAttestationService.UnderAgeException());
        Map<String, Object> body = new HashMap<>();
        body.put("adult", false);
        AjaxResult r = controller(5L).confirm(body, mock(HttpServletRequest.class));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        assertEquals(MemberAgeAttestationService.UNDER_AGE, r.get("errorCode"));
        assertEquals(MemberAgeAttestationService.UNDER_AGE_MESSAGE, r.get(AjaxResult.MSG_TAG));
    }

    @Test
    void missing_answer_is_not_treated_as_yes() {
        AjaxResult r = controller(5L).confirm(new HashMap<>(), mock(HttpServletRequest.class));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(service, never()).confirm(anyLong(), anyBoolean(), any(), any());
        Map<String, Object> body = new HashMap<>();
        body.put("adult", 1);
        controller(5L).confirm(body, mock(HttpServletRequest.class));
        verify(service, never()).confirm(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void signed_out_cannot_confirm() {
        Map<String, Object> body = new HashMap<>();
        body.put("adult", true);
        AjaxResult r = controller(null).confirm(body, mock(HttpServletRequest.class));
        assertEquals("请先登录", r.get(AjaxResult.MSG_TAG));
        verify(service, never()).confirm(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    void strict_boolean_accepts_only_true_or_false() {
        assertEquals(Boolean.TRUE, ApiAgeGateController.strictBoolean(true));
        assertEquals(Boolean.FALSE, ApiAgeGateController.strictBoolean("false"));
        assertNull(ApiAgeGateController.strictBoolean("yes"));
        assertNull(ApiAgeGateController.strictBoolean(1));
        assertNull(ApiAgeGateController.strictBoolean(null));
    }
}
