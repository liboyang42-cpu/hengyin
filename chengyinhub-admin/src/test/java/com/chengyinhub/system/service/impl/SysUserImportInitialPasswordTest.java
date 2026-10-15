package com.chengyinhub.system.service.impl;

import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.mapper.SysUserMapper;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysDeptService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** F-L5S-09：导入的新后台账号不能落成 RuoYi 公开默认口令（种子里 sys.user.initPassword=123456）。 */
class SysUserImportInitialPasswordTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"123456", "admin123", "ADMIN123", " ry123 ", "password", "1234567"})
    void weakOrDefaultInitialPasswordsAreRejected(String password) {
        assertTrue(SysUserServiceImpl.isWeakInitialPassword(password));
    }

    @Test
    void strongInitialPasswordIsAccepted() {
        assertFalse(SysUserServiceImpl.isWeakInitialPassword("Fixture-Init-8204"));
    }

    @Test
    void importWithSeedDefaultInitialPasswordCreatesNoAccount() {
        SysUserServiceImpl service = new SysUserServiceImpl();
        SysUserMapper users = mock(SysUserMapper.class);
        ISysConfigService configs = mock(ISysConfigService.class);
        Validator validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(configs.selectConfigByKey("sys.user.initPassword")).thenReturn("123456");
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "configService", configs);
        ReflectionTestUtils.setField(service, "deptService", mock(ISysDeptService.class));
        ReflectionTestUtils.setField(service, "validator", validator);
        SysUser imported = new SysUser();
        imported.setUserName("fixture-imported");

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.importUser(Collections.singletonList(imported), false, "fixture-operator"));

        assertTrue(error.getMessage().contains("sys.user.initPassword"));
        assertFalse(error.getMessage().contains("123456"));
        verify(users, never()).insertUser(any());
    }
}
