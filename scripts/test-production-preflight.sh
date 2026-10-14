#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/production-preflight.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if env -i PATH="$PATH" bash "$SCRIPT" --env >"$TMP/missing.log" 2>&1; then
  echo "缺少生产配置时预检不应通过" >&2
  exit 1
fi
grep -q '缺少必填环境变量' "$TMP/missing.log"

if env \
  CHENGYIN_JWT_SECRET=test-jwt-secret \
  CHENGYIN_WXPAY_APIV3_KEY=test-pay-key \
  CHENGYIN_WXMINI_SECRET=test-mini-secret \
  CHENGYIN_DRUID_PASSWORD=test-druid-secret \
  COUPON_QR_SECRET=test-qr-secret \
  ALARM_WEBHOOK=https://alarm.example.com/hook \
  CHENGYIN_DB_PASSWORD=test-db-password \
  CHENGYIN_WITHDRAWAL_PII_KEY=invalid \
  CHENGYIN_OSS_ENDPOINT=oss.example.com \
  CHENGYIN_OSS_ACCESS_KEY_ID=test-access-key \
  CHENGYIN_OSS_ACCESS_KEY_SECRET=test-access-secret \
  CHENGYIN_OSS_BUCKET_NAME=test-bucket \
  CHENGYIN_TENCENT_MAP_ENABLED=true \
  CHENGYIN_TENCENT_MAP_KEY=test-map-key \
  bash "$SCRIPT" --env >"$TMP/invalid-key.log" 2>&1; then
  echo "非法提现密钥时预检不应通过" >&2
  exit 1
fi
grep -q 'Base64 编码的 32 字节 AES-256 密钥' "$TMP/invalid-key.log"

KEY='AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
if env \
  CHENGYIN_JWT_SECRET=shared-secret \
  CHENGYIN_WXPAY_APIV3_KEY=test-pay-key \
  CHENGYIN_WXMINI_SECRET=test-mini-secret \
  CHENGYIN_DRUID_PASSWORD=test-druid-secret \
  COUPON_QR_SECRET=shared-secret \
  ALARM_WEBHOOK=https://alarm.example.com/hook \
  CHENGYIN_DB_PASSWORD=test-db-password \
  CHENGYIN_WITHDRAWAL_PII_KEY="$KEY" \
  CHENGYIN_OSS_ENDPOINT=oss.example.com \
  CHENGYIN_OSS_ACCESS_KEY_ID=test-access-key \
  CHENGYIN_OSS_ACCESS_KEY_SECRET=test-access-secret \
  CHENGYIN_OSS_BUCKET_NAME=test-bucket \
  CHENGYIN_TENCENT_MAP_ENABLED=true \
  CHENGYIN_TENCENT_MAP_KEY=test-map-key \
  bash "$SCRIPT" --env >"$TMP/reused.log" 2>&1; then
  echo "券码密钥复用 JWT 密钥时预检不应通过" >&2
  exit 1
fi
grep -q 'CHENGYIN_JWT_SECRET 与 COUPON_QR_SECRET 不得复用同一值' "$TMP/reused.log"
if grep -q 'shared-secret' "$TMP/reused.log"; then
  echo "预检输出不得包含密钥值" >&2
  exit 1
fi

env \
  CHENGYIN_JWT_SECRET=test-jwt-secret \
  CHENGYIN_WXPAY_APIV3_KEY=test-pay-key \
  CHENGYIN_WXMINI_SECRET=test-mini-secret \
  CHENGYIN_DRUID_PASSWORD=test-druid-secret \
  COUPON_QR_SECRET=test-qr-secret \
  ALARM_WEBHOOK=https://alarm.example.com/hook \
  CHENGYIN_DB_PASSWORD=test-db-password \
  CHENGYIN_WITHDRAWAL_PII_KEY="$KEY" \
  CHENGYIN_OSS_ENDPOINT=oss.example.com \
  CHENGYIN_OSS_ACCESS_KEY_ID=test-access-key \
  CHENGYIN_OSS_ACCESS_KEY_SECRET=test-access-secret \
  CHENGYIN_OSS_BUCKET_NAME=test-bucket \
  CHENGYIN_TENCENT_MAP_ENABLED=true \
  CHENGYIN_TENCENT_MAP_KEY=test-map-key \
  bash "$SCRIPT" --env >"$TMP/valid.log" 2>&1
grep -q '生产配置预检通过' "$TMP/valid.log"
