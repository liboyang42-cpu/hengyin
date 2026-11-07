#!/usr/bin/env bash
# Round5D 敏感词误伤报告。只读,不连库、不联网。
# 用法: scripts/sensitive/run.sh <词表文件> [放行短语文件]
#   词表格式与后台「批量导入」相同:每行 词条,分类,动作(# 开头为注释)
#   放行短语文件每行一个短语
# 先编译 chengyinhub-system(mvn -q -DskipTests -pl chengyinhub-system install),脚本用它的 classes。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLASSES="$ROOT/chengyinhub-system/target/classes"
if [ ! -d "$CLASSES/com/chengyinhub/business/service/safety" ]; then
  echo "缺少 $CLASSES,请先编译 chengyinhub-system" >&2
  exit 2
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 -cp "$CLASSES" -d "$OUT" "$ROOT/scripts/sensitive/SensitiveFalsePositiveReport.java"
java -Dfile.encoding=UTF-8 -cp "$OUT:$CLASSES" SensitiveFalsePositiveReport "$ROOT" "$@"
