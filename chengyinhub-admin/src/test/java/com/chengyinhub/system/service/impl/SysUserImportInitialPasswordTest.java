package com.chengyinhub.system.service.impl;

import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.system.mapper.SysUserMapper;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysDeptService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** F-L5S-09：仅 admin/ry 的新建导入拒绝弱初始密码，所有账号仍拒绝空密码。 */
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

    @ParameterizedTest
    @CsvSource({"admin,123456", "ry,123456", "' AdMiN ',ADMIN123", "' Ry ',short"})
    void protectedAccountImportWithWeakInitialPasswordCreatesNoAccount(String username, String password) {
        Fixture f = fixture(password);
        SysUser imported = user(username);

        ServiceException error = assertThrows(ServiceException.class,
                () -> f.service.importUser(Collections.singletonList(imported), false, "fixture-operator"));

        assertTrue(error.getMessage().contains("sys.user.initPassword"));
        assertFalse(error.getMessage().contains(password));
        verify(f.users, never()).insertUser(any());
        verify(f.validator).validate(imported);
        verify(f.depts).checkDeptDataScope(42L);
    }

    @Test
    void importWithSeedDefaultInitialPasswordForOrdinaryAccountIsAllowed() {
        Fixture f = fixture("123456");
        SysUser imported = user("fixture-imported");

        String result = assertDoesNotThrow(
                () -> f.service.importUser(Collections.singletonList(imported), false, "fixture-operator"));

        assertTrue(result.contains("导入成功"));
        verify(f.users).insertUser(imported);
        assertTrue(SecurityUtils.matchesPassword("123456", imported.getPassword()));
    }

    @ParameterizedTest
    @CsvSource({"fixture-imported,password", "fixture-imported,short",
            "admin-helper,123456", "ry-reviewer,123456"})
    void ordinaryAccountImportDoesNotApplyProtectedAccountPasswordRule(String username, String password) {
        Fixture f = fixture(password);
        SysUser imported = user(username);

        String result = assertDoesNotThrow(
                () -> f.service.importUser(Collections.singletonList(imported), false, "fixture-operator"));

        assertTrue(result.contains("导入成功"));
        assertTrue(SecurityUtils.matchesPassword(password, imported.getPassword()));
        assertNotEquals(password, imported.getPassword());
        assertEquals("fixture-operator", imported.getCreateBy());
        verify(f.users).insertUser(imported);
        verify(f.validator).validate(imported);
        verify(f.depts).checkDeptDataScope(42L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void emptyInitialPasswordCreatesNoAccountForAnyAccountScope(String password) {
        for (String username : new String[] {"admin", "ry", "fixture-imported"}) {
            Fixture f = fixture(password);
            ServiceException error = assertThrows(ServiceException.class,
                    () -> f.service.importUser(Collections.singletonList(user(username)), false, "fixture-operator"));
            assertTrue(error.getMessage().contains("sys.user.initPassword"));
            verify(f.users, never()).insertUser(any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "ry", "fixture-imported"})
    void strongPasswordImportStillCreatesAccount(String username) {
        Fixture f = fixture("Fixture-Init-8204");
        SysUser imported = user(username);

        f.service.importUser(Collections.singletonList(imported), false, "fixture-operator");

        verify(f.users).insertUser(imported);
        assertTrue(SecurityUtils.matchesPassword("Fixture-Init-8204", imported.getPassword()));
    }

    @Test
    void ordinaryAccountImportStillRequiresDepartmentScope() {
        Fixture f = fixture("123456");
        doThrow(new ServiceException("没有权限访问部门数据")).when(f.depts).checkDeptDataScope(42L);

        assertThrows(ServiceException.class,
                () -> f.service.importUser(Collections.singletonList(user("fixture-imported")), false, "fixture-operator"));

        verify(f.users, never()).insertUser(any());
        verify(f.configs, never()).selectConfigByKey(any());
    }

    @Test
    void existingAccountUpdateDoesNotReadOrReplaceInitialPassword() {
        Fixture f = fixture(null);
        SysUser existing = new SysUser(42L);
        when(f.users.selectUserByUserName("ry")).thenReturn(existing);
        SysUserServiceImpl service = spy(f.service);
        doNothing().when(service).checkUserDataScope(42L);
        SysUser imported = user("ry");

        service.importUser(Collections.singletonList(imported), true, "fixture-operator");

        verify(service).checkUserAllowed(existing);
        verify(service).checkUserDataScope(42L);
        verify(f.depts).checkDeptDataScope(42L);
        verify(f.users).updateUser(imported);
        verify(f.users, never()).insertUser(any());
        verify(f.configs, never()).selectConfigByKey(any());
        assertNull(imported.getPassword());
        assertEquals("fixture-operator", imported.getUpdateBy());
    }

    private static SysUser user(String username) {
        SysUser user = new SysUser();
        user.setUserName(username);
        user.setDeptId(42L);
        return user;
    }

    private static Fixture fixture(String password) {
        SysUserServiceImpl service = new SysUserServiceImpl();
        SysUserMapper users = mock(SysUserMapper.class);
        ISysConfigService configs = mock(ISysConfigService.class);
        ISysDeptService depts = mock(ISysDeptService.class);
        Validator validator = mock(Validator.class);
        when(validator.validate(any())).thenReturn(Collections.emptySet());
        when(configs.selectConfigByKey("sys.user.initPassword")).thenReturn(password);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "configService", configs);
        ReflectionTestUtils.setField(service, "deptService", depts);
        ReflectionTestUtils.setField(service, "validator", validator);
        return new Fixture(service, users, configs, depts, validator);
    }

    private record Fixture(SysUserServiceImpl service, SysUserMapper users, ISysConfigService configs,
                           ISysDeptService depts, Validator validator) {}
}
