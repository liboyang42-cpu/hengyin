#!/usr/bin/env bash
# 拦「CI runs-on 被静默改回 self-hosted」的回潮。
#
# 实证:2026-07-21 账单问题把 7 个 job 从 ubuntu-latest 切到 self-hosted(#146)解围,
# 账单解决后切回 ubuntu-latest(#166)。但一个跟 CI 毫无关系的 PR(#145,"补齐三模式
# 漫游照片归档")从更早的旧 master 拉出分支、没重新 rebase 到 #166 之后就合并了——它自己
# 的 ci.yml 副本还是 self-hosted 版本,squash 合并直接把 #166 的修复覆盖回去,且这个回潮
# 本身不会让任何 CI 变红(改的是 runs-on 不是业务逻辑),没人发现,直到整批 PR 堆积在单台
# self-hosted runner 上排队才被注意到。
#
# 这道门禁把「runs-on 是否符合当前策略」做成显式断言,回潮会让这一步直接变红。
set -euo pipefail

# 调用方传进来的 CI_YML 若是相对路径,它相对的是调用方的 CWD —— 必须在 cd 之前定住,
# 否则回到仓库根后就指到别处了(ci/test_ci_config_guard.sh 传的是绝对路径,不受影响)。
if [ -n "${CI_YML:-}" ] && [ "${CI_YML#/}" = "$CI_YML" ]; then CI_YML="$PWD/$CI_YML"; fi
cd "$(git rev-parse --show-toplevel)" || exit 1   # 见 ci/test_cwd_independence.sh

CI_YML="${CI_YML:-.github/workflows/ci.yml}"
EXPECTED_SELF_HOSTED_LABELS=(self-hosted macOS ARM64 chengyin)

if [ ! -f "$CI_YML" ]; then
  echo "找不到 $CI_YML" >&2
  exit 1
fi

# 2026-07-23 billing 复发:CI 临时切 self-hosted(见 ci.yml 顶部 TEMP 说明)。每个 self-hosted
# runs-on 都必须带 TEMP 标记，且标签集合严格等于当前 Mac runner 的四个标签。逐项解析 flow-style
# YAML 数组，避免只靠整行子串导致漏掉缺标签、错标签或多余标签。
self_hosted_count=0
invalid_count=0

has_expected_labels() {
  local -a actual_labels=("$@")
  local expected actual

  [ "${#actual_labels[@]}" -eq "${#EXPECTED_SELF_HOSTED_LABELS[@]}" ] || return 1
  for expected in "${EXPECTED_SELF_HOSTED_LABELS[@]}"; do
    for actual in "${actual_labels[@]}"; do
      [ "$actual" = "$expected" ] && break
    done
    [ "$actual" = "$expected" ] || return 1
  done
}

while IFS= read -r line; do
  [[ "$line" =~ ^[[:space:]]*runs-on:[[:space:]]* ]] || continue

  line_without_comment="${line%%#*}"
  runs_on="${line_without_comment#*runs-on:}"
  runs_on="${runs_on#"${runs_on%%[![:space:]]*}"}"
  runs_on="${runs_on%"${runs_on##*[![:space:]]}"}"

  labels_text="$runs_on"
  if [[ "$runs_on" = \[*\] ]]; then
    labels_text="${runs_on:1:${#runs_on}-2}"
  fi
  IFS=',' read -r -a labels <<< "$labels_text"
  for index in "${!labels[@]}"; do
    labels[$index]="${labels[$index]#"${labels[$index]%%[![:space:]]*}"}"
    labels[$index]="${labels[$index]%"${labels[$index]##*[![:space:]]}"}"
  done

  is_self_hosted=false
  for label in "${labels[@]}"; do
    [ "$label" = "self-hosted" ] && is_self_hosted=true
  done
  [ "$is_self_hosted" = true ] || continue

  self_hosted_count=$((self_hosted_count + 1))
  if [[ "$line" != *TEMP* ]]; then
    echo "❌ $CI_YML 的 self-hosted runs-on 缺少 TEMP 标记: $line" >&2
    invalid_count=$((invalid_count + 1))
  fi
  if ! has_expected_labels "${labels[@]}"; then
    echo "❌ $CI_YML 的 self-hosted runner labels 必须且只能为: ${EXPECTED_SELF_HOSTED_LABELS[*]}" >&2
    echo "   实际: ${labels[*]}" >&2
    invalid_count=$((invalid_count + 1))
  fi
done < "$CI_YML"

if [ "$invalid_count" -ne 0 ]; then
  exit 1
fi
if [ "$self_hosted_count" -ne 0 ]; then
  echo "✅ $CI_YML 的 self-hosted runs-on 均带 TEMP 标记且 labels 完整。"
else
  echo "✅ $CI_YML 的 runs-on 符合默认策略(ubuntu-latest)。"
fi
