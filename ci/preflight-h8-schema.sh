#!/usr/bin/env bash
# 生产机部署【前置】闸:在换 jar 之前,向生产库只读求证「H8 两份迁移确实已落库」。
# 由 .github/workflows/deploy.yml 经 ssh 管道喂进来执行(bash -s),**必须**排在
# retry_jar_upload 与 ci/deploy-restart.sh 之前 —— 不过就 exit 非零,jar 未被替换、
# 旧进程继续服务。这是把「迁移必须先于 jar」这条既定顺序机器化,不新增任何产品语义。
#
# ★ 为什么需要它(2026-08-16 第五轮双轴复审 Spec P2-1 / Standards P2-1 同一个根因):
#   合并 PR 命中 chengyinhub-system/** ⇒ **自动部署 jar**;而 chengyinhub-admin/sql/** 落库是
#   **人工步骤**。顺序一反(jar 先上),两件事同时发生且都零预警:
#     ① settlement_rule 里三类玩法的 creator_rate 仍是迁移前的非零值 ⇒
#        SettlementEntryServiceImpl:227-234 写侧 fail-closed 抛 ⇒ 活动线支付回调整笔回滚
#        (微信已扣款、平台侧订单没成;WechatPayServiceImpl:545-552 会回 FAIL 触发重试,
#         但那是最长 24h 的支付不可用窗,不是"没事");
#     ② ORDER 1/2 新建的 5 列不存在 ⇒ H8 专用 mapper 语句 ERROR 1054(这是**要的**响亮失败,
#        通用积分/核销路径已由 mapper 隔离契约保住,见 ci/test_mapper_h8_column_isolation.py)。
#   `deploy.yml` 的文件分类只决定"要不要部署",它**不知道迁移跑没跑**;
#   `ci/migration-safety-check.sh` 只管文件长相;`ci/migration_ledger.py` 从不连库。
#   ⇒ 这条链路此前 100% 靠人。本脚本是它唯一的机器观测点。
#
# ★ 口令处理(不新增任何 GitHub secret):生产口令由 systemd drop-in 以环境变量
#   CHENGYIN_DB_PASSWORD 注入应用进程(application-druid.yml:11 的 ${CHENGYIN_DB_PASSWORD})。
#   本脚本以 root 取它,**两个来源、按顺序**:
#     ① 服务在跑 ⇒ 服务唯一 MainPID 的 /proc/<pid>/environ —— 那就是 jar 正在用的那一份;
#     ② 服务没在跑 ⇒ 回落 systemd drop-in 这份**磁盘真源**
#        (/etc/systemd/system/<unit>.service.d/*.conf 的 Environment= 行;
#         ci/deploy-restart.sh:12-16 与 docs/CICD工作流说明_GitHub-first.md:244-247
#         记载 secrets.conf/pii.conf/deepseek.conf 共 7 个 CHENGYIN_*,均 600 root:root)。
#   为什么必须有 ②:本闸排在换 jar 之前。口令若只能从活着的进程取,一旦上了个起不来的 jar
#   (deploy-restart.sh 换 jar 后没有回滚),下一次「发回退版本」的部署会在第一步被自己挡住 ——
#   「服务活着才能部署」恰好在最需要部署的那一刻失效,唯一出路退回人工 ssh。
#   ⚠️ ② 不是把 fail-closed 放宽:**两处都取不到仍然红**,只是把它从"进程存活"这条
#      与 H8 无关的前提上摘下来。② 的判据反而更严 —— 文件须属主独占(600/400 + uid 0)、
#      赋值须唯一且形状无歧义,任何拿不准的转义一律拒绝解析(见 dropin_line_value)。
#   ⚠️ 故意**不读** EnvironmentFile= 指向的 /var/tmp/springboot/vhost/env/*.env:
#      2026-07-17 起密钥已全部迁出该文件(主单元那行被 drop-in 用空赋值重置),
#      deploy-restart.sh:44-54 只把它补成**空文件**;且 /var/tmp 会被宝塔面板 chmod -R 777。
#      从一个记载为空、又可能全服务器可写的路径取密钥,正是本回落要避免的那种"安全"。
#   全程:不 echo、不进 argv、不落进普通文件、不 source 任何 shell —— 只经管道写进 0600 的
#   defaults-extra-file,并由 trap 删除。
#
# 用法:bash ci/preflight-h8-schema.sh
# 测试:PREFLIGHT_H8_SOURCED=1 source ci/preflight-h8-schema.sh,再单调各函数;
#      整流程测试见 ci/test_preflight_h8_schema.sh(用 PATH shim + 假 /proc + 假 drop-in,
#      不连任何库)。
set -euo pipefail

# 这几个 H8_PREFLIGHT_* 只是**测试 seam**;生产上一律走默认值。
SERVICE="${H8_PREFLIGHT_SERVICE:-spring_chengyinhub-admin}"
PROC_ROOT="${H8_PREFLIGHT_PROC_ROOT:-/proc}"
DROPIN_ROOT="${H8_PREFLIGHT_DROPIN_ROOT:-/etc/systemd/system}"
SECRET_OWNER_UID="${H8_PREFLIGHT_SECRET_OWNER_UID:-0}"
DB_HOST="${H8_PREFLIGHT_DB_HOST:-127.0.0.1}"
DB_PORT="${H8_PREFLIGHT_DB_PORT:-3306}"
DB_NAME="${H8_PREFLIGHT_DB_NAME:-chengyinhubdb}"
DB_USER="${H8_PREFLIGHT_DB_USER:-chengyinhubdb}"
PASSWORD_VAR=CHENGYIN_DB_PASSWORD

# 5 列的唯一来源是 migration_20260815_tandianri_h8_publisher_points_funds.sql(ORDER 1/2)。
PREFLIGHT_SQL="SELECT
 (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND ((table_name = 'cms_points_setting' AND column_name = 'rule_version')
       OR (table_name = 'ums_member_points_detail'
           AND column_name IN ('points_rule_id', 'points_rule_version',
                               'points_rule_value', 'publisher_settlement_topic_id')))),
 (SELECT COUNT(*) FROM settlement_rule
   WHERE status = 1 AND del_flag = 0
     AND biz_type IN ('activity', 'topic', 'topic_club')
     AND (creator_rate IS NULL OR creator_rate <> 0));"

EXPECTED_H8_COLUMNS=5

# ── 函数定义(可被测试 source) ──────────────────────────────────────────────

# systemd 认的那个唯一主进程。取不到 / 为 0 = 服务没在跑或单元名变了 ⇒ 红,不猜。
resolve_main_pid() {
  local service="${1:?服务名必填}"
  local pid
  pid="$(systemctl show -p MainPID --value "$service" 2>/dev/null || true)"
  case "$pid" in
    ''|0|*[!0-9]*)
      echo "❌ 取不到 $service 的 MainPID(实得 '${pid}')——服务没在跑?单元名变了?" >&2
      return 1 ;;
  esac
  printf '%s\n' "$pid"
}

# 从 NUL 分隔的 environ 里取一个变量。**只写 stdout**,调用方必须直接接管道,不要落变量。
environ_value() {
  local environ_file="${1:?environ 路径必填}"
  local name="${2:?变量名必填}"
  if [ ! -r "$environ_file" ]; then
    echo "❌ 读不到 $environ_file —— 需要 root,且进程必须存在" >&2
    return 1
  fi
  local value
  value="$(tr '\0' '\n' < "$environ_file" | sed -n "s/^${name}=//p" | head -n 1)"
  if [ -z "$value" ]; then
    echo "❌ 进程环境里没有 $name —— systemd drop-in 变了?这时候【不能】跳过校验" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

# 密钥文件必须属主独占:属主是期望的那个 uid(生产 = root/0),权限**恰好** 600 或 400
# (ci/deploy-restart.sh:12-16 记载生产三份 drop-in 都是 600 root:root)。
# 受理集合与文案逐字一致:不要写"须 600/400"却顺手放行 700/000 —— 那种"比文案松一点"
# 正是下一轮复审要抓的假绿。权限被放松过的密钥文件按「可能已被替换」处置,停下,
# 而不是跳过它去找下一份。
require_secret_file_mode() {
  local file="${1:?文件路径必填}"
  local owner mode
  owner="$(stat -c '%u' "$file" 2>/dev/null || stat -f '%u' "$file" 2>/dev/null || true)"
  mode="$(stat -c '%a' "$file" 2>/dev/null || stat -f '%Lp' "$file" 2>/dev/null || true)"
  if [ "$owner" != "$SECRET_OWNER_UID" ]; then
    echo "❌ $file 属主是 uid '${owner}',期望 '${SECRET_OWNER_UID}' —— 不从别人写得了的文件取口令" >&2
    return 1
  fi
  case "$mode" in
    600|0600|400|0400) ;;
    *)
      echo "❌ $file 权限是 '${mode}',密钥文件须恰好 600 或 400 —— 按 fail-closed 停下" >&2
      return 1 ;;
  esac
}

# 只认两种**无歧义**形状,其余一律红 —— 绝不去猜 systemd 的反转义规则:
#   Environment=CHENGYIN_DB_PASSWORD=值      值内不含空白 / 双引号 / 反斜杠
#   Environment="CHENGYIN_DB_PASSWORD=值"    值内不含双引号 / 反斜杠(可含空格、# 等)
# 一行多赋值、引号不闭合、含反斜杠都归入「拿不准」:猜错一个字符就是拿错口令去连生产库,
# 而连不上在本闸里同样是红 —— 猜的代价和不解析一样,却多制造一层"已经查过了"的假象。
# 这时正确出路是先 systemctl start 用进程真值,或把 drop-in 改成单一赋值。
dropin_line_value() {
  local line="${1-}"
  local quoted_re bare_re value
  quoted_re='^[[:space:]]*Environment="'"$PASSWORD_VAR"'=([^\"]*)"[[:space:]]*$'
  bare_re='^[[:space:]]*Environment='"$PASSWORD_VAR"'=([^[:space:]\"]*)[[:space:]]*$'
  if [[ "$line" =~ $quoted_re ]]; then
    value="${BASH_REMATCH[1]}"
  elif [[ "$line" =~ $bare_re ]]; then
    value="${BASH_REMATCH[1]}"
  else
    echo "❌ drop-in 里的 ${PASSWORD_VAR} 不是可无歧义解析的单一赋值" >&2
    echo "   (一行多赋值 / 引号不闭合 / 含反斜杠转义)—— 拒绝猜测,不拿猜出来的口令连生产库。" >&2
    return 1
  fi
  if [ -z "$value" ]; then
    echo "❌ drop-in 里的 ${PASSWORD_VAR} 是空值,拒绝继续" >&2
    return 1
  fi
  printf '%s\n' "$value"
}

# 磁盘真源:systemd drop-in。**只写 stdout**,调用方必须直接接管道,不要落变量。
# 只认行首(除空白外)直接是 Environment= 的**可执行行**;注释掉的赋值不是生效配置。
dropin_password() {
  local service="${1:?服务名必填}"
  local dir="$DROPIN_ROOT/${service}.service.d"
  local file line hits=0 chosen=""
  for file in "$dir"/*.conf; do
    [ -f "$file" ] || continue
    while IFS= read -r line; do
      require_secret_file_mode "$file" || return 1
      hits=$((hits + 1))
      chosen="$line"
    done < <(grep -E "^[[:space:]]*Environment=.*${PASSWORD_VAR}=" "$file" || true)
  done
  if [ "$hits" -eq 0 ]; then
    echo "❌ $dir/*.conf 里也没有 ${PASSWORD_VAR} 的生效赋值 —— 这时候【不能】跳过校验" >&2
    echo "   先 systemctl start $service 用进程真值,或修好 drop-in 再部署。" >&2
    return 1
  fi
  if [ "$hits" -ne 1 ]; then
    echo "❌ drop-in 里有 $hits 处 ${PASSWORD_VAR} 赋值 —— 歧义,不猜用哪一个" >&2
    return 1
  fi
  dropin_line_value "$chosen"
}

# 口令来源解析。**只写 stdout**,调用方直接接管道。
#
# ★ 回落的触发条件【只有一个】:MainPID 取不到(服务没在跑 / 单元名变了)。
#   MainPID 有效就一律只认进程真值 —— environ 读不到、或里面没有这个变量,都必须红,
#   **不许回落磁盘**。理由:那两种情况说的是「以 root 都读不了 /proc」或
#   「在跑的进程与 drop-in 已经不一致」,两条都是要人看见的信号;回落会拿一份
#   【和生产实际在用的未必相同】的口令把红变绿,等于用一次成功的连接把信号抹掉。
#   回落只为解决「进程根本不存在,所以无从问起」这一件事,不为解决"问不出来"。
resolve_db_password() {
  local service="${1:?服务名必填}"
  local main_pid
  if main_pid="$(resolve_main_pid "$service" 2>/dev/null)"; then
    environ_value "$PROC_ROOT/$main_pid/environ" "$PASSWORD_VAR" || return 1
    return 0
  fi
  echo "   ⚠️ 服务没在跑(取不到 MainPID),回落 systemd drop-in 磁盘真源" >&2
  dropin_password "$service"
}

# mysql / mariadb 客户端。没有 = 红(fail-closed):装上再部署,不回退成跳过。
resolve_mysql_client() {
  local client
  for client in mysql mariadb; do
    if command -v "$client" >/dev/null 2>&1; then
      command -v "$client"
      return 0
    fi
  done
  echo "❌ 生产机上没有 mysql / mariadb 客户端 —— 无法向生产库求证迁移是否落库。" >&2
  echo "   按 fail-closed 处置:装上客户端再部署,不得把本闸退化成跳过。" >&2
  return 1
}

# 口令从 **stdin** 读、写进 0600 的 defaults-extra-file。永不 echo、永不进 argv。
write_defaults_file() {
  local target="${1:?defaults 文件路径必填}"
  local user="${2:?数据库用户必填}"
  local password
  IFS= read -r password || true
  if [ -z "$password" ]; then
    echo "❌ 没有拿到数据库口令,拒绝继续" >&2
    return 1
  fi
  case "$password" in
    *'"'*)
      echo "❌ 口令含双引号,无法安全写进 my.cnf 选项文件 —— 按 fail-closed 停下" >&2
      return 1 ;;
  esac
  # my.cnf 的双引号值里反斜杠是转义引导符,必须先加倍。
  local escaped=${password//\\/\\\\}
  umask 077
  printf '[client]\nuser="%s"\npassword="%s"\n' "$user" "$escaped" > "$target"
  chmod 600 "$target"
}

# 只读查询。--defaults-extra-file 必须是第一个参数;口令只在该文件里,不进 argv。
run_preflight_query() {
  local client="${1:?客户端路径必填}"
  local defaults_file="${2:?defaults 文件路径必填}"
  "$client" "--defaults-extra-file=$defaults_file" \
    "--host=$DB_HOST" "--port=$DB_PORT" --batch --skip-column-names \
    "--database=$DB_NAME" --execute="$PREFLIGHT_SQL"
}

# 判据本体。空输出 / 非数字 = 连不上或查失败 ⇒ 红,绝不当成通过。
assert_preflight_counts() {
  local columns="${1:-}"
  local unconverged="${2:-}"
  case "$columns" in ''|*[!0-9]*)
    echo "❌ 读不到 H8 列计数(实得 '${columns}')——查询失败/连不上,按 fail-closed 停下" >&2
    return 1 ;;
  esac
  case "$unconverged" in ''|*[!0-9]*)
    echo "❌ 读不到未收敛规则计数(实得 '${unconverged}')——按 fail-closed 停下" >&2
    return 1 ;;
  esac
  if [ "$columns" -ne "$EXPECTED_H8_COLUMNS" ]; then
    echo "❌ ORDER 1/2 未落库:H8 新列只有 $columns/$EXPECTED_H8_COLUMNS 存在。" >&2
    echo "   先跑 chengyinhub-admin/sql/migration_20260815_tandianri_h8_publisher_points_funds.sql" >&2
    return 1
  fi
  if [ "$unconverged" -ne 0 ]; then
    echo "❌ ORDER 2/2 未落库或未收敛:仍有 $unconverged 条 active 三类规则的 creator_rate 为 NULL 或非 0。" >&2
    echo "   部署后这些玩法的入账会被 SettlementEntryServiceImpl 写侧闸整笔拒绝(含支付回调)。" >&2
    echo "   先跑 chengyinhub-admin/sql/migration_20260815_tandianri_h8_rate_convergence.sql" >&2
    return 1
  fi
  echo "   ✅ H8 新列 $columns/$EXPECTED_H8_COLUMNS 齐备,未收敛规则 $unconverged 条"
}

# ── 主流程(被 source 用于测试时跳过) ────────────────────────────────────────
if [ "${PREFLIGHT_H8_SOURCED:-}" != "1" ]; then

echo "== H8 部署前置闸:向生产库只读求证迁移已落库 =="

client="$(resolve_mysql_client)"

umask 077
defaults_file="$(mktemp)"
trap 'rm -f "$defaults_file"' EXIT
chmod 600 "$defaults_file"

# 口令全程只走管道:进程 environ 或 drop-in → write_defaults_file → 0600 文件。
# 不落变量、不进 argv、不打印。
resolve_db_password "$SERVICE" | write_defaults_file "$defaults_file" "$DB_USER"

counts="$(run_preflight_query "$client" "$defaults_file")"
columns="$(printf '%s\n' "$counts" | awk 'NR == 1 { print $1 }')"
unconverged="$(printf '%s\n' "$counts" | awk 'NR == 1 { print $2 }')"
assert_preflight_counts "$columns" "$unconverged"

echo "✅ H8 迁移已落库,允许替换 jar"
fi
