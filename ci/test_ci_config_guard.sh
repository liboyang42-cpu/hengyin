#!/usr/bin/env bash
# ci-config-guard 的 runner 标签契约测试。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GUARD="$REPO_ROOT/ci/ci-config-guard.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

pass=0
fail=0

write_workflow() {
  local name="$1"
  local runs_on="$2"

  cat > "$TEMP_DIR/$name.yml" <<EOF
name: guard fixture
jobs:
  check:
    runs-on: $runs_on
EOF
}

expect_pass() {
  local name="$1"
  if CI_YML="$TEMP_DIR/$name.yml" bash "$GUARD" >/dev/null 2>&1; then
    pass=$((pass + 1))
  else
    echo "应通过但失败: $name" >&2
    fail=$((fail + 1))
  fi
}

expect_fail() {
  local name="$1"
  if CI_YML="$TEMP_DIR/$name.yml" bash "$GUARD" >/dev/null 2>&1; then
    echo "应拒绝但通过: $name" >&2
    fail=$((fail + 1))
  else
    pass=$((pass + 1))
  fi
}

write_workflow complete '[self-hosted, macOS, ARM64, chengyin] # TEMP intentional'
expect_pass complete

write_workflow complete_reordered '[chengyin, ARM64, self-hosted, macOS] # TEMP intentional'
expect_pass complete_reordered

write_workflow missing_arm64 '[self-hosted, macOS, chengyin] # TEMP intentional'
expect_fail missing_arm64

write_workflow incomplete_matrix '[self-hosted, macOS] # TEMP intentional'
expect_fail incomplete_matrix

write_workflow missing_chengyin '[self-hosted, macOS, ARM64] # TEMP intentional'
expect_fail missing_chengyin

write_workflow extra_label '[self-hosted, macOS, ARM64, chengyin, x64] # TEMP intentional'
expect_fail extra_label

write_workflow wrong_label '[self-hosted, macOS, ARM64, wrong-city] # TEMP intentional'
expect_fail wrong_label

write_workflow unmarked '[self-hosted, macOS, ARM64, chengyin]'
expect_fail unmarked

if [ "$fail" -ne 0 ]; then
  echo "ci-config-guard tests failed: $fail failed, $pass passed" >&2
  exit 1
fi

echo "ci-config-guard tests passed: $pass cases"
