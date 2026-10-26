#!/usr/bin/env bash
# Run only on approved test VPS, after final candidate/public scan is accepted.
set -euo pipefail
task=${1:?task directory name required}
[[ "$task" =~ ^[a-z0-9][a-z0-9-]*$ ]] || exit 2
bundle=$(cd -- "$(dirname -- "$0")" && pwd)
source_dir="/home/ubuntu/previews/$task/source"
cache_dir="/home/ubuntu/previews/$task/m2"
wrapper=/home/ubuntu/.local/bin/chengyin-build-budget-212
[[ -x "$wrapper" && -d "$source_dir" && -d "$cache_dir" ]] || exit 2
[[ ! -L "$source_dir" && ! -L "$cache_dir" ]] || exit 2
[[ $(stat -c %u "$source_dir") == "$(id -u)" && $(stat -c %u "$cache_dir") == "$(id -u)" ]] || exit 2
[[ $(realpath "$source_dir") == "$source_dir" && $(realpath "$cache_dir") == "$cache_dir" ]] || exit 2
# Logs/reports are unique and preserved on failures; never reuse existing reports.
run_dir="$source_dir/w8-12-validation-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$run_dir"
python3 - "$source_dir" "$bundle" <<'PY'
import hashlib,json,pathlib,subprocess,sys,tempfile,os
source,bundle=map(pathlib.Path,sys.argv[1:]);m=json.loads((bundle/'manifest.json').read_text())
with tempfile.TemporaryDirectory() as d:
 env=dict(os.environ,GIT_INDEX_FILE=d+'/index')
 subprocess.run(['git','-C',str(source),'read-tree',m['tree']],env=env,check=True)
 subprocess.run(['git','-C',str(source),'add','-u'],env=env,check=True)
 tree=subprocess.check_output(['git','-C',str(source),'write-tree'],env=env).decode().strip()
 assert tree==m['tree'], 'source tree mismatch'
for x in json.loads((bundle/'test-targets.json').read_text())['targets']:
 assert hashlib.sha256((source/x['path']).read_bytes()).hexdigest()==x['sha256'],x['path']
PY
if find "$source_dir" -path '*/target/surefire-reports/TEST-*.xml' -print -quit | grep -q .; then
  echo 'Existing Surefire reports: use a fresh isolated source checkout.' >&2
  exit 2
fi
cp "$bundle/manifest.json" "$bundle/test-targets.json" "$bundle/offline-tests.txt" "$run_dir/"
archive_reports() {
  mkdir -p "$run_dir/reports"
  while IFS= read -r -d '' report; do
    rel=${report#"$source_dir/"}
    mkdir -p "$run_dir/reports/$(dirname "$rel")"
    cp "$report" "$run_dir/reports/$rel"
  done < <(find "$source_dir" -path '*/target/surefire-reports/*' -type f -print0)
  find "$run_dir" -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > "$run_dir/SHA256SUMS"
}
trap archive_reports EXIT
cd "$source_dir"
# Wrapper owns the lock, live memory/concurrency checks and fixed resource limits.
"$wrapper" --name "cy-budget-212-$task-compile" --source "$source_dir" --cache "$cache_dir" -- \
  mvn -o -B -l "$run_dir/compile.log" -pl chengyinhub-admin -am -DskipTests package \
  | tee "$run_dir/compile-wrapper.txt"
grep -q 'BUILD SUCCESS' "$run_dir/compile.log"
classes=$(paste -sd, "$bundle/offline-tests.txt")
[[ -n "$classes" && "$classes" != *'*'* ]] || exit 2
"$wrapper" --name "cy-budget-212-$task-unit" --source "$source_dir" --cache "$cache_dir" -- \
  mvn -o -B -l "$run_dir/unit.log" -pl chengyinhub-admin -am \
  "-Dtest=$classes" -Dsurefire.failIfNoSpecifiedTests=false test \
  | tee "$run_dir/unit-wrapper.txt"
grep -q 'BUILD SUCCESS' "$run_dir/unit.log"
archive_reports
python3 "$bundle/check-reports.py" "$bundle/offline-tests.txt" "$run_dir/reports" > "$run_dir/class-results.json"
# Reactor exemption only allows upstream modules with no selected tests;
# check-reports.py must show every requested class executed, with zero skips.
