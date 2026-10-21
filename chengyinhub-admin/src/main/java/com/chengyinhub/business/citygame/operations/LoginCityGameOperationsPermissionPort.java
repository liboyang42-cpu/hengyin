package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.common.core.domain.entity.SysRole;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.framework.web.service.PermissionService;
import org.springframework.stereotype.Component;

/**
 * 操作员门禁。只认登录身份上的权限和角色键，不认请求正文里的角色。
 */
@Component
public class LoginCityGameOperationsPermissionPort implements CityGameOperationsPermissionPort {

    private final PermissionService permissions;

    public LoginCityGameOperationsPermissionPort(PermissionService permissions) {
        this.permissions = permissions;
    }

    @Override
    public boolean allows(long actorUserId, String permission) {
        LoginUser login = login();
        if (login == null || login.getUserId() == null || login.getUserId().longValue() != actorUserId) {
            return false;
        }
        if (permission == null || !CityGameOperationsPermissions.ALL.contains(permission)) {
            return false;
        }
        return permissions.hasPermi(permission);
    }

    @Override
    public String roleKeys(long actorUserId) {
        LoginUser login = login();
        if (login == null || login.getUserId() == null || login.getUserId().longValue() != actorUserId
                || login.getUser() == null || login.getUser().getRoles() == null) {
            return "";
        }
        StringBuilder keys = new StringBuilder();
        for (SysRole role : login.getUser().getRoles()) {
            String key = role == null ? null : role.getRoleKey();
            if (key != null && key.matches("\\A[A-Za-z0-9][A-Za-z0-9_-]{0,95}\\z")) {
                if (keys.length() > 0) {
                    keys.append(',');
                }
                keys.append(key);
            }
        }
        return keys.toString();
    }

    private static LoginUser login() {
        try {
            return SecurityUtils.getLoginUser();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
