#!/usr/bin/env bash
# deploy.sh 备份轮转的负控。
#
# ★ 为什么需要(2026-08-28 生产实测):
#   /www/wwwroot/bin/backups 已堆到 699MB / 7 份,而云效 CD 才跑过 5 次 ——
#   deploy.sh 每次 cp 一份 99MB 的 jar 进去,【没有任何上限】。
#   若它成为现役部署路径按天部署,一年 ≈ 36G。
#   对照:GitHub 侧的 ci/deploy-restart.sh 有 KEEP_BACKUPS=5,生产实测正好 5 份。
#
# ★ 测的是【真函数】,不是拷贝:
#   从 deploy.sh 里按结构抽出 rotate_backups() 定义再 eval。
#   2026-08-27 的教训:ci/verify-pipeline-run.sh 的 --selftest 内联复制了一份判据,
#   于是真函数里的 bug 存在而自测全绿 —— 负控不走真代码路径就等于没验。
set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

fail=0
say() { printf '  %s\n' "$*"; }

# ── 抽真函数 ──────────────────────────────────────────────
src=$(sed -n '/^rotate_backups() {$/,/^}$/p' deploy.sh)
[ -n "$src" ] || { echo "❌ 没在 deploy.sh 里找到 rotate_backups() —— 结构变了或被删了"; exit 1; }
eval "$src"
type rotate_backups >/dev/null 2>&1 || { echo "❌ rotate_backups 没定义成功"; exit 1; }

mk() { # $1=目录 $2=份数;造 N 份,mtime 递增(序号越大越新)
  rm -rf "$1"; mkdir -p "$1"
  # ⚠️ BSD 的 `seq 1 0` 会【倒数】输出 "1 0",不是空 —— 0 份的用例会因此造出文件。
  #    2026-08-28 写这个测试时实测踩到,必须显式挡住。
  [ "$2" -ge 1 ] || return 0
  i=1
  while [ "$i" -le "$2" ]; do
    f="$1/chengyinhub-admin-2026082800$(printf '%04d' "$i").jar"
    : > "$f"
    # touch -t 的格式是 [[CC]YY]MMDDhhmm[.SS] —— 必须正好 12 位(YYYYMMDDhhmm)
    touch -t "20260828$(printf '%02d' $((i + 10)))00" "$f"
    i=$((i + 1))
  done
}
count() { ls -1 "$1"/chengyinhub-admin-*.jar 2>/dev/null | wc -l | tr -d ' '; }

t() { # $1=造几份 $2=keep $3=期望剩
  d=$(mktemp -d); mk "$d" "$1"
  rotate_backups "$d" "$2"
  got=$(count "$d")
  if [ "$got" = "$3" ]; then say "✅ $1 份 keep=$2 → 剩 $got"
  else say "❌ $1 份 keep=$2 → 期望剩 $3,实得 $got"; fail=1; fi
  rm -rf "$d"
}

echo "== 轮转行为 =="
t 8 5 5      # 超出要删
t 5 5 5      # 正好不删
t 3 5 3      # 不足不删
t 0 5 0      # 空目录不炸

echo "== 负控:保留的必须是【最新】那几份,不是随便几份 =="
d=$(mktemp -d); mk "$d" 8
rotate_backups "$d" 3
kept=$(ls -1 "$d" | sort | tr '\n' ' ')
# mtime 最新的是序号最大的三个(0006/0007/0008)
if echo "$kept" | grep -q "0008" && echo "$kept" | grep -q "0007" && echo "$kept" | grep -q "0006" \
   && ! echo "$kept" | grep -q "0001"; then
  say "✅ 保留的是最新 3 份($kept)"
else
  say "❌ 保留的不是最新 3 份:$kept"; fail=1
fi
rm -rf "$d"

echo "== 负控:调用点必须在 cp 之后(顺序反了会先删后备份失败) =="
cp_line=$(grep -n 'cp -pf "$APP_JAR" "$BACKUP_JAR"' deploy.sh | cut -d: -f1)
rot_line=$(grep -n 'rotate_backups "$BACKUP_DIR"' deploy.sh | cut -d: -f1)
if [ -n "$cp_line" ] && [ -n "$rot_line" ] && [ "$rot_line" -gt "$cp_line" ]; then
  say "✅ 轮转($rot_line 行)在 cp($cp_line 行)之后"
else
  say "❌ 顺序不对:cp=$cp_line 轮转=$rot_line"; fail=1
fi

echo "== 负控:KEEP_BACKUPS 必须与 ci/deploy-restart.sh 同口径 =="
a=$(grep -oE '^KEEP_BACKUPS=[0-9]+' deploy.sh | head -1 | cut -d= -f2)
b=$(grep -oE '^KEEP_BACKUPS=[0-9]+' ci/deploy-restart.sh | head -1 | cut -d= -f2)
if [ -n "$a" ] && [ "$a" = "$b" ]; then say "✅ 两条路径同口径($a)"
else say "❌ 口径不一致:deploy.sh=$a ci/deploy-restart.sh=$b"; fail=1; fi

[ "$fail" = "0" ] || { echo "轮转负控未通过"; exit 1; }
echo "轮转负控通过(4 组行为 + 3 条结构断言)"

echo "== 原部署入口:服务配置预检失败必须挡在 JAR 检查与写入之前 =="
d=$(mktemp -d)
mkdir -p "$d/scripts" "$d/bin" "$d/ci"
cp ci/check_deploy_health.py "$d/ci/check_deploy_health.py"
# 执行真脚本；仅在首次 JAR 检查前换成哨兵退出，绝不触碰生产目录或启动 Java。
sed 's/test -s "$NEW_JAR" || exit 1/printf "would-inspect-jar\\n"; exit 0/' deploy.sh > "$d/deploy.sh"
grep -q 'would-inspect-jar' "$d/deploy.sh" || { rm -rf "$d"; exit 1; }
cat > "$d/scripts/production-preflight.sh" <<'PREFLIGHT'
#!/bin/sh
[ "$1" = --service ] && [ "$2" = spring_chengyinhub-admin ] || exit 90
echo checked-running-service
exit "$PREFLIGHT_FIXTURE_STATUS"
PREFLIGHT
printf '%s\n' '#!/bin/sh' 'echo 0' > "$d/bin/id"
printf '%s\n' '#!/bin/sh' 'printf "systemctl:%s:%s\n" "$1" "$2"' > "$d/bin/systemctl"
cat > "$d/bin/python3" <<'HEALTH'
#!/bin/sh
if [ "$2" = --validate-token-file ]; then
  [ -n "$3" ] || exit 42
  echo checked-private-token-file
  exit "${TOKEN_FIXTURE_STATUS:-0}"
fi
echo checked-protected-health
exit "${HEALTH_FIXTURE_STATUS:-0}"
HEALTH
chmod +x "$d/bin/id" "$d/bin/systemctl" "$d/bin/python3"
for mode in start restart; do
  for status in 0 17; do
    output=$(env PATH="$d/bin:$PATH" PREFLIGHT_FIXTURE_STATUS="$status" DEPLOY_HEALTH_TOKEN_FILE=/fixture/private-token sh "$d/deploy.sh" "$mode" 2>&1)
    rc=$?
    if [ "$status" = 0 ]; then
      [ "$rc" = 0 ] && printf '%s' "$output" | grep -q 'would-inspect-jar' || fail=1
    else
      [ "$rc" != 0 ] && ! printf '%s' "$output" | grep -q 'would-inspect-jar' || fail=1
    fi
    printf '%s' "$output" | grep -q 'checked-running-service' || fail=1
    ! printf '%s' "$output" | grep -q 'systemctl:' || fail=1
  done
done

for missing in helper token; do
  if [ "$missing" = helper ]; then mv "$d/ci/check_deploy_health.py" "$d/ci/check_deploy_health.py.saved"; fi
  token=/fixture/private-token
  [ "$missing" != token ] || token=''
  output=$(env PATH="$d/bin:$PATH" PREFLIGHT_FIXTURE_STATUS=0 DEPLOY_HEALTH_TOKEN_FILE="$token" sh "$d/deploy.sh" restart 2>&1)
  rc=$?
  [ "$rc" != 0 ] && ! printf '%s' "$output" | grep -q 'would-inspect-jar' || fail=1
  if [ "$missing" = helper ]; then mv "$d/ci/check_deploy_health.py.saved" "$d/ci/check_deploy_health.py"; fi
done

mkdir "$d/bin-no-python"
for program in id systemctl bash dirname; do
  if [ -f "$d/bin/$program" ]; then ln -s "$d/bin/$program" "$d/bin-no-python/$program"
  else ln -s "$(command -v "$program")" "$d/bin-no-python/$program"; fi
done
output=$(env PATH="$d/bin-no-python" PREFLIGHT_FIXTURE_STATUS=0 DEPLOY_HEALTH_TOKEN_FILE=/fixture/private-token "$(command -v sh)" "$d/deploy.sh" restart 2>&1)
rc=$?
[ "$rc" != 0 ] && printf '%s' "$output" | grep -q '缺少 Python3' || fail=1
! printf '%s' "$output" | grep -q 'would-inspect-jar' || fail=1

# 复用真健康函数；mock 仅是进程检查和已独立测试过的健康出口，不连接生产。
health_function=$(sed -n '/^is_healthy() {$/,/^}$/p' deploy.sh)
[ -n "$health_function" ] || fail=1
for status in 0 17; do
  output=$(env PATH="$d/bin:$PATH" HEALTH_FIXTURE_STATUS="$status" bash -c '
    SERVICE=spring_chengyinhub-admin
    HEALTH_CHECKER=$1
    DEPLOY_HEALTH_TOKEN_FILE=/fixture/private-token
    eval "$2"
    is_healthy
  ' fixture "$d/ci/check_deploy_health.py" "$health_function" 2>&1)
  rc=$?
  [ "$rc" = "$status" ] && printf '%s' "$output" | grep -q 'checked-protected-health' || fail=1
done
! printf '%s' "$health_function" | grep -q 'ss -ltn' || fail=1

# 真回滚流程：任何关键步骤失败都不能被健康 UP 掩盖；原部署最终仍失败。
rollback_function=$(sed -n '/^rollback() {$/,/^}$/p' deploy.sh | sed 's|/usr/bin/cp|fixture_cp|g; s|/usr/bin/chown|fixture_chown|g')
[ -n "$rollback_function" ] || fail=1
: > "$d/backup.jar"
for fault in none stop cp chown reset-failed start health missing-backup; do
  output=$(bash -c '
    SERVICE=spring_chengyinhub-admin
    BACKUP_JAR=$1
    APP_JAR=/fixture/current.jar
    FAULT=$2
    [ "$FAULT" != missing-backup ] || BACKUP_JAR=/fixture/nonexistent-backup.jar
    systemctl() { [ "$FAULT" != "$1" ]; }
    fixture_cp() { [ "$FAULT" != cp ]; }
    fixture_chown() { [ "$FAULT" != chown ]; }
    wait_healthy() { [ "$FAULT" != health ]; }
    eval "$3"
    rollback
  ' fixture "$d/backup.jar" "$fault" "$rollback_function" 2>&1)
  rc=$?
  [ "$rc" = 1 ] || fail=1
  if [ "$fault" = none ]; then
    printf '%s' "$output" | grep -q '回滚成功' || fail=1
  else
    ! printf '%s' "$output" | grep -q '回滚成功' || fail=1
    printf '%s' "$output" | grep -q '回滚未验证成功' || fail=1
  fi
done
output=$(env PATH="$d/bin:$PATH" PREFLIGHT_FIXTURE_STATUS=17 sh "$d/deploy.sh" stop 2>&1)
rc=$?
[ "$rc" = 0 ] && printf '%s' "$output" | grep -q 'systemctl:stop:spring_chengyinhub-admin' || fail=1
! printf '%s' "$output" | grep -q 'checked-running-service' || fail=1
rm -rf "$d"
[ "$fail" = "0" ] || { echo "原部署入口预检负控未通过"; exit 1; }
echo "原部署入口配置/健康预检正负控通过(start/restart 各两组 + 缺 helper/token/Python + 健康出口两组 + 回滚八组 + 独立 stop)"
