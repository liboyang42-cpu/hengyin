#!/usr/bin/env bash
# push 回执不等于镜像 master 真的移动；从 Codeup 自己的 ref 回读并比对。
set -euo pipefail

EXPECTED_SHA="${1:?缺少期望 commit}"
REMOTE_URL="${2:?缺少 Codeup 远程 URL}"

git_cmd=(git)
if [ -n "${CODEUP_SSH_COMMAND:-}" ]; then
  git_cmd+=(-c "core.sshCommand=$CODEUP_SSH_COMMAND")
fi

if ! remote_output="$("${git_cmd[@]}" ls-remote "$REMOTE_URL" refs/heads/master)"; then
  echo "::error::Codeup master 回读失败；ls-remote 没有产生可验证的对端事实。"
  exit 1
fi
actual_sha="$(printf '%s\n' "$remote_output" | awk 'NR == 1 { print $1 }')"
if [ "$actual_sha" != "$EXPECTED_SHA" ]; then
  echo "::error::Codeup master = ${actual_sha:-<缺失>},预期 ${EXPECTED_SHA}。push 被接受 ≠ master 动了(服务端可能重定向了 ref)。"
  exit 1
fi

echo "Codeup master verified at $EXPECTED_SHA"
