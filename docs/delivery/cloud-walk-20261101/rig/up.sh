#!/usr/bin/env bash
# Cloud walk rig: real MySQL 8 + Redis (pinned Docker images) + candidate jar with e2e stubs on 127.0.0.1:8099.
# Reuses ci/chain_acceptance.py stages (plan/precheck/explode/storage-start/schema) from the candidate itself.
# Usage: bash up.sh            # full bring-up (~6-9 min cold; ~2 min if jar already built)
#        RIG_SKIP_BUILD=1 bash up.sh   # reuse existing candidate jar
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8   # POSIX locale breaks javac on the repo's Chinese filenames
SHA=a1d30fb295d361122aec76fa9c180b4b496a9b0a
RIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git -C "$RIG_DIR" rev-parse --show-toplevel)"
export RIG_HOME="${RIG_HOME:-$HOME/walk-rig}"
export CAND="${CAND:-$RIG_HOME/candidate}"
mkdir -p "$RIG_HOME"
t0=$(date +%s); log(){ echo "[up.sh +$(( $(date +%s)-t0 ))s] $*"; }

# 1. docker daemon (cloud container has dockerd installed but not started)
if ! docker ps >/dev/null 2>&1; then
  log "starting dockerd"; nohup dockerd >"$RIG_HOME/dockerd.log" 2>&1 &
  for i in $(seq 1 30); do docker ps >/dev/null 2>&1 && break; sleep 1; done
  docker ps >/dev/null
fi
# system python3 has a broken cryptography (pyo3 panic on import), so PyMySQL lives in a venv
PY="$RIG_HOME/venv/bin/python"
if ! "$PY" -c 'import pymysql, cryptography' 2>/dev/null; then
  python3 -m venv "$RIG_HOME/venv"
  "$PY" -m pip install -q --disable-pip-version-check PyMySQL==1.1.1 cryptography
fi

# 2. candidate checkout at the tested commit (detached worktree, never pushed)
git -C "$REPO" fetch -q origin master || true
if [ ! -d "$CAND/.git" ] && [ ! -f "$CAND/.git" ]; then
  git -C "$REPO" worktree add --detach "$CAND" "$SHA" >/dev/null
fi
[ "$(git -C "$CAND" rev-parse HEAD)" = "$SHA" ] || { echo "candidate HEAD is not $SHA"; exit 1; }
# chain_acceptance precheck wants origin/master inside the candidate repo; worktree shares refs.

# 3. build jar (JDK per candidate pom: 21)
JAR="$CAND/chengyinhub-admin/target/chengyinhub-admin.jar"
if [ -z "${RIG_SKIP_BUILD:-}" ] || [ ! -f "$JAR" ]; then
  log "mvn package (JDK $(java -version 2>&1 | grep -o 'version "[0-9]*' | head -1))"
  (cd "$CAND" && mvn -B -q -DskipTests package) >"$RIG_HOME/mvn.log" 2>&1 || { tail -40 "$RIG_HOME/mvn.log"; exit 1; }
fi

# 4. chain_acceptance stages
OUT="$RIG_HOME/out"; WORK="$RIG_HOME/work"
rm -rf "$OUT"; mkdir -p "$OUT" "$WORK"
CA=("$PY" "$CAND/ci/chain_acceptance.py" --out "$OUT" --gate "$CAND" --source "$CAND" --work "$WORK" --app "$WORK/app" --jar "$JAR")
export SOURCE_SHA=$SHA THROUGH=player FAIL_PATH=""
"$PY" "$RIG_DIR/app.py" stop >/dev/null 2>&1 || true
"${CA[@]}" plan
"${CA[@]}" precheck
log "explode + compile e2e stubs"; "${CA[@]}" explode
log "storage-start"; "${CA[@]}" storage-start
log "schema + seed"; "${CA[@]}" schema
# 5. start app with persisted throwaway secrets
log "start app"; "$PY" "$RIG_DIR/app.py" start
log "probe"; curl -s -o /dev/null -w "GET /api/city/nodes (anon) -> %{http_code}\n" http://127.0.0.1:8099/api/city/nodes
# 6. browser runtime: bundle the real mini-program, start the rig web server (also the fake jscode2session)
log "build browser bundle"; bash "$RIG_DIR/mpweb/build.sh" >"$RIG_HOME/mpweb-build.log" 2>&1 || { tail -20 "$RIG_HOME/mpweb-build.log"; exit 1; }
if [ -f "$RIG_HOME/web.pid" ] && kill -0 "$(cat "$RIG_HOME/web.pid")" 2>/dev/null; then kill "$(cat "$RIG_HOME/web.pid")"; sleep 1; fi
nohup node "$RIG_DIR/mpweb/server.mjs" >"$RIG_HOME/web.log" 2>&1 & echo $! >"$RIG_HOME/web.pid"
sleep 1; curl -s -o /dev/null -w "rig web http://127.0.0.1:8097/ -> %{http_code}\n" http://127.0.0.1:8097/
curl -s -X POST -d code=rig-e2e-openid-9004 http://127.0.0.1:8097/__api/api/login/code | head -c 120; echo
log "done. redis-cli shim: export PATH=$WORK/bin:\$PATH"
