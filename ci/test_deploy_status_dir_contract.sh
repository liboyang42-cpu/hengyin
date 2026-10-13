#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_RESTART_SOURCED=1
source "$SCRIPT_DIR/deploy-restart.sh"

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
REAL_INSTALL="$(command -v install)"
OWNER=""; GROUP=""; SU_USER=""; SU_RC=0

systemctl() {
  case "$*" in
    *"-p User"*) echo "www" ;;
    *"-p Group"*) echo "www-data" ;;
  esac
}
id() { echo "www-data"; }
install() {
  local -a filtered=()
  while [ "$#" -gt 0 ]; do
    case "$1" in
      -o) OWNER="${2:-}"; shift 2 ;;
      -g) GROUP="${2:-}"; shift 2 ;;
      *) filtered+=("$1"); shift ;;
    esac
  done
  command "$REAL_INSTALL" "${filtered[@]}"
}
su() { SU_USER="${*: -1}"; return "$SU_RC"; }
perms() { stat -c "%a" "$1" 2>/dev/null || stat -f "%Lp" "$1"; }

target="$TEMP_DIR/status"
ensure_service_writable_dir fake-service "$target" >/dev/null
[ -d "$target" ]
[ "$(perms "$target")" = "750" ]
[ "$OWNER" = "www" ]
[ "$GROUP" = "www-data" ]
[ "$SU_USER" = "www" ]

SU_RC=1
if ( ensure_service_writable_dir fake-service "$TEMP_DIR/unwritable" >/dev/null 2>&1 ); then
  echo "服务用户不可写时必须失败" >&2
  exit 1
fi

grep -q '^[[:space:]]*ensure_service_writable_dir "\$SERVICE" "\$STATUS_DIR"$' "$SCRIPT_DIR/deploy-restart.sh"
grep -q '^[[:space:]]*ensure_service_writable_dir "\$SERVICE" "\$CRM_EXPORT_DIR"$' "$SCRIPT_DIR/deploy-restart.sh"
echo "deploy status dir contract passed"
