package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.guestwalk.GuestWalkPolicy;
import com.chengyinhub.business.guestwalk.GuestWalkService;
import com.chengyinhub.common.core.domain.AjaxResult;

class ApiRoamGuestWalkControllerTest {

    private static ApiRoamGuestWalkController controller(long member, GuestWalkService service) {
        ApiRoamGuestWalkController c = new ApiRoamGuestWalkController() {
            @Override
            Long uid() {
                return member;
            }
        };
        ReflectionTestUtils.setField(c, "guestWalkService", service);
        return c;
    }

    @Test
    void signedOutIsRefusedWithoutTouchingTheService() {
        GuestWalkService service = mock(GuestWalkService.class);
        AjaxResult r = controller(0L, service).save("{}");
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(service, never()).save(anyLong(), anyString(), anyLong());
    }

    @Test
    void successWrapsTheServiceData() {
        GuestWalkService service = mock(GuestWalkService.class);
        when(service.save(eq(7L), eq("body"), anyLong())).thenReturn(Map.of("xp", 12));
        AjaxResult r = controller(7L, service).save("body");
        assertEquals(200, r.get(AjaxResult.CODE_TAG));
        assertEquals(Map.of("xp", 12), r.get(AjaxResult.DATA_TAG));
    }

    @Test
    void refusalsCarryAStableReasonAndRetryability() {
        GuestWalkService service = mock(GuestWalkService.class);
        when(service.save(eq(7L), eq("bad"), anyLong())).thenThrow(new GuestWalkPolicy.Rejected("内容格式不正确"));
        when(service.save(eq(7L), eq("used"), anyLong())).thenThrow(
                new GuestWalkService.Refused(GuestWalkService.CODE_INSTALL_USED, "已经保存过了", false));
        when(service.save(eq(7L), eq("race"), anyLong())).thenThrow(new DuplicateKeyException("uk"));
        AjaxResult bad = controller(7L, service).save("bad");
        assertEquals("INVALID", bad.get("reason"));
        assertEquals(false, bad.get("retryable"));
        AjaxResult used = controller(7L, service).save("used");
        assertEquals(GuestWalkService.CODE_INSTALL_USED, used.get("reason"));
        AjaxResult race = controller(7L, service).save("race");
        assertEquals(true, race.get("retryable"));
    }
}
