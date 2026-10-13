#!/usr/bin/env bash
# 迁移布局门禁。CI 与本地共用。用法:bash ci/migration-layout-check.sh  (从任何目录执行皆可,脚本自己回到仓库根)
#
# 团队铁律原文是「新 SQL 一律 migration_* 前缀,前缀就是保护半径」——
# 但 2026-07-15 两次实证说明,保护半径其实有【两半】,缺一即漏:
#
#   前缀那半(检查 2):注册 sys_job 的 SQL,18 个叫 migration_* 的全跑了,
#     唯一一个叫 seed_* 的从没跑过 → 退款兜底任务一周形同虚设。
#   目录那半(检查 1):#43 的 migration_settlement_resui_2fix.sql 与 #48 的
#     migration_20260715_recall_job.sql 前缀对、却落在仓库根 sql/,而
#     scripts/release-manifest.sh 硬编码 `find chengyinhub-admin/sql -maxdepth 1`
#     → 二者永不进 release-manifest.json、_precheck_migration_status.sql 也扫不到。
#
# 两类漏法的共同点:【不跑不报错、不炸、零告警】——功能静默地根本不存在。
# 靠人记必漏,所以写成机器门禁。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1   # 路径均为仓库根相对,见 ci/test_cwd_independence.sh

EXPECTED_DIR="chengyinhub-admin/sql"
[ -d "$EXPECTED_DIR" ] || { echo "找不到 $EXPECTED_DIR"; exit 1; }

fail=0

# ── 检查 1:目录(全量)──────────────────────────────────────────────
# migration_*.sql 必须在 $EXPECTED_DIR 的【第一层】。用 `^\./DIR/[^/]*$` 精确匹配第一层,
# 与 release-manifest.sh 的 -maxdepth 1 同口径 —— 所以放进子目录同样拦(manifest 一样扫不到)。
echo "== [1/2] 迁移目录门:migration_*.sql 必须在 ${EXPECTED_DIR}/ 第一层 =="
strays="$(find . -type f -name 'migration_*.sql' \
    -not -path './.git/*' \
    -not -path '*/node_modules/*' \
    -not -path '*/target/*' \
  | grep -Ev "^\./${EXPECTED_DIR}/[^/]*$" \
  | sort || true)"

if [ -n "$strays" ]; then
  echo "::error::发现放错目录的迁移文件 —— migration_*.sql 必须在 ${EXPECTED_DIR}/ 第一层"
  echo "$strays" | sed 's/^/  ✗ /'
  echo "  为什么:release-manifest.sh 只扫 ${EXPECTED_DIR}(find -maxdepth 1),放别处的迁移"
  echo "          不进 release-manifest.json、预检也扫不到 → 静默漏落库(不报错、不炸、零告警)"
  echo "  怎么修:"
  echo "$strays" | sed "s|^|    git mv |; s|$| ${EXPECTED_DIR}/|"
  fail=1
else
  count="$(find "$EXPECTED_DIR" -maxdepth 1 -type f -name 'migration_*.sql' | wc -l | tr -d ' ')"
  echo "  OK: ${count} 个 migration_*.sql 全在 ${EXPECTED_DIR}/ 第一层"
fi

# ── 检查 2:前缀(增量)──────────────────────────────────────────────
# 「要在库上跑的 SQL 一律 migration_* 前缀」。查【本分支新增或修改】的文件:
# 目录里已有 seed_preset_demo.sql 等历史件,全量拦会直接红,且它们不该由本门禁追溯处置。
# 豁免的是不在库上跑的辅助件:_precheck_(只读预检)/ _deploy_(历史部署包)/ bak_(备份)。
# seed_ 刻意【不豁免】—— 它正是铁律的由来。
echo "== [2/2] 新增 SQL 前缀门:要在库上跑的 SQL 一律 migration_* =="
# 基线取「主仓 master」。⚠️ 本地 origin = Codeup 镜像,可能严重落后(2026-07-15 实测落后 2 天),
# 拿它当基线会把早已在 master 上的历史文件误判成本分支新增。故 github 优先;
# GitHub Actions 里 checkout 设的 remote 恰好叫 origin(= 主仓),所以回退 origin 对 CI 正确。
BASE=""; BASE_REF=""
for _r in github origin; do
  git rev-parse --verify -q "refs/remotes/${_r}/master" >/dev/null 2>&1 || continue
  git fetch -q --depth=200 "$_r" master 2>/dev/null || true
  _b="$(git merge-base "${_r}/master" HEAD 2>/dev/null || true)"
  if [ -n "$_b" ]; then BASE="$_b"; BASE_REF="${_r}/master"; break; fi
done

if [ -z "$BASE" ]; then
  echo "  (取不到 github/master 或 origin/master 基线,跳过增量前缀检查,不阻塞)"
elif [ "$BASE" = "$(git rev-parse HEAD)" ]; then
  echo "  (无分支增量,跳过)"
else
  changed="$(git diff --name-only --diff-filter=AM "$BASE"...HEAD -- "$EXPECTED_DIR" 2>/dev/null \
    | grep -E '\.sql$' \
    | grep -Ev "^${EXPECTED_DIR}/(migration_|_precheck_|_deploy_|bak_)" \
    | sort || true)"
  if [ -n "$changed" ]; then
    echo "::error::新增/修改的 SQL 前缀不合规 —— 要在库上跑的 SQL 一律用 migration_* 前缀"
    echo "$changed" | sed 's/^/  ✗ /'
    echo "  为什么:判据是「要不要在库上跑」而非「是不是 DDL」。seed_ / insert into sys_job / sys_menu"
    echo "          都要跑,但非 migration_* 前缀的不会进 release-manifest.json 的迁移集合"
    echo "          → 实证:唯一一个 seed_* 从没跑过,退款兜底任务一周形同虚设(不报错、零告警)"
    echo "  怎么修:git mv <文件> ${EXPECTED_DIR}/migration_<描述>_<日期>.sql"
    echo "  例外:确实不在库上跑的辅助件用 _precheck_ / _deploy_ / bak_ 前缀"
    fail=1
  else
    echo "  OK: 本分支(相对 ${BASE_REF})新增/修改的 SQL 前缀均合规"
  fi
fi

if [ "$fail" -ne 0 ]; then echo "迁移布局门禁:未通过"; exit 1; fi
echo "迁移布局门禁:通过"
