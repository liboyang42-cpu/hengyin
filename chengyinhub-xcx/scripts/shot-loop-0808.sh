#!/bin/bash
# 2026-08-08 全量+补拍循环：DevTools 长跑必断连（实测 60 张左右一次），
# 断了就换端口重来，按磁盘上已有的图续跑，直到没有剩余为止。
set -u
cd "$(dirname "$0")/.."
OUT="${SHOT_OUT:-$HOME/Desktop/城瘾全量截图_20260808}"
BATCH="${1:-25}"
ROUNDS="${2:-20}"

PREV_LEFT=""
STALL=0
for i in $(seq 1 "$ROUNDS"); do
  echo "===== 第 $i 轮 ====="
  SHOT_OUT="$OUT" bash scripts/shot-resume.sh "$BATCH"
  RC=$?
  LEFT=$(SHOT_OUT="$OUT" bash -c 'cd "'"$PWD"'"; python3 - "'"$OUT"'" <<PY
import json, os, subprocess, sys
out = sys.argv[1]
have = set()
for sub in ("玩家版", "商家版"):
    d = os.path.join(out, sub)
    if os.path.isdir(d):
        have.update(os.listdir(d))
rows = json.loads(subprocess.run(["node","-e",
  "const S=require(\"./scripts/shot-matrix.js\").SHOTS;console.log(JSON.stringify(S.filter(s=>!s.blocked).map(s=>({id:s.id,file:s.file}))))"],
  capture_output=True, text=True).stdout)
print(len([r for r in rows if r["file"] not in have]))
PY')
  echo "剩余 $LEFT 张 (rc=$RC)"
  [ "$LEFT" = "0" ] && { echo "全部拍完"; break; }
  # ★空转上限(2026-08-19):剩余张数连着两轮不减 = 这一批里有拍不出来的行,
  #   再转下去只是每轮重开一个 DevTools 实例。2026-08-18 实测 F05(纯重定向壳)
  #   一条就让循环空转了 17 轮。转到超时不是"尽力了",是把问题埋进日志里。
  if [ "$LEFT" = "$PREV_LEFT" ]; then
    STALL=$((STALL + 1))
    if [ "$STALL" -ge 2 ]; then
      echo "★ 连续 $STALL 轮剩余张数没有减少(仍剩 $LEFT 张)——停止空转。"
      echo "  这 $LEFT 张要么是页面里不存在的状态,要么该标 blocked(改 scripts/uiaudit/blocked-baseline.json 并写清 reason + altVerification),"
      echo "  不要靠再转几轮碰运气。"
      exit 3
    fi
  else
    STALL=0
  fi
  PREV_LEFT="$LEFT"
  sleep 6
done
