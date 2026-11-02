#!/usr/bin/env bash
# Build the browser bundle from the candidate's real chengyinhub-xcx (all app.json pages + subpackages + app.js).
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RIG_HOME="${RIG_HOME:-$HOME/walk-rig}"; CAND="${CAND:-$RIG_HOME/candidate}"; XCX="$CAND/chengyinhub-xcx"
OUT="$RIG_HOME/mpweb"; mkdir -p "$OUT"
SEED="$HERE/../mpweb-seed/tools"
ENTRIES=$(python3 - "$XCX/app.json" <<'PY'
import json,sys
a=json.load(open(sys.argv[1]))
e=list(a['pages'])
for s in a.get('subPackages',a.get('subpackages',[])):
    e+=[s['root'].rstrip('/')+'/'+p for p in s['pages']]
print(' '.join(e+['custom-tab-bar/index']))
PY
)
python3 "$SEED/bundle.py" "$XCX" "$OUT/mp.js" --with-app $ENTRIES
node "$SEED/compile.mjs" "$OUT/mp.js" "$OUT/bundle.js"
python3 -c "import json,sys; print('window.__APPJSON=' + json.dumps(json.load(open(sys.argv[1])), ensure_ascii=False) + ';')" "$XCX/app.json" > "$OUT/appjson.js"
if [ ! -d "$OUT/node_modules/puppeteer-core" ]; then (cd "$OUT" && npm i --silent --no-audit --no-fund puppeteer-core@23.11.1); fi
ln -sfn "$OUT/node_modules" "$HERE/node_modules"   # gitignored; lets walk.mjs import puppeteer-core
echo "bundle ready in $OUT"
