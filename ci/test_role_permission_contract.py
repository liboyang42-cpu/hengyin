#!/usr/bin/env python3
import importlib.util
import pathlib
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("role_permission_contract.py")
SPEC = importlib.util.spec_from_file_location("role_permission_contract", MODULE_PATH)
contract = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(contract)


class RolePermissionContractTest(unittest.TestCase):
    def test_current_repository_passes(self):
        self.assertEqual(contract.audit(), [])

    def test_negative_control_frontend_only_permission_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "chengyinhub-ui/src/views/x").mkdir(parents=True)
            (root / "chengyinhub-admin/src/main/java/x").mkdir(parents=True)
            (root / "chengyinhub-ui/src/views/x/index.vue").write_text(
                '<button v-hasPermi="[\'business:x:remove\']"/>', encoding="utf-8")
            problems = contract.layer_problems(
                contract.frontend_permissions(root), contract.backend_permissions(root))
            self.assertEqual(len(problems), 1)
            self.assertIn("business:x:remove", problems[0])

    def test_negative_control_operator_cannot_receive_fund_review(self):
        sql = contract.ROLE_SQL.read_text(encoding="utf-8")
        policies = contract.role_predicates(sql)
        self.assertFalse(contract.policy_grants(policies["operator"], "business:refund:review"))
        self.assertFalse(contract.policy_grants(policies["operator"], "business:memberwithdrawal:audit"))
        self.assertFalse(contract.policy_grants(policies["operator"], "business:cashwithdraw:manage"))
        self.assertTrue(contract.policy_grants(policies["finance"], "business:memberwithdrawal:audit"))
        for role in contract.ROLES:
            self.assertFalse(contract.policy_grants(policies[role], "system:config:edit"))


class XcxCapabilityContractTest(unittest.TestCase):
    """X03：小程序能力位必须「要么有服务端强制点，要么写明是展示位」。"""

    @staticmethod
    def _write_domain(root, extra_fields):
        """fixture 的 UserPermission 必须包含全部已登记展示位，
        否则会触发「登记过期」那条无关断言，把用例真正想验的那条淹掉。"""
        domain = root / contract.XCX_PERMISSION_DOMAIN.relative_to(contract.ROOT)
        domain.parent.mkdir(parents=True, exist_ok=True)
        fields = ["    private Long id;"]
        fields += ["    private Boolean %s;" % bit for bit in contract.XCX_DISPLAY_ONLY]
        fields += list(extra_fields)
        domain.write_text("public class UserPermission {\n" + "\n".join(fields) + "\n}\n",
                          encoding="utf-8")

    def test_current_repository_passes(self):
        self.assertEqual(contract.xcx_problems(), [])

    def test_max_nodes_per_theme_remains_unlimited_compatibility_key(self):
        """已取消的节点数量限制保留 null=不限 兼容键，不得重新成为客户端独有的闸。"""
        self.assertIn("maxNodesPerTheme", contract.XCX_DISPLAY_ONLY)
        self.assertEqual(contract.xcx_enforcement_points("maxNodesPerTheme"), [])
        role_service = contract.ROOT / "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RoleServiceImpl.java"
        self.assertIn("c.setMaxNodesPerTheme(null)", role_service.read_text(encoding="utf-8"))

    def test_negative_control_unenforced_and_undeclared_bit_is_rejected(self):
        """★负控：新增一个两端都不拦、又没登记的能力位，必须判红。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write_domain(root, ["    private Boolean canTeleport;"])
            problems = contract.xcx_problems(root)
            self.assertEqual(len(problems), 1, problems)
            self.assertIn("canTeleport", problems[0])
            self.assertIn("服务端零强制", problems[0])

    def test_negative_control_stale_display_only_entry_is_rejected(self):
        """★负控：登记为展示位、实际已经有强制点的，必须要求删掉登记（棘轮双向）。

        用的位从 XCX_DISPLAY_ONLY 现取，不写死——写死的话，每次棘轮收紧
        （某一位补上强制点、从登记表移走）这条负控就会跟着失效，
        而失效的负控看起来和通过一模一样。
        """
        self.assertTrue(contract.XCX_DISPLAY_ONLY, "登记表空了就没有这条负控可验的对象")
        bit = sorted(contract.XCX_DISPLAY_ONLY)[0]
        getter = "get" + bit[0].upper() + bit[1:]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write_domain(root, [])
            enforcing = root / "chengyinhub-system/src/main/java/com/chengyinhub/Gate.java"
            enforcing.parent.mkdir(parents=True, exist_ok=True)
            enforcing.write_text(
                "class Gate { void f(){ if (!perm.%s()) throw new X(); } }\n" % getter,
                encoding="utf-8")
            problems = contract.xcx_problems(root)
            self.assertEqual(len(problems), 1, problems)
            self.assertIn(bit, problems[0])
            self.assertIn("必须从 XCX_DISPLAY_ONLY 里删掉", problems[0])

    def test_four_promised_bits_are_really_enforced(self):
        """权益清单向用户承诺过的四位，必须有服务端强制点，且不许退回展示位登记。

        界面写着「未解锁勋章设计」却照收 medalName/medalImg，是 X03 的原始病灶。
        这条正控钉住它不复发。
        """
        for bit in ("canBranch", "canGameMechanic", "canDesignMedal", "canCreateGroup"):
            with self.subTest(bit=bit):
                self.assertNotIn(bit, contract.XCX_DISPLAY_ONLY)
                self.assertTrue(contract.xcx_enforcement_points(bit),
                                "%s 是权益清单里向用户承诺过的一位，服务端必须真的拦" % bit)

    def test_negative_control_delivery_site_does_not_count_as_enforcement(self):
        """★负控：下发口（permission.put）和多角色合并（RoleServiceImpl）不算强制点。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write_domain(root, ["    private Boolean canTeleport;"])
            api = root / "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api"
            api.mkdir(parents=True, exist_ok=True)
            (api / "ApiRoleController.java").write_text(
                'class C { void f(){ permission.put("canTeleport", perm.getCanTeleport()); } }\n',
                encoding="utf-8")
            svc = root / "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl"
            svc.mkdir(parents=True, exist_ok=True)
            (svc / "RoleServiceImpl.java").write_text(
                "class S { void f(){ merged.setCanTeleport(or(merged.getCanTeleport(), "
                "row.getCanTeleport())); } }\n", encoding="utf-8")
            self.assertEqual(contract.xcx_enforcement_points("canTeleport", root), [])
            problems = contract.xcx_problems(root)
            self.assertEqual(len(problems), 1, problems)
            self.assertIn("canTeleport", problems[0])

    def test_real_enforcement_site_does_count(self):
        """正控：普通业务文件里的 getter 调用必须被认成强制点，
        否则这道门禁会把所有位都报成零强制，变成噪音。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write_domain(root, ["    private Boolean canTeleport;"])
            gate = root / "chengyinhub-system/src/main/java/com/chengyinhub/Gate.java"
            gate.parent.mkdir(parents=True, exist_ok=True)
            gate.write_text(
                "class Gate { void f(){ if (!perm.getCanTeleport()) throw new X(); } }\n",
                encoding="utf-8")
            self.assertEqual(len(contract.xcx_enforcement_points("canTeleport", root)), 1)
            self.assertEqual(contract.xcx_problems(root), [])


class AdminEndpointCoverageTest(unittest.TestCase):
    """③ 后台业务端点必须带 @PreAuthorize 或 @Anonymous。"""

    @staticmethod
    def _write(root, filename, body):
        directory = root / contract.ADMIN_BUSINESS_DIR.relative_to(contract.ROOT)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / filename).write_text(body, encoding="utf-8")

    def test_current_repository_passes(self):
        self.assertEqual(contract.admin_endpoint_problems(), [])

    def test_home_data_is_guarded(self):
        """曾经是 476 个里唯一裸奔的那个：把平台全量营收发给了所有后台角色。"""
        raw = (contract.ADMIN_BUSINESS_DIR / "HomeController.java").read_text(encoding="utf-8")
        # 必须先剥注释：javadoc 里正解释着「为什么删掉 totalSalesAmount」，
        # 不剥的话这条断言会被自己的文档触发——正是本文件另一条负控要防的那个坑。
        code = contract._strip_java_comments(raw)
        self.assertIn("@PreAuthorize", code)
        self.assertIn("totalSalesAmount", raw, "解释为什么删它的那段 javadoc 不该被删掉")
        self.assertNotIn("totalSalesAmount", code,
                         "营收字段首页从来没渲染过，已随权限收口一并删除，不该被加回来")

    def test_negative_control_unguarded_endpoint_is_rejected(self):
        """★负控：新增一个没有权限注解的端点，必须判红。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write(root, "XController.java",
                        "@RestController\n@RequestMapping(\"/business/x\")\n"
                        "public class XController {\n"
                        "    @GetMapping(\"/data\")\n"
                        "    public AjaxResult leak() { return null; }\n}\n")
            problems = contract.admin_endpoint_problems(root)
            self.assertEqual(len(problems), 1, problems)
            self.assertIn("leak()", problems[0])

    def test_negative_control_comment_does_not_count_as_guard(self):
        """★负控：注释里的 @PreAuthorize 不算数——否则一句 javadoc 就能骗过这道门。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write(root, "YController.java",
                        "public class YController {\n"
                        "    /** 本来想加 @PreAuthorize 的，还没加 */\n"
                        "    @GetMapping(\"/data\")\n"
                        "    public AjaxResult leak() { return null; }\n}\n")
            problems = contract.admin_endpoint_problems(root)
            self.assertEqual(len(problems), 1, problems)

    def test_negative_control_class_level_mapping_is_not_an_endpoint(self):
        """★负控：类级 @RequestMapping 是控制器根路径，不是端点。

        把它算进来会得到 83 个假阳性，然后这道门就会被当成噪音关掉——
        一道满口胡说的门禁比没有门禁更糟。
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write(root, "ZController.java",
                        "@RestController\n@RequestMapping(\"/business/z\")\n"
                        "public class ZController {\n"
                        "    @PreAuthorize(\"@ss.hasPermi('business:z:list')\")\n"
                        "    @GetMapping(\"/list\")\n"
                        "    public AjaxResult list() { return null; }\n}\n")
            self.assertEqual(contract.admin_endpoint_problems(root), [])

    def test_anonymous_counts_as_a_decision(self):
        """@Anonymous 是显式决定「这个端点公开」，与漏挂权限不是一回事。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self._write(root, "PController.java",
                        "public class PController {\n"
                        "    @Anonymous\n"
                        "    @GetMapping(\"/ping\")\n"
                        "    public AjaxResult ping() { return null; }\n}\n")
            self.assertEqual(contract.admin_endpoint_problems(root), [])


if __name__ == "__main__":
    unittest.main()
