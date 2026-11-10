#!/bin/bash
# Rebuild the rig browser bundle from a given commit of the repo (backend/jar untouched), restart the rig web server.
set -euo pipefail
SHA=${1:?sha}
export RIG_HOME=$HOME/walk-rig CAND=$HOME/walk-rig/candidate
RIG=$HOME/rig/docs/delivery/cloud-walk-20261101/rig
git -C /home/user/hengyin rev-parse --verify "$SHA" >/dev/null
git -C "$CAND" checkout -q --detach "$SHA"
echo "candidate at $(git -C "$CAND" log --oneline -1)"
bash "$RIG/mpweb/build.sh" > "$RIG_HOME/mpweb-build.log" 2>&1 || { tail -20 "$RIG_HOME/mpweb-build.log"; exit 1; }
if [ -f "$RIG_HOME/web.pid" ] && kill -0 "$(cat "$RIG_HOME/web.pid")" 2>/dev/null; then kill "$(cat "$RIG_HOME/web.pid")"; sleep 1; fi
nohup node "$RIG/mpweb/server.mjs" >"$RIG_HOME/web.log" 2>&1 & echo $! >"$RIG_HOME/web.pid"
sleep 1; curl -s -o /dev/null -w "rig web -> %{http_code}\n" http://127.0.0.1:8097/
