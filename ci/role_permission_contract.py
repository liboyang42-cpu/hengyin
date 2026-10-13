#!/usr/bin/env python3
"""E-3 Web 后台角色 / Vue 按钮 / Spring API 三层权限契约，外加小程序能力位契约。

纯静态、纯 stdlib，不连数据库。运行：python3 ci/role_permission_contract.py

三段审计：
  ① Web 后台三层：角色 SQL / Vue v-hasPermi / API @PreAuthorize 必须一致。
  ② 小程序能力位（X03）：/api/role/info 下发的每一个能力位，服务端必须至少有一个
     强制点；确实只是展示用的，必须在 XCX_DISPLAY_ONLY 里逐条写明理由。
  ③ 后台端点全覆盖：web/controller/business 下每一个 HTTP 端点都必须带
     @PreAuthorize 或 @Anonymous。①只能核对「已声明的权限」彼此一致，
     一个**什么注解都没有**的端点根本不在它的权限宇宙里 —— 它不会红，
     只是从来没被看见过。实测 476 个端点里正好有 1 个是这样：
     HomeController.getHomeData 把平台全量已支付营收发给了所有后台角色，
     包括 role_permission_contract 自己明确挡在全部资金/PII 权限之外的 auditor。
     棘轮钉在 0：新增端点忘了挂权限，这里直接红。

  第二段存在的理由和第一段完全一样。utils/roleGuard.js 文件头写着「三端权限单一
  事实源」，但下发的能力位里曾有 5 个两端都不拦：canBranch / canGameMechanic /
  canDesignMedal / canCreateGroup / nodeTypes 只在 components/cy/profile 的「权益
  清单」里显示「已解锁/未解锁」。界面告诉低阶身份「未解锁勋章设计」，却没有任何
  东西阻止他用——模板发布请求里的 medalName / medalImg 后端照收。
  这正是第一段那条负控 test_negative_control_frontend_only_permission_is_rejected
  想拦的同一类缺口，只是当时没覆盖到小程序这一侧。

  其中 4 位已经补上服务端强制点（CapabilityGuardImpl，闸的口径严格对齐权益清单的
  那句承诺，清单没提的机制一概不拦）。剩下的 nodeTypes 走了另一条路：它连界面都不显示，已直接停止下发（见下方登记）。

  本门禁不替产品决定某一位该不该变成真闸。它只强制一件事：
  **是展示还是闸，必须写下来**，不能靠看起来像闸蒙混过去；
  而且棘轮双向——一位补上强制点后，登记表里那行必须删掉。
"""
from __future__ import annotations

import pathlib
import re
import sys
from typing import Dict, Iterable, List, Set

ROOT = pathlib.Path(__file__).resolve().parents[1]
ROLE_SQL = ROOT / "chengyinhub-admin/sql/migration_web_admin_roles_20260719.sql"
ROLES = ("operator", "auditor", "support", "finance")

# 后台业务控制器目录（第③段审计的扫描范围）
ADMIN_BUSINESS_DIR = ROOT / "chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business"

# 小程序能力位的定义处（/api/role/info 下发的就是它的字段）
XCX_PERMISSION_DOMAIN = ROOT / "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/UserPermission.java"

# 不算「强制点」的文件：
#   RoleServiceImpl  —— 多角色合并(or/maxInt/unionCsv)与克隆，是搬运不是判断
#   ApiRoleController / ApiPublishController —— 下发口，permission.put / quota.put
NOT_ENFORCEMENT_FILES = (
    "RoleServiceImpl.java",
    "ApiRoleController.java",
    "ApiPublishController.java",
)

# 不是能力位的字段
NON_CAPABILITY_FIELDS = {"serialVersionUID", "id", "role"}

# 服务端不强制的能力位登记表。每条必须写清楚**为什么不强制**。
# 要把某一位变成真闸，先加强制点，再把它从这里删掉 —— 顺序反了本门禁会红，那是对的。
#
# 这里有两种「不强制」，理由必须分清：
#   · 展示位：界面会显示它，但它不是闸（membershipLevel）
#   · 已停止下发：两端零消费，界面不显示、接口也不再发（nodeTypes）
# 后者不该久留 —— 列还在库里，只是不发了。真确认没人要，就另开迁移删列。
XCX_DISPLAY_ONLY = {
    "maxNodesPerTheme":
        "【兼容位】2026-09-27 已取消主题节点数量限制（41e0f3de8e）：RoleServiceImpl 下发 null=不限，"
        "保留旧客户端额度键；QuotaServiceOnlineLimitTest 覆盖旧额度与回退路径，不得恢复前端独有的限制。",
    "nodeTypes":
        "【已停止下发】X03：曾以 CSV → 数组下发，但两端零消费——小程序的 identity-policy.can() "
        "支持列表位判断并有单测，却没有任何页面调用它；权益清单里也不显示这一位。"
        "已从 /api/role/info 移除（键集合由 ApiRoleInfoContractTest 钉住），"
        "user_permission.node_types 列先保留，删列另开迁移。"
        "要加回来必须先定死它的取值（'scan'/'quiz'/'gps'…）与 cms 节点 validation_method "
        "整数之间的映射表；那张表不存在，恰恰因为从来没人需要它——补一张出来是发明产品规则。",
    "membershipLevel":
        "会员等级文案，本身不是权限位；俱乐部会员收费由 ClubMembershipFeatureGate 单独管。",
}


def backend_permissions(root: pathlib.Path = ROOT) -> Set[str]:
    result: Set[str] = set()
    for path in root.glob("**/src/main/java/**/*.java"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for value in re.findall(r"has(?:Any)?Permi\(\s*'([^']+)'", text):
            result.update(part.strip() for part in value.split(",") if part.strip())
    return result


def frontend_permissions(root: pathlib.Path = ROOT) -> Dict[str, Set[str]]:
    result: Dict[str, Set[str]] = {}
    for path in root.glob("chengyinhub-ui/src/**/*.vue"):
        text = re.sub(r"<!--.*?-->", "", path.read_text(encoding="utf-8", errors="ignore"), flags=re.S)
        for binding in re.findall(r'v-hasPermi\s*=\s*"([^"]+)"', text):
            for permission in re.findall(r"'([^']+)'", binding):
                result.setdefault(permission, set()).add(str(path.relative_to(root)))
    return result


def layer_problems(frontend: Dict[str, Set[str]], backend: Set[str]) -> List[str]:
    return [
        f"前端权限无后端 @PreAuthorize: {permission} ({', '.join(sorted(frontend[permission]))})"
        for permission in sorted(set(frontend) - backend)
    ]


def _like(value: str, pattern: str) -> bool:
    regex = "".join(".*" if char == "%" else "." if char == "_" else re.escape(char) for char in pattern)
    return re.fullmatch(regex, value, flags=re.S) is not None


def role_predicates(sql: str) -> Dict[str, str]:
    predicates = {}
    for role in ROLES:
        match = re.search(
            rf"SELECT @{role}, m\.menu_id FROM sys_menu m\s+"
            rf"WHERE m\.status = '0' AND m\.visible = '0'\s+(.*?)\s+"
            rf"AND NOT EXISTS \(SELECT 1 FROM sys_role_menu x WHERE x\.role_id = @{role}",
            sql,
            flags=re.S,
        )
        if not match:
            raise ValueError(f"找不到 @{role} 的角色授权谓词")
        predicates[role] = re.sub(r"^AND\s+", "", match.group(1).strip())
    return predicates


def policy_grants(expression: str, permission: str) -> bool:
    def atom(match: re.Match) -> str:
        negated, pattern = match.group(1), match.group(2)
        value = _like(permission, pattern)
        return str(not value if negated else value)

    evaluated = re.sub(r"m\.perms\s+(NOT\s+)?LIKE\s+'([^']+)'", atom, expression, flags=re.I)
    evaluated = re.sub(r"\bAND\b", "and", evaluated, flags=re.I)
    evaluated = re.sub(r"\bOR\b", "or", evaluated, flags=re.I)
    evaluated = re.sub(r"\bNOT\b", "not", evaluated, flags=re.I)
    evaluated = " ".join(evaluated.split())
    if re.sub(r"\b(?:True|False|and|or|not)\b|[()\s]", "", evaluated):
        raise ValueError(f"不支持的角色 SQL 谓词: {evaluated}")
    return bool(eval(evaluated, {"__builtins__": {}}, {}))


def audit(root: pathlib.Path = ROOT) -> List[str]:
    problems: List[str] = []
    backend = backend_permissions(root)
    frontend = frontend_permissions(root)
    problems.extend(layer_problems(frontend, backend))

    sql = (root / ROLE_SQL.relative_to(ROOT)).read_text(encoding="utf-8")
    try:
        policies = role_predicates(sql)
    except ValueError as error:
        return problems + [str(error)]

    # 代表性正控：防止策略收紧后把角色缩成“空壳”。
    required = {
        "operator": {"business:topic:list", "business:topic:edit", "business:comment:audit"},
        "auditor": {"business:topic:list", "business:comment:audit"},
        "support": {"business:order:list", "business:order:query"},
        "finance": {"business:refund:review", "business:settlement:confirm", "business:memberwithdrawal:audit",
                    "business:memberwithdrawal:viewpii", "business:cashwithdraw:manage"},
    }
    for role, permissions in required.items():
        for permission in permissions:
            if permission in backend and not policy_grants(policies[role], permission):
                problems.append(f"{role} 缺少应授权限: {permission}")

    fund_words = ("refund", "withdraw", "settlement", "payout")
    high_risk_actions = {"review", "audit", "confirm", "payout", "manage", "viewpii", "pii"}
    universe = backend | set(frontend)
    for permission in sorted(universe):
        action = permission.rsplit(":", 1)[-1]
        high_risk = action in high_risk_actions and (any(word in permission for word in fund_words) or action == "pii")
        if high_risk:
            for role in ("operator", "auditor", "support"):
                if policy_grants(policies[role], permission):
                    problems.append(f"{role} 越权命中资金/PII 高危权限: {permission}")
        if action in {"add", "edit", "remove", "delete", "create"} and policy_grants(policies["finance"], permission):
            problems.append(f"finance 越权命中业务 CRUD: {permission}")

    # 迁移必须是可收敛的，且权限页必须补齐动态菜单祖先。
    reset = re.search(r"DELETE FROM sys_role_menu WHERE role_id IN \(([^)]+)\)", sql)
    reset_roles = set(re.findall(r"@(operator|auditor|support|finance)", reset.group(1))) if reset else set()
    for role in ROLES:
        if role not in reset_roles:
            problems.append(f"@{role} 未进入权威矩阵清理，旧越权无法收敛")
        if policy_grants(policies[role], "system:config:edit"):
            problems.append(f"{role} 越权命中系统配置权限")
    if sql.count("SELECT DISTINCT rm.role_id, p.menu_id FROM sys_role_menu rm") < 3:
        problems.append("未递归补齐角色菜单祖先，按钮权限可能没有可达页面")
    return problems


def xcx_capability_bits(root: pathlib.Path = ROOT) -> List[str]:
    """从 UserPermission.java 读出下发的能力位（字段名即 JSON key）。"""
    path = root / XCX_PERMISSION_DOMAIN.relative_to(ROOT)
    text = path.read_text(encoding="utf-8", errors="ignore")
    bits = re.findall(r"^\s*private\s+(?:static\s+final\s+)?[\w.<>\[\]]+\s+(\w+)\s*;", text, flags=re.M)
    return [b for b in bits if b not in NON_CAPABILITY_FIELDS]


def xcx_enforcement_points(bit: str, root: pathlib.Path = ROOT) -> List[str]:
    """某能力位的服务端强制点。合并/克隆/下发三类调用不算。"""
    getter = "get" + bit[0].upper() + bit[1:]
    needle = "." + getter + "()"
    hits: List[str] = []
    for path in root.glob("**/src/main/java/**/*.java"):
        if path.name in NOT_ENFORCEMENT_FILES or path.name == XCX_PERMISSION_DOMAIN.name:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for lineno, line in enumerate(text.splitlines(), 1):
            if needle in line:
                hits.append(f"{path.relative_to(root)}:{lineno}")
    return hits


def xcx_problems(root: pathlib.Path = ROOT) -> List[str]:
    problems: List[str] = []
    for bit in xcx_capability_bits(root):
        enforced = bool(xcx_enforcement_points(bit, root))
        declared_display_only = bit in XCX_DISPLAY_ONLY
        if not enforced and not declared_display_only:
            problems.append(
                f"小程序能力位 {bit} 服务端零强制，也没登记为展示位："
                f"要么在业务链路加强制点，要么写进 XCX_DISPLAY_ONLY 并说明理由"
            )
        if enforced and declared_display_only:
            problems.append(
                f"小程序能力位 {bit} 已有服务端强制点（{', '.join(xcx_enforcement_points(bit, root)[:3])}），"
                f"必须从 XCX_DISPLAY_ONLY 里删掉——只挡新增不要求删旧，登记表会永远停在最初那个长度"
            )
    for bit, reason in XCX_DISPLAY_ONLY.items():
        if not isinstance(reason, str) or len(reason.strip()) < 20:
            problems.append(f"小程序能力位 {bit} 的展示位理由必须逐条写明真实原因")
    unknown = set(XCX_DISPLAY_ONLY) - set(xcx_capability_bits(root))
    for bit in sorted(unknown):
        problems.append(f"XCX_DISPLAY_ONLY 里的 {bit} 已不是 UserPermission 的字段，登记过期，请删除")
    return problems


_METHOD_MAPPING = re.compile(r"@(?:Get|Post|Put|Delete|Patch)Mapping")


def _strip_java_comments(text: str) -> str:
    """等量换空格保住行号——注释里的 @PreAuthorize 不能算数，否则一句 javadoc 就能骗过这道门。"""
    text = re.sub(r"/\*[\s\S]*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)
    text = re.sub(r"(^|[^:\"'])//[^\n]*", lambda m: m.group(1), text, flags=re.M)
    return text


def admin_endpoint_problems(root: pathlib.Path = ROOT) -> List[str]:
    """后台业务端点必须带 @PreAuthorize 或 @Anonymous。

    只看方法级映射（@GetMapping / @PostMapping / ...）；类级 @RequestMapping 是控制器根路径，
    不是端点——把它算进来会得到 83 个假阳性，然后这道门就会被当成噪音关掉。
    """
    directory = root / ADMIN_BUSINESS_DIR.relative_to(ROOT)
    if not directory.is_dir():
        return []
    problems: List[str] = []
    for path in sorted(directory.glob("*.java")):
        lines = _strip_java_comments(path.read_text(encoding="utf-8", errors="ignore")).split("\n")
        for index, line in enumerate(lines):
            if not _METHOD_MAPPING.search(line):
                continue
            window = "\n".join(lines[max(0, index - 9):index + 1])
            if "@PreAuthorize" in window or "@Anonymous" in window:
                continue
            name = "?"
            for follow in lines[index + 1:index + 10]:
                matched = re.search(r"public\s+.*?\s(\w+)\s*\(", follow)
                if matched:
                    name = matched.group(1)
                    break
            problems.append(
                f"后台端点 {path.name}:{index + 1} {name}() 没有 @PreAuthorize / @Anonymous："
                f"任何登录后台的账号都能调，不分角色；这类端点不在权限契约的扫描范围内，"
                f"「谁能看」等于从来没被决定过"
            )
    return problems


def main() -> int:
    problems = audit() + xcx_problems() + admin_endpoint_problems()
    if problems:
        print("E-3 三层权限契约失败：", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    print("✓ E-3 三层权限一致：角色 SQL / Vue v-hasPermi / API @PreAuthorize")
    endpoints = sum(
        1
        for path in sorted(ADMIN_BUSINESS_DIR.glob("*.java"))
        for line in _strip_java_comments(path.read_text(encoding="utf-8", errors="ignore")).split("\n")
        if _METHOD_MAPPING.search(line)
    )
    print(f"✓ 后台端点权限全覆盖：{endpoints} 个业务端点全部带 @PreAuthorize / @Anonymous")
    bits = xcx_capability_bits()
    enforced = [b for b in bits if b not in XCX_DISPLAY_ONLY]
    print(f"✓ 小程序能力位契约：{len(bits)} 位中 {len(enforced)} 位有服务端强制点，"
          f"{len(XCX_DISPLAY_ONLY)} 位已登记为展示位")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
