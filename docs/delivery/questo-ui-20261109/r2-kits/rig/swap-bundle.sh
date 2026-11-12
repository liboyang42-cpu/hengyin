#!/usr/bin/env bash
# 换 rig 的小程序包:CAND=<含 chengyinhub-xcx 的工作树> bash swap-bundle.sh
# 后端不动(本 lane 只改了题面渲染那两处 Java,玩家屏截图走 pickPlayKit 喂视图,不经后端)。
set -euo pipefail
RIG=/root/rig/docs/delivery/cloud-walk-20261101/rig
export RIG_HOME="${RIG_HOME:-$HOME/walk-rig}"
bash "$RIG/mpweb/build.sh" > "$RIG_HOME/mpweb-build.log" 2>&1 || { tail -20 "$RIG_HOME/mpweb-build.log"; exit 1; }
if [ -f "$RIG_HOME/web.pid" ] && kill -0 "$(cat "$RIG_HOME/web.pid")" 2>/dev/null; then kill "$(cat "$RIG_HOME/web.pid")"; sleep 1; fi
nohup node "$RIG/mpweb/server.mjs" > "$RIG_HOME/web.log" 2>&1 & echo $! > "$RIG_HOME/web.pid"
sleep 1; curl -s -o /dev/null -w "rig web -> %{http_code} (CAND=$CAND)\n" http://127.0.0.1:8097/
