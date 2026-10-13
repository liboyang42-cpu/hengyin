#!/bin/bash
# 续跑截图矩阵：从 manifest*.json 里读出已经 ok 的，只补剩下的。
# DevTools 长跑会自己断连（实测每几十张一次），所以按批跑，断了重跑本脚本即可。
#
#   bash scripts/shot-resume.sh [每批张数，默认 30]
#
# 前置：微信开发者工具已手动打开且项目能正常编译。
# ⚠️ 别反复 pkill DevTools —— 连续强杀会让 simulator 子系统起不来，
#    表现为 connect 成功但拍两三张就 Connection closed。真坏了就手动重启它。
set -u
cd "$(dirname "$0")/.."
OUT="${SHOT_OUT:-$HOME/Desktop/城瘾全量截图_20260805}"
EXTRA="${SHOT_EXTRA:-}"
BATCH="${1:-30}"

TODO=$(python3 - "$OUT" <<'PY'
import json, glob, os, subprocess, sys
out = sys.argv[1]
# 判据是"磁盘上有没有这张图"，不是"判定绿不绿"。判红的那些红在 fixture 或
# 断言上，重拍多少次还是红 —— 早期版本按 status!=ok 续跑，结果同一批图被反复
# 重拍好几轮。要修红项得改 fixtures/selectors，不是再拍一次。
# ⚠️ 直接扫磁盘上真实存在的图，**不读 manifest**。
# 之前从 manifest 反查文件名，撞上过一个顺序坑：清空目录重拍时，本轮早期批次
# 已经写了新 manifest，脚本读到它就以为"都拍过了"，227 张只跑了 11 张就报
# "全部已 ok"。磁盘是唯一真相，manifest 只是它的一份可能过期的索引。
have = set()
for sub in ('玩家版', '商家版'):
    d = os.path.join(out, sub)
    if os.path.isdir(d):
        have.update(os.listdir(d))
done = set()  # 稍后按 id→file 映射填充
extra = os.environ.get('SHOT_EXTRA', '')
js = 'const S=require("./scripts/shot-matrix.js").SHOTS;'
js += f'const e=require("{extra}");' if extra else 'const e=[];'
js += 'console.log(JSON.stringify(S.concat(e).filter(s=>!s.blocked).map(s=>s.id)))'
js2 = js.replace('.map(s=>s.id)', '.map(s=>({id:s.id,file:s.file}))')
rows = json.loads(subprocess.run(['node', '-e', js2], capture_output=True, text=True).stdout)
print(','.join(r['id'] for r in rows if r['file'] not in have))
PY
)

if [ -z "$TODO" ]; then echo "全部已 ok，无需续跑"; exit 0; fi
COUNT=$(tr ',' '\n' <<<"$TODO" | grep -c .)
BATCH_IDS=$(cut -d, -f1-"$BATCH" <<<"$TODO")
echo "剩余 $COUNT 张，本批跑 $(tr ',' '\n' <<<"$BATCH_IDS" | grep -c .) 张"

# 端口每次换一个，避免撞上没释放干净的旧实例
PORT=$((9400 + RANDOM % 200))
STAMP=$(date +%H%M%S)
WX_AUTO_PORT=$PORT SHOT_OUT="$OUT" SHOT_EXTRA="$EXTRA" SHOT_ONLY="$BATCH_IDS" \
  node scripts/_shot_full_matrix.js
RC=$?
# 每批的 manifest 单独留档，合并交给 manifest.merged.json 的生成方
[ -f "$OUT/manifest.json" ] && cp "$OUT/manifest.json" "$OUT/manifest.part-$STAMP.json"
exit $RC
