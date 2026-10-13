#!/usr/bin/env bash
# Local filesystem fixtures only: never start Java or inspect/stop host processes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
VERIFY_DEPLOYED_SOURCED=1 source "$ROOT/ci/verify-deployed.sh"
mkdir -p "$FIXTURE/proc" "$FIXTURE/one" "$FIXTURE/other" "$FIXTURE/bin"
printf fixture > "$FIXTURE/one/chengyinhub-admin.jar"
printf other > "$FIXTURE/other/chengyinhub-admin.jar"
printf java > "$FIXTURE/bin/java"
printf python > "$FIXTURE/bin/python"
WANTED=$(readlink -f "$FIXTURE/one/chengyinhub-admin.jar")
make_process() {
  local pid="$1" exe="$2" jar="$3"
  mkdir -p "$FIXTURE/proc/$pid"
  ln -sf "$FIXTURE/bin/$exe" "$FIXTURE/proc/$pid/exe"
  ln -sf "$FIXTURE/one" "$FIXTURE/proc/$pid/cwd"
  printf '%s\0' java -Dprivate=fixture -jar "$jar" > "$FIXTURE/proc/$pid/cmdline"
  printf '%s\n' "$pid (java name (test)) S $(printf '0 %.0s' {1..18})99" > "$FIXTURE/proc/$pid/stat"
}
expect_match() { jar_process_matches "$FIXTURE/proc/$1" "$WANTED" || { echo 'expected exact jar match'; exit 1; }; }
expect_reject() { if jar_process_matches "$FIXTURE/proc/$1" "$WANTED"; then echo 'unexpected jar match'; exit 1; fi; }
make_process 101 java "$WANTED"
expect_match 101
make_process 101 java chengyinhub-admin.jar
expect_match 101
make_process 101 java "$FIXTURE/other/chengyinhub-admin.jar"
expect_reject 101
make_process 101 python "$WANTED"
expect_reject 101
make_process 101 java "$WANTED"
printf '%s\0' java -Dnote=-jar "$WANTED" > "$FIXTURE/proc/101/cmdline"
expect_reject 101
make_process 101 java "$WANTED"
[[ "$(jar_start_ticks "$FIXTURE/proc/101")" == 99 ]]

# Test the real verifier orchestration with fixture proc facts, no production override flag.
eval "$(declare -f exact_jar_pids | sed '1s/exact_jar_pids/original_exact_jar_pids/')"
eval "$(declare -f jar_process_matches | sed '1s/jar_process_matches/original_jar_process_matches/')"
eval "$(declare -f jar_start_ticks | sed '1s/jar_start_ticks/original_jar_start_ticks/')"
exact_jar_pids() {
  original_exact_jar_pids "$1" "$FIXTURE/proc" || return
  [[ "${PID_READER_ERROR:-}" != 1 || "${HEALTH_CHECKED:-}" != 1 ]]
}
jar_process_matches() {
  local proc="$1"
  [[ "$proc" != /proc/* ]] || proc="$FIXTURE/proc/${proc##*/}"
  original_jar_process_matches "$proc" "$2"
}
jar_start_ticks() {
  original_jar_start_ticks "$FIXTURE/proc/${1##*/}" || return
  [[ "${TICKS_READER_ERROR:-}" != 1 || "${HEALTH_CHECKED:-}" != 1 ]]
}
sha256sum() { printf '%s  fixture\n' "${DIGEST:-good}"; [[ "${DIGEST_ERROR:-}" != 1 ]]; }
ps() { printf '%s\n' "${ETIMES:-10}"; [[ "${PS_ERROR:-}" != 1 ]]; }
date() { [[ "$1" != +%s ]] || { printf '200\n'; [[ "${DATE_ERROR:-}" != 1 ]]; return; }; printf 'fixture time\n'; }
grep() {
  if [[ "$1" == -c && "${COUNT_ERROR:-}" == 1 ]]; then printf '1\n'; return 2; fi
  command grep "$@"
}
python3() {
  HEALTH_CHECKED=1
  if [[ "${REUSE_PID:-}" == 1 ]]; then
    printf '%s\n' "101 (java) S $(printf '0 %.0s' {1..18})100" > "$FIXTURE/proc/101/stat"
  fi
  [[ "${HEALTH_FAIL:-}" != 1 ]]
}
assert_no_argv_leak() {
  local rc
  command -v grep >/dev/null 2>&1 || { echo 'leak checker unavailable'; return 1; }
  if grep -Fq 'private=fixture' "$FIXTURE/result"; then
    echo 'argv leaked'; return 1
  else
    rc=$?
    [[ "$rc" == 1 ]] || { echo 'leak checker failed'; return 1; }
  fi
}
expect_main() {
  local label="$1" expected="$2" rc
  if (verify_deployed_main "$WANTED" 100 good fixture-checker fixture-token) > "$FIXTURE/result" 2>&1; then rc=0; else rc=$?; fi
  if [[ "$expected" == pass ]]; then [[ "$rc" == 0 ]]; else [[ "$rc" != 0 ]]; fi
  assert_no_argv_leak || exit 1
  printf 'PASS %s\n' "$label"
}
expect_main exact-positive pass
DIGEST=bad expect_main bad-digest fail
ETIMES=150 expect_main old-process fail
HEALTH_FAIL=1 expect_main health-down fail
REUSE_PID=1 expect_main pid-reused fail
make_process 101 java "$WANTED"
make_process 202 java "$WANTED"
expect_main two-exact-processes fail
rm -rf "$FIXTURE/proc/202"
make_process 202 java "$FIXTURE/other/chengyinhub-admin.jar"
expect_main same-name-other-checkout pass
rm -rf "$FIXTURE/proc/101"
expect_main no-exact-process fail
printf 'exact argv/cwd/java/starttime fixtures and 8 verifier cases passed\n'

# Matching output must not conceal helper failures when the caller uses an if condition.
make_process 101 java "$WANTED"
DIGEST_ERROR=1 expect_main digest-reader-error fail
PS_ERROR=1 expect_main process-reader-error fail
DATE_ERROR=1 expect_main clock-reader-error fail
COUNT_ERROR=1 expect_main count-reader-error fail
TICKS_READER_ERROR=1 expect_main health-after-ticks-reader-error fail
PID_READER_ERROR=1 expect_main health-after-pids-reader-error fail
# A broken observation tool must fail, rather than being treated as no sensitive output.
grep() { return 2; }
if assert_no_argv_leak; then echo 'broken leak checker passed'; exit 1; fi
unset -f grep
printf 'helper failure propagation and leak-checker negative control passed\n'
