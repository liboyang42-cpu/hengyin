#!/usr/bin/env bash
# 回归门：分类器在 pipefail 下遇到长文件列表也必须保持命中结果。
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
source "$repo_root/ci/classify-runtime.sh"

scratch=$(mktemp -d)
cleanup() { rm -rf "$scratch"; }
trap cleanup EXIT

git -C "$scratch" init -q
git -C "$scratch" config user.email ci@example.invalid
git -C "$scratch" config user.name "CI"

mkdir -p "$scratch/docs"
touch "$scratch/docs/base.md"
git -C "$scratch" add .
git -C "$scratch" commit -qm "base"
base=$(git -C "$scratch" rev-parse HEAD)

# 运行时文件排在前面；旧实现的 grep -q 会提前退出，让后续长列表的 printf 收到 SIGPIPE，
# 再被 pipefail 错误地解释为“没有运行时改动”。
mkdir -p "$scratch/chengyinhub-system/src" "$scratch/chengyinhub-ui/src"
touch "$scratch/chengyinhub-system/src/Runtime.java" "$scratch/chengyinhub-ui/src/App.vue"
for i in $(seq 1 1600); do
  touch "$scratch/docs/$(printf '%04d' "$i")-classifier-regression-padding.md"
done
git -C "$scratch" add .
git -C "$scratch" commit -qm "runtime and ui changes"
head=$(git -C "$scratch" rev-parse HEAD)

cd "$scratch"
if ! runtime_changed_between "$base" "$head"; then
  echo "expected runtime classifier to detect the runtime change" >&2
  exit 1
fi
if ! ui_changed_between "$base" "$head"; then
  echo "expected UI classifier to detect the UI change" >&2
  exit 1
fi

echo "runtime classifier regression checks passed"
