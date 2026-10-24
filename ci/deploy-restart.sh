#!/usr/bin/env bash
# 生产机部署:换 jar 并重启 chengyinhub-admin。
# 由 .github/workflows/deploy.yml 经 ssh 管道喂进来执行(bash -s -- <新jar路径>)。
#
# ★ 真正管着 jar 的是 systemd(spring_chengyinhub-admin.service,Restart=always),
#   不是宝塔面板、也不是本脚本。2026-07-16 journalctl 实证:此前本脚本 kill 掉进程后,
#   systemd 立刻把服务拉了起来,而脚本自己 su+nohup 起的那个因 8080 被占静默退出 ——
#   两次部署(14:09 Flow / 20:14 GitHub Actions)都是 systemd 干的活,脚本那段纯属空转。
#   能跑是侥幸,不是设计:systemd 慢一拍,两个进程就会抢端口。故改为走 systemctl,
#   让唯一的 supervisor 干它该干的事。
#
# ⚠️ 密钥由 systemd drop-in 注入:/etc/systemd/system/spring_chengyinhub-admin.service.d/
#   (secrets.conf + pii.conf + deepseek.conf,共 7 个 CHENGYIN_*,均 600 root:root)。
#   **不是** /www/wwwroot/bin/chengyin.env —— 那个文件是孤儿,没有任何东西 source 它
#   (2026-07-16 实证:DeepSeek 密钥因此一直没进 jar,报「未配置 apiKey」)。
#   加密钥 = 加 drop-in + systemctl daemon-reload,别碰 chengyin.env。
#
# ★ 2026-07-17:5 个核心密钥已从 /var/tmp/springboot/vhost/env/ 迁入 drop-in secrets.conf,
#   主单元那行 EnvironmentFile= 已被空赋值重置。原因:①/var/tmp 有 30d tmpfiles 自动清理
#   ②宝塔 javaModel.py 的 start_project() 写死 chmod -R 777 /var/tmp/springboot/ + 重写启动脚本。
#   ⇒ 本脚本(走 systemctl restart,见下方前置检查)不触发那两条;但**别在宝塔面板点启动/重启**,那会把
#     /var/tmp/springboot 刷成 777 并覆盖启动脚本(给该脚本加 set -e 因此是白改)。
#
# 用法:bash ci/deploy-restart.sh /path/to/新jar        # 不传则默认 $JAR.new
# 测试:source ci/deploy-restart.sh 设 DEPLOY_RESTART_SOURCED=1,调用 ensure_env_file /
#      ensure_jar_readable。
set -euo pipefail

JAR=/www/wwwroot/bin/chengyinhub-admin.jar
SERVICE=spring_chengyinhub-admin
PORT=8080
KEEP_BACKUPS=5
# ⚠️ 是 wwwlogs 这个,不是 /www/wwwroot/bin/chengyinhub-admin.log —— 后者是旧脚本 nohup
# 进程留下的残骸(17KB、早已静止)。systemd 的 ExecStart 脚本把 stdout/stderr 重定向到这里,
# 启动失败的堆栈只在这个文件里。出事时看错文件 = 什么都查不到。
LOG=/www/wwwlogs/java/springboot/chengyinhub-admin.log
STATUS_DIR=/www/wwwroot/status
CRM_EXPORT_DIR=/www/wwwroot/uploadPath-private/merchant-crm-export

# ── 函数定义(可被测试 source) ──────────────────────────────────────────────

# 确保 systemd 主 unit 引用的 EnvironmentFile= 路径存在(空文件,0600)。
# 密钥全量在 drop-in,此文件仅需存在——绝不写入密钥。目录 0700,已有文件不覆盖。
# 参数: $1=目录(默认 /var/tmp/springboot/vhost/env)
#       $2=文件(默认 $dir/chengyinhub-admin.env)
ensure_env_file() {
  local dir="${1:-/var/tmp/springboot/vhost/env}"
  local file="${2:-$dir/chengyinhub-admin.env}"
  if [ ! -f "$file" ]; then
    install -d -m 0700 -o root -g root "$dir"
    install -m 0600 -o root -g root /dev/null "$file"
    echo "  已创建空 $file (0600 root:root)"
  else
    echo "  已存在 $file,跳过创建"
  fi
}

# 把 jar 的 mode 钉成 0644 并断言 systemd 单元里的 User 真读得到它。
# 为什么需要:jar 是 scp 以 root 落到生产的,mode = 源文件 & ~root 的 umask —— 是 runner
# 环境的偶然值,不是契约;而 $SERVICE 以 User=www 运行。2026-07-31 实证:某次 scp 落成
# 0600 root:root,java 直接 `Unable to access jarfile`,systemd 只显示 status=1/FAILURE、
# 重启 5 次后放弃,生产挂了 31 小时。换 jar 那步此前完全不碰权限,漂移即事故。
# 断言用 su 真以该用户读一次 —— 只比 mode 会漏掉属主/上级目录权限。
# 参数: $1=jar 路径  $2=服务名(取 User=)
ensure_jar_readable() {
  local jar="${1:?jar 路径必填}"
  local service="${2:?服务名必填}"
  chmod 0644 "$jar"
  local user
  user="$(systemctl show -p User --value "$service" 2>/dev/null || true)"
  user="${user:-root}"
  su -s /bin/sh -c "test -r '$jar'" "$user" || {
    echo "❌ 服务用户 $user 读不到 $jar —— 起不来,不必往下走了"; exit 1; }
  echo "  $jar 已置 0644,且 $user 可读"
}

ensure_service_writable_dir() {
  local service="${1:?服务名必填}"
  local dir="${2:?状态目录必填}"
  local user group
  user="$(systemctl show -p User --value "$service" 2>/dev/null || true)"
  user="${user:-root}"
  group="$(systemctl show -p Group --value "$service" 2>/dev/null || true)"
  group="${group:-$(id -gn "$user")}"
  install -d -m 0750 -o "$user" -g "$group" "$dir"
  su -s /bin/sh -c "test -w '$dir'" "$user" || {
    echo "❌ 服务用户 $user 无法写入目录 $dir"; exit 1; }
  echo "  $dir 已置 0750 $user:$group,且服务用户可写"
}

# ── 主流程(被 source 用于测试时跳过) ────────────────────────────────────────
if [ "${DEPLOY_RESTART_SOURCED:-}" != "1" ]; then
NEW_JAR="${1:-$JAR.new}"
HEALTH_CHECKER="${2:?缺少部署健康检查器}"
HEALTH_TOKEN_FILE="${3:?缺少受保护健康接口授权文件路径}"
# 必须在换 jar 前拒绝缺失/不安全授权文件；不自动登录或输出令牌。
python3 "$HEALTH_CHECKER" --validate-token-file "$HEALTH_TOKEN_FILE"

[ -f "$NEW_JAR" ] || { echo "找不到新 jar:$NEW_JAR"; exit 1; }
systemctl cat "$SERVICE" >/dev/null 2>&1 || {
  echo "找不到 systemd 服务 $SERVICE —— 部署机制变了,先查清再跑,别退回 nohup"; exit 1; }

echo "== 1/7 备份旧 jar =="
if [ -f "$JAR" ]; then
  cp -f "$JAR" "$JAR.bak.$(date +%Y%m%d%H%M%S)"
  # 只留最近 KEEP_BACKUPS 份。jar 每份 99MB,不设上限就是慢性磁盘泄漏:
  # 2026-07-16 实测,隔壁 /www/wwwroot/bin/backups(云效 Flow 时代 deploy.sh 留的)
  # 攒了 112 个 = 11GB,占整机已用空间 26G 的四成 —— 没人会回滚到 112 次之前那一版。
  # 那堆已随 Flow 出局停止增长(最后一个停在 14:08),但本脚本产生的 .bak.* 还在长:
  # 今晚 84 分钟内就攒了 4 个。5 份 ≈ 495MB,足够回滚。
  # `|| true`:清旧备份是非关键路径 —— 新备份已经存好,清不掉历史只是占磁盘,不该让
  # 部署红;且 set -euo pipefail 下 ls 无匹配会非零退出并被 pipefail 传导,会杀掉脚本。
  ls -1t "$JAR".bak.* 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -f || true
fi

echo "== 2/7 换上新 jar =="
mv -f "$NEW_JAR" "$JAR"
ensure_jar_readable "$JAR" "$SERVICE"

echo "== 3/7 确保 env 文件存在(空,0600,防 systemd 因 EnvironmentFile= 无 - 而启动失败) =="
ensure_env_file

echo "== 4/7 确保服务状态快照目录可写 =="
ensure_service_writable_dir "$SERVICE" "$STATUS_DIR"

echo "== 5/7 确保 CRM 私有导出目录可写 =="
ensure_service_writable_dir "$SERVICE" "$CRM_EXPORT_DIR"

echo "== 6/7 重启服务 =="
systemctl restart "$SERVICE"

echo "== 7/7 业务健康检查(应用、数据库和 Redis 全部 UP，最多 60s)=="
for _ in $(seq 1 8); do
  if python3 "$HEALTH_CHECKER" "$HEALTH_TOKEN_FILE"; then
    echo "✅ 受保护健康检查通过；新 jar 指纹仍由 verify-deployed.sh 核验"
    exit 0
  fi
  sleep 2
done
echo "❌ 60s 内应用/数据库/Redis 未全部就绪。查 journalctl -u $SERVICE -n 50 与 $LOG(旧 jar 备份在 $JAR.bak.*)"
echo "--- 死前诊断:现在有几个进程在跑这个 jar ---"
# systemctl restart 只管 systemd 自己的 MainPID。若这里 >1,说明有野进程(非 systemd 托管)
# 占着 8080,systemd 新实例会 BindException 起不来 —— 而野进程照样答 curl,足以让上面的
# 健康检查变绿。ci/verify-deployed.sh 的「恰好一个进程」会拦住它,这行只是让日志直接说清。
pgrep -af '[-]jar .*chengyinhub-admin\.jar' || echo "  (一个都没有)"
exit 1
fi
