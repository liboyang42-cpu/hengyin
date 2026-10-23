#!/usr/bin/env python3
"""SQL 分句器 —— 本仓库的【唯一真源】。

为什么它在仓库里(而不是在 skill 里):
  2026-07-15,migration_c3_team_job.sql 的 remark 里一个半角分号
  ('...(不接微信原路);默认暂停,真机验证后启用')把 INSERT 从中间劈开 → 1064,
  autoRefundTask 因此从写下那天起就没跑过一次,8 天零告警。
  病根是裸 split(";") 不认字符串字面量。

  真执行器(chengyin-db-migrate skill 的 run_migration.py)和 CI 门禁必须用【同一份】
  分句逻辑 —— 各写一份 = 门禁在保护一个不存在的东西。故本文件是唯一真源,
  skill 侧反过来 import 它。
  ⚠️ 顺序不能反:本文件进 master 之前不要改 skill,否则在主仓跑 run_migration.py
  会 import 失败而崩 —— 而它是跑生产迁移的。

纯 stdlib、零依赖(不碰 pymysql、不读配置)—— CI 的 ubuntu-latest 自带 python3 直跑。

【DELIMITER 边界】
  - 支持 MySQL 客户端的独立行 `DELIMITER $$` / `DELIMITER //` 指令；
    指令不会作为 SQL 下发。
  - 自定义分隔符必须恢复为 `;`；复合 routine 由 migration_gates 要求自定义终止符。
    完整的单条拒绝 SIGNAL trigger 可由默认分号结束，门禁按精确语法识别。
  - 非独立行、含字母数字/引号/反斜杠、与注释符冲突的 delimiter 一律 fail-closed。
  - 不认 `#` 行注释(MySQL 支持,但本仓库 100% 用 `--`,实测 0 处使用)。
    真写了 `#...;...` 会切错 → 执行时 1064 报错,是【响】的失败,不是静默,故不补。

【边界要说准,别过度概括】
  整行 `--` 注释里的半角分号【本来就是安全的】(旧实现先整行剥掉)。2026-07-14 badge
  迁移踩坑后流传的「注释里不要有分号」是过度概括 —— 害得后来的人以为整行注释危险,
  而【真正危险的字符串字面量反而没被点名】。危险的是引号里的分号,不是注释里的。
"""
import re

_QUOTES = ("'", '"', '`')
_DELIMITER_LINE = re.compile(r"[ \t]*DELIMITER[ \t]+([^\s]+)[ \t]*\r?", re.IGNORECASE)
_ALLOWED_DELIMITERS = {";", "$$", "//"}

RUNNER_ASSERTION = re.compile(
    r"(?mi)^\s*--\s*runner:\s*assert=(?P<mode>[a-z0-9-]+)\s*$")


def executable_statement(statement):
    """剥掉 runner 元数据，返回用于静态形状判定的可执行 SQL。"""
    return RUNNER_ASSERTION.sub("", statement).lstrip()


def _scan(sql_text):
    """扫一遍 SQL,返回 (语句, 终止符, 去引号语法视图, 未闭合符号, delimiter 错误)。

    未闭合的符号 ∈ {"'", '"', "`", "/*"} —— 都会把后面的内容整块吞掉。

    split_statements 与 unterminated_token 共用本函数 —— 门禁的判据必须和真分句
    走同一套规则,否则门禁保护的是另一个东西。
    """
    statements = []
    terminators = []
    code_statements = []
    buf = []
    code_buf = []
    unterminated = None
    delimiter_error = None
    delimiter = ";"
    i, n = 0, len(sql_text)

    def flush(terminator=None):
        st = "".join(buf).strip()
        if st:
            statements.append(st)
            terminators.append(terminator)
            code_statements.append("".join(code_buf).strip())
        del buf[:]
        del code_buf[:]

    while i < n:
        ch = sql_text[i]

        # DELIMITER 是 mysql client 指令,不是发给 MySQL 的 SQL。只接受独立行,且前一条
        # SQL 必须已由当前 delimiter 结束；否则跳过该行会静默改变 SQL,所以直接记红。
        if i == 0 or sql_text[i - 1] == "\n":
            line_end = sql_text.find("\n", i)
            line_end = n if line_end == -1 else line_end
            line = sql_text[i:line_end]
            if line.lstrip(" \t").upper().startswith("DELIMITER"):
                match = _DELIMITER_LINE.fullmatch(line)
                if not match:
                    delimiter_error = delimiter_error or "DELIMITER 指令必须独占一行且只含一个分隔符"
                elif "".join(buf).strip():
                    delimiter_error = delimiter_error or "DELIMITER 指令前存在未结束的 SQL"
                else:
                    token = match.group(1)
                    if token not in _ALLOWED_DELIMITERS:
                        delimiter_error = delimiter_error or (
                            "DELIMITER 只能使用仓库合同中的安全标点 $$ 或 //"
                        )
                    else:
                        delimiter = token
                i = n if line_end == n else line_end + 1
                buf.append("\n")
                code_buf.append("\n")
                continue

        # -- 注释。两条规则【并存】,少一条都出事:
        #   整行注释:整行以 -- 打头 → 整行丢掉,不管 -- 后面跟什么(旧实现的行为,必须保留)。
        #     少了它,`--- 分隔线` / `------------` 这类常见分隔线会漏一个 `-` 进 SQL → 1064,
        #     而门禁 1-b 抓不到(没有引号问题)→ 一路放行到生产。
        #   行尾注释:MySQL 规则,-- 后须跟空白或行尾(`a--b` 是减号不是注释)。
        if ch == "-" and sql_text.startswith("--", i):
            行首 = sql_text.rfind("\n", 0, i) + 1
            整行注释 = sql_text[行首:i].strip() == ""
            行尾注释 = i + 2 >= n or sql_text[i + 2] in " \t\r\n"
            if 整行注释 or 行尾注释:
                nl = sql_text.find("\n", i)
                comment_end = n if nl == -1 else nl
                comment = sql_text[i:comment_end]
                # runner 断言必须与紧随的 SQL 绑定。其他注释仍丢弃，
                # 仅保留这个机器合同，供真执行器在 execute 后回读结果。
                if 整行注释 and comment.strip().lower().startswith(
                        "-- runner: assert="):
                    buf.append(comment)
                i = n if nl == -1 else nl
                buf.append("\n")      # 留个空白,防注释两侧的 token 粘连
                code_buf.append("\n")
                continue

        # /* */ 块注释。/*! ... */ 是 MySQL 可执行注释 —— 剥掉会【静默丢语句】,原样留着
        if sql_text.startswith("/*", i) and not sql_text.startswith("/*!", i):
            end = sql_text.find("*/", i + 2)
            if end == -1:
                # 没闭合的 /* 会把后面所有语句当注释【静默吞掉】—— 不报错、不炸、零告警,
                # 正是本仓库要杀的那类失败。记下来让门禁 1-b 显式红。
                if unterminated is None:
                    unterminated = "/*"
                i = n
            else:
                i = end + 2
            buf.append(" ")
            code_buf.append(" ")
            continue

        # 引号段:整段原样拷走 —— 里头的分号是数据,不是分隔符。这就是病根所在。
        if ch in _QUOTES:
            buf.append(ch)
            i += 1
            closed = False
            while i < n:
                c = sql_text[i]
                # 反斜杠转义(MySQL 默认开启;反引号内不适用)
                if c == "\\" and ch != "`" and i + 1 < n:
                    buf.append(sql_text[i:i + 2])
                    i += 2
                    continue
                if c == ch:
                    # '' / "" / `` 是转义的引号本身,不闭合
                    if i + 1 < n and sql_text[i + 1] == ch:
                        buf.append(c * 2)
                        i += 2
                        continue
                    buf.append(c)
                    i += 1
                    closed = True
                    break
                buf.append(c)
                i += 1
            if not closed and unterminated is None:
                unterminated = ch     # 扫到结尾都没闭合
            code_buf.append(" ")      # 语法检查不得把字符串内容当 SQL token
            continue

        if sql_text.startswith(delimiter, i):
            flush(delimiter)
            i += len(delimiter)
            continue

        buf.append(ch)
        code_buf.append(ch)
        i += 1

    flush()
    if delimiter != ";" and delimiter_error is None:
        delimiter_error = "DELIMITER 自定义分隔符在文件结束前未恢复为 ;"
    return statements, terminators, code_statements, unterminated, delimiter_error


def split_statements(sql_text):
    """按当前 delimiter 切分语句,跳过注释与指令。引号内的分隔符不切。"""
    return _scan(sql_text)[0]


def split_statements_with_delimiters(sql_text):
    """返回 (statement, terminator)；EOF 未带终止符时 terminator 为 None。"""
    statements, terminators, _code_statements, _unterminated, _delimiter_error = _scan(sql_text)
    return list(zip(statements, terminators))


def split_code_statements_with_delimiters(sql_text):
    """返回剔除引号段的语法视图与终止符，供 SQL shape 门禁使用。"""
    _statements, terminators, code_statements, _unterminated, _delimiter_error = _scan(sql_text)
    return list(zip(code_statements, terminators))


def unterminated_token(sql_text):
    """返回未闭合的符号(' / " / ` / /*);全部闭合则返回 None。

    ⚠️ 必须对【整个文件】判,不能只对拆出的单条语句判:没闭合的 /* 会把后面的语句
    整块吞掉,吞完之后剩下的每条语句自己都是「干净」的 —— 逐条判恒绿,漏得无声无息。
    """
    return _scan(sql_text)[3]


def delimiter_error(sql_text):
    """返回 fail-closed 的 DELIMITER 合同错误；合法则为 None。"""
    return _scan(sql_text)[4]
