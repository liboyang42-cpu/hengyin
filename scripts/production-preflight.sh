#!/usr/bin/env bash
# 生产发布前的配置闸门：只校验存在性和安全开关，不输出任何密钥值。
set -euo pipefail

required=(
  CHENGYIN_DB_PASSWORD
  CHENGYIN_JWT_SECRET
  CHENGYIN_WXPAY_APIV3_KEY
  CHENGYIN_WXMINI_SECRET
  CHENGYIN_DRUID_PASSWORD
  COUPON_QR_SECRET
  ALARM_WEBHOOK
  CHENGYIN_WITHDRAWAL_PII_KEY
  CHENGYIN_OSS_ENDPOINT
  CHENGYIN_OSS_ACCESS_KEY_ID
  CHENGYIN_OSS_ACCESS_KEY_SECRET
  CHENGYIN_OSS_BUCKET_NAME
  CHENGYIN_TENCENT_MAP_KEY
  CHENGYIN_WXPAY_REFUND_NOTIFY_URL
)
missing=()

case "${1:---service}" in
  --env) ;; # 制品清单/CI 显式校验调用者传入的环境。
  --service)
    service="${2:-spring_chengyinhub-admin}"
    pid="$(systemctl show --property=MainPID --value "$service")" || exit 1
    [[ "$pid" =~ ^[1-9][0-9]*$ && -r "/proc/$pid/environ" ]] || { echo "无法读取运行服务的有效环境" >&2; exit 1; }
    # 丢弃 shell 中同名变量，避免把未注入服务的密钥误判为已配置。
    while IFS= read -r name; do unset "$name"; done < <(compgen -v | grep -E "^(CHENGYIN_|ALARM_)")
    unset COUPON_QR_SECRET TZ JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
    while IFS= read -r -d "" entry; do
      name="${entry%%=*}"
      case "$name" in
        CHENGYIN_*|ALARM_*|COUPON_QR_SECRET|TZ|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS)
          [[ "$name" =~ ^[A-Z_][A-Z0-9_]*$ ]] && printf -v "$name" "%s" "${entry#*=}" ;;
      esac
    done < "/proc/$pid/environ"
    timezone="${TZ:-}"
    read -r -a java_options <<< "${JAVA_TOOL_OPTIONS:-} ${JDK_JAVA_OPTIONS:-}"
    for option in "${java_options[@]}"; do
      case "$option" in -Duser.timezone=*) timezone="${option#*=}" ;; esac
    done
    while IFS= read -r -d "" option; do
      case "$option" in -Duser.timezone=*) timezone="${option#*=}" ;; esac
    done < "/proc/$pid/cmdline"
    [[ "$timezone" == Asia/Shanghai ]] || { echo "运行服务必须显式使用 Asia/Shanghai 时区" >&2; exit 1; }
    ;;
  *) echo "用法: $0 [--service 服务名 | --env]" >&2; exit 2 ;;
esac

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done

if [[ ${#missing[@]} -gt 0 ]]; then
  printf '缺少必填环境变量: %s\n' "${missing[*]}" >&2
  exit 1
fi

if [[ ! "$CHENGYIN_WITHDRAWAL_PII_KEY" =~ ^[A-Za-z0-9+/]{43}=$ ]]; then
  echo 'CHENGYIN_WITHDRAWAL_PII_KEY 必须是 Base64 编码的 32 字节 AES-256 密钥' >&2
  exit 1
fi

# 退款结果通知默认留空(application.yml);生产必须显式给出 HTTPS 回调,且指向退款回调端点。
if [[ ! "$CHENGYIN_WXPAY_REFUND_NOTIFY_URL" =~ ^https://[^[:space:]]+/api/registration/refund-callback$ ]]; then
  echo 'CHENGYIN_WXPAY_REFUND_NOTIFY_URL 必须是指向 /api/registration/refund-callback 的 HTTPS 地址' >&2
  exit 1
fi

# 密钥复用会让一次轮换或一次泄露波及多个能力（F-L6-11）：只比较是否相同，不输出任何值。
secret_names=(CHENGYIN_JWT_SECRET COUPON_QR_SECRET CHENGYIN_WXPAY_APIV3_KEY CHENGYIN_WXMINI_SECRET
  CHENGYIN_DRUID_PASSWORD CHENGYIN_DB_PASSWORD CHENGYIN_WITHDRAWAL_PII_KEY CHENGYIN_OSS_ACCESS_KEY_SECRET)
for ((i = 0; i < ${#secret_names[@]}; i++)); do
  for ((j = i + 1; j < ${#secret_names[@]}; j++)); do
    if [[ "${!secret_names[i]}" == "${!secret_names[j]}" ]]; then
      echo "${secret_names[i]} 与 ${secret_names[j]} 不得复用同一值，请分别注入独立密钥" >&2
      exit 1
    fi
  done
done

if [[ -n "${ALARM_FAILURE_WEBHOOK:-}" && "${ALARM_FAILURE_WEBHOOK}" == "${ALARM_WEBHOOK}" ]]; then
  echo 'ALARM_FAILURE_WEBHOOK 必须是独立于 ALARM_WEBHOOK 的告警出口' >&2
  exit 1
fi

if [[ "${CHENGYIN_TENCENT_MAP_ENABLED:-false}" != "true" ]]; then
  echo 'CHENGYIN_TENCENT_MAP_ENABLED 必须为 true，生产定位服务不可静默降级' >&2
  exit 1
fi

if [[ "${CHENGYIN_DRUID_CONSOLE_ENABLED:-false}" == "true" ]]; then
  echo '生产发布禁止启用 Druid 控制台' >&2
  exit 1
fi

if [[ "${CHENGYIN_SPRINGDOC_ENABLED:-false}" == "true" ]]; then
  echo '生产发布禁止启用 SpringDoc 接口文档' >&2
  exit 1
fi

if [[ "$(printf '%s' "${CHENGYIN_LOG_LEVEL:-info}" | tr '[:upper:]' '[:lower:]')" == "debug" ]]; then
  echo '生产发布禁止使用 debug 业务日志级别' >&2
  exit 1
fi

echo '生产配置预检通过；仍须人工核验密钥轮换、生产 migration、支付与微信平台证据。'
