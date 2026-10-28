#!/usr/bin/env python3
"""迁移与定时任务的 CI 门禁判定逻辑。由 ci/migration-safety-check.sh 调用。

三道门,各有各的靶子(设计见 backlog设计/CI门禁_迁移与定时任务_设计_2026-07-15.md):

  1-b  新增/修改的迁移 SQL:引号闭合 + 拦 DELIMITER 形状(存储过程/触发器)
  2    有 task bean 就必须有注册 SQL —— 否则 jar 部署了也永不调度,且零告警
  3    PR 动了迁移就必须显式声明 —— 直接防 44797404 那个病(标题写 test(xcx) 却夹带碰钱迁移)

【为什么门禁只管这些】
机器可判定的才归门禁:能不能执行、有没有配套注册、有没有声明。
业务正确性(cron 合不合理、status 该 0 还是 1)机器判不了,不碰。

【反向的「悬空注册」不在这儿】
「库里注册了但 bean 不存在」由 chengyin-db-migrate skill 的 audit_sys_job.py 管 ——
它要连生产库,CI 里放生产库凭据是更大的问题。它的位置是人工/定时,不是 PR 门禁。
(实证:migration_c3_team_job.sql 至今注册着 autoRefundTask,而该 bean 已从代码里拆除 ——
 这是【注销没落库】,反射性补注册会把一条全自动微信原路退款接回生产。方向搞反会出事。)
"""
import ast
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sql_splitter import (  # noqa: E402
    delimiter_error,
    executable_statement,
    split_statements,
    split_statements_with_delimiters,
    unterminated_token,
)

# 注册 SQL 的搜索范围。chengyinhub-admin/sql = 业务迁移;sql/ = RuoYi 自带基础库
# (ryTask 的 sys_job 注册就在 sql/ry_20250522.sql —— 它真在那儿,不是给它开的后门)。
SQL_DIRS = ("chengyinhub-admin/sql", "sql")

# 不在库上跑的辅助件,不能当注册来源 —— 口径与 ci/migration-layout-check.sh 检查 2 一致
# (那里把这三个前缀定义为「确实不在库上跑的辅助件」)。历史部署包里躺着一条 INSERT,
# 不等于生产库里有这条注册。⚠️ seed_ 刻意【不】排除:它正是铁律的由来,
# 且 refundReconcileTask 的真注册就在 seed_refund_manual_menu_20260714.sql(实测排掉它会假红)。
_AUX_SQL_PREFIXES = ("_precheck_", "_deploy_", "bak_")

# 只认 INSERT/REPLACE。DELETE FROM sys_job 是【注销】,把它算成注册 = 方向搞反:
# 实测仓库里有 7 条 DELETE FROM sys_job ... invoke_target='xxxTask.run()'。
_INSERT_STMT = re.compile(r"^\s*(?:INSERT|REPLACE)\b", re.IGNORECASE)

# 复合 routine 必须由 tokenizer 识别到的自定义 delimiter 完整结束。
# 唯一默认分号形状是下方完整匹配的单条拒绝 SIGNAL trigger，没有内部语句分隔符。
# 只解析 CREATE 头部：字符串/普通注释正文不会被当成 routine，
# 而 /*!... DEFINER=...*/ 是 MySQL 可执行注释，其中的 token 仍必须参与判定。
_ROUTINE_KINDS = {"PROCEDURE", "FUNCTION", "TRIGGER"}
_SQL_IDENTIFIER_PATTERN = r"[0-9A-Za-z_$\u0080-\uffff]+"
_SQL_IDENTIFIER = re.compile(_SQL_IDENTIFIER_PATTERN)
_SQL_TOKEN = re.compile(
    r"/\*!\s*\d*\s*|\*/|"
    r"'(?:\\.|''|[^'])*'|\"(?:\\.|\"\"|[^\"])*\"|`(?:``|[^`])*`|"
    + _SQL_IDENTIFIER_PATTERN + r"|[@=()]|\S"
)
_QUOTED_VALUE = re.compile(r"'(?:''|\\.|[^'])*'|\"(?:\"\"|\\.|[^\"])*\"")
_READBACK_DECLARATION = re.compile(
    r"(?mi)^\s*--\s*(?:readback\s*[:：]|回读合同\b|执行后的对端回读必须)[^\r\n]*$")
# 只允许跳过语句最前面的 SQL 注释后锚定 SELECT；不得用 substring 把 UPDATE ... SELECT 误判成回读。
_LEADING_SQL_COMMENTS_THEN_SELECT = re.compile(
    r"\A\s*(?:(?:--[^\r\n]*(?:\r?\n|\Z)|/\*.*?\*/)\s*)*SELECT\b",
    re.IGNORECASE | re.DOTALL,
)

_INVALID_DEFINER = object()

_REJECT_TRIGGER_IDENTIFIER = r"(?:`(?:``|[^`])+`|[A-Za-z_$][A-Za-z0-9_$]*)"
_SINGLE_REJECT_TRIGGER = re.compile(
    r"CREATE\s+TRIGGER\s+" + _REJECT_TRIGGER_IDENTIFIER
    + r"\s+BEFORE\s+(?:UPDATE|DELETE)\s+ON\s+" + _REJECT_TRIGGER_IDENTIFIER
    + r"(?:\s*\.\s*" + _REJECT_TRIGGER_IDENTIFIER + r")?"
    + r"\s+FOR\s+EACH\s+ROW\s+SIGNAL\s+SQLSTATE\s+'45000'"
    + r"\s+SET\s+MESSAGE_TEXT\s*=\s*'(?:''|\\.|[^'\\])*'\s*",
    re.IGNORECASE | re.DOTALL,
)


def _create_tokens(statement):
    """CREATE 头部 token；可执行注释外壳透明，引号账号保留为单 token。"""
    tokens = []
    for match in _SQL_TOKEN.finditer(statement):
        token = match.group(0)
        if token.startswith("/*!") or token == "*/":
            continue
        if token[0] in "'\"`":
            tokens.append(("QUOTED", token))
        elif _SQL_IDENTIFIER.fullmatch(token):
            tokens.append(("WORD", token.upper()))
        else:
            tokens.append(("SYMBOL", token))
    return tokens


def _valid_account_part(token):
    """账号的 user/host 段：引号内不得为空；未引号 identifier 不得为纯数字。"""
    kind, value = token
    if kind == "QUOTED":
        return len(value) > 2
    return kind == "WORD" and not value.isdigit()


def _created_routine_kind(statement):
    """返回 routine 类型；非 routine 返回 None，非法 DEFINER 返回失败标记。"""
    tokens = _create_tokens(statement)
    if not tokens or tokens[0] != ("WORD", "CREATE"):
        return None
    pos = 1
    if tokens[pos:pos + 2] == [("WORD", "OR"), ("WORD", "REPLACE")]:
        pos += 2
    if pos >= len(tokens):
        return None

    # CREATE VIEW 的可选头部有固定顺序；只从 CREATE 后向前消费，
    # 不在 VIEW 正文搜 ALGORITHM/DEFINER/SQL SECURITY 关键字。
    view_only_header_seen = False
    if tokens[pos] == ("WORD", "ALGORITHM"):
        view_only_header_seen = True
        pos += 1
        if pos >= len(tokens) or tokens[pos] != ("SYMBOL", "="):
            return _INVALID_DEFINER
        pos += 1
        if pos >= len(tokens) or tokens[pos] not in {
                ("WORD", "UNDEFINED"), ("WORD", "MERGE"), ("WORD", "TEMPTABLE")}:
            return _INVALID_DEFINER
        pos += 1

    if pos >= len(tokens):
        return _INVALID_DEFINER if view_only_header_seen else None
    if not view_only_header_seen and tokens[pos][0] == "WORD" and tokens[pos][1] in _ROUTINE_KINDS:
        return tokens[pos][1]
    has_definer = tokens[pos] == ("WORD", "DEFINER")
    if not has_definer and tokens[pos] != ("WORD", "SQL"):
        if view_only_header_seen:
            return None if tokens[pos] == ("WORD", "VIEW") else _INVALID_DEFINER
        return None

    # DEFINER = user，其中 user 是完整 account：user_name[@host_name]，
    # 两段均可为合法裸 identifier 或独立引号 token；亦支持 CURRENT_USER[()]。
    if has_definer:
        pos += 1
        if pos >= len(tokens) or tokens[pos] != ("SYMBOL", "="):
            return _INVALID_DEFINER
        pos += 1
        if pos >= len(tokens):
            return _INVALID_DEFINER

        if tokens[pos] == ("WORD", "CURRENT_USER"):
            pos += 1
            if pos < len(tokens) and tokens[pos] == ("SYMBOL", "("):
                if pos + 1 >= len(tokens) or tokens[pos + 1] != ("SYMBOL", ")"):
                    return _INVALID_DEFINER
                pos += 2
        elif _valid_account_part(tokens[pos]):
            pos += 1
            if pos < len(tokens) and tokens[pos] == ("SYMBOL", "@"):
                pos += 1
                if pos >= len(tokens) or not _valid_account_part(tokens[pos]):
                    return _INVALID_DEFINER
                pos += 1
        else:
            return _INVALID_DEFINER

    # VIEW 允许 DEFINER 之后再出现 SQL SECURITY；只消费这一个明确头部子句。
    if tokens[pos:pos + 2] == [("WORD", "SQL"), ("WORD", "SECURITY")]:
        view_only_header_seen = True
        pos += 2
        if pos >= len(tokens) or tokens[pos] not in {
                ("WORD", "DEFINER"), ("WORD", "INVOKER")}:
            return _INVALID_DEFINER
        pos += 1

    if pos >= len(tokens) or tokens[pos][0] != "WORD":
        return _INVALID_DEFINER
    object_kind = tokens[pos][1]
    if view_only_header_seen:
        return None if object_kind == "VIEW" else _INVALID_DEFINER
    if object_kind in _ROUTINE_KINDS:
        return object_kind
    if object_kind in {"VIEW", "EVENT"}:
        return None
    return _INVALID_DEFINER

# P1-3A core migration 的历史奖励回填会改变业务键/旧三元组。若 guard 在它之后，
# 既有坏数据会先被部分写入，之后才 fail-closed。因此这个文件的 preflight guard
# 必须先于第一条历史奖励 UPDATE 真正执行。
_P1_FUNDS_CORE_MIGRATION = "migration_p1_funds_core_uniqueness_20260724.sql"
_P1_HISTORICAL_REWARD_UPDATE = re.compile(r"^\s*UPDATE\s+ums_member_points_detail\b", re.IGNORECASE | re.MULTILINE)
_P1_PREFLIGHT_GUARD = re.compile(
    r"INSERT\s+INTO\s+p1_funds_core_guard\s*\(\s*id\s*\)\s*"
    r"SELECT\s+1\s+FROM\s*\(",
    re.IGNORECASE | re.DOTALL,
)

# ⚠️ 别改回「只认 @Component("xxxTask") 字面量」:实测四种逃逸都能被 quartz 真调度却对门禁隐形
# —— 裸 @Component + 类名 FooTask(Spring 默认 bean 名 fooTask)、@Component(value="fooTask")、
# @Service("fooTask")、bean 名叫 fooJob。根因见 JobInvokeUtil.invokeMethod:它走
# SpringUtils.getBean(beanName),认的是【bean 名】,不是注解里有没有写字面量。
_STEREOTYPE = r"@(?:Component|Service)"
_BEAN_NAMED = re.compile(_STEREOTYPE + r'\s*\(\s*(?:value\s*=\s*)?"([A-Za-z0-9_$]+)"\s*\)')
# 裸注解:bean 名 = 类名首字母小写(Spring 的 Introspector.decapitalize)
_BEAN_BARE = re.compile(_STEREOTYPE + r'\s*(?:\(\s*\))?\s*(?:@\w+(?:\([^)]*\))?\s*)*'
                        r'(?:public\s+|final\s+)*class\s+([A-Za-z0-9_$]+)')
_TASKISH = re.compile(r"(?:Task|Job)$")
# 判别式:@Scheduled = Spring 自调度,不进 sys_job ⇒ 豁免。
# 实测(2026-07-16,master 702d6077):新正则捕获 18 个 *Task/*Job bean,
# 其中【恰好 1 个】命中豁免 —— AccountDeregistrationJob(裸 @Component + @Scheduled(cron)),
# 其余 17 个全是 quartz 任务且全有注册 SQL。豁免不是近似,是精确到人头核过的。
_SELF_SCHEDULED = re.compile(r"@Scheduled\b")


def _default_bean_name(cls):
    """Spring 默认 bean 名规则:首字母小写;但前两个字母都大写时原样保留(AIJob → AIJob)。"""
    if len(cls) > 1 and cls[0].isupper() and cls[1].isupper():
        return cls
    return cls[0].lower() + cls[1:]


def _has_top_level_where(statement):
    """只认外层 WHERE；子查询或反引号标识符里的 WHERE 不能给全表写开绿灯。"""
    depth = 0
    i = 0
    while i < len(statement):
        ch = statement[i]
        if ch == "`":
            i += 1
            while i < len(statement):
                if statement[i] == "`":
                    if i + 1 < len(statement) and statement[i + 1] == "`":
                        i += 2
                        continue
                    break
                i += 1
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth = max(0, depth - 1)
        elif depth == 0 and statement[i:i + 5].upper() == "WHERE":
            before = statement[i - 1] if i > 0 else " "
            after = statement[i + 5] if i + 5 < len(statement) else " "
            if not (before.isalnum() or before in "_$") and not (after.isalnum() or after in "_$"):
                return True
        i += 1
    return False


def _unbounded_write_kind(statement):
    """返回无边界写的语句类型，否则返回 None。

    这里只分类首个关键字，不尝试写 SQL parser：UPDATE/DELETE 必须带
    WHERE，TRUNCATE 天生是全表写，直接拒绝。DELETE 的 IGNORE/LOW_PRIORITY/
    alias 只改变后续语法，不影响这个判定。
    """
    match = re.match(r"^\s*([A-Za-z]+)\b", statement)
    if not match:
        return None
    keyword = match.group(1).upper()
    if keyword in ("UPDATE", "DELETE"):
        return keyword if not _has_top_level_where(statement) else None
    if keyword == "TRUNCATE":
        return keyword
    return None


# PR 描述里的迁移声明。判据不是「出现过 ③/迁移」,而是【影响分类段里标了 ③】。
# ⚠️ 裸子串匹配与真相【反相关】,别改回去:2026-07-16 实测,6 个「无迁移」PR 里 5 个
# 会被旧规则判为已声明 —— 因为 body 写着「无迁移」,而「无迁移」含「迁移」二字。
# 写「本 PR 不含迁移」= 完成声明,正是 44797404 那个病。
# ③ 单独也不作数:本仓 ①②③ 是列举序号(#80「### ③ NPC 收编进网关」与分类无关)。
_DECLARE_ANCHOR = "影响分类"
# 窗口=10 行:本仓真实格式是「## 影响分类」标题 + 下方表格/列表里写 ③,不是同一行。
# 实测(7 个真迁移 PR):窗口 1→5 红、3→4 红、6→1 红、10→全绿。10 是最小可用值。
_DECLARE_WINDOW = 10
# 被否定的 ③ 不算声明:「无 ③」「③ 迁移：无」「| ③ 迁移 | ✗ |」= 说自己没迁移,同「不含迁移」一个病。
# 前缀:③ 所在分句(;；。·|，, 切开)里出现否定词 ——「无 ②,③ 迁移」不误伤。
#   「/」「、」故意不切:「无 ① / ③ / ④」是整串被否定;代价是「无 ② / ③ 迁移:x.sql」偏严判红,改写即可。
# 后缀:③ 后第一个冒号/竖线/破折号后的值【整体】是否定词(后面只跟标点或行尾),
#   「③ 迁移：无损加列」「③ 迁移 — 无 schema 改动」不算否定;分句切断处不越过,免得吃到下一项 ④ 的「无」。
# 词表取自 7-01 起 556 个真实 PR 描述;66 个真带迁移的 PR 判定与改前逐一相同(无误红)。
_NEGATION = "无|没有|不含|不涉及|未涉及|不命中|未命中"
_NEGATED_PREFIX = re.compile(
    r"(%s|零|\[ \]|\bno\b|\bnone\b)[^;；。·|，,]*$" % _NEGATION, re.IGNORECASE)
_NEGATED_SUFFIX = re.compile(
    r"(?:[^:：|—;；。·，,]*[:：|—]+)?[\s*`]*"
    r"(无\s*(?:迁移|SQL)|%s|均无|否|不需要|不适用|✗|❌|N/A|0|none|no)"
    r"(?=\s*(?:$|[*`。.；;，,:：|、·()（）]))" % _NEGATION, re.IGNORECASE)


def _declares_migration(line):
    """该行里有一个没被否定的 ③。"""
    return any(not _NEGATED_PREFIX.search(line[:m.start()])
               and not _NEGATED_SUFFIX.match(line, m.end())
               for m in re.finditer("③", line))


# ── 门禁 1-b:新增/修改的迁移 SQL 必须能被真执行器正确分句 ──────────────
def check_sql_text(path, text):
    """返回问题列表(空 = 通过)。判据是【符号闭合】+【DELIMITER 严格解析】。

    ⚠️ 判据「拆出的语句数 == 未注释的分号数」已作废,别再加回来:
    「数分号」和「裸 split(';')」是同一个计算的两次表达 —— 裸切段数【恒等于】分号总数,
    对任何文件都恒绿,互相印证不出任何问题。(设计文档举的 3 == 3 连算术都是错的:
    真病例文件只有 1 条语句、2 个分号,实测 2 == 2。数字错了,结论对。)
    """
    problems = []

    # 判据①【对整个文件判,不是逐条判】:没闭合的引号/块注释会把后面的内容整块吞掉,
    # 吞完之后剩下的每条语句自己都是「干净」的 —— 逐条判恒绿,漏得无声无息。
    未闭合 = unterminated_token(text)
    if 未闭合:
        problems.append(
            "%s: 有未闭合的 %s —— 后面的内容会被整块吞掉 → 落库时 1064,\n"
            "      或语句被静默丢弃(不报错、不炸、零告警)" % (path, 未闭合)
        )

    分隔符错误 = delimiter_error(text)
    if 分隔符错误:
        problems.append("%s: %s" % (path, 分隔符错误))

    statements = []
    for idx, (st, terminator) in enumerate(split_statements_with_delimiters(text), 1):
        statements.append(st)
        # runner 元数据是注释里的机器合同,不是 SQL。routine 头解析与无 WHERE 判定都必须看
        # 剥掉它之后的可执行 SQL,否则 `-- runner: assert=` 前缀会让 CREATE 不再是第一个 token
        # ⇒ routine 门禁静默瞎掉(两侧测试仍全绿,零告警)。
        executable = executable_statement(st)
        shape = _created_routine_kind(executable)
        if shape is _INVALID_DEFINER:
            problems.append(
                "%s: 第 %d 条语句的 CREATE DEFINER 账号或对象头不完整/非法，\n"
                "      无法安全判定是否为 routine；必须 fail-closed。" % (path, idx)
            )
        elif shape and (terminator is None or terminator == ";") and not (
                shape == "TRIGGER" and terminator == ";"
                and _SINGLE_REJECT_TRIGGER.fullmatch(executable)):
            problems.append(
                "%s: 第 %d 条语句是 CREATE %s,但没有由自定义 DELIMITER 完整结束,\n"
                "      BEGIN...END 里的分号会被切错；必须使用受校验的独立行 DELIMITER 指令。"
                % (path, idx, shape)
            )
        masked = _QUOTED_VALUE.sub(" ", executable)
        unbounded_kind = _unbounded_write_kind(masked)
        if unbounded_kind:
            problems.append(
                "%s: 第 %d 条 %s 没有 WHERE（TRUNCATE 等同全表无 WHERE 写），"
                "可能扩大到全表；必须改为窄范围写入或用可执行 guard 明确证明范围"
                % (path, idx, unbounded_kind)
            )
    if _READBACK_DECLARATION.search(text):
        for idx, st in enumerate(statements, 1):
            executable = executable_statement(st)
            if _LEADING_SQL_COMMENTS_THEN_SELECT.match(executable) and executable == st.lstrip():
                problems.append(
                    "%s: 第 %d 条声明为 readback/回读的 SELECT 未绑定 runner 断言；"
                    "执行器会丢弃观察结果，必须使用 scalar-true 等机器断言" % (path, idx)
                )
    if os.path.basename(path) == _P1_FUNDS_CORE_MIGRATION:
        first_backfill = _P1_HISTORICAL_REWARD_UPDATE.search(text)
        preflight_guard = _P1_PREFLIGHT_GUARD.search(text)
        if not preflight_guard or (first_backfill and preflight_guard.start() > first_backfill.start()):
            problems.append(
                "%s: P1 资金历史奖励 UPDATE 早于 fail-closed preflight guard —— "
                "坏既有数据会先被部分回填；guard 必须先验证投影后的回填结果" % path
            )
    return problems


# ── 门禁 2:有 task bean 就必须有注册 SQL ────────────────────────────
def find_task_beans(root):
    """扫【全仓库】java 里的 *Task / *Job bean,返回 ({需注册: 路径}, {豁免: 路径})。

    ⚠️ 不限定 **/task/ 目录 —— 这是这个仓库拿钱换来的教训:
    「保护半径的另一半是【目录】」。前缀门只看文件名,结果 #43/#48 的迁移前缀对、
    却落在仓库根 sql/,被 -maxdepth 1 漏掉 → 永不落库、零告警。
    同理:只扫 task/ 目录,一个放在 tasks/ 或 schedule/ 的 quartz 任务就【无声逃逸】
    —— 而逃逸的后果正是本门禁要防的那个病(部署了也永不调度)。
    宁可对未来某个非 quartz 的 *Task bean 误红(响的、一眼能解释),
    也不要让真任务从门禁边上溜过去(静默的、8 天没人发现)。
    实测(2026-07-16,master 552a5cb2):18 个现存 *Task bean 全是 quartz 任务,全绿。

    豁免的唯一口径是 @Scheduled(Spring 自调度,不经 quartz、不进 sys_job)。
    豁免【必须被打印出来】—— 可见的豁免才是豁免,静默的豁免就是洞。
    """
    beans, exempt = {}, {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if d not in (".git", "node_modules", "target")]
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            full = os.path.join(dirpath, fn)
            with open(full, encoding="utf-8") as f:
                text = f.read()
            m = _BEAN_NAMED.search(text)
            if m:
                bean = m.group(1)
            else:
                m = _BEAN_BARE.search(text)
                if not m:
                    continue
                bean = _default_bean_name(m.group(1))
            if not _TASKISH.search(bean):
                continue
            rel = os.path.relpath(full, root)
            if _SELF_SCHEDULED.search(text):
                exempt[bean] = rel
            else:
                beans[bean] = rel
    return beans, exempt


def find_registered_targets(root):
    """返回所有 .sql 里【真会执行的】invoke_target 引用文本(注释已剥掉)。

    ⚠️ 必须剥注释再搜:一条被注释掉的 INSERT INTO sys_job 长得和真注册一模一样,
    裸 grep 会把它算成「有注册」→ 假绿。这正是本轮要杀的静默失败类型,
    所以复用 sql_splitter(它剥注释),而不是 grep。
    """
    blobs = []
    for d in SQL_DIRS:
        base = os.path.join(root, d)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for fn in filenames:
                if not fn.endswith(".sql") or fn.startswith(_AUX_SQL_PREFIXES):
                    continue
                full = os.path.join(dirpath, fn)
                with open(full, encoding="utf-8", errors="replace") as f:
                    blobs.extend(s for s in split_statements(f.read())
                                 if _INSERT_STMT.match(s))
    return "\n".join(blobs)


def _strip_java_comments(text):
    # Migration ledger tools are packaged independently; keep their lexical dependency local.
    return re.sub(_QUOTED_VALUE.pattern + r"|//[^\n]*|/\*[\s\S]*?\*/",
                  lambda match: match.group(0) if match.group(0).startswith(('"', "'")) else " ", text)


def find_leader_scheduled_beans(root, beans):
    """Only the actual startup registration and matching typed callback replace sys_job."""
    path = os.path.join(root, "chengyinhub-admin/src/main/java/com/chengyinhub/web/job/LeaderScheduledJobsConfig.java")
    if not os.path.isfile(path):
        return {}
    with open(path, encoding="utf-8") as source:
        text = _strip_java_comments(source.read())
    if "import com.chengyinhub.quartz.service.MySqlSchedulerCoordinator;" not in text or not re.search(r"@Configuration\b", text):
        return {}
    receiver = re.search(r"private\s+final\s+MySqlSchedulerCoordinator\s+(\w+)\s*;", text)
    lifecycle = re.search(r"@PostConstruct\s+public\s+void\s+register\s*\(\s*\)\s*\{([^{}]*)\}", text)
    if receiver is None or lifecycle is None:
        return {}
    fields = dict((name, kind) for kind, name in re.findall(r"private\s+final\s+([\w.]+)\s+(\w+)\s*;", text))
    calls = re.compile(r"\s*" + re.escape(receiver.group(1)) + r'\.register(?:Cron|FixedDelay)\(\s*"(\w+)\.(\w+)\(\)"[^;]*,\s*(\w+)::(\w+)\s*\);')
    body = lifecycle.group(1)
    if calls.sub("", body).strip():
        return {}
    registered = {}
    for bean, method, callback, callback_method in calls.findall(body):
        if bean not in beans:
            continue
        with open(os.path.join(root, beans[bean]), encoding="utf-8") as source:
            target = _strip_java_comments(source.read())
        kind = re.search(r"\bclass\s+(\w+)\b", target)
        if method == callback_method and kind and fields.get(callback) == kind.group(1) \
                and re.search(r"\bpublic\s+[\w.<>]+\s+" + re.escape(method) + r"\s*\(\s*\)", target):
            registered[bean] = beans[bean]
    return registered


def find_unregistered_beans(root):
    """返回 [(bean, java 路径)] —— 有 bean 却搜不到注册 SQL 的。

    【方向是单向的】:有 bean 必须有注册。bean 被删时注册 SQL 也该没,门禁不该因此报错
    —— 所以只遍历现存 bean,不反查悬空注册(那是 audit_sys_job.py 的活)。
    """
    registered = find_registered_targets(root)
    beans = find_task_beans(root)[0]
    leader_scheduled = find_leader_scheduled_beans(root, beans)
    missing = []
    for bean, java in sorted(beans.items()):
        # invoke_target 长这样:'refundReconcileTask.reconcile()'
        # 带前引号搜,免得 fooTask 被 barFooTask 的注册蹭中
        if bean not in leader_scheduled and "'%s." % bean not in registered:
            missing.append((bean, java))
    return missing


# ── 门禁 3:PR 动了迁移就必须显式声明 ────────────────────────────────
def check_pr_declaration(title, body, migration_files):
    """迁移文件有变动、而 PR 没在【影响分类段】标 ③ → 返回问题描述,否则 None。

    判据是【锚点 + 窗口】:某行出现「影响分类」,且该行起 _DECLARE_WINDOW 行内出现 ③。
    锚点缺一不可 —— 只认 ③ 会被列举序号骗,只认「迁移」会被「无迁移」骗(反相关)。
    被否定的 ③(「无 ③」「③ 迁移：无」)不算,见 _declares_migration。
    """
    if not migration_files:
        return None
    lines = ("%s\n%s" % (title or "", body or "")).split("\n")
    for i, ln in enumerate(lines):
        if _DECLARE_ANCHOR in ln and any(map(_declares_migration, lines[i:i + _DECLARE_WINDOW])):
            return None
    return ("PR 动了迁移文件,但【影响分类】段里没标 ③(「无 ③」「③ 迁移：无」这类否定写法不算):\n%s"
            % "\n".join("      - %s" % f for f in migration_files))


# ── CLI ────────────────────────────────────────────────────────────
DEPENDENCIES_JSON = "ci/migration-dependencies.json"


def _collation_key(name):
    """一个「忽略标点」的排序键 —— 用来模拟 glibc/en_US 那类 locale collation。

    ★ 为什么需要它(G6):门禁比先后用的是 Python 的【码点序】,而人手里的 `ls` 用的是
      【locale 序】,两者对 `_` 与字母、`.` 与 `_` 的相对次序不一致。实测在本仓真实文件名上
      **74 处位置不同**(master 与候选都是 74),不是理论风险:
        码点序:  migration_club.sql              < migration_club_apply_fields.sql  ('.'0x2E < '_'0x5F)
        locale:  migration_club_apply_fields.sql < migration_club.sql
      也就是说「门禁绿」原本【不等于】「按 ls 出来的顺序跑安全」—— 而这道门禁的全部价值
      就建立在「人会按 ls 顺序跑」这个前提上,前提对不上的话保证是空的。

    做法不是去读运行时 locale(CI 与本机 locale 不一定一样,判定会随环境漂),
    而是要求依赖在【两种序】下都排在前面:码点序 + 这个忽略标点的模型序。
    两者都满足 = 无论哪种 collation 都安全;两者打架 = 这一对本来就有歧义,该改名消歧。
    """
    return re.sub(r"[^0-9A-Za-z]", "", name).lower()


def check_dependency_order(batch_files, all_migration_files, dependencies):
    """本批要落库的迁移里,按【文件名排序】跑会不会踩到「依赖排在后面」。

    为什么门禁盯的是【批内】而不是全仓:
      全仓早就不满足「文件名序 == 拓扑序」—— master 上就有 16 对历史违反。那些没出事,
      是因为它们是【写一条跑一条】、按写作时间落的库,批内先后从来没被考验过。
      真正会咬人的形状是「攒一批未落库的迁移,一次性按文件名顺序跑下去」——
      2026-08-18 在生产 schema 快照上实测:探店日候选那 25 条按文件名跑,
      migration_20260817_tandianri_r7_distribution_backfill.sql 第 1 句就
      `1054 Unknown column 'organizer.declared_by'` 当场挂,因为加那一列的
      migration_shop_day_organizer_actor_20260815.sql 被文件名序排到了它后面。

    所以判据是:**本批新增的迁移之间**,依赖必须排在被依赖者之后。
    指向批外(即已在 master、生产上早已跑过)的依赖不算数 —— 那些已经就位了。

    ⚠️ 不要把它放宽成「给出一个拓扑序就行」:人手里没有拓扑序,人手里只有 `ls`。
       门禁要守的是「按最自然的做法跑也不会错」,不是「记得先算一遍拓扑序」。

    返回违反项列表 [(晚跑的文件, 它依赖但排在更后面的文件)],空表示通过。
    """
    batch = set(batch_files)
    known = set(all_migration_files)
    stem_to_file = {}
    for name in known:
        if name.startswith("migration_") and name.endswith(".sql"):
            stem_to_file[name[len("migration_"):-len(".sql")]] = name
    violations = []
    for stem, deps in sorted(dependencies.items()):
        name = stem_to_file.get(stem)
        if name is None or name not in batch:
            continue
        for dep_stem in deps:
            dep_name = stem_to_file.get(dep_stem)
            if dep_name is None or dep_name not in batch:
                continue          # 批外依赖:已在 master / 生产上就位
            # ★ 两种序都要满足(见 _collation_key):码点序 + 忽略标点的模型序。
            #   只比码点序的话,「门禁绿」不等于「按 ls 跑安全」—— 实测两者在本仓有 74 处分歧。
            if dep_name >= name or _collation_key(dep_name) >= _collation_key(name):
                violations.append((name, dep_name))
    return violations


def check_dependency_ratchet(current_deps, master_deps):
    """依赖声明【只许加不许删】(相对该批固定祖先基线)。

    没有这一条,上面那道顺序门一行 JSON 就能消掉:把 A 依赖 B 那条边删了,顺序门看不见边、
    当场转绿,而落库时该 1054 还是 1054。**「闸红了就把闸拆了」是最省事的绕法,必须先堵死。**

    ⚠️ 与「本批新增必须有条目」是两回事:那条管【新文件不许沉默】,这条管【老边不许消失】。
    ⚠️ 判据是「master 有的,现在必须还有」,不是「不许改」——
       加边、加新条目都放行;真要删边,得先把这道闸的红看清楚再决定。
    """
    problems = []
    for stem, deps in sorted(master_deps.items()):
        if stem not in current_deps:
            problems.append("依赖条目被整条删掉了: %s" % stem)
            continue
        lost = [d for d in deps if d not in current_deps[stem]]
        for d in sorted(lost):
            problems.append("依赖边被删掉了: %s 不再声明依赖 %s" % (stem, d))
    return problems


def master_dependencies(root, base=None):
    """Read the batch's fixed ancestor, never a later live-master declaration set.

    Without --base, use the merge-base with the original remote preference. Explicit
    unreadable/non-ancestor bases fail closed; they must not fall back to another ref.
    """
    refs = (base,) if base is not None else ("github/master", "origin/master")
    for ref in refs:
        try:
            resolved = subprocess.run(
                ["git", "-C", root, "rev-parse", "--verify", "%s^{commit}" % ref],
                capture_output=True, text=True)
            if resolved.returncode != 0:
                continue
            commit = resolved.stdout.strip()
            if base is None:
                common = subprocess.run(
                    ["git", "-C", root, "merge-base", commit, "HEAD"],
                    capture_output=True, text=True)
                if common.returncode != 0:
                    continue
                commit = common.stdout.strip()
            ancestor = subprocess.run(
                ["git", "-C", root, "merge-base", "--is-ancestor", commit, "HEAD"],
                capture_output=True, text=True)
            if ancestor.returncode != 0:
                continue
            out = subprocess.run(
                ["git", "-C", root, "show", "%s:%s" % (commit, DEPENDENCIES_JSON)],
                capture_output=True, text=True)
            if out.returncode == 0:
                return json.loads(out.stdout)["dependencies"], commit
        except (OSError, ValueError, KeyError):
            pass
    return None, None


def check_dependency_declared(batch_files, dependencies):
    """本批【新增】的迁移必须在 ci/migration-dependencies.json 里有条目(空数组也算)。

    为什么要这一条:顺序门只读已声明的边 —— **没人强制新增迁移补声明,不写就恒绿**。
    于是「不能漏」这条裁决此前只对已声明者成立,门禁可以一边报「0 对违反」,
    一边有 20% 的批内文件从来没被看过一眼。沉默不该是一种可以通过的状态。

    ⚠️ 只对【本批新增】要求。全仓 236 条里有 204 条(86%)没有条目 —— 那是历史,
       要求全量补等于一上线就红且永久红,门禁会被绕过(同 dep-order 只看批内的理由)。

    ⚠️ 已知天花板,老实写在这:写 `[]` 就能过。想不清楚依赖的人会直接填空数组当橡皮图章。
       但它仍然比沉默强 —— 空数组是一句【写在 diff 里、有人签字、可被 review 质疑】的断言,
       而沉默什么都不是。真正要根治得从 SQL 正文推导批内引用关系(见报告 F3 的建议)。
    """
    return [f for f in sorted(batch_files)
            if f[len("migration_"):-len(".sql")] not in dependencies]


def load_dependencies(root):
    path = os.path.join(root, DEPENDENCIES_JSON)
    with open(path, encoding="utf-8") as f:
        return json.load(f)["dependencies"]


def select_added_migrations(root, base):
    """本批 = 本分支【新增】的迁移(--diff-filter=A),不含仅被修改的历史迁移。

    ★ 2026-08-18 收紧。原先用的是 --diff-filter=AM(与门禁 2/4 同一份 CHANGED),
      结果把「已在 master、已在生产落库、本分支只改了一行注释」的迁移也算进批,
      于是它跟本批新增文件比先后 —— 报出一条**假阳性**。
      实证代价:候选那边为了消这条违反,去回退那处注释,撞上契约测试才发现改错了。
      **会误报的门禁和不会红的门禁一样会被绕过,而且更坏:它诱导人去改坏本来正确的东西。**

      判据回到第一性:这道门禁问的是「这一批**要落库**的迁移,按文件名跑会不会踩依赖」。
      仅被修改的历史迁移【已经在生产上跑过了】,它不在本次落库批次里,不该参与排序。

    ⚠️ --no-renames:改名是修这类顺序问题的正规手法(候选就是这么修的)。
      对 master 上的文件改名,三点 diff 会算成 R,--diff-filter=A 就看不见新名字了 ——
      那正好漏掉「刚被改名以修顺序」的那个文件。加上 --no-renames 让它拆成 A + D,
      新名字照样进批。(本分支自己新增的文件不受影响,它们本来就是纯 A。)

    门禁 2(分句)与门禁 4(PR 声明)仍然用 AM —— 改了历史迁移的内容确实要重新分句、
    也确实要在 PR 里声明。只有「排序」这件事只关新增的。
    """
    out = subprocess.run(
        ["git", "-C", root, "diff", "--no-renames", "--name-only", "--diff-filter=A",
         "%s...HEAD" % base, "--", SQL_DIRS[0]],
        capture_output=True, text=True)
    if out.returncode != 0:
        return None
    names = []
    for line in out.stdout.split():
        rel = line.strip()
        if not rel:
            continue
        head, name = os.path.split(rel)
        if head != SQL_DIRS[0]:          # 只认第一层,子目录里的不是要落库的迁移
            continue
        if not (name.startswith("migration_") and name.endswith(".sql")):
            continue
        if name.startswith(_AUX_SQL_PREFIXES):
            continue
        names.append(name)
    return names


def check_manifest_dependency_order(manifest, batch, dependencies):
    """Validate the actual ledger order, including every declared edge and batch path."""
    migrations = manifest.get("migrations", [])
    problems = []
    ids = [m["migrationId"] for m in migrations]
    paths = [m["path"] for m in migrations]
    if len(ids) != len(set(ids)) or len(paths) != len(set(paths)):
        problems.append("duplicate migration identity or path")
    positions = {migration_id: index for index, migration_id in enumerate(ids)}
    for name in batch:
        path = SQL_DIRS[0] + "/" + name
        if paths.count(path) != 1:
            problems.append("batch path missing or duplicated: " + path)
    for index, migration in enumerate(migrations, 1):
        migration_id = migration["migrationId"]
        expected = sorted(set(dependencies.get(migration_id, [])))
        if migration.get("order") != index or migration.get("dependsOn") != expected:
            problems.append("ledger order/dependency metadata mismatch: " + migration_id)
        for dependency in expected:
            if dependency not in positions or positions[dependency] >= positions[migration_id]:
                problems.append("dependency must run first: %s -> %s" % (migration_id, dependency))
    for migration_id in dependencies:
        if migration_id not in positions:
            problems.append("declared dependency owner missing: " + migration_id)
    return problems


def _schema_contract_dump(node):
    # Python 3.13+ omits empty AST lists by default. The reviewed fingerprints
    # include them (e.g. Call.keywords=[]); retain those exact bytes on hosted
    # Python 3.14 instead of replacing hashes or accepting a second source plan.
    if sys.version_info >= (3, 13):
        return ast.dump(node, show_empty=True)
    return ast.dump(node)


def check_schema_manifest_order_source(source):
    """Exact ledger pipeline + the two approved fresh-only forward compositions."""
    import hashlib

    try:
        tree = ast.parse(source)
    except SyntaxError:
        return ["schema runner syntax invalid"]
    imported = any(isinstance(node, ast.ImportFrom) and node.module == "ci.migration_ledger"
                   and any(alias.name == "build_manifest" and alias.asname is None
                           for alias in node.names) for node in tree.body)
    mains = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "main"]
    if not imported or len(mains) != 1:
        return ["schema runner must import the shared build_manifest and define main"]
    main = mains[0]
    problems = []
    # Original notice source fingerprints remain pinned; broadcast uses an exact AST literal below, not a second
    # migration/file allowlist. Parse only: never import or execute the runner under review.
    # Pin the selector's identity/dependency checks and its loopback/scratch guard too;
    # a valid call site cannot lend proof to a fake replacement or a foreign database.
    contracts = {
        'ALLOWED_HOSTS': '2b430974e32ce8845a3849c5ed78816bcd63829a6503d7b414927801cc68ddd4',
        'SCRATCH_SCHEMA': 'd01e4e12bb1f2547ede9032f190e258fb07b90ade698fbc547bd7ef6c1933020',
        'validate_target': 'e81885977856a74699cea1967c18a1017b3731d498b749c51d0f8a11171dd4a1',
        'connect': '61c0251d63524541132ffdfe1fc5586126daddcc609bc0134045f35644d4801b',
        'NOTICE_FORWARD_FILES': '7b5ec4c83406a6ae16a674593400f24808a3cf81774b8ffebf0dd0c9792f274b',
        'NOTICE_PRODUCER': '22159e17f7ec6a3148ce47336d07e02077dc34a698d507a61369b7efb2707196',
        'NOTICE_OLD': 'ea92bb087d55f82092043e1eaa8a0ef4eb035a1bbdf675cf71961f339715a2bd',
        'NOTICE_V2': 'fc92ac6b04a54d3f69d4c748819a46f0e820da9781647928fc91a32f6108704d',
        'notice_forward_plan': 'df04e9a8cbbfdd83df9920382eadff6c4fdddbcf9dcefa121ab1f8bb3b316ca3',
    }
    for name, digest in contracts.items():
        definitions = [node for node in tree.body
                       if (isinstance(node, ast.FunctionDef) and node.name == name)
                       or (isinstance(node, ast.Assign) and len(node.targets) == 1
                           and isinstance(node.targets[0], ast.Name) and node.targets[0].id == name)]
        if len(definitions) != 1:
            problems.append("schema runner must preserve notice forward contract: " + name)
            continue
        node = definitions[0]
        if isinstance(node, ast.FunctionDef):
            # Exclude Python 3.12's added *empty* type_params field from the digest.
            if node.decorator_list or node.returns or node.type_comment or getattr(node, "type_params", []):
                problems.append("schema runner must preserve notice forward function: " + name)
            shape = repr((_schema_contract_dump(node.args),
                          [_schema_contract_dump(statement) for statement in node.body]))
        else:
            shape = _schema_contract_dump(node)
        if hashlib.sha256(shape.encode()).hexdigest() != digest:
            problems.append("schema runner must preserve notice forward contract: " + name)


    # Exact SOURCE contract for the root-approved b1d9 broadcast selector. Compare
    # ASTs without importing/executing the runner; formatting/comments may differ.
    # Keeping the full literal avoids computing new project AST hashes during SOURCE-only delivery.
    broadcast_contract_source = r'''BROADCAST_D = "20261023_d_official_broadcast_delivery"
BROADCAST_OLD = "20261026_official_broadcast_delivery"
BROADCAST_UNION = "20261028_official_broadcast_protocol_union"
BROADCAST_FORWARD_FILES = {
    BROADCAST_D: ("chengyinhub-admin/sql/migration_20261023_d_official_broadcast_delivery.sql",
                  "c822ad772727ed4ae0328998f7a99be1ece5d6c881f6259fc63de3ebb0822ba1"),
    BROADCAST_OLD: ("chengyinhub-admin/sql/migration_20261026_official_broadcast_delivery.sql",
                    "ca5538fae79b01ac4690065da6cde4ac3b7922dce5c0d09a04a5ff58bca1076f"),
    BROADCAST_UNION: ("chengyinhub-admin/sql/migration_20261028_official_broadcast_protocol_union.sql",
                      "44dc1ee52ab1591f0e893f26aaed8b6b1af4948ab53f98307c41a30c637b8b8b"),
}


def broadcast_forward_plan(migrations, selected):
    ids = {BROADCAST_OLD, BROADCAST_UNION}
    paths = {BROADCAST_FORWARD_FILES[key][0] for key in ids}
    if not any(row.get("migrationId") in ids or row.get("path") in paths
               for row in migrations + selected):
        return None
    positions, pending_positions = {}, {}
    for key, (path, expected_sha) in BROADCAST_FORWARD_FILES.items():
        rows = [(i, row) for i, row in enumerate(migrations)
                if row.get("migrationId") == key or row.get("path") == path]
        pending = [(i, row) for i, row in enumerate(selected)
                   if row.get("migrationId") == key or row.get("path") == path]
        if len(rows) != 1 or len(pending) != 1:
            raise RuntimeError("broadcast forward requires complete pending D/old/union: " + key)
        index, entry = rows[0]
        pending_index, pending_entry = pending[0]
        target = REPO / path
        deps = [BROADCAST_D, BROADCAST_OLD] if key == BROADCAST_UNION else ["official_broadcast"]
        if (entry.get("migrationId") != key or entry.get("path") != path
                or entry.get("sha256") != expected_sha or pending_entry != entry
                or entry.get("order") != index + 1 or entry.get("executionPhase") is not None
                or entry.get("dependsOn") != deps
                or target.is_symlink() or not target.is_file()
                or hashlib.sha256(target.read_bytes()).hexdigest() != expected_sha):
            raise RuntimeError("broadcast forward identity/fingerprint/dependency/order drift: " + key)
        positions[key], pending_positions[key] = index, pending_index
    upstream = [i for i, row in enumerate(migrations) if row.get("migrationId") == "official_broadcast"]
    if (len(upstream) != 1 or upstream[0] >= positions[BROADCAST_D]
            or not positions[BROADCAST_D] < positions[BROADCAST_OLD] < positions[BROADCAST_UNION]
            or not pending_positions[BROADCAST_D] < pending_positions[BROADCAST_OLD] < pending_positions[BROADCAST_UNION]):
        raise RuntimeError("broadcast forward prerequisite/sequence drift")
    return {
        "fingerprints": {key: {"path": value[0], "sha256": value[1]}
                         for key, value in BROADCAST_FORWARD_FILES.items()},
        "selected": [row for row in selected if row["migrationId"] != BROADCAST_OLD],
    }
'''
    broadcast_contracts = {
        node.name if isinstance(node, ast.FunctionDef) else node.targets[0].id: node
        for node in ast.parse(broadcast_contract_source).body
    }
    for name, wanted in broadcast_contracts.items():
        definitions = [node for node in tree.body
                       if (isinstance(node, ast.FunctionDef) and node.name == name)
                       or (isinstance(node, ast.Assign) and len(node.targets) == 1
                           and isinstance(node.targets[0], ast.Name) and node.targets[0].id == name)]
        if len(definitions) != 1 or ast.dump(definitions[0]) != ast.dump(wanted):
            problems.append("schema runner must preserve broadcast forward contract: " + name)
    contract_names = set(contracts) | set(broadcast_contracts)

    expected = [
        'manifest = build_manifest(REPO, target_commit=head, dependency_path=REPO / "ci/migration-dependencies.json")',
        'all_migrations = manifest["migrations"]',
        'selected = migrations_after_baseline(all_migrations)',
        'notice_forward = notice_forward_plan(all_migrations, selected)',
        'selected = notice_forward["selected"] if notice_forward else selected',
        'broadcast_forward = broadcast_forward_plan(all_migrations, selected)',
        'selected = broadcast_forward["selected"] if broadcast_forward else selected',
        'deferred = [m for m in selected if m.get("executionPhase") == "POST_DEPLOY"]',
        'ordered = [m for m in selected if m.get("executionPhase") != "POST_DEPLOY"]',
    ]
    indices = []
    for statement in expected:
        wanted = ast.parse(statement).body[0]
        target = wanted.targets[0].id
        writes = [node for node in ast.walk(main) if isinstance(node, ast.Name)
                  and isinstance(node.ctx, (ast.Store, ast.Del)) and node.id == target]
        matches = [i for i, node in enumerate(main.body) if ast.dump(node) == ast.dump(wanted)]
        if len(matches) != 1 or len(writes) != (3 if target == "selected" else 1):
            problems.append("schema runner must preserve ledger selection: " + target)
        else:
            indices.append(matches[0])

    # This whole direct try/finally is the sole legal preparation/reporting block.
    # The two exact selected rebindings above are the only approved replacement filters.
    # The exact absence check, CREATE without IF NOT EXISTS, reporting and close all
    # precede imports/run_file. Existing or concurrently created schemas cannot be adopted.
    expected_prepare = ast.parse("""\
try:
    cur = c.cursor()
    cur.execute("SELECT VERSION(), @@version_comment")
    ver = cur.fetchone()
    print("目标实例: %s / %s  @ %s:%d" % (ver[0], ver[1], a.host, a.port))
    if notice_forward or broadcast_forward:
        # This check precedes every DROP/CREATE/import, including explicit --drop.
        cur.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s", (a.schema,))
        fresh = cur.fetchone()
        if (not isinstance(fresh, (tuple, list)) or len(fresh) != 1
                or type(fresh[0]) is not int or fresh[0] != 0):
            raise RuntimeError("notice forward requires a nonexistent fresh scratch schema; --drop cannot bypass")
        # No IF NOT EXISTS: a concurrent creator must fail, never adopt an unknown/typed schema.
        cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % a.schema)
        for forward, old, new in ((notice_forward, NOTICE_OLD, NOTICE_V2),
                                  (broadcast_forward, BROADCAST_OLD, BROADCAST_UNION)):
            if forward:
                print("REPLACED_UNAPPLIED %s -> %s fingerprints=%s" %
                      (old, new, json.dumps(forward["fingerprints"], sort_keys=True)))
    else:
        if a.drop:
            cur.execute("DROP DATABASE IF EXISTS `%s`" % a.schema)
        cur.execute("CREATE DATABASE IF NOT EXISTS `%s` DEFAULT CHARACTER SET utf8mb4" % a.schema)
finally:
    c.close()
""").body[0]
    expected_connect = ast.parse('c = connect(a)').body[0]
    expected_reconnect = ast.parse('c = connect(a, a.schema)').body[0]
    preparations = [i for i, node in enumerate(main.body) if ast.dump(node) == ast.dump(expected_prepare)]
    if (len(preparations) != 1 or preparations[0] == 0
            or ast.dump(main.body[preparations[0] - 1]) != ast.dump(expected_connect)
            or preparations[0] + 1 >= len(main.body)
            or ast.dump(main.body[preparations[0] + 1]) != ast.dump(expected_reconnect)):
        problems.append("schema runner must preserve fresh-only notice preparation")
    validation = ast.parse('validate_target(a)').body[0]
    validated = [i for i, node in enumerate(main.body) if ast.dump(node) == ast.dump(validation)]
    if len(validated) != 1 or not indices or validated[0] >= indices[0]:
        problems.append("schema runner must validate its scratch target before selection")

    expected_loop = ast.parse(
        'for m in ordered:\n'
        '    strict_notice = ((bool(notice_forward) and m["migrationId"] == NOTICE_V2)\n'
        '                     or (bool(broadcast_forward) and m["migrationId"] == BROADCAST_UNION))\n'
        '    ok = run_file(cur, REPO / m["path"], m["migrationId"], results, '
        'keep_going=False if strict_notice else a.keep_going, schema_only=True, observations=observations)\n'
        '    if not ok and (strict_notice or not a.keep_going):\n'
        '        break\n').body[0]
    expected_close = ast.parse('c.close()').body[0]
    loops = []
    for i, node in enumerate(main.body):
        if (isinstance(node, ast.Try) and not node.handlers and not node.orelse
                and len(node.finalbody) == 1
                and ast.dump(node.finalbody[0]) == ast.dump(expected_close)):
            loops.extend(i for statement in node.body if ast.dump(statement) == ast.dump(expected_loop))
    ledger_loops = [node for node in ast.walk(main) if isinstance(node, ast.For)
                    and any(isinstance(reference, ast.Name) and reference.id == "ordered"
                            for reference in ast.walk(node.iter))]
    if (len(loops) != 1 or len(ledger_loops) != 1 or len(indices) != len(expected)
            or indices != sorted(indices) or len(preparations) != 1
            or not loops or indices[-1] >= preparations[0] or preparations[0] >= loops[0]):
        problems.append("schema runner must execute the ledger selection in its original order")

    calls = [node for node in ast.walk(main) if isinstance(node, ast.Call)
             and isinstance(node.func, ast.Name) and node.func.id == "run_file"]
    expected_calls = [ast.parse(expression, mode="eval").body for expression in (
        'run_file(cur, BASELINE, "baseline", results, keep_going=True)',
        'run_file(cur, BASELINE_SEED, "baseline-seed", results, keep_going=False)',
        'run_file(cur, REPO / m["path"], m["migrationId"], results, '
        'keep_going=False if strict_notice else a.keep_going, schema_only=True, observations=observations)',
    )]
    if sorted(map(ast.dump, calls)) != sorted(map(ast.dump, expected_calls)):
        problems.append("schema runner must not add or change run_file calls")
    connections = [node for node in ast.walk(main) if isinstance(node, ast.Call)
                   and isinstance(node.func, ast.Name) and node.func.id == "connect"]
    if sorted(map(ast.dump, connections)) != sorted(map(ast.dump, [expected_connect.value, expected_reconnect.value])):
        problems.append("schema runner must preserve its exact scratch connections")
    # The other four loops only report the two fixed replacements, failures/observations. An extra loop may not
    # replay another selection even if it does not mention `ordered`.
    if len([node for node in ast.walk(main) if isinstance(node, (ast.For, ast.AsyncFor, ast.While))]) != 5:
        problems.append("schema runner must not add execution loops")
    flow = {"manifest", "all_migrations", "selected", "ordered", "notice_forward", "broadcast_forward", "deferred"}
    protected = flow | contract_names | {"build_manifest", "migrations_after_baseline", "run_file"}
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, (ast.Store, ast.Del)) and node.id in protected:
            # Only the nine exact direct ledger assignments and the pinned top-level
            # constants are legal writes; no extra selected/ordered rebind is admitted.
            parents = [parent for parent in ast.walk(tree) if isinstance(parent, ast.Assign)
                       and len(parent.targets) == 1 and parent.targets[0] is node]
            allowed = any(parent in main.body and any(ast.dump(parent) == ast.dump(ast.parse(text).body[0])
                                                     for text in expected) for parent in parents)
            allowed = allowed or any(parent in tree.body and node.id in contract_names for parent in parents)
            if not allowed:
                problems.append("schema runner must not rebind ledger/notice contracts: " + node.id)
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            for alias in node.names:
                bound = alias.asname or alias.name.split(".")[0]
                if bound in protected and not (isinstance(node, ast.ImportFrom)
                        and node.module == "ci.migration_ledger" and alias.name == "build_manifest" and alias.asname is None):
                    problems.append("schema runner must not shadow ledger/notice contracts: " + bound)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            if isinstance(node.func.value, ast.Name) and node.func.value.id in flow:
                problems.append("schema runner must not mutate ledger selection")
        if (isinstance(node, ast.Attribute) and isinstance(node.ctx, (ast.Store, ast.Del))
                and isinstance(node.value, ast.Name) and node.value.id == "a"
                and node.attr in {"schema", "host", "port"}):
            problems.append("schema runner must not change its validated scratch target")
        if isinstance(node, (ast.Subscript, ast.Attribute)) and isinstance(node.ctx, (ast.Store, ast.Del)):
            if any(isinstance(ref, ast.Name) and ref.id in protected for ref in ast.walk(node.value)):
                problems.append("schema runner must not overwrite ledger/notice selection")
    return problems


def _cmd_dep_order(root, argv):
    """用法:dep-order --base <ref>   或   dep-order <文件...>(手工核查用)。

    --base 形态下由本函数自己算批(见 select_added_migrations)—— 批的口径就是这道门禁的
    判据本身,放在 shell 里算过一次就出过一次假阳性,收回来放在能被单测钉住的地方。
    """
    explicit_base = None
    if argv[:1] == ["--base"]:
        if len(argv) < 2:
            print("::error::dep-order --base 缺少基线 ref")
            return 1
        explicit_base = argv[1]
        batch = select_added_migrations(root, explicit_base)
        if batch is None:
            print("::error::无法读取指定基线 diff，不得跳过依赖检查")
            return 1
    else:
        batch = [os.path.basename(p) for p in argv
                 if os.path.basename(p).startswith("migration_")]
    dependencies = load_dependencies(root)

    # ★ 声明棘轮必须在「本批为空就跳过」【之前】跑:删一条依赖边跟本分支有没有新增迁移无关,
    #   任何一个 PR 都能删。放在早退之后 = 闸放错了层,一个不带新迁移的 PR 就能把边删干净。
    master_deps, master_ref = master_dependencies(root, explicit_base)
    if master_deps is None:
        if explicit_base is not None:
            print("::error::无法读取指定祖先基线的依赖声明，不得回退到 live master")
            return 1
        print("  (取不到 github/master 上的依赖声明,本次不校验「只许加不许删」)")
    else:
        lost = check_dependency_ratchet(dependencies, master_deps)
        if lost:
            print("::error::依赖声明被删了 —— 顺序门只看得见声明过的边,删边等于把闸拆了")
            for x in lost:
                print("  ✗ %s" % x)
            print("  为什么:这道门禁红的时候,最省事的绕法就是把那条边删掉 —— 闸当场转绿,")
            print("          而落库时该 1054 还是 1054。风险没消失,只是没人看见了。")
            print("  怎么修:把边加回去,改用【改名】消顺序违反。确实是声明写错了要删,")
            print("          单独一个 commit 写清为什么它不需要先跑。")
            return 1
        print("  OK: 依赖声明相对 %s 只增未减" % master_ref)

    if not batch:
        print("  (本分支没有【新增】迁移,跳过批内排序 —— 仅修改历史迁移不参与本批排序)")
        return 0
    undeclared = check_dependency_declared(batch, dependencies)
    if undeclared:
        print("::error::本批新增的迁移没有依赖声明 —— 顺序门只看得见已声明的边,不写就等于没被检查")
        for name in undeclared:
            print("  ✗ %s" % name)
        print("  为什么:这道门禁只读 %s。缺条目的文件,它连看都没看过一眼," % DEPENDENCIES_JSON)
        print("          却仍然会报「0 对违反」—— 那是一句假的全覆盖。沉默不该是能通过的状态。")
        print("  怎么修:在 %s 的 dependencies 里加一条," % DEPENDENCIES_JSON)
        print("          key 是去掉 migration_ 前缀和 .sql 后缀的文件名。")
        print("          确实不依赖本仓任何其它迁移,就写空数组 [] —— 那是一句明确的声明,不是省略。")
        return 1

    from migration_ledger import LedgerError, build_manifest
    try:
        target = subprocess.run(["git", "-C", root, "rev-parse", "HEAD"],
                                capture_output=True, text=True, check=True).stdout.strip()
        manifest = build_manifest(root, target_commit=target, dependencies=dependencies)
        violations = check_manifest_dependency_order(manifest, batch, dependencies)
        with open(os.path.join(root, "scripts/e2e/build_schema.py"), encoding="utf-8") as handle:
            violations.extend(check_schema_manifest_order_source(handle.read()))
    except (LedgerError, OSError, subprocess.CalledProcessError) as exc:
        print("::error::无法验证共享 migration 台账执行序: %s" % exc)
        return 1
    if violations:
        print("::error::共享 migration 台账或 schema 执行器依赖顺序不安全")
        for violation in violations:
            print("  ✗ " + violation)
        return 1
    print("  OK: 本批新增 %d 个迁移均被共享台账覆盖,全部依赖边顺序正确;schema 执行器使用同一拓扑序" % len(batch))
    print("  必须通过台账执行;裸 ls 顺序未经验证,不得用于落库。")
    return 0


def _cmd_sql(files):
    problems = []
    for path in files:
        with open(path, encoding="utf-8") as f:
            problems.extend(check_sql_text(path, f.read()))
    if problems:
        print("::error::迁移 SQL 无法被真执行器正确分句 —— 落库时会 1064 或跑错")
        for p in problems:
            print("  ✗ %s" % p)
        print("  为什么:CI 与 run_migration.py 共用 ci/sql_splitter.py 分句。这里切不对,")
        print("          落库时就切不对 —— 实证:autoRefundTask 因此 8 天零告警地从没跑过。")
        return 1
    print("  OK: %d 个变动的迁移文件均可被正确分句" % len(files))
    return 0


def _cmd_beans(root):
    _beans, exempt = find_task_beans(root)
    for bean, java in sorted(exempt.items()):
        print("  · 豁免(@Scheduled 自调度,不进 sys_job):%s  (%s)" % (bean, java))
    for bean, java in sorted(find_leader_scheduled_beans(root, _beans).items()):
        print("  · 已登记(MySqlSchedulerCoordinator 启动计划,不进 sys_job):%s  (%s)" % (bean, java))
    missing = find_unregistered_beans(root)
    if missing:
        print("::error::有 task bean 却没有配套的注册 SQL —— jar 部署了也永不调度,且零告警")
        for bean, java in missing:
            print("  ✗ %s  (%s)" % (bean, java))
        print("  为什么:@Component 只是把 bean 放进 Spring 容器,不等于 quartz 会调度它。")
        print("          没有 sys_job 注册 = 代码在、任务从不执行、不报错、零告警。")
        print("          实证:autoRefundTask 的注册 SQL 从写下那天起就跑不了 → 8 天没人发现。")
        print("  怎么修:在 %s/ 加 migration_<描述>_<日期>.sql," % SQL_DIRS[0])
        print("          内含 INSERT INTO sys_job ... invoke_target = '<bean>.<方法>()'")
        print("          暂停态(status='1')注册必须在 remark 写明「触发时机+负责人+到期日」。")
        return 1
    print("  OK: 全部 task bean 都有配套注册 SQL 或真实启动调度计划")
    return 0


def _cmd_pr_declare(files):
    title = os.environ.get("PR_TITLE")
    body = os.environ.get("PR_BODY")
    # ⚠️ 判「空」不能判 is None:push 事件里 ${{ github.event.pull_request.title }} 是 null,
    # 注入 env 后是【空字符串】而不是未设置 —— 判 None 会漏,把 push 当成「没声明的 PR」误红。
    # GitHub 不允许空标题,所以「标题和描述都空」= 不是 PR 上下文(push / 本地跑)。
    if not (title or body):
        print("  (非 PR 事件,拿不到标题/描述,跳过)")
        return 0
    problem = check_pr_declaration(title, body, files)
    if problem:
        print("::error::PR 夹带了迁移却没声明 —— 夹带必须无法静默")
        print("  ✗ %s" % problem)
        print("  为什么:44797404 那个 commit 标题写 test(xcx),却夹带了碰钱的 sys_job 注册。")
        print("          三道防线(review / CI / 对账)同时空转,8 天没人看见。")
        print("  怎么修:PR 描述里写一段「## 影响分类」,段内标出 ③(数据库迁移/数据),并写明落库顺序")
        print("          —— 迁移必须先于 jar 部署手动落库(chengyin-db-migrate skill)。")
        print("  ⚠️ 光改描述不会让这道门转绿,也不要去点 re-run:")
        print("          workflow 传进来的 PR_BODY 取自 github.event.pull_request.body,")
        print("          那是 run 派发那一刻冻结的 payload;re-run 复用同一份 payload。")
        print("          而 ci.yml 的 pull_request 没写 types,默认只有")
        print("          opened / synchronize / reopened —— edited 不在内,改描述不重新派发。")
        print("          所以顺序是:先改描述,再推下一个提交(synchronize)带上新 body。")
        return 1
    print("  OK: PR 已声明迁移")
    return 0


def main(argv):
    if len(argv) < 2:
        sys.exit("用法: migration_gates.py {sql|beans|pr-declare|dep-order} [文件...]")
    cmd, files = argv[1], argv[2:]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if cmd == "sql":
        return _cmd_sql(files)
    if cmd == "beans":
        return _cmd_beans(root)
    if cmd == "pr-declare":
        return _cmd_pr_declare(files)
    if cmd == "dep-order":
        return _cmd_dep_order(root, files)  # files 已是 argv[2:]
    sys.exit("未知子命令: %s" % cmd)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
