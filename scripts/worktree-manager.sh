#!/usr/bin/env bash
# 城瘾 AI 协作 Worktree 管理器：只管理仓库外的 ../chengyin-worktrees 目录。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKTREE_ROOT="${CHENGYIN_WORKTREE_ROOT:-$(dirname "$ROOT")/chengyin-worktrees}"
DEFAULT_BASE="${CHENGYIN_WORKTREE_BASE:-master}"

usage() {
  cat <<'EOF'
用法：
  scripts/worktree-manager.sh create <feature|fix> <业务域-任务短名> [基线分支]
  scripts/worktree-manager.sh review <待审分支>
  scripts/worktree-manager.sh status
  scripts/worktree-manager.sh cleanup <Worktree绝对路径>

示例：
  scripts/worktree-manager.sh create feature game-mode
  scripts/worktree-manager.sh create feature xcx-ui-consistency
  scripts/worktree-manager.sh create fix merchant-order-null

规则：create 创建 <feature|fix>/<业务域-任务短名>（跨端用业务域名，单端可带 xcx/admin/ui 前缀）；
新分支默认从 master 创建；review 使用 detached Worktree 只读复核；
cleanup 只删除干净的、位于 CHENGYIN_WORKTREE_ROOT 下的 Worktree，绝不强制删除。
EOF
}

fail() {
  echo "错误：$*" >&2
  exit 1
}

require_git_ref() {
  git -C "$ROOT" rev-parse --verify --quiet "$1^{commit}" >/dev/null \
    || fail "找不到 Git 引用：$1"
}

safe_name() {
  [[ "$1" =~ ^[a-z0-9][a-z0-9-]*$ ]] || fail "名称仅允许小写字母、数字和连字符：$1"
}

create_worktree() {
  local branch_type="$1"
  local task="$2"
  local base="${3:-$DEFAULT_BASE}"
  local branch destination

  case "$branch_type" in
    feature|fix) ;;
    *) fail "分支类型必须是 feature 或 fix" ;;
  esac
  safe_name "$task"
  require_git_ref "$base"

  branch="${branch_type}/${task}"
  destination="$WORKTREE_ROOT/${branch_type}-${task}"
  git -C "$ROOT" show-ref --verify --quiet "refs/heads/$branch" \
    && fail "本地分支已存在：$branch"
  [[ ! -e "$destination" ]] || fail "目标目录已存在：$destination"

  mkdir -p "$WORKTREE_ROOT"
  git -C "$ROOT" worktree add -b "$branch" "$destination" "$base"
  printf '\n已创建：\n  分支：%s\n  目录：%s\n  基线：%s\n' "$branch" "$destination" "$base"
  echo "下一步：在该目录创建任务说明、开发、最小提交、推送并发起 Codeup MR。"
}

review_worktree() {
  local branch="$1"
  local slug destination
  require_git_ref "$branch"
  slug="${branch//\//-}"
  destination="$WORKTREE_ROOT/review-${slug}"
  [[ ! -e "$destination" ]] || fail "目标目录已存在：$destination"

  mkdir -p "$WORKTREE_ROOT"
  git -C "$ROOT" worktree add --detach "$destination" "$branch"
  printf '\n已创建只读审查 Worktree：\n  目录：%s\n  审查对象：%s\n' "$destination" "$branch"
}

show_status() {
  git -C "$ROOT" worktree list --porcelain | awk '
    /^worktree / { location=$2 }
    /^HEAD / { commit=substr($0, 6, 8) }
    /^branch / { branch=$2; sub("refs/heads/", "", branch); printf "%-65s %-10s %s\n", location, commit, branch; location=""; commit=""; branch="" }
    /^detached$/ { printf "%-65s %-10s detached\n", location, commit; location=""; commit=""; branch="" }
  '
  echo
  echo "工作目录状态（仅列出已注册 Worktree）："
  while IFS= read -r registered; do
    git -C "$registered" status --short | sed "s#^#  ${registered}: #" || true
  done < <(git -C "$ROOT" worktree list --porcelain | awk '/^worktree / {print $2}')
}

cleanup_worktree() {
  local destination="$1"
  local canonical_root canonical_destination
  canonical_root="$(cd "$WORKTREE_ROOT" 2>/dev/null && pwd -P)" \
    || fail "Worktree 根目录不存在：$WORKTREE_ROOT"
  canonical_destination="$(cd "$destination" 2>/dev/null && pwd -P)" \
    || fail "Worktree 目录不存在：$destination"
  [[ "$canonical_destination" == "$canonical_root"/* ]] \
    || fail "只允许清理 $canonical_root 下的 Worktree"

  git -C "$ROOT" worktree remove "$canonical_destination"
  echo "已移除干净 Worktree：$canonical_destination"
}

command="${1:-}"
case "$command" in
  create)
    [[ $# -ge 3 && $# -le 4 ]] || { usage; exit 1; }
    create_worktree "$2" "$3" "${4:-}"
    ;;
  review)
    [[ $# -eq 2 ]] || { usage; exit 1; }
    review_worktree "$2"
    ;;
  status)
    [[ $# -eq 1 ]] || { usage; exit 1; }
    show_status
    ;;
  cleanup)
    [[ $# -eq 2 ]] || { usage; exit 1; }
    cleanup_worktree "$2"
    ;;
  -h|--help|help|'')
    usage
    ;;
  *)
    usage
    exit 1
    ;;
esac
