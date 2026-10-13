#!/bin/bash
# 补拍循环：断连就换端口重来，按磁盘已有的图续跑。
set -u
cd "$(dirname "$0")/.."
OUT="${SHOT_OUT:-$HOME/Desktop/城瘾全量截图_20260808}"
ROUNDS="${1:-20}"

for i in $(seq 1 "$ROUNDS"); do
  echo "===== 补拍第 $i 轮 ====="
  PORT=$((9400 + RANDOM % 200))
  WX_AUTO_PORT=$PORT SHOT_OUT="$OUT" SHOT_SKIP_EXISTING=1 node scripts/_shot_extra_0808.js
  LEFT=$(SHOT_OUT="$OUT" node -e '
const fs=require("fs"),path=require("path");
const E=require("./scripts/shot-extra-0808");
const ROOT=process.env.SHOT_OUT;
const D={player:"玩家版",guest:"玩家版",club:"玩家版",merchant:"商家版"};
const left=E.filter(s=>!s.blocked).filter(s=>{const b=s.file.replace(/\.png$/,"");
  const f=s.scrollTops.length>1?b+"-s1.png":b+".png";
  return !fs.existsSync(path.join(ROOT,D[s.role]||"玩家版",f));});
console.log(left.length);')
  echo "补拍剩余 $LEFT 条"
  [ "$LEFT" = "0" ] && { echo "补拍全部完成"; break; }
  sleep 6
done
