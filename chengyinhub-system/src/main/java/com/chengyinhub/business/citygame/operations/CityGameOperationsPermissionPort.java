package com.chengyinhub.business.citygame.operations;

/** 运营命令的权限端口。不读取客户端角色字段，默认不放行。 */
public interface CityGameOperationsPermissionPort {

    boolean allows(long actorUserId, String permission);

    /** 登录身份上的角色键。客户端正文里的角色不算。 */
    default String roleKeys(long actorUserId) {
        return "";
    }
}
