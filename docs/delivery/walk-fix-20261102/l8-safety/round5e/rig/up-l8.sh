#!/usr/bin/env bash
# l8-safety Round5E: bring the rig up at SHA, then replace the e2e content-security stub (which overrides
# checkText wholesale and so would skip the Round5D library) with RigWxOutboundStub (outbound HTTP only).
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8
L8="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; R="$(dirname "$L8")"
SHA="$1"; CAND="${CAND:-$HOME/walk-rig/candidate}"
git -C "$CAND" fetch -q origin claude/walk-fix-1103-l8-safety || true
git -C "$CAND" checkout -q --detach "$SHA"
sed -i "s/^SHA=.*/SHA=$SHA/" "$R/up.sh"
bash "$R/up.sh"
PY="$HOME/walk-rig/venv/bin/python"; A="$HOME/walk-rig/work/app"
"$PY" "$R/app.py" stop
mkdir -p "$HOME/walk-rig/disabled"
mv -f "$A/BOOT-INF/classes/com/chengyinhub/e2e/E2eWxContentSecurityStub.class" "$HOME/walk-rig/disabled/"
CP="$A/BOOT-INF/classes:$(ls "$A"/BOOT-INF/lib/*.jar | tr '\n' ':')"
javac -encoding UTF-8 -proc:none -cp "$CP" -d "$A/BOOT-INF/classes" "$L8/RigWxOutboundStub.java"
echo pass > "${RIG_WXSEC_MODE_FILE:-/tmp/rig-wxsec-mode}"
"$PY" "$R/app.py" start
"$PY" -m pip -q install pillow >/dev/null 2>&1 || true
