#!/bin/bash
# 细节打磨前后对比:同一页面、同一状态、同一组步骤,master(before)与本分支(after)各走一遍,再拼成左右对。
# usage: BEFORE_XCX=<master worktree>/chengyinhub-xcx AFTER_XCX=<branch>/chengyinhub-xcx RIG_WALK=<rig>/mpweb/walk.mjs \
#        PY=<python with pillow> compare.sh <scenario>   (scenario 见 scenarios.sh;不给 = 全部)
S=$(cd "$(dirname "$0")" && pwd); OUT=$(cd "$S/.." && pwd)/compare; RAW=${RAW:-/tmp/dp-raw}; mkdir -p "$OUT" "$RAW"
PY=${PY:-python3}; P8=8097; A8=8098
# scn <name> <LOOK|none> <previewKind|-> <MODE> <opts-json> <steps-json>
scn() {
  local name=$1 look=$2 kind=$3 mode=$4 opts=$5 steps=$6
  [ -z "$ONLY" ] || [ "$ONLY" = "$name" ] || [ "${name%%-*}" = "$ONLY" ] || return 0
  echo "== $name"
  for side in before after; do
    local port x; [ $side = before ] && { port=$P8; x=$BEFORE_XCX; } || { port=$A8; x=$AFTER_XCX; }
    local o; o=$(node -e "const o=JSON.parse(process.argv[1]);o.identity=o.identity||'e2e-openid-9004';if(process.argv[2]!=='-')o.previewKind=process.argv[2];o.path=o.path||'pages/play/index?preview=1';console.log(JSON.stringify(o))" "$opts" "$kind")
    local st; st=$(echo "$steps" | sed "s/@@/$name-$side-/g")
    (cd "$S" && LOOK=$look MODE=$mode XCX=$x RIG_WEB=http://127.0.0.1:$port RIG_SHOTS=$RAW timeout 200 node drive.mjs "$o" "$st" 2>&1 | grep -E "^(ERR|FAIL)" )
  done
  for f in "$RAW"/$name-before-*.jpg; do [ -f "$f" ] || continue; local b=${f##*/}; local suffix=${b#$name-before-}; suffix=${suffix%.jpg}; local a="$RAW/$name-after-$suffix.jpg"
    local dst="$OUT/$name.jpg"; [ "$suffix" = main ] || dst="$OUT/$name-$suffix.jpg"
    [ -f "$a" ] && $PY "$S/pair.py" "$f" "$a" "$dst"; done
}
. "$S/scenarios.sh"
