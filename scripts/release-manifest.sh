#!/usr/bin/env bash
# 生成可归档的发布清单。正式调用必须来自干净工作区。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="${1:-$ROOT/release-manifest.json}"
VERSION="${RELEASE_VERSION:-}"
JAR_PATH="${BACKEND_JAR:-$ROOT/chengyinhub-admin/target/chengyinhub-admin.jar}"

if [[ ! "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "RELEASE_VERSION 必须是仅含字母、数字、点、下划线或连字符的版本号" >&2
  exit 1
fi
if [[ ! -f "$JAR_PATH" ]]; then
  echo "找不到后端产物: $JAR_PATH" >&2
  exit 1
fi

cd "$ROOT"
if [[ "${RELEASE_ENV:-}" == "production" ]]; then
  bash "$ROOT/scripts/production-preflight.sh" --env
fi
UNTRACKED="$(git ls-files --others --exclude-standard)"
WORKTREE_CLEAN=true
if ! git diff --quiet || ! git diff --cached --quiet || [[ -n "$UNTRACKED" ]]; then
  WORKTREE_CLEAN=false
fi
if [[ "$WORKTREE_CLEAN" != true && "${ALLOW_DIRTY_RELEASE_MANIFEST:-}" != "1" ]]; then
  echo "工作区不干净，拒绝生成正式发布清单；联调验证可显式设置 ALLOW_DIRTY_RELEASE_MANIFEST=1" >&2
  exit 1
fi

sha256() {
  shasum -a 256 "$1" | awk '{print $1}'
}

APP_LOCK="chengyinhub-xcx/package-lock.json"
UI_LOCK="chengyinhub-ui/package-lock.json"
for file in "$APP_LOCK" "$UI_LOCK"; do
  if [[ ! -f "$file" ]]; then
    echo "缺少锁文件: $file" >&2
    exit 1
  fi
done

mkdir -p "$(dirname "$OUTPUT")"
GIT_SHA="$(git rev-parse HEAD)"
JAR_RELATIVE="${JAR_PATH#$ROOT/}"
LEDGER_FILE="$(mktemp "${TMPDIR:-/tmp}/chengyin-migration-ledger.XXXXXX")"
MIGRATIONS_FILE="$(mktemp "${TMPDIR:-/tmp}/chengyin-migrations.XXXXXX")"
cleanup() {
  rm -f "$LEDGER_FILE" "$MIGRATIONS_FILE"
}
trap cleanup EXIT

# migration_ledger 会全量盘点 SQL、校验路径/前缀/碰撞/分句形状，并按显式依赖稳定排序。
# 任一未管理 SQL 或历史内容漂移都在写正式发布清单前响亮失败；本脚本不含 apply 能力。
python3 "$ROOT/ci/migration_ledger.py" manifest \
  --root "$ROOT" \
  --target-commit "$GIT_SHA" \
  --output "$LEDGER_FILE"
python3 - "$LEDGER_FILE" > "$MIGRATIONS_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    ledger = json.load(stream)
json.dump(
    [{"path": item["path"], "sha256": item["sha256"]} for item in ledger["migrations"]],
    sys.stdout,
    ensure_ascii=False,
    separators=(",", ":"),
)
PY

{
  printf '{\n'
  printf '  "version": "%s",\n' "$VERSION"
  printf '  "gitSha": "%s",\n' "$GIT_SHA"
  printf '  "worktreeClean": %s,\n' "$WORKTREE_CLEAN"
  printf '  "backendJar": {"path": "%s", "sha256": "%s"},\n' "$JAR_RELATIVE" "$(sha256 "$JAR_PATH")"
  printf '  "lockfiles": {\n'
  printf '    "chengyinhub-xcx/package-lock.json": "%s",\n' "$(sha256 "$APP_LOCK")"
  printf '    "chengyinhub-ui/package-lock.json": "%s"\n' "$(sha256 "$UI_LOCK")"
  printf '  },\n'
  printf '  "migrations": '
  cat "$MIGRATIONS_FILE"
  printf ',\n'
  printf '  "migrationLedger": '
  cat "$LEDGER_FILE"
  printf '}\n'
} > "$OUTPUT"

echo "发布清单已生成: $OUTPUT"
