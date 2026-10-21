"""Read-only fixture-superadmin HTTP/SQL evidence; never real-role acceptance."""
import re


class AdminReadbackError(RuntimeError):
    """Only fixed codes may leave the observer."""


def _require(condition, code):
    if not condition:
        raise AdminReadbackError(code)


def _internal_frame(value):
    # MySQL INT is returned as int; Java SysMenu exposes the same column as String.
    return type(value) in (int, str) and value in (1, "1")


def _menu_routes(rows):
    menus = {row[0]: row for row in rows}
    _require(len(menus) == len(rows), "ADMIN_MENU_DUPLICATE_ID")
    expected = []
    plain = r"[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"
    for menu_id, parent_id, path, component, is_frame, kind in rows:
        if (kind != "C" or not _internal_frame(is_frame) or not isinstance(component, str)
                or not re.fullmatch(plain, component) or not isinstance(path, str)
                or not re.fullmatch(plain, path)):
            continue
        parts, seen, supported = [path], {menu_id}, True
        while parent_id:
            _require(parent_id not in seen, "ADMIN_MENU_ANCESTRY_INVALID")
            if parent_id not in menus:
                supported = False
                break
            seen.add(parent_id)
            ancestor = menus[parent_id]
            if not (ancestor[5] == "M" and _internal_frame(ancestor[4])
                    and isinstance(ancestor[2], str) and re.fullmatch(plain, ancestor[2])):
                supported = False
                break
            parts.append(ancestor[2])
            parent_id = ancestor[1]
        if supported:
            expected.append({"menuId": menu_id, "path": "/" + "/".join(reversed(parts)),
                             "component": component})
    _require(expected, "ADMIN_MENU_NO_SUPPORTED_INTERNAL_ROUTE")
    return expected


def _http_routes(routes, parent=""):
    _require(isinstance(routes, list), "ADMIN_ROUTER_SHAPE_INVALID")
    result = []
    for route in routes:
        _require(isinstance(route, dict) and isinstance(route.get("path"), str),
                 "ADMIN_ROUTER_SHAPE_INVALID")
        path = route["path"] if route["path"].startswith("/") else parent + "/" + route["path"]
        path = "/" + "/".join(part for part in path.split("/") if part)
        result.append((path, route.get("component")))
        result.extend(_http_routes(route.get("children", []), path))
    return result


def _observe_admin(get_json, connect):
    info, routers = get_json("/getInfo"), get_json("/getRouters")
    _require(isinstance(info, dict) and isinstance(routers, dict)
             and info.get("code") == 200 and routers.get("code") == 200
             and info.get("_status", 200) == 200 and routers.get("_status", 200) == 200,
             "ADMIN_HTTP_FAILED")
    user = info.get("user")
    _require(isinstance(user, dict) and type(user.get("userId")) is int
             and user["userId"] == 1 and user.get("userName") == "admin"
             and user.get("status") == "0" and user.get("delFlag") == "0",
             "ADMIN_HTTP_IDENTITY_MISMATCH")
    # SysUser.isAdmin(1) intentionally returns admin/*:*:* without ordinary role-menu filtering.
    _require(info.get("roles") == ["admin"] and info.get("permissions") == ["*:*:*"],
             "ADMIN_SUPERADMIN_PERMISSION_MISMATCH")
    observed_routes = _http_routes(routers.get("data"))
    connection = connect()
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE()")
            database = cursor.fetchone()
            _require(database == ("cy_e2e_51",), "ADMIN_DATABASE_MISMATCH")
            cursor.execute("SELECT user_id,user_name,status,del_flag FROM sys_user WHERE user_id=%s", (1,))
            users = cursor.fetchall()
            _require(list(users) == [(1, "admin", "0", "0")], "ADMIN_SQL_IDENTITY_MISMATCH")
            cursor.execute("SELECT r.role_id,r.role_key,r.status,r.del_flag FROM sys_user_role ur "
                           "JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=%s ORDER BY r.role_id", (1,))
            roles = cursor.fetchall()
            _require(list(roles) == [(1, "admin", "0", "0")], "ADMIN_SQL_ROLE_BINDING_MISMATCH")
            cursor.execute("SELECT menu_id,parent_id,path,component,is_frame,menu_type FROM sys_menu "
                           "WHERE status='0' AND menu_type IN ('M','C') ORDER BY menu_id")
            menu_rows = cursor.fetchall()
    finally:
        connection.close()
    expected = _menu_routes(menu_rows)
    for route in expected:
        _require(observed_routes.count((route["path"], route["component"])) == 1,
                 "ADMIN_ROUTER_SQL_MISMATCH")
    return {
        "status": "PASS_FIXTURE_SUPERADMIN_HTTP_SQL_ONLY",
        "userId": user["userId"], "roleKeys": list(info["roles"]),
        "permissions": list(info["permissions"]),
        "sqlReadback": {
            "schema": database[0],
            "userIdentity": dict(zip(("userId", "userName", "status", "delFlag"), users[0])),
            "roleBindings": [dict(zip(("roleId", "roleKey", "status", "delFlag"), row)) for row in roles],
        },
        "routes": expected,
        # shortcut: external/dynamic paths are outside this internal-menu subset; add source-specific cases if needed.
        "routeScope": "enabled_internal_C_plain_paths_with_enabled_M_ancestors_only",
        "ordinaryRbacIsolationVerified": False,
        "realRoleCardsVerified": 0,
    }


def observe_admin(get_json, connect):
    """Caller supplies its existing normal-login token closure and a fresh CI-only connection."""
    try:
        return _observe_admin(get_json, connect)
    except AdminReadbackError:
        raise
    except Exception:
        raise AdminReadbackError("ADMIN_READBACK_FAILED") from None
