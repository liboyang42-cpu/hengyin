#!/usr/bin/env python3
"""
城瘾(chengyin)项目数据库迁移执行器。

本机通常没有 mysql 客户端,但有 python3 + pymysql。本脚本免装客户端,
从 RuoYi 的 application-druid.yml 读取 master 数据源(host/库/账号/密码),
连库做只读预检或执行 DDL。密码不打印。

用法:
  # 只读:连库 + 打印 MySQL 版本(连通性 sanity)
  python3 run_migration.py --check

  # 只读:打印某表的列(执行 ALTER 前确认列是否已存在)
  python3 run_migration.py --describe ums_creative_square

  # 干跑:本地打印将执行的语句,不连接数据库也不执行
  python3 run_migration.py --sql /path/to/migration.sql --dry-run

  # 执行:逐条执行 .sql 文件(autocommit)
  python3 run_migration.py --config /path/to/application-druid.yml --sql /path/to/migration.sql

  # 窄事务模式:仅允许 DML 与 CREATE/DROP TEMPORARY TABLE
  python3 run_migration.py --config /path/to/application-druid.yml --sql /path/to/dml.sql --transaction

  # 执行:执行单条内联语句
  python3 run_migration.py --config /path/to/application-druid.yml --exec "ALTER TABLE foo ADD COLUMN bar VARCHAR(50) NULL"

只读 --check/--describe 的配置文件默认路径:
  /Users/arlan/Downloads/chengyin/chengyinhub-admin/src/main/resources/application-druid.yml
任何非 dry-run 的 --sql / --exec 都必须在命令行显式传 --config。

★ 分句器不在本文件里,在仓库的 ci/sql_splitter.py(唯一真源,与 CI 门禁共用)。
  --sql 模式会去仓库里加载它;加载不到就【响亮地失败】,绝不退回裸 split。理由见 load_split_statements()。
"""
import argparse
import importlib.util
import os
import re
import subprocess
import sys
import uuid

try:
    from sql_splitter import (  # 唯一真源：脚本执行
        RUNNER_ASSERTION,
        executable_statement as shared_executable_statement,
    )
except ModuleNotFoundError:
    from ci.sql_splitter import (  # 唯一真源：按模块/skill entry 导入
        RUNNER_ASSERTION,
        executable_statement as shared_executable_statement,
    )

DEFAULT_CONFIG = "/Users/arlan/Downloads/chengyin/chengyinhub-admin/src/main/resources/application-druid.yml"

# 兜底仓库(cwd 不在任何 git 仓库里时才用)—— 同 audit_sys_job.py
FALLBACK_REPO = "/Users/arlan/Downloads/chengyin"


def load_db_config(config_path):
    """从 application-druid.yml 解析 master 数据源连接信息。"""
    cfg = open(config_path, encoding="utf-8").read()
    m = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?\s]+)", cfg)
    if not m:
        sys.exit("未在配置里找到 jdbc:mysql url: " + config_path)
    host, port, db = m.group(1), int(m.group(2)), m.group(3)
    # 取第一个(master)出现的 username/password
    user = _resolve_env(re.search(r"username:\s*(\S+)", cfg).group(1))
    pwd = _resolve_env(re.search(r"password:\s*(\S+)", cfg).group(1))
    return host, port, db, user, pwd


def _resolve_env(val):
    """解析 ${VAR} / ${VAR:-default} 占位符,从环境变量取值(yml 已改为 env 注入)。"""
    import os
    m = re.fullmatch(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}", val)
    if not m:
        return val
    name, default = m.group(1), m.group(2)
    resolved = os.environ.get(name, default)
    if resolved is None:
        sys.exit(f"环境变量 {name} 未设置,且配置无默认值;请先 export {name}=...")
    return resolved


# 生产库 3306 已从公网收回(2026-07-16),只能经 ssh 隧道到达。
# 历史:此前 3306 对全网开放且账号授权是 'chengyinhubdb'@'%' —— 任何人拿到密码
# 就能从任意 IP 直连生产库读写。收口后本机工具必须走隧道。
SSH_KEY = os.path.expanduser("~/.ssh/chengyin_deploy")
SSH_USER = "root"
_LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}
_ER_NO_SUCH_TABLE = 1146
_ER_CHECK_CONSTRAINT_VIOLATED = 3819
_REQUIRED_MYSQL_VERSION = "8.0.45"
# 自报「我不是 Oracle MySQL」的血统标记：MySQL 协议兼容的分支/魔改版本，只要在
# VERSION() 或 @@version_comment 里出现其中之一，一律拒绝进入写路径。
# 逐个的来由：mariadb/percona(xtradb 是 Percona 的引擎名)=两大分支；aurora/polardb/
# alisql/greatsql=云厂商在 MySQL 上的魔改分支；tidb/oceanbase=只是说 MySQL 协议的
# 另一套内核；wsrep/galera=Codership 的 MySQL-wsrep 构建。
_NON_ORACLE_LINEAGE_MARKERS = (
    "mariadb", "percona", "xtradb", "aurora", "polardb", "alisql",
    "greatsql", "tidb", "oceanbase", "wsrep", "galera",
)


def _pick_free_port():
    import socket
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def _open_ssh_tunnel(remote_host, remote_port):
    """起 ssh -L 隧道到远端 MySQL,返回本地端口。失败即响亮退出,绝不静默回退直连。"""
    import atexit
    import socket
    import time

    if not os.path.exists(SSH_KEY):
        sys.exit(f"缺少 ssh 密钥 {SSH_KEY} —— 3306 已从公网收回,没有隧道就连不上生产库")

    local_port = _pick_free_port()
    cmd = [
        "ssh", "-N",
        "-o", "ExitOnForwardFailure=yes",   # 端口转发建不起来就让 ssh 直接死,别假装连上了
        "-o", "BatchMode=yes",
        "-o", "ConnectTimeout=15",
        "-o", "IdentitiesOnly=yes",
        "-i", SSH_KEY,
        "-L", f"127.0.0.1:{local_port}:127.0.0.1:{remote_port}",
        f"{SSH_USER}@{remote_host}",
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def _close():
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except Exception:
                proc.kill()
    atexit.register(_close)

    # 等隧道真的能用 —— Popen 返回不等于端口已在监听(回执≠观测)。
    deadline = time.time() + 20
    while time.time() < deadline:
        if proc.poll() is not None:
            err = proc.stderr.read().decode("utf-8", "replace").strip()
            sys.exit(f"ssh 隧道启动失败(exit {proc.returncode}):{err or '(无 stderr)'}")
        try:
            s = socket.create_connection(("127.0.0.1", local_port), timeout=1)
            s.close()
            return local_port
        except OSError:
            time.sleep(0.3)
    _close()
    sys.exit("ssh 隧道 20 秒未就绪 —— 检查 22 端口可达性与部署密钥(见 PR #86)")


# 显式声明「目标就是本机」的唯一写法。
# ⚠️ 空字符串 / 未设置**不算**这个意思 —— 它多半来自上一次 IT 清理不干净,
#    所以仍然 fail-closed 到受控的生产 SSH 目标(见 connect 里那段注释)。
#    2026-08-19 加这一档,是因为 ci/test_tandianri_mysql_integration.py 要在一台
#    **真的本机 scratch MySQL** 上跑:它自己也 fail-closed 只接受 127.0.0.1,
#    而 yml 里的 host 也恒是 127.0.0.1 ⇒ 不给一个显式出口,这条 IT 在任何机器上都跑不了
#    (PR #741 的 Backend tests 就红在这:runner 去连生产、CI 上没有部署密钥)。
_EXPLICIT_LOCAL_TOKENS = {"local", "localhost", "none", "127.0.0.1", "::1"}


def resolve_ssh_host(raw):
    """把 CHENGYIN_DB_SSH_HOST 解析成「隧道打到哪」;返回 "" 表示不开隧道。"""
    value = (raw or "").strip()
    if value.lower() in _EXPLICIT_LOCAL_TOKENS:
        return ""
    if not value:
        return "47.103.124.64"
    return value


def connect(config_path):
    import pymysql
    host, port, db, user, pwd = load_db_config(config_path)

    # 2026-07-26:生产 yml 的 datasource 已改成 127.0.0.1(应用与库同机,不再绕公网,
    # 因为 3306 已从公网收回、绕公网会被自己的防火墙挡死并让服务起不来)。
    # 但对**本机**来说 127.0.0.1 是我自己的回环,不是生产库 —— 直连分支会连到本地的空气。
    # 所以这里显式给出隧道要打到哪台机器;要改机器就设 CHENGYIN_DB_SSH_HOST。
    # 空字符串不是「显式选择本机」；它常来自前一次 IT 清理不完整。
    # fail closed 到受控的生产 SSH 目标，绝不允许回落到操作者本机 MySQL。
    ssh_host = resolve_ssh_host(os.environ.get("CHENGYIN_DB_SSH_HOST"))
    if host in _LOCAL_HOSTS and not ssh_host:
        print("CHENGYIN_DB_SSH_HOST 显式声明目标就是本机,不开隧道")
    if host in _LOCAL_HOSTS and ssh_host:
        print(f"yml 里是 {host}(生产机视角的本地库),改经 ssh 隧道打到 {ssh_host}")
        host = ssh_host

    if host in _LOCAL_HOSTS:
        print(f"连接 {host}:{port}/{db} (用户 {user})")
        return pymysql.connect(host=host, port=port, user=user, password=pwd,
                               database=db, connect_timeout=8, autocommit=True), db

    local_port = _open_ssh_tunnel(host, port)
    print(f"连接 {host}:{port}/{db} (用户 {user};经 ssh 隧道 127.0.0.1:{local_port},3306 已不对公网开放)")
    return pymysql.connect(host="127.0.0.1", port=local_port, user=user, password=pwd,
                           database=db, connect_timeout=8, autocommit=True), db


def _cwd_git_root():
    """cwd 所在的 git 根;不在 git 仓库里就返回 None(不崩)。"""
    r = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                       capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 and r.stdout.strip() else None


def load_split_statements():
    """从仓库的 ci/sql_splitter.py 加载分句器。返回 (函数, 来源路径),找不到返回 (None, None)。

    ★ 为什么本文件【不再自带】分句实现:
      2026-07-15,migration_c3_team_job.sql 的 remark 里一个半角分号
      ('...(不接微信原路);默认暂停...')把 INSERT 从中间劈开 → 1064,
      autoRefundTask 从写下那天起就没跑过一次,8 天零告警。
      病根是本文件原先那个裸 split(";") 不认字符串字面量。
      2026-07-16 实测:它会切坏仓库里【18 个】SQL,不止那一个已知的
      (例:migration_c3_growth_badge_club.sql 的 COMMENT '...归属;NULL=平台全局' 会让整条 ALTER 断掉)。

      修好的 tokenizer 在仓库 ci/sql_splitter.py(PR #64),是【唯一真源】—— CI 门禁与本执行器共用。
      这儿再留一份副本 = 两份实现迟早分叉 = 门禁在保护一个不存在的东西。
      所以找不到就响亮地失败,【绝不退回裸 split】:静默用坏分句器跑生产迁移,
      正是这整件事要杀的那个病。

    找法同 audit_sys_job.py:cwd 的 git 根优先(标准工作流是「一并行任务 = 一 worktree」),
    取不到 / 那儿没有(老 worktree 从旧 master 切出,还没有这个文件)再兜底主仓。
    """
    for root in (_cwd_git_root(), FALLBACK_REPO):
        if not root:
            continue
        path = os.path.join(root, "ci", "sql_splitter.py")
        if not os.path.isfile(path):
            continue
        spec = importlib.util.spec_from_file_location("cy_sql_splitter", path)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod.split_statements, path
    return None, None


_IDENTIFIER = r"(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)"
_QUALIFIED_IDENTIFIER = rf"{_IDENTIFIER}(?:\.{_IDENTIFIER})?"
_TRANSACTION_INSERT = re.compile(
    rf"^INSERT\s+INTO\s+{_QUALIFIED_IDENTIFIER}(?=\s|\()", re.IGNORECASE)
_QUALIFIED_COLUMN = rf"{_IDENTIFIER}(?:\.{_IDENTIFIER})?"
# 单条 INNER JOIN + 单个等值 ON 是白名单里唯一的多表 UPDATE 形状。放它进来是因为 MySQL 不允许
# 同一语句二次打开同一 TEMPORARY 表(ERROR 1137),标量子查询搬运临时表数据的写法在真库上跑不了;
# 形状之外(LEFT JOIN / 多次 JOIN / 复合 ON / 逗号多表)一律仍在连库前拒绝。
# ⚠️ 别名槽必须拒吃 JOIN 关键字(2026-08-16):原先只挡 SET/JOIN,于是【不带别名】的
# `UPDATE t LEFT JOIN x ON a = b SET …` 里 LEFT 被当成 t 的别名消费掉,剩下的正好命中
# INNER JOIN 分支被放行 —— 承诺拒绝的 LEFT/RIGHT/CROSS/NATURAL 实际全放行,而负控 5 个形状
# 恰好全带别名(别名槽被占,LEFT 无处安放才失配)完美绕开了这个洞。多表 LEFT JOIN UPDATE
# 会把未匹配的左表行照样更新、右表列取 NULL,是能静默写坏账本的形状。
_TRANSACTION_UPDATE = re.compile(
    rf"^UPDATE\s+(?!(?:LOW_PRIORITY|IGNORE)\b){_QUALIFIED_IDENTIFIER}"
    rf"(?:\s+(?:AS\s+)?"
    rf"(?!(?:SET|JOIN|INNER|LEFT|RIGHT|CROSS|NATURAL|STRAIGHT_JOIN)\b){_IDENTIFIER})?"
    rf"(?:\s+(?:INNER\s+)?JOIN\s+{_QUALIFIED_IDENTIFIER}"
    rf"(?:\s+(?:AS\s+)?(?!SET\b|ON\b){_IDENTIFIER})?"
    rf"\s+ON\s+{_QUALIFIED_COLUMN}\s*=\s*{_QUALIFIED_COLUMN})?"
    rf"\s+SET\b",
    re.IGNORECASE,
)
_UNSUPPORTED_FROM_SHAPE = re.compile(
    rf"\bFROM\s+(?:\(|{_QUALIFIED_IDENTIFIER}(?:\s+(?:AS\s+)?{_IDENTIFIER})?\s*,)",
    re.IGNORECASE,
)
_TRANSACTION_TEMPORARY_DDL = re.compile(
    r"^(?:CREATE|DROP)\s+TEMPORARY\s+TABLE\b", re.IGNORECASE)
_WHERE_CLAUSE = re.compile(r"\bWHERE\b", re.IGNORECASE)
_TEMPORARY_TABLE_NAME = re.compile(
    r"^CREATE\s+TEMPORARY\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"(?P<name>(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*))\b", re.IGNORECASE)
_DROP_TEMPORARY_TABLE_NAMES = re.compile(
    r"^DROP\s+TEMPORARY\s+TABLE\s+(?:IF\s+EXISTS\s+)?"
    rf"(?P<names>{_IDENTIFIER}(?:\s*,\s*{_IDENTIFIER})*)"
    r"(?:\s+(?:RESTRICT|CASCADE))?\s*$", re.IGNORECASE)
_WRITE_TABLE_REFERENCE = re.compile(
    rf"^(?:INSERT\s+INTO|UPDATE)\s+(?P<name>{_QUALIFIED_IDENTIFIER})",
    re.IGNORECASE,
)
_READ_TABLE_REFERENCE = re.compile(
    r"\b(?:FROM|STRAIGHT_JOIN|JOIN)\s+"
    r"(?P<name>(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)"
    r"(?:\.(?:`[^`]+`|[A-Za-z_][A-Za-z0-9_]*))?)",
    re.IGNORECASE,
)
_REQUIRED_TRANSACTION_DECLARATION = re.compile(
    r"(?m)^\s*--\s*runner:\s*requires=transaction\s*$", re.IGNORECASE)
_SUPPORTED_RUNNER_ASSERTIONS = {"rowcount-positive", "scalar-true"}


def _mask_literals_and_comments(sql, mask_backticks=False):
    """Mask quoted data and comments while preserving offsets and SQL identifiers."""
    chars = list(sql)
    index = 0
    length = len(chars)
    while index < length:
        char = chars[index]
        if char in {"'", '"'} or (char == "`" and mask_backticks):
            quote = char
            chars[index] = " "
            index += 1
            while index < length:
                current = chars[index]
                chars[index] = " "
                if current == "\\" and index + 1 < length:
                    index += 1
                    chars[index] = " "
                elif current == quote:
                    if index + 1 < length and chars[index + 1] == quote:
                        index += 1
                        chars[index] = " "
                    else:
                        index += 1
                        break
                index += 1
            continue
        line_start = sql.rfind("\n", 0, index) + 1
        whole_line_comment = sql[line_start:index].strip() == ""
        trailing_line_comment = (
            index + 2 >= length or sql[index + 2] in " \t\r\n")
        if char == "#" or (
                char == "-" and index + 1 < length and chars[index + 1] == "-"
                and (whole_line_comment or trailing_line_comment)):
            while index < length and chars[index] not in {"\r", "\n"}:
                chars[index] = " "
                index += 1
            continue
        if (char == "/" and index + 1 < length and chars[index + 1] == "*"
                and not (index + 2 < length and chars[index + 2] == "!")):
            chars[index] = chars[index + 1] = " "
            index += 2
            while index < length:
                if chars[index] == "*" and index + 1 < length and chars[index + 1] == "/":
                    chars[index] = chars[index + 1] = " "
                    index += 2
                    break
                chars[index] = " "
                index += 1
            continue
        index += 1
    return "".join(chars)


def _contains_unquoted_semicolon(statement):
    return ";" in _mask_literals_and_comments(statement, mask_backticks=True)


def required_transaction_declared(sql_text):
    return _REQUIRED_TRANSACTION_DECLARATION.search(sql_text) is not None


def statement_assertion(statement):
    """Return the explicit result assertion bound to one SQL statement."""
    matches = list(RUNNER_ASSERTION.finditer(statement))
    if len(matches) > 1:
        raise ValueError("同一 SQL 语句不得声明多个 runner 断言")
    if not matches:
        return None
    mode = matches[0].group("mode").lower()
    if mode not in _SUPPORTED_RUNNER_ASSERTIONS:
        raise ValueError(f"不支持的 runner 断言: {mode}")
    return mode


def executable_statement(statement):
    """Remove runner metadata before static shape checks, not before execute()."""
    return shared_executable_statement(statement)


def assert_statement_result(cur, assertion, statement_index):
    """Turn declared migration observations into process-failing assertions."""
    if assertion == "rowcount-positive":
        rowcount = getattr(cur, "rowcount", None)
        if not isinstance(rowcount, int) or rowcount <= 0:
            raise ValueError(
                f"第 {statement_index} 句 runner 断言 rowcount-positive 失败: "
                f"rowcount={rowcount!r}")
        return f"rowcount={rowcount}"
    if assertion == "scalar-true":
        row = cur.fetchone()
        if row is None or len(row) != 1 or row[0] != 1:
            raise ValueError(
                f"第 {statement_index} 句 runner 断言 scalar-true 失败: "
                f"readback={row!r}")
        return "readback=1"
    raise ValueError(f"未处理的 runner 断言: {assertion}")


def validate_transaction_statements(statements):
    """Fail closed unless every statement fits the narrow transaction-safe allowlist.

    This mode deliberately does not try to prove arbitrary migrations atomic. It accepts
    DML plus CREATE/DROP TEMPORARY TABLE, which do not cause MySQL implicit
    commits, and rejects every other statement shape before opening a database connection.
    The caller must still prove every persistently written table uses a transactional engine.
    """
    for index, statement in enumerate(statements, 1):
        normalized = executable_statement(statement)
        if "/*!" in _mask_literals_and_comments(normalized, mask_backticks=True):
            raise ValueError(
                f"第 {index} 句含 MySQL 可执行注释，无法证明事务形状，已在连接数据库前拒绝")
        if _contains_unquoted_semicolon(normalized):
            raise ValueError(
                f"第 {index} 句仍含引号外分号，疑似分句失败，已在连接数据库前拒绝")
        dml_allowed = (_TRANSACTION_INSERT.match(normalized)
                       or _TRANSACTION_UPDATE.match(normalized))
        if (_TRANSACTION_UPDATE.match(normalized)
                and not _WHERE_CLAUSE.search(
                    _mask_literals_and_comments(normalized, mask_backticks=True))):
            raise ValueError(
                f"第 {index} 句 UPDATE 没有 WHERE，--transaction 已在连接数据库前拒绝")
        if dml_allowed and _UNSUPPORTED_FROM_SHAPE.search(normalized):
            dml_allowed = False
        if dml_allowed or _TRANSACTION_TEMPORARY_DDL.match(normalized):
            continue
        preview = " ".join(normalized.split())[:160]
        raise ValueError(
            f"第 {index} 句不在 --transaction 安全白名单，已在连接数据库前拒绝: {preview}")


def referenced_persistent_tables(statements, current_db):
    """Extract all read/write sources, applying temporary shadows in statement order."""
    temporary_tables = set()
    persistent_tables = set()
    for statement in statements:
        normalized = _mask_literals_and_comments(executable_statement(statement)).lstrip()
        references = []
        write_match = _WRITE_TABLE_REFERENCE.match(normalized)
        if write_match:
            references.append(write_match.group("name"))
        references.extend(match.group("name") for match in _READ_TABLE_REFERENCE.finditer(normalized))
        for raw_reference in references:
            raw_name = raw_reference.replace("`", "")
            parts = raw_name.split(".")
            if len(parts) == 2 and parts[0].lower() != current_db.lower():
                raise ValueError(f"--transaction 不支持跨 schema 表引用: {raw_name}")
            table_name = parts[-1]
            if table_name.lower() not in temporary_tables:
                persistent_tables.add(table_name)

        create_match = _TEMPORARY_TABLE_NAME.match(normalized)
        if create_match:
            temporary_tables.add(create_match.group("name").replace("`", "").lower())
        drop_match = _DROP_TEMPORARY_TABLE_NAMES.match(normalized)
        if drop_match:
            for name_match in re.finditer(_IDENTIFIER, drop_match.group("names")):
                temporary_tables.discard(
                    name_match.group(0).replace("`", "").lower())
    return sorted(persistent_tables, key=str.lower)


def _mysql_error_code(error):
    try:
        return int(error.args[0])
    except (AttributeError, IndexError, TypeError, ValueError):
        return None


def assert_check_enforcement_and_same_session_cleanup(cur):
    """Mutate one session-local probe and prove CHECK rejection plus DROP receipt."""
    probe_table = "__chengyin_check_probe_" + uuid.uuid4().hex[:16]
    quoted_probe_table = f"`{probe_table}`"
    probe_created = False
    try:
        cur.execute(
            f"CREATE TEMPORARY TABLE {quoted_probe_table} ("
            "probe_value TINYINT NOT NULL CHECK (probe_value = 1)"
            ") ENGINE=InnoDB"
        )
        probe_created = True
        try:
            cur.execute(
                f"INSERT INTO {quoted_probe_table}(probe_value) VALUES (0)")
        except Exception as error:
            error_code = _mysql_error_code(error)
            if error_code != _ER_CHECK_CONSTRAINT_VIOLATED:
                raise ValueError(
                    "CHECK enforcement probe 返回错误码 "
                    f"{error_code!r}（预期 {_ER_CHECK_CONSTRAINT_VIOLATED}）") from error
        else:
            raise ValueError("CHECK enforcement probe 非法值未被 CHECK 拒绝")
    except ValueError:
        raise
    except Exception as error:
        raise ValueError(f"CHECK enforcement probe 无法建立或执行: {error}") from error
    finally:
        if probe_created:
            try:
                cur.execute(f"DROP TEMPORARY TABLE {quoted_probe_table}")
            except Exception as error:
                raise ValueError(
                    f"CHECK enforcement probe 临时表清理失败: {error}") from error
            try:
                cur.execute(f"SELECT 1 FROM {quoted_probe_table} LIMIT 1")
            except Exception as error:
                error_code = _mysql_error_code(error)
                if error_code != _ER_NO_SUCH_TABLE:
                    raise ValueError(
                        "CHECK enforcement probe DROP 后同会话回读错误码 "
                        f"{error_code!r}（预期 {_ER_NO_SUCH_TABLE}）") from error
            else:
                raise ValueError(
                    "CHECK enforcement probe 临时表 DROP 后仍可查询，拒绝进入 BEGIN")

    return _ER_CHECK_CONSTRAINT_VIOLATED, _ER_NO_SUCH_TABLE


def assert_mysql_server_provenance(mysql_version, version_comment):
    """Fail closed unless the server is exact 8.0.45 with no non-Oracle lineage marker."""
    # ★ 2026-08-19 判据修正(本函数由本分支 bfdbd1364 引入，改前从未对着生产跑过)：
    #   旧判据要求 version_comment 含 "MySQL Community Server"/"MySQL Enterprise Server"，
    #   把**生产自己**挡在了门外 —— 生产是宝塔自编译的 8.0.45，version_comment 是
    #   "Source distribution"。闸真正要表达的是「不是 MariaDB / 不是 Percona / 版本必须
    #   是 8.0.45」，**发行渠道**(官方 tarball / 自行编译 / 企业版)本就不在判据里，
    #   拿它当白名单是把判据写错了，不是把闸修严了。
    #   ⚠️ 严格度没降：异血统与版本这两条照旧红，且新增「读不到 version_comment 也红」。
    #   ⚠️ 但要如实说：version_comment 是编译期 -DWITH_COMMENT 的自由文本，可被任意改写，
    #      它只能用来**排除自报的异血统**，永远不能用来**证明**是 Oracle 血统。
    #      更强的判据(host 侧 mysqld 指纹)见 /tmp/tandianri_provenance_gate_fix_20260819.md。
    if version_comment is None or not str(version_comment).strip():
        raise ValueError(
            "runner 仅接受 Oracle MySQL 8.0.45：version_comment 为空或读不到，"
            "无法排除异血统（没验 ≠ 没问题），拒绝进入写路径")
    version_comment = str(version_comment).strip()
    mysql_version = "" if mysql_version is None else str(mysql_version).strip()
    identity = f"{mysql_version} {version_comment}".lower()
    lineage_hits = [marker for marker in _NON_ORACLE_LINEAGE_MARKERS if marker in identity]
    if lineage_hits:
        raise ValueError(
            "runner 仅接受 Oracle MySQL 8.0.45：检出非 Oracle 血统标记 "
            f"{'、'.join(lineage_hits)}，实际为 {mysql_version} ({version_comment})")
    if mysql_version != _REQUIRED_MYSQL_VERSION:
        raise ValueError(
            "runner 仅接受 Oracle MySQL 8.0.45：版本不符，"
            f"实际为 {mysql_version} ({version_comment})")


def probe_mysql_server_provenance(cur):
    """Read and validate the server identity before any write-path statement."""
    cur.execute("SELECT VERSION(), @@version_comment")
    row = cur.fetchone()
    if not row or len(row) != 2:
        raise ValueError("无法完整回读 MySQL 版本与发行注释")
    mysql_version, version_comment = row
    assert_mysql_server_provenance(mysql_version, version_comment)
    return str(mysql_version), str(version_comment)


def probe_transaction_server_contract(cur, statements):
    """Read transaction facts, then mutate and clean one session-local CHECK probe."""
    cur.execute(
        "SELECT @@GLOBAL.gtid_mode, @@GLOBAL.enforce_gtid_consistency, "
        "@@SESSION.binlog_format, @@SESSION.transaction_isolation"
    )
    row = cur.fetchone()
    if not row or len(row) != 4:
        raise ValueError("无法完整回读事务所需 MySQL 变量")
    gtid_mode, enforce_gtid, binlog_format, isolation = row
    gtid_mode = str(gtid_mode).upper()
    enforce_gtid = str(enforce_gtid).upper()
    binlog_format = str(binlog_format).upper()
    isolation = str(isolation).upper()

    uses_temporary_tables = any(
        _TRANSACTION_TEMPORARY_DDL.match(statement.lstrip()) for statement in statements)
    if uses_temporary_tables and enforce_gtid == "ON" and binlog_format != "ROW":
        raise ValueError(
            "GTID 一致性与临时表事务不兼容: "
            f"gtid_mode={gtid_mode}, enforce_gtid_consistency={enforce_gtid}, "
            f"binlog_format={binlog_format};必须为 binlog_format=ROW")

    check_error, cleanup_error = assert_check_enforcement_and_same_session_cleanup(cur)
    return (gtid_mode, enforce_gtid, binlog_format, isolation,
            check_error, cleanup_error)


def assert_innodb_references(cur, current_db, statements):
    """Fail before BEGIN unless every referenced persistent table is InnoDB."""
    table_names = referenced_persistent_tables(statements, current_db)
    if not table_names:
        raise ValueError("--transaction 未识别出任何持久表，无法证明事务引擎")
    placeholders = ",".join(["%s"] * len(table_names))
    cur.execute(
        "SELECT TABLE_NAME, ENGINE FROM INFORMATION_SCHEMA.TABLES "
        f"WHERE TABLE_SCHEMA=%s AND TABLE_NAME IN ({placeholders})",
        (current_db, *table_names),
    )
    observed = {str(name).lower(): str(engine) for name, engine in cur.fetchall()}
    missing = [name for name in table_names if name.lower() not in observed]
    non_innodb = [
        f"{name}={observed[name.lower()]}"
        for name in table_names
        if name.lower() in observed and observed[name.lower()].lower() != "innodb"
    ]
    if missing:
        raise ValueError("无法验证持久表引擎（表不存在或解析不完整）: " + ", ".join(missing))
    if non_innodb:
        raise ValueError("--transaction 拒绝非 InnoDB 持久表: " + ", ".join(non_innodb))
    return table_names


def close_preserving_active_exception(resource, label):
    """Close resources without replacing SystemExit(2) or another active failure."""
    active_exception = sys.exc_info()[0] is not None
    try:
        resource.close()
    except Exception as error:
        if not active_exception:
            raise
        print(f"{label}关闭失败（保留原始退出语义）: {error}")


def requires_community_legacy_owner_guard(statements):
    """Recognize the write itself, including renamed files and inline extracts."""
    targets = r"community_post(?:_media|_reference|_comment_action|_comment|_member_action)?"
    for statement in statements:
        sql = _mask_literals_and_comments(executable_statement(statement)).lower()
        if (re.search(r"\bcreate\s+temporary\s+table\s+community_legacy_route_repair\b", sql)
                and "ums_creative_square" in sql):
            return True
        if (re.search(r"\b(?:insert\s+(?:ignore\s+)?into|update)\s+(?:(?:`[^`]+`|[a-z0-9_]+)\s*\.\s*)?`?" + targets + r"`?\b", sql)
                and re.search(r"\b(?:ums_creative_square(?:_like)?|ums_comment(?:_like)?)\b", sql)):
            return True
    return False


def assert_community_legacy_statement_scope(statements, db):
    for statement in statements:
        sql = _mask_literals_and_comments(executable_statement(statement)).lower()
        for schema in re.findall(
                r"(`[^`]+`|[a-z0-9_]+)\s*\.\s*`?(?:community_post(?:_[a-z_]+)?|ums_creative_square(?:_like)?|ums_comment(?:_like)?)`?\b", sql):
            if schema.strip('`') != db.lower():
                raise ValueError("legacy owner preflight rejects cross-schema source/target")


def assert_community_legacy_counter_preflight(cur, db, statements):
    """Predict the retained broad prefix assertions, without weakening them."""
    text = "\n".join(executable_statement(statement).lower() for statement in statements)
    prefixes = (
        ("legacy-square-like-%", "community_post_member_action", """
          SELECT NOT EXISTS(SELECT 1 FROM community_post_member_action a
            WHERE a.action_type='LIKE' AND a.request_id LIKE 'legacy-square-like-%' AND NOT EXISTS(
              SELECT 1 FROM ums_creative_square_like l JOIN ums_creative_square s ON s.id=l.data_id
              WHERE l.like_type=1 AND l.member_id=a.member_id AND s.member_id IS NOT NULL
                AND a.post_id=l.data_id AND a.action_type='LIKE' AND a.source='LEGACY_MIGRATION'
                AND a.request_id=CONCAT('legacy-square-like-',l.id)))"""),
        ("legacy-comment-like-%", "community_post_comment_action", """
          SELECT NOT EXISTS(SELECT 1 FROM community_post_comment_action a
            WHERE a.action_type='LIKE' AND a.request_id LIKE 'legacy-comment-like-%' AND NOT EXISTS(
              SELECT 1 FROM ums_comment_like l JOIN ums_comment c ON c.id=l.comment_id
              JOIN ums_creative_square s ON s.id=c.owner_id AND s.member_id IS NOT NULL
              WHERE c.owner_type=3 AND c.member_id IS NOT NULL AND l.member_id=a.member_id
                AND a.comment_id=l.comment_id AND a.action_type='LIKE'
                AND a.request_id=CONCAT('legacy-comment-like-',l.id)))"""),
    )
    for prefix, target, query in prefixes:
        if not re.search(r"request_id\s+like\s+'" + re.escape(prefix) + "'", text):
            continue
        cur.execute("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s AND table_name=%s", (db, target))
        if cur.fetchone() == (0,):
            continue
        cur.execute(query)
        if cur.fetchone() != (1,):
            raise ValueError("legacy owner preflight counter provenance ambiguous: " + target)


def assert_community_legacy_owner_preflight(cur, db):
    """Read all legacy identity conflicts before *any* statement can commit."""
    cur.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=%s", (db,))
    tables = {row[0].lower() for row in cur.fetchall()}
    comment_tree = """WITH RECURSIVE legacy_owner_comment_tree AS (
      SELECT id, owner_id, id root_id FROM ums_comment
      WHERE owner_type=3 AND (reply_id IS NULL OR reply_id=0)
      UNION ALL
      SELECT child.id, child.owner_id, tree.root_id FROM ums_comment child
      JOIN legacy_owner_comment_tree tree ON child.reply_id=tree.id AND child.owner_id=tree.owner_id
      WHERE child.owner_type=3) """
    if {"ums_comment", "ums_creative_square"}.issubset(tables):
        cur.execute(comment_tree + """SELECT NOT EXISTS (
          SELECT 1 FROM ums_comment c JOIN ums_creative_square s ON s.id=c.owner_id
          LEFT JOIN legacy_owner_comment_tree tree ON tree.id=c.id AND tree.owner_id=c.owner_id
          WHERE c.owner_type=3 AND c.member_id IS NOT NULL AND s.member_id IS NOT NULL
            AND tree.id IS NULL)""")
        if cur.fetchone() != (1,):
            raise ValueError("legacy owner preflight conflict: source comment tree")
    checks = [
        ("source-post-like-unique", {"ums_creative_square_like", "ums_creative_square"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_creative_square_like l JOIN ums_creative_square s ON s.id=l.data_id
            WHERE l.like_type=1 AND l.member_id IS NOT NULL AND s.member_id IS NOT NULL
            GROUP BY l.data_id,l.member_id HAVING COUNT(*)>1)"""),
        ("source-comment-like-unique", {"ums_comment_like", "ums_comment", "ums_creative_square"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_comment_like l JOIN ums_comment c ON c.id=l.comment_id
            JOIN ums_creative_square s ON s.id=c.owner_id AND s.member_id IS NOT NULL
            WHERE c.owner_type=3 AND c.member_id IS NOT NULL AND l.member_id IS NOT NULL
            GROUP BY l.comment_id,l.member_id HAVING COUNT(*)>1)"""),
        ("comment-target", {"ums_comment", "ums_creative_square", "community_post"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_comment c JOIN community_post p ON p.id=c.owner_id
            WHERE c.owner_type=3 AND c.member_id IS NOT NULL AND NOT EXISTS(
              SELECT 1 FROM ums_creative_square s WHERE s.id=p.id AND s.member_id=p.author_id
                AND p.client_request_id=CONCAT('legacy-square-',s.id)))"""),
        ("post-like-target", {"ums_creative_square_like", "ums_creative_square", "community_post"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_creative_square_like l JOIN community_post p ON p.id=l.data_id
            WHERE l.like_type=1 AND l.member_id IS NOT NULL AND NOT EXISTS(
              SELECT 1 FROM ums_creative_square s WHERE s.id=p.id AND s.member_id=p.author_id
                AND p.client_request_id=CONCAT('legacy-square-',s.id)))"""),
        ("comment-like-target", {"ums_comment_like", "ums_comment", "ums_creative_square", "community_post", "community_post_comment"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_comment_like l JOIN community_post_comment n ON n.id=l.comment_id
            WHERE l.member_id IS NOT NULL AND NOT EXISTS(
              SELECT 1 FROM ums_comment c JOIN ums_creative_square s ON s.id=c.owner_id
              JOIN community_post p ON p.id=s.id AND p.author_id=s.member_id
                AND p.client_request_id=CONCAT('legacy-square-',s.id)
              WHERE c.owner_type=3 AND c.member_id=n.author_id AND c.id=n.id AND c.owner_id=n.post_id
                AND n.client_request_id=CONCAT('legacy-square-comment-',c.id)))"""),
        ("post", {"ums_creative_square", "community_post"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_creative_square s JOIN community_post p
            ON p.id=s.id OR (p.author_id=s.member_id AND p.client_request_id=CONCAT('legacy-square-',s.id))
            WHERE s.member_id IS NOT NULL AND NOT
              (p.id=s.id AND p.author_id=s.member_id AND p.client_request_id=CONCAT('legacy-square-',s.id)))"""),
        ("comment", {"ums_comment", "ums_creative_square", "community_post_comment"}, comment_tree + """
          SELECT NOT EXISTS(SELECT 1 FROM ums_comment c
            JOIN ums_creative_square s ON s.id=c.owner_id AND s.member_id IS NOT NULL
            JOIN legacy_owner_comment_tree tree ON tree.id=c.id AND tree.owner_id=c.owner_id
            JOIN community_post_comment n ON n.id=c.id OR
              (n.author_id=c.member_id AND n.client_request_id=CONCAT('legacy-square-comment-',c.id))
            WHERE c.owner_type=3 AND c.member_id IS NOT NULL AND NOT
              (n.id=c.id AND n.post_id=c.owner_id AND n.author_id=c.member_id
               AND n.client_request_id=CONCAT('legacy-square-comment-',c.id)
               AND (n.parent_id <=> NULLIF(c.reply_id,0))
               AND (n.root_id <=> CASE WHEN c.reply_id IS NULL OR c.reply_id=0 THEN NULL ELSE tree.root_id END)))"""),
        ("media", {"ums_creative_square", "community_post", "community_post_media"}, """
          SELECT NOT EXISTS(SELECT 1 FROM community_post_media m WHERE
            (m.upload_request_id LIKE 'legacy-square-media-%' OR
             m.upload_request_id LIKE 'legacy-square-file-%' OR m.upload_request_id LIKE 'legacy-square-route-%')
            AND NOT EXISTS(SELECT 1 FROM ums_creative_square s JOIN community_post p
              ON p.id=s.id AND p.author_id=s.member_id AND p.client_request_id=CONCAT('legacy-square-',s.id)
              WHERE m.owner_member_id=s.member_id AND m.post_id=p.id AND
                (m.upload_request_id LIKE CONCAT('legacy-square-media-',s.id,'-%') OR
                 m.upload_request_id LIKE CONCAT('legacy-square-file-',s.id,'-%') OR
                 m.upload_request_id=CONCAT('legacy-square-route-',s.id))))"""),
        ("post-like", {"ums_creative_square_like", "ums_creative_square", "community_post_member_action"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_creative_square_like l
            JOIN ums_creative_square s ON s.id=l.data_id AND s.member_id IS NOT NULL
            JOIN community_post_member_action a ON (a.post_id=l.data_id AND a.member_id=l.member_id AND a.action_type='LIKE')
               OR (a.member_id=l.member_id AND a.request_id=CONCAT('legacy-square-like-',l.id))
            WHERE l.like_type=1 AND l.member_id IS NOT NULL AND NOT
              (a.post_id=l.data_id AND a.member_id=l.member_id AND a.action_type='LIKE'
               AND a.request_id=CONCAT('legacy-square-like-',l.id) AND a.source='LEGACY_MIGRATION'))"""),
        ("comment-like", {"ums_comment_like", "ums_comment", "community_post_comment_action"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_comment_like l JOIN ums_comment c
            ON c.id=l.comment_id AND c.owner_type=3 AND c.member_id IS NOT NULL
            JOIN community_post_comment_action a ON (a.comment_id=l.comment_id AND a.member_id=l.member_id AND a.action_type='LIKE')
              OR (a.member_id=l.member_id AND a.request_id=CONCAT('legacy-comment-like-',l.id))
            WHERE l.member_id IS NOT NULL AND NOT
              (a.comment_id=l.comment_id AND a.member_id=l.member_id AND a.action_type='LIKE'
               AND a.request_id=CONCAT('legacy-comment-like-',l.id)))"""),
        ("reference", {"ums_creative_square", "community_post", "community_post_reference"}, """
          SELECT NOT EXISTS(SELECT 1 FROM community_post_reference r
            WHERE JSON_EXTRACT(r.snapshot_json,'$.legacy')=TRUE AND NOT EXISTS(
              SELECT 1 FROM ums_creative_square s JOIN community_post p
                ON p.id=s.id AND p.author_id=s.member_id AND p.client_request_id=CONCAT('legacy-square-',s.id)
              WHERE r.post_id=p.id AND r.reference_id=s.data_id
                AND r.reference_type=CASE WHEN s.data_type=1 THEN 'ACTIVITY' WHEN s.data_type=2 THEN 'TOPIC' WHEN s.data_type=3 THEN 'ROUTE' ELSE '' END))"""),
        ("reference-key", {"ums_creative_square", "community_post", "community_post_reference"}, """
          SELECT NOT EXISTS(SELECT 1 FROM ums_creative_square s
            JOIN community_post p ON p.id=s.id
            JOIN community_post_reference r ON r.post_id=s.id AND r.reference_id=s.data_id
              AND r.reference_type=CASE WHEN s.data_type=1 THEN 'ACTIVITY' WHEN s.data_type=2 THEN 'TOPIC' ELSE 'ROUTE' END
            WHERE s.data_id>0 AND s.data_type IN(1,2,3) AND NOT
              (p.author_id=s.member_id AND p.client_request_id=CONCAT('legacy-square-',s.id)
               AND (JSON_EXTRACT(r.snapshot_json,'$.legacy') <=> TRUE)))"""),
    ]
    for label, required, query in checks:
        targets = {name for name in required if name.startswith("community_")}
        if not targets.issubset(tables):
            continue  # First schema installation has no existing target rows to collide.
        if not required.issubset(tables):
            raise ValueError("legacy owner preflight missing source: " + label)
        cur.execute(query)
        row = cur.fetchone()
        if row is None or len(row) != 1 or row[0] != 1:
            raise ValueError("legacy owner preflight conflict: " + label + "; restore verified backup; no writes permitted")
    print("legacy owner preflight: all existing identity mappings passed (read-only)")


def run_connected(args, statements, splitter_src, conn, db):
    """Run the database-backed part of the CLI and always close its cursor."""
    cur = conn.cursor()
    try:
        if statements:
            try:
                mysql_version, version_comment = probe_mysql_server_provenance(cur)
            except ValueError as error:
                print(f"写路径来源检查失败: {error}")
                sys.exit(1)
            print(f"MySQL 版本:{mysql_version}; version_comment:{version_comment}")
        else:
            cur.execute("SELECT VERSION()")
            print("MySQL 版本:", cur.fetchone()[0])
        if args.transaction:
            print("事务边界:只承诺白名单形状 + 已回读为 InnoDB 的持久表；不适用于任意 migration。")

        if args.check:
            return

        if args.describe:
            cur.execute(
                """SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
                   FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s ORDER BY ORDINAL_POSITION""",
                (db, args.describe))
            rows = cur.fetchall()
            if not rows:
                print(f"表 {args.describe} 不存在或无列")
            else:
                print(f"表 {args.describe} 的列:")
                for row in rows:
                    print(f"  {row[0]:<28} {row[1]:<18} null={row[2]:<3} {row[3]}")
            return

        if splitter_src:
            print(f"分句器:{splitter_src}")

        if requires_community_legacy_owner_guard(statements):
            print("REQUIRED: exclusive maintenance window; stop application writes before legacy DDL/backfill. "
                  "Without that locked window do not execute; read-only preflight is not a concurrency lock.")
            try:
                assert_community_legacy_statement_scope(statements, db)
                assert_community_legacy_owner_preflight(cur, db)
                assert_community_legacy_counter_preflight(cur, db, statements)
            except Exception as error:
                print(f"legacy owner preflight failed before all DDL/DML: {error}")
                sys.exit(1)

        if args.transaction:
            try:
                server_facts = probe_transaction_server_contract(cur, statements)
                innodb_tables = assert_innodb_references(cur, db, statements)
            except ValueError as error:
                print(f"事务前置检查失败: {error}")
                sys.exit(1)
            (gtid_mode, enforce_gtid, binlog_format, isolation,
             check_error, cleanup_error) = server_facts
            print(
                f"事务前置检查:CHECK probe={check_error}; "
                f"same-session cleanup={cleanup_error}; "
                f"gtid_mode={gtid_mode}; enforce_gtid_consistency={enforce_gtid}; "
                f"binlog_format={binlog_format}; transaction_isolation={isolation}"
            )
            print("事务前置检查:全部持久表均为 InnoDB: " + ", ".join(innodb_tables))
            conn.autocommit(False)
            conn.begin()

        for index, statement in enumerate(statements, 1):
            preview = " ".join(statement.split())
            print(f"\n[{index}/{len(statements)}] {preview[:200]}")
            try:
                cur.execute(statement)
                assertion = statement_assertion(statement)
                if assertion:
                    observed = assert_statement_result(cur, assertion, index)
                    print(f"  断言通过:{assertion}; {observed}")
                print("  OK")
            except Exception as error:
                print(f"  失败: {error}")
                if args.transaction:
                    try:
                        conn.rollback()
                        print("ROLLBACK OK")
                    except Exception as rollback_error:
                        print(f"ROLLBACK UNKNOWN: {rollback_error}")
                        print("事务结果不确定，禁止自动重跑；必须从新连接 readback 后人工处置。")
                        sys.exit(2)
                sys.exit(1)

        if args.transaction:
            try:
                conn.commit()
                print("COMMIT OK")
            except Exception as commit_error:
                print(f"COMMIT UNKNOWN: {commit_error}")
                print("事务结果不确定，禁止自动重跑；必须从新连接 readback 后人工处置。")
                sys.exit(2)

        print("\nSQL 提交完成；仍须从新连接回读业务表，提交回执不能代替效果观测。")
    finally:
        close_preserving_active_exception(cur, "cursor")


def main():
    ap = argparse.ArgumentParser(allow_abbrev=False)
    ap.add_argument("--config", default=argparse.SUPPRESS)
    ap.add_argument("--check", action="store_true", help="只读:打印 MySQL 版本")
    ap.add_argument("--describe", metavar="TABLE", help="只读:打印某表的列")
    ap.add_argument("--sql", metavar="FILE", help="执行 .sql 文件")
    ap.add_argument("--exec", metavar="STMT", dest="stmt", help="执行单条内联语句")
    ap.add_argument("--dry-run", action="store_true", help="只打印不执行")
    ap.add_argument("--transaction", action="store_true",
                    help="仅 --sql:在显式事务内执行事务安全语句")
    args = ap.parse_args()

    config_explicit = hasattr(args, "config")
    if not config_explicit:
        args.config = DEFAULT_CONFIG

    if (args.sql or args.stmt) and not args.dry_run and not config_explicit:
        ap.error(
            "非 dry-run 的 --sql / --exec 必须显式传 --config；"
            "默认配置仅供只读 --check / --describe")

    if args.transaction and not args.sql:
        ap.error("--transaction 仅适用于 --sql")
    if args.dry_run and (args.check or args.describe):
        ap.error("--dry-run 与 --check / --describe 互斥；dry-run 不会连接服务器")

    statements = []
    splitter_src = None
    if args.sql:
        split_statements, splitter_src = load_split_statements()
        if split_statements is None:
            sys.exit(
                "找不到分句器 ci/sql_splitter.py。\n"
                "  找过:cwd 的 git 根 / %s\n"
                "  它是 CI 门禁与本执行器共用的【唯一真源】,本脚本不留副本 ——\n"
                "  退回裸 split(';') 会把字符串里带分号的语句从中间劈开 → 1064 或跑错,\n"
                "  而那正是 autoRefundTask 8 天零告警的病根,所以这里宁可停也不猜。\n"
                "  怎么办:在城瘾仓库目录下跑,或先 git pull(该文件 2026-07-16 随 PR #64 进 master)。"
                % FALLBACK_REPO)
        with open(args.sql, encoding="utf-8") as sql_file:
            sql_text = sql_file.read()
        if required_transaction_declared(sql_text) and not args.transaction:
            ap.error(
                "SQL 声明 runner: requires=transaction；必须显式传 --transaction，"
                "已在 connect() 前拒绝")
        statements = split_statements(sql_text)
    elif args.stmt:
        statements = [args.stmt.strip().rstrip(";")]
    elif not (args.check or args.describe):
        sys.exit("需指定 --check / --describe / --sql / --exec 之一")

    if args.sql and not statements:
        sys.exit("没有可执行的语句")

    try:
        for statement in statements:
            statement_assertion(statement)
    except ValueError as error:
        sys.exit(str(error))

    if args.transaction:
        try:
            validate_transaction_statements(statements)
        except ValueError as error:
            sys.exit(str(error))

    if args.dry_run:
        if splitter_src:
            print(f"分句器:{splitter_src}")
        for i, statement in enumerate(statements, 1):
            preview = " ".join(statement.split())
            print(f"\n[{i}/{len(statements)}] {preview[:200]}")
            print("  (dry-run 未执行)")
            assertion = statement_assertion(statement)
            if assertion:
                print(f"  (已声明执行后断言:{assertion}; dry-run 不会冒充通过)")
        print("\ndry-run 完成：未连接数据库，未执行任何语句。")
        print("dry-run 不证明服务器变量、持久表 InnoDB、锁影响或真实 MySQL 行为。")
        return

    conn, db = connect(args.config)
    try:
        run_connected(args, statements, splitter_src, conn, db)
    finally:
        close_preserving_active_exception(conn, "connection")


if __name__ == "__main__":
    main()
