#!/usr/bin/env bash
# 生产发布前的配置闸门：只校验存在性和安全开关，不输出任何密钥值。
set -euo pipefail

required=(
  CHENGYIN_DB_PASSWORD
  CHENGYIN_WITHDRAWAL_PII_KEY
  CHENGYIN_OSS_ENDPOINT
  CHENGYIN_OSS_ACCESS_KEY_ID
  CHENGYIN_OSS_ACCESS_KEY_SECRET
  CHENGYIN_OSS_BUCKET_NAME
  CHENGYIN_TENCENT_MAP_KEY
)
missing=()

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
