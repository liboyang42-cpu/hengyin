#!/bin/sh
set -u

APP_DIR=/www/wwwroot/bin
APP_JAR=$APP_DIR/chengyinhub-admin.jar
BACKUP_DIR=$APP_DIR/backups
# 只留最近 N 份。不设上限就是慢性磁盘泄漏:jar 每份约 99MB,
# 2026-08-28 实测本目录已堆到 699MB / 7 份,而云效 CD 才跑过 5 次。
# 若它成为现役部署路径按天部署,一年 ≈ 36G。
# 数值与 ci/deploy-restart.sh 的 KEEP_BACKUPS 保持一致 —— 两条路径同一口径。
KEEP_BACKUPS=5
SERVICE=spring_chengyinhub-admin
JAVA_HOME=/www/server/java/jdk-21.0.2
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
HEALTH_CHECKER=$SCRIPT_DIR/ci/check_deploy_health.py
DEPLOY_HEALTH_TOKEN_FILE=${DEPLOY_HEALTH_TOKEN_FILE:-}
NEW_JAR=$SCRIPT_DIR/chengyinhub-admin/target/chengyinhub-admin.jar
STAGED_JAR=$APP_DIR/.chengyinhub-admin.next.jar
BACKUP_JAR=

# 轮转备份:按 mtime 新→旧排序,超出 keep 份的删掉。
# ⚠️ 调用点必须在 cp 之后 —— 先保住这一份再清旧的;顺序反了会在磁盘将满时
#    出现「旧的删了、新的没备成」的窗口。
# 抽成函数是为了让 ci/test_deploy_sh_backup_rotate.sh 能跑【真函数】而不是拷贝。
rotate_backups() {
  _dir=${1:?备份目录必填}
  _keep=${2:?保留份数必填}
  ls -1t "$_dir"/chengyinhub-admin-*.jar 2>/dev/null \
    | tail -n +$((_keep + 1)) | xargs -r rm -f || true
}

rollback() {
  echo "部署失败，回滚..."
  rollback_failed=0
  systemctl stop "$SERVICE" || rollback_failed=1
  if [ -n "$BACKUP_JAR" ] && [ -f "$BACKUP_JAR" ]; then
    /usr/bin/cp -f "$BACKUP_JAR" "$APP_JAR" || rollback_failed=1
    /usr/bin/chown www:www "$APP_JAR" || rollback_failed=1
  else
    rollback_failed=1
  fi
  systemctl reset-failed "$SERVICE" || rollback_failed=1
  systemctl start "$SERVICE" || rollback_failed=1
  wait_healthy || rollback_failed=1
  [ "$rollback_failed" = 0 ] && echo "回滚成功" || echo "回滚未验证成功，请核对失败步骤和服务健康" >&2
  exit 1
}

is_healthy() {
  systemctl is-active --quiet "$SERVICE" && python3 "$HEALTH_CHECKER" "$DEPLOY_HEALTH_TOKEN_FILE"
}

wait_healthy() {
  i=0
  while [ "$i" -lt 30 ]; do
    is_healthy && return 0
    i=$((i + 1))
    sleep 1
  done
  return 1
}

case "${1:-restart}" in
  stop)
    systemctl stop "$SERVICE"
    ;;
  start|restart)
    [ "$(id -u)" = 0 ] || { echo "必须使用 root 执行部署"; exit 1; }
    bash "$SCRIPT_DIR/scripts/production-preflight.sh" --service "$SERVICE" || exit 1
    test -f "$HEALTH_CHECKER" || { echo "缺少同版本健康检查脚本" >&2; exit 1; }
    command -v python3 >/dev/null 2>&1 || { echo "缺少 Python3，禁止部署" >&2; exit 1; }
    python3 "$HEALTH_CHECKER" --validate-token-file "$DEPLOY_HEALTH_TOKEN_FILE" || exit 1
    test -s "$NEW_JAR" || exit 1
    "$JAVA_HOME/bin/jar" tf "$NEW_JAR" >/dev/null || exit 1

    mkdir -p "$BACKUP_DIR"
    BACKUP_JAR=$BACKUP_DIR/chengyinhub-admin-$(date +%Y%m%d%H%M%S).jar
    /usr/bin/cp -pf "$APP_JAR" "$BACKUP_JAR" || exit 1
    rotate_backups "$BACKUP_DIR" "$KEEP_BACKUPS"
    /usr/bin/install -o www -g www -m 0644 "$NEW_JAR" "$STAGED_JAR" || rollback
    "$JAVA_HOME/bin/jar" tf "$STAGED_JAR" >/dev/null || rollback

    systemctl stop "$SERVICE" || true
    sleep 2
    systemctl is-active --quiet "$SERVICE" && rollback
    /usr/bin/mv -f "$STAGED_JAR" "$APP_JAR" || rollback
    /usr/bin/chown www:www "$APP_JAR" || rollback
    systemctl reset-failed "$SERVICE" || rollback
    systemctl start "$SERVICE" || true
    wait_healthy || rollback
    echo "部署成功"
    ;;
  *)
    echo "用法: $0 [start|restart|stop]"
    exit 2
    ;;
esac
