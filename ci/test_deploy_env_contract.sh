#!/usr/bin/env bash
# deploy-restart.sh 的 env 文件缺失安全契约测试。
# Source 真实脚本的 ensure_env_file 函数(非复制逻辑),测试即 seam 绑定。
# 若有人误删 deploy-restart.sh 中的 ensure_env_file 调用,该函数仍在此可调,
# 故需额外做 mutation 测试验证"删除→红,还原→绿"。
#
# 验证:①缺失时自动创建空文件(0600)与目录(0700);
#      ②已有文件不覆盖内容、不改权限。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_RESTART_SOURCED=1
source "$SCRIPT_DIR/deploy-restart.sh"
# 至此 ensure_env_file 已定义,可被测试调用

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

# 部署机运行脚本时必须新建 root:root 文件；本地 CI 无 root 权限，故只在这里
# shim install 的 chown 参数，同时记录真实函数是否把 root:root 契约传给 install。
REAL_INSTALL="$(command -v install)"
root_dir_install_calls=0
root_file_install_calls=0
install() {
  local is_directory=0
  local is_empty_file=0
  local has_root_owner=0
  local has_root_group=0
  local -a filtered=()
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -d)
        is_directory=1
        filtered+=("$1")
        shift
        ;;
      -o|-g)
        if [ "$1" = "-o" ] && [ "${2:-}" = "root" ]; then
          has_root_owner=1
        fi
        if [ "$1" = "-g" ] && [ "${2:-}" = "root" ]; then
          has_root_group=1
        fi
        shift 2
        ;;
      /dev/null)
        is_empty_file=1
        filtered+=("$1")
        shift
        ;;
      *)
        filtered+=("$1")
        shift
        ;;
    esac
  done
  if [ "$has_root_owner" -eq 1 ] && [ "$has_root_group" -eq 1 ]; then
    if [ "$is_directory" -eq 1 ]; then
      root_dir_install_calls=$((root_dir_install_calls + 1))
    elif [ "$is_empty_file" -eq 1 ]; then
      root_file_install_calls=$((root_file_install_calls + 1))
    fi
  fi
  command "$REAL_INSTALL" "${filtered[@]}"
}

pass=0
fail=0

get_perms() {
  stat -c "%a" "$1" 2>/dev/null || stat -f "%Lp" "$1" 2>/dev/null || echo "error"
}

# 测试 1:缺失时创建空文件 0600,目录 0700
t1_dir="$TEMP_DIR/missing"
t1_file="$t1_dir/chengyinhub-admin.env"
ensure_env_file "$t1_dir" "$t1_file"
err=""
[ -f "$t1_file" ] || err="$err file missing"
[ ! -s "$t1_file" ] || err="$err not empty"
[ "$(get_perms "$t1_file")" = "600" ] || err="$err file perms=$(get_perms "$t1_file")"
[ "$(get_perms "$t1_dir")" = "700" ] || err="$err dir perms=$(get_perms "$t1_dir")"
if [ -n "$err" ]; then
  echo "FAIL t1:$err"; fail=$((fail + 1))
else
  pass=$((pass + 1))
fi

# 测试 2:已有文件(644)→不覆盖内容、不改权限
t2_dir="$TEMP_DIR/existing"
t2_file="$t2_dir/chengyinhub-admin.env"
mkdir -p "$t2_dir"
echo "PRESERVED_CONTENT" > "$t2_file"
chmod 644 "$t2_file"
before_content=$(cat "$t2_file")
before_perms=$(get_perms "$t2_file")
ensure_env_file "$t2_dir" "$t2_file"
err=""
[ "$before_content" = "$(cat "$t2_file")" ] || err="$err content changed"
[ "$before_perms" = "$(get_perms "$t2_file")" ] || err="$err perms changed"
if [ -n "$err" ]; then
  echo "FAIL t2:$err"; fail=$((fail + 1))
else
  pass=$((pass + 1))
fi

# 测试 3:已有文件(0600)→不覆盖内容
t3_dir="$TEMP_DIR/existing2"
t3_file="$t3_dir/chengyinhub-admin.env"
mkdir -p "$t3_dir"
echo "ALSO_PRESERVED" > "$t3_file"
chmod 600 "$t3_file"
before_content=$(cat "$t3_file")
ensure_env_file "$t3_dir" "$t3_file"
[ "$before_content" = "$(cat "$t3_file")" ] && pass=$((pass + 1)) || { echo "FAIL t3: overwritten"; fail=$((fail + 1)); }

# 测试 4:嵌套目录缺失→创建目录链
t4_dir="$TEMP_DIR/nested/deep/missing"
t4_file="$t4_dir/chengyinhub-admin.env"
ensure_env_file "$t4_dir" "$t4_file"
err=""
[ -f "$t4_file" ] || err="$err file missing"
[ "$(get_perms "$t4_dir")" = "700" ] || err="$err dir perms=$(get_perms "$t4_dir")"
if [ -n "$err" ]; then
  echo "FAIL t4:$err"; fail=$((fail + 1))
else
  pass=$((pass + 1))
fi

# 测试 5:生产创建必须显式请求 root:root。删除任一 owner/group 参数应让此处变红；
# CI shim 只移除 chown 参数，以便非 root runner 仍能验证真实文件模式和不覆盖语义。
if [ "$root_dir_install_calls" -lt 2 ] || [ "$root_file_install_calls" -lt 2 ]; then
  echo "FAIL t5: new env dir/file must request root:root (dir=$root_dir_install_calls file=$root_file_install_calls)"
  fail=$((fail + 1))
else
  pass=$((pass + 1))
fi

# 负控:确保 deploy-restart.sh 的主流程中仍有 ensure_env_file 调用行。
# 若有人只删调用、保留函数定义,前面四项测试均绿——这道检查让它红。
call_count=$(grep -c '^[[:space:]]*ensure_env_file$' "$SCRIPT_DIR/deploy-restart.sh" || true)
if [ "$call_count" -lt 1 ]; then
  echo "FAIL mutation: ensure_env_file call line missing from main flow"
  fail=$((fail + 1))
else
  pass=$((pass + 1))
fi

if [ "$fail" -ne 0 ]; then
  echo "deploy env contract FAILED: $fail failed, $pass passed" >&2
  exit 1
fi
echo "deploy env contract passed: $pass cases"
