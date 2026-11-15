package com.chengyinhub.business.signupdevice;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.service.AppLoginService;
import com.chengyinhub.web.controller.api.ApiLoginController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SEC-11: the sign-up points go to the first account created from a device, once. */
class SignupDevicePointsTest {

    private static final String IFV = "6F9619FF-8B86-D011-B42D-00C04FC964FF";

    /** JdbcTemplate double with a real primary key on device_hash. */
    static JdbcTemplate deviceTable(Set<String> rows) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO cy_signup_device"), (Object) any(), (Object) any()))
                .thenAnswer(inv -> rows.add((String) inv.getArgument(1)) ? 1 : 0);
        when(jdbc.update(startsWith("DELETE FROM cy_signup_device"), (Object) any()))
                .thenAnswer(inv -> rows.remove((String) inv.getArgument(1)) ? 1 : 0);
        return jdbc;
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void firstAccountOnADeviceGetsPointsAndLaterOnesDoNot() {
        SignupDevicePoints gate = new SignupDevicePoints(deviceTable(new HashSet<>()), false);
        assertTrue(gate.claim(IFV, "apple").pointsAllowed());
        assertFalse(gate.claim(IFV, "phone").pointsAllowed(), "same device, other channel");
        assertFalse(gate.claim(IFV.toLowerCase(), "wechat").pointsAllowed(), "UUID case does not make a new device");
        assertTrue(gate.claim("7A1B2C3D-0000-4000-8000-000000000001", "apple").pointsAllowed(), "another device");
    }

    @Test
    void missingOrMalformedHeaderKeepsTodaysRuleUnlessTheFlagRequiresADevice() {
        JdbcTemplate jdbc = deviceTable(new HashSet<>());
        SignupDevicePoints today = new SignupDevicePoints(jdbc, false);
        for (String header : new String[]{null, "", "   ", "not a device", "x'; DROP TABLE ums_member; --"}) {
            SignupDevicePoints.Claim claim = today.claim(header, "phone");
            assertTrue(claim.pointsAllowed(), String.valueOf(header));
            assertNull(claim.hash());
        }
        verifyNoInteractions(jdbc);
        SignupDevicePoints strict = new SignupDevicePoints(jdbc, true);
        assertFalse(strict.claim(null, "phone").pointsAllowed());
        assertFalse(strict.claim("not a device", "phone").pointsAllowed());
    }

    @Test
    void appAttestKeyIdIsAcceptedAndOnlyAHashIsStored() {
        Set<String> rows = new HashSet<>();
        SignupDevicePoints gate = new SignupDevicePoints(deviceTable(rows), false);
        String keyId = "q1W2e3R4t5Y6u7I8o9P0a1S2d3F4g5H6j7K8l9Z0x1c=";
        assertTrue(gate.claim(keyId, "apple").pointsAllowed());
        assertFalse(gate.claim(keyId, "apple").pointsAllowed());
        assertEquals(1, rows.size());
        String stored = rows.iterator().next();
        assertTrue(stored.matches("[0-9a-f]{64}"));
        assertFalse(stored.contains(keyId));
    }

    @Test
    void databaseErrorNeverBlocksRegistrationAndKeepsTodaysRule() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), (Object) any(), (Object) any())).thenThrow(new DataAccessResourceFailureException("down"));
        assertTrue(new SignupDevicePoints(jdbc, false).claim(IFV, "apple").pointsAllowed());
    }

    @Test
    void failedMemberInsertGivesTheClaimBack() {
        Set<String> rows = new HashSet<>();
        SignupDevicePoints gate = new SignupDevicePoints(deviceTable(rows), false);
        SignupDevicePoints.Claim claim = gate.claim(IFV, "apple");
        gate.release(claim);
        assertTrue(rows.isEmpty());
        assertTrue(gate.claim(IFV, "apple").pointsAllowed());
    }

    // ---- controller: the app phone sign-up ----

    static class PhoneController extends ApiLoginController {
        @Override
        protected boolean checkMscode(String phone, String code, jakarta.servlet.http.HttpServletRequest request) {
            return true;
        }
    }

    private final List<UmsMember> inserted = new ArrayList<>();
    private final IUmsMemberPointsDetailService pointsDetail = mock(IUmsMemberPointsDetailService.class);

    private ApiLoginController controller(SignupDevicePoints gate) {
        PhoneController controller = new PhoneController();
        IUmsMemberService members = mock(IUmsMemberService.class);
        when(members.selectUmsMemberList(any())).thenAnswer(inv -> {
            UmsMember q = inv.getArgument(0);
            List<UmsMember> found = new ArrayList<>();
            for (UmsMember m : inserted) if (q.getPhone() != null && q.getPhone().equals(m.getPhone())) found.add(m);
            return found;
        });
        when(members.insertUmsMember(any())).thenAnswer(inv -> {
            UmsMember m = inv.getArgument(0);
            m.setId((long) (inserted.size() + 1));
            inserted.add(m);
            return 1;
        });
        CmsPointsSetting rule = new CmsPointsSetting();
        rule.setStatus(1L);
        rule.setValue(new BigDecimal("50"));
        ICmsPointsSettingService settings = mock(ICmsPointsSettingService.class);
        when(settings.selectCmsPointsSettingById(1L)).thenReturn(rule);
        AppLoginService login = mock(AppLoginService.class);
        when(login.loginByMember(any(), anyString(), anyString(), any())).thenReturn("session-token");
        ReflectionTestUtils.setField(controller, "umsMemberService", members);
        ReflectionTestUtils.setField(controller, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(controller, "umsMemberPointsDetailService", pointsDetail);
        ReflectionTestUtils.setField(controller, "appLoginService", login);
        ReflectionTestUtils.setField(controller, "signupDevicePoints", gate);
        return controller;
    }

    private AjaxResult signUp(ApiLoginController controller, String phone, String device) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/login/phone");
        if (device != null) request.addHeader(SignupDevicePoints.HEADER, device);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return controller.loginPhone(phone, "123456", request);
    }

    @Test
    void secondAccountOnTheSameDeviceGetsNoSignUpPoints() {
        Set<String> rows = new HashSet<>();
        ApiLoginController controller = controller(new SignupDevicePoints(deviceTable(rows), false));
        assertEquals(200, signUp(controller, "13800000001", IFV).get("code"));
        assertEquals(200, signUp(controller, "13800000002", IFV).get("code"));
        assertEquals(200, signUp(controller, "13800000003", null).get("code"));
        assertEquals(50L, inserted.get(0).getPoint());
        assertEquals(0L, inserted.get(1).getPoint(), "second account on the device: created, no points");
        assertEquals(50L, inserted.get(2).getPoint(), "no header: today's rule");
        verify(pointsDetail, times(2)).insertUmsMemberPointsDetail(any());
        // logging in again to an existing account never claims anything
        assertEquals(200, signUp(controller, "13800000001", IFV).get("code"));
        assertEquals(3, inserted.size());
        assertEquals(1, rows.size());
    }

    @Test
    void withoutTheGateBeanSignUpIsExactlyToday() {
        ApiLoginController controller = controller(null);
        signUp(controller, "13800000001", IFV);
        signUp(controller, "13800000002", IFV);
        assertEquals(50L, inserted.get(0).getPoint());
        assertEquals(50L, inserted.get(1).getPoint());
    }

    @Test
    void miniProgramPathsDoNotUseTheDeviceGate() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/chengyinhub/web/controller/api/ApiLoginController.java"),
                StandardCharsets.UTF_8);
        String logincode = source.substring(source.indexOf("public AjaxResult logincode("), source.indexOf("String wechatSessionBaseUrl()"));
        assertFalse(logincode.contains("SignupDevice"), "mini-program login stays as today");
        int register = source.indexOf("public AjaxResult register(");
        String registerBody = source.substring(register, source.indexOf("@PostMapping", register));
        assertFalse(registerBody.contains("SignupDevice"));
        assertEquals(3, source.split("claimSignupDevice\\(", -1).length - 2, "phone, wechat app and apple only");
    }
}
