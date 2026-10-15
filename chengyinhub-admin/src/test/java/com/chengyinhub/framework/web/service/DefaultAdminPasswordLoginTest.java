package com.chengyinhub.framework.web.service;

import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultAdminPasswordLoginTest {
    @BeforeEach void request() { RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest())); }
    @AfterEach void cleanupRequest() { RequestContextHolder.resetRequestAttributes(); }
    private SysLoginService service() {
        SysLoginService service = new SysLoginService();
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey("sys.login.blackIPList")).thenReturn("");
        ReflectionTestUtils.setField(service,"configService",config);return service;
    }
    @Test void knownBootstrapAdministratorPasswordsCannotAuthenticate() {
        for(String account:new String[]{"admin","ry","ADMIN"," Ry "})
            for(String password:new String[]{"admin123","ry123","123456","admin","ry"}) {
                ServiceException error=assertThrows(ServiceException.class,()->service().loginPreCheck(account,password));
                assertEquals(403,error.getCode());assertFalse(error.getMessage().contains(password));
            }
    }
    @Test void otherAccountsAndStrongAdministratorPasswordRetainExistingPrecheck() {
        assertDoesNotThrow(()->service().loginPreCheck("reviewer","123456"));
        assertDoesNotThrow(()->service().loginPreCheck("admin","Separate-Strong-4729"));
        assertDoesNotThrow(()->service().loginPreCheck("ry","Separate-Strong-4729"));
    }
}
