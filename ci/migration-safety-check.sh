#!/usr/bin/env bash
# 迁移安全门禁。CI 与本地共用。用法:bash ci/migration-safety-check.sh  (从任何目录执行皆可,脚本自己回到仓库根)
#
# 姊妹门禁 ci/migration-layout-check.sh 管【文件长相】(前缀 + 目录);
# 本门禁管【内容能不能真跑起来】:
#
#   1-a 分句器单测      锁住 tokenizer 行为,防未来有人改坏它(负控在单测里)
#   1-b 迁移 SQL 扫描   新增/修改的迁移:引号闭合 + 拦 DELIMITER 形状
#   2   task bean 注册  有 bean 必须有注册 SQL,否则 jar 部署了也永不调度
#   3   PR 迁移声明     夹带迁移必须无法静默
#   4   批内依赖顺序    本批【新增】的迁移按文件名顺序跑必须满足批内依赖(见下)
#
# 病历:autoRefundTask 代码里有 bean、生产库 sys_job 零注册 ⇒ 从没跑过一次,8 天零告警。
# 注册 SQL 早在仓库里,但藏在一个标题写 test(xcx) 的 commit 里,且从写下那天起就跑不了
# (remark 里一个半角分号把 INSERT 劈开 → 1064)。review / CI / 对账三道防线同时空转。
# 「前缀不是保护,人就是会漏」—— 把「人记得住」换成「机器拦」。
#
# 门禁 4 的病历(2026-08-18,在生产 schema 快照上实测):探店日候选攒了 25 条未落库的迁移,
# 按 `ls` 出来的文件名顺序跑,migration_20260817_tandianri_r7_distribution_backfill.sql
# 第 1 句就 `1054 Unknown column 'organizer.declared_by'` —— 加那一列的
# migration_shop_day_organizer_actor_20260815.sql 文件名排在它后面('2' < 's')。
# 依赖声明写在 ci/migration-dependencies.json 里、注释里也写了【前置】,但没有任何东西在拦。
# 「落库前先自己算一遍拓扑序」是许愿不是防线 —— 人手里只有 ls。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1   # 路径均为仓库根相对,见 ci/test_cwd_independence.sh

SQL_DIR="chengyinhub-admin/sql"
[ -d "$SQL_DIR" ] || { echo "找不到 $SQL_DIR"; exit 1; }

fail=0

# ── [1/4] 门禁 1-a:分句器单测 ────────────────────────────────────────
# 先证明分句器本身没坏,再拿它去判别人 —— 顺序不能反。
echo "== [1/5] 分句器 + migration 台账单测(含负控)=="
# ⚠️ 不要给这行接管道(| tail 之类):管道的退出码是最后一个命令的,单测红了也会被吞成绿。
# 一道自己会假绿的门禁,就是这个仓库正在修的那个病。
if ! python3 ci/test_migration_gates.py || ! python3 ci/test_migration_ledger.py; then
  echo "::error::分句器或 migration 台账单测未通过 —— ci/sql_splitter.py 是【真执行器和门禁共用】的唯一真源,"
  echo "        或 migration manifest/history 状态机负控未通过。先修实现,别绕过单测。"
  fail=1
fi

# ── 基线:算出本分支【新增/修改】了哪些应在库上跑的 SQL ─────────────────
# 与 ci/migration-layout-check.sh 检查 2 同口径。⚠️ 本地 origin = Codeup 镜像,可能严重落后,
# 拿它当基线会把早已在 master 上的历史文件误判成本分支新增。故 github 优先;
# GitHub Actions 里 checkout 设的 remote 恰好叫 origin(= 主仓),所以回退 origin 对 CI 正确。
#
# ⚠️ 为什么是【增量】不是全量:历史坏文件还在仓库里(migration_c3_team_job.sql 至今是坏的),
# 扫全量 = CI 立刻红且永久红。历史的不红,新的被拦。不要用白名单(会腐烂)。
BASE=""; BASE_REF=""
for _r in github origin; do
  git rev-parse --verify -q "refs/remotes/${_r}/master" >/dev/null 2>&1 || continue
  git fetch -q --depth=200 "$_r" master 2>/dev/null || true
  _b="$(git merge-base "${_r}/master" HEAD 2>/dev/null || true)"
  if [ -n "$_b" ]; then BASE="$_b"; BASE_REF="${_r}/master"; break; fi
done

CHANGED=""
if [ -z "$BASE" ]; then
  echo "  (取不到 github/master 或 origin/master 基线,跳过增量检查,不阻塞)"
elif [ "$BASE" = "$(git rev-parse HEAD)" ]; then
  echo "  (无分支增量,跳过增量检查)"
else
  CHANGED="$(git diff --name-only --diff-filter=AM "$BASE"...HEAD -- "$SQL_DIR" 2>/dev/null \
    | grep -E "^${SQL_DIR}/[^/]+\.sql$" \
    | grep -Ev "^${SQL_DIR}/(_precheck_|_deploy_|bak_)" \
    | sort || true)"
fi

# ── [2/5] 门禁 1-b:新增/修改的执行 SQL 必须能被正确分句 ────────────────
echo "== [2/5] 迁移 SQL 门:新增/修改的执行 SQL 必须能被真执行器正确分句 =="
if [ -z "$CHANGED" ]; then
  echo "  (本分支没有新增/修改应在库上跑的 SQL,跳过)"
else
  echo "$CHANGED" | sed 's/^/  · /'
  # 文件名不含空格:migration_<描述>_<日期>.sql 的命名规范 + 前缀门共同保证
  # shellcheck disable=SC2086
  python3 ci/migration_gates.py sql $CHANGED || fail=1
fi

# ── [3/5] 门禁 2:有 task bean 就必须有注册 SQL ────────────────────────
# 【全量】门,不是增量 —— 2026-07-16 实测 18 个现存 bean 全都有注册 SQL(master 552a5cb2),
# 所以扫全量不会一上线就红,而全量比增量更强:它对每个 bean 永远有效,不只拦新增的。
echo "== [3/5] 定时任务注册门:有 task bean 就必须有注册 SQL =="
python3 ci/migration_gates.py beans || fail=1

# ── [4/5] 门禁 3:PR 动了迁移就必须显式声明 ────────────────────────────
# PR_TITLE / PR_BODY 由 workflow 从 PR 事件里注入(push 事件拿不到 → 脚本自己跳过)。
# ⚠️ 必须走 env 传,不能把 ${{ github.event.pull_request.title }} 直接插进 run: —— 那是脚本注入。
echo "== [4/5] 迁移声明门:PR 动了迁移就必须标明 =="
if [ -z "$CHANGED" ]; then
  echo "  (本分支没有新增/修改应在库上跑的 SQL,无需声明)"
else
  # shellcheck disable=SC2086
  python3 ci/migration_gates.py pr-declare $CHANGED || fail=1
fi

# ── [5/5] 门禁 4:本批迁移按文件名顺序跑必须满足批内依赖 ─────────────────
# 【增量】门:只看本分支新增/修改的迁移之间的先后。指向 master 已落库迁移的依赖不算数。
# ⚠️ 不能扫全仓 —— master 上就有 16 对历史「文件名序违反依赖」,那些是【写一条跑一条】
#    落的库、批内先后从没被考验过。扫全量 = 一上线就红且永久红 ⇒ 门禁被绕过。
echo "== [5/5] 批内依赖顺序门:按文件名顺序跑必须满足批内依赖 =="
# ★ 这道门【自己算批】,不复用上面的 CHANGED(AM 口径)。
#   2026-08-18:复用 AM 时,「已在 master、已在生产落库、本分支只改了一行注释」的迁移
#   也被算进批去比先后 ⇒ 假阳性。批的口径就是这道门的判据本身,收进 python 里由单测钉住。
#   门禁 2/4 继续用 AM —— 改了历史迁移的内容确实要重新分句、也确实要在 PR 里声明。
if [ -z "$BASE" ]; then
  echo "  (取不到基线,跳过,不阻塞)"
else
  python3 ci/migration_gates.py dep-order --base "$BASE" || fail=1
fi

if [ "$fail" -ne 0 ]; then echo "迁移安全门禁:未通过"; exit 1; fi
echo "迁移安全门禁:通过"
