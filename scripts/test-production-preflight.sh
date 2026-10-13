#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/production-preflight.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if env -i PATH="$PATH" "$SCRIPT" >"$TMP/missing.log" 2>&1; then
  echo "缺少生产配置时预检不应通过" >&2
  exit 1
fi
grep -q '缺少必填环境变量' "$TMP/missing.log"

if env \
  CHENGYIN_DB_PASSWORD=test-db-password \
  CHENGYIN_WITHDRAWAL_PII_KEY=invalid \
  CHENGYIN_OSS_ENDPOINT=oss.example.com \
  CHENGYIN_OSS_ACCESS_KEY_ID=test-access-key \
  CHENGYIN_OSS_ACCESS_KEY_SECRET=test-access-secret \
  CHENGYIN_OSS_BUCKET_NAME=test-bucket \
  CHENGYIN_TENCENT_MAP_ENABLED=true \
  CHENGYIN_TENCENT_MAP_KEY=test-map-key \
  "$SCRIPT" >"$TMP/invalid-key.log" 2>&1; then
  echo "非法提现密钥时预检不应通过" >&2
  exit 1
fi
grep -q 'Base64 编码的 32 字节 AES-256 密钥' "$TMP/invalid-key.log"

KEY="$(node -e 'process.stdout.write(Buffer.alloc(32).toString("base64"))')"
env \
  CHENGYIN_DB_PASSWORD=test-db-password \
  CHENGYIN_WITHDRAWAL_PII_KEY="$KEY" \
  CHENGYIN_OSS_ENDPOINT=oss.example.com \
  CHENGYIN_OSS_ACCESS_KEY_ID=test-access-key \
  CHENGYIN_OSS_ACCESS_KEY_SECRET=test-access-secret \
  CHENGYIN_OSS_BUCKET_NAME=test-bucket \
  CHENGYIN_TENCENT_MAP_ENABLED=true \
  CHENGYIN_TENCENT_MAP_KEY=test-map-key \
  "$SCRIPT" >"$TMP/valid.log" 2>&1
grep -q '生产配置预检通过' "$TMP/valid.log"
