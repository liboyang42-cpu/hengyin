import copy
import json
import unittest
from unittest.mock import Mock

from ci.chain_admin_readback import AdminReadbackError, observe_admin


class Connection:
    def __init__(self):
        self.schema = ("cy_e2e_51",)
        self.users = ((1, "admin", "0", "0"),)
        self.roles = ((1, "admin", "0", "0"),)
        self.menus = ((1, 0, "system", None, 1, "M"),
                      (100, 1, "user", "system/user/index", 1, "C"))
        self.closed = False
        self.sql = []

    def cursor(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass

    def execute(self, sql, params=()):
        assert sql.startswith("SELECT "), "observer must not write"
        self.sql.append((sql, params))
        if sql == "SELECT DATABASE()": self.result = self.schema
        elif "FROM sys_user WHERE" in sql: self.result = self.users
        elif "FROM sys_user_role" in sql: self.result = self.roles
        elif "FROM sys_menu" in sql:
            assert "status='0'" in sql and "menu_type IN ('M','C')" in sql
            self.result = self.menus
        else: raise AssertionError("unexpected SQL")

    def fetchone(self):
        return self.result

    def fetchall(self):
        return self.result

    def close(self):
        self.closed = True


class ObserverTests(unittest.TestCase):
    def setUp(self):
        self.db = Connection()
        self.payloads = {
            "/getInfo": {"code": 200, "roles": ["admin"], "permissions": ["*:*:*"],
                         "token": "secret-token-fixture", "user": {
                             "userId": 1, "userName": "admin", "status": "0", "delFlag": "0",
                             "password": "secret-password-hash-fixture", "email": "secret-profile-fixture"}},
            "/getRouters": {"code": 200, "data": [{"path": "/system", "component": "Layout",
                              "children": [{"path": "user", "component": "system/user/index"}]}]},
        }
        self.connect = Mock(return_value=self.db)
        self.get = Mock(side_effect=lambda path: copy.deepcopy(self.payloads[path]))

    def test_positive_exact_http_identity_db_binding_and_concrete_menu(self):
        result = observe_admin(self.get, self.connect)
        self.assertEqual(self.get.call_args_list, [unittest.mock.call("/getInfo"), unittest.mock.call("/getRouters")])
        self.connect.assert_called_once_with()
        self.assertTrue(self.db.closed)
        self.assertEqual(result["routes"], [{"menuId": 100, "path": "/system/user", "component": "system/user/index"}])
        self.assertEqual(result["sqlReadback"]["roleBindings"][0]["roleKey"], "admin")
        self.assertFalse(result["ordinaryRbacIsolationVerified"])
        self.assertEqual(result["realRoleCardsVerified"], 0)
        for value in ("secret-token-fixture", "secret-password-hash-fixture", "secret-profile-fixture"):
            self.assertNotIn(value, json.dumps(result))

    def test_integer_and_string_internal_flags_on_leaf_and_ancestor(self):
        for parent_flag, leaf_flag in ((1, 1), ("1", "1"), (1, "1"), ("1", 1)):
            with self.subTest(parent=parent_flag, leaf=leaf_flag):
                self.db.menus = ((1, 0, "system", None, parent_flag, "M"),
                                 (100, 1, "user", "system/user/index", leaf_flag, "C"))
                result = observe_admin(self.get, self.connect)
                self.assertEqual(result["routes"][0]["path"], "/system/user")

    def test_boolean_float_or_other_flag_never_claims_internal_menu(self):
        for bad in (True, False, 1.0, 0.0, 0, "0", "01"):
            for index in (0, 1):
                with self.subTest(flag=bad, row=index):
                    rows = [list(row) for row in Connection().menus]
                    rows[index][4] = bad
                    self.db.menus = tuple(tuple(row) for row in rows)
                    with self.assertRaisesRegex(AdminReadbackError, "ADMIN_MENU_NO_SUPPORTED_INTERNAL_ROUTE"):
                        observe_admin(self.get, self.connect)

    def test_wrong_http_user_status_or_wildcard_fails_before_db(self):
        for path, value in [("userId", 2), ("userId", True), ("userName", "other"),
                            ("status", "1"), ("delFlag", "2")]:
            with self.subTest(field=path, value=value):
                original = self.payloads["/getInfo"]["user"][path]
                self.payloads["/getInfo"]["user"][path] = value
                with self.assertRaises(AdminReadbackError): observe_admin(self.get, self.connect)
                self.payloads["/getInfo"]["user"][path] = original
        self.payloads["/getInfo"]["permissions"] = ["system:user:list"]
        with self.assertRaisesRegex(AdminReadbackError, "ADMIN_SUPERADMIN_PERMISSION_MISMATCH"):
            observe_admin(self.get, self.connect)
        self.connect.assert_not_called()

    def test_http_failure_body_never_leaves_error(self):
        self.payloads["/getInfo"] = {"code": 500, "msg": "secret-failure-body"}
        with self.assertRaises(AdminReadbackError) as caught: observe_admin(self.get, self.connect)
        self.assertEqual(str(caught.exception), "ADMIN_HTTP_FAILED")
        self.connect.assert_not_called()

    def test_wrong_database_user_or_role_binding_fails_and_closes(self):
        for field, value in [("schema", ("another_database",)),
                             ("users", ((1, "admin", "1", "0"),)),
                             ("roles", ((1, "operator", "0", "0"),)),
                             ("roles", ((1, "admin", "1", "0"),))]:
            with self.subTest(field=field, value=value):
                self.db = Connection(); setattr(self.db, field, value)
                self.connect.return_value = self.db
                with self.assertRaises(AdminReadbackError): observe_admin(self.get, self.connect)
                self.assertTrue(self.db.closed)

    def test_json_nonempty_missing_wrong_or_duplicate_concrete_route_fails(self):
        for children in [[], [{"path": "other", "component": "system/user/index"}],
                         [{"path": "user", "component": "wrong/component"}],
                         [{"path": "user", "component": "system/user/index"}] * 2]:
            with self.subTest(children=children):
                self.payloads["/getRouters"]["data"][0]["children"] = children
                with self.assertRaisesRegex(AdminReadbackError, "ADMIN_ROUTER_SQL_MISMATCH"):
                    observe_admin(self.get, self.connect)
                self.assertTrue(self.db.closed)

    def test_nested_and_top_level_frame_menu_paths(self):
        self.db.menus += ((101, 0, "standalone", "tool/standalone/index", "1", "C"),)
        self.payloads["/getRouters"]["data"].append({"path": "/", "component": "Layout", "children": [
            {"path": "standalone", "component": "tool/standalone/index"}]})
        result = observe_admin(self.get, self.connect)
        self.assertEqual(result["routes"][1]["path"], "/standalone")

    def test_external_dynamic_or_unsupported_ancestry_is_not_claimed(self):
        self.db.menus += ((200, 0, "https://fixture.invalid", "external/index", "0", "C"),
                          (201, 0, "user/:id", "dynamic/index", "1", "C"),
                          (202, 999, "orphan", "orphan/index", "1", "C"))
        result = observe_admin(self.get, self.connect)
        self.assertEqual(len(result["routes"]), 1)
        self.assertIn("plain_paths", result["routeScope"])

    def test_no_supported_menu_cannot_pass_with_nonempty_json(self):
        self.db.menus = ()
        with self.assertRaisesRegex(AdminReadbackError, "ADMIN_MENU_NO_SUPPORTED_INTERNAL_ROUTE"):
            observe_admin(self.get, self.connect)

    def test_menu_cycle_or_duplicate_id_fails(self):
        for menus in [self.db.menus + (self.db.menus[1],),
                      ((1, 1, "system", None, "1", "M"), self.db.menus[1])]:
            with self.subTest(menus=menus):
                self.db.menus = menus
                with self.assertRaises(AdminReadbackError): observe_admin(self.get, self.connect)

    def test_transport_and_sql_exception_payloads_are_suppressed(self):
        for failing in ("http", "sql"):
            with self.subTest(failing=failing):
                get_json, connect = self.get, self.connect
                if failing == "http": get_json = Mock(side_effect=OSError("secret-http-payload"))
                else: connect = Mock(side_effect=OSError("secret-sql-payload"))
                with self.assertRaises(AdminReadbackError) as caught: observe_admin(get_json, connect)
                self.assertEqual(str(caught.exception), "ADMIN_READBACK_FAILED")
                self.assertTrue(caught.exception.__suppress_context__)


if __name__ == "__main__":
    unittest.main()
