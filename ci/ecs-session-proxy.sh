#!/usr/bin/env bash
# OpenSSH ProxyCommand：通过阿里云 Session Manager 连接生产 ECS 的 22 端口。
# 这条通道由实例内 Cloud Assistant 主动连出，不依赖当前网络能否直连公网 TCP/22。
set -euo pipefail

cli="${ALI_INSTANCE_CLI:-$HOME/.local/bin/ali-instance-cli}"
config="${ALIYUN_CONFIG_PATH:-$HOME/.aliyun/config.json}"
instance="${ECS_INSTANCE_ID:-i-uf6532ng9somcth7c4yj}"
region="${ECS_REGION:-cn-shanghai}"

[ -x "$cli" ] || {
  echo "ali-instance-cli 不存在或不可执行：$cli" >&2
  exit 127
}
[ -r "$config" ] || {
  echo "阿里云 CLI 配置不可读：$config" >&2
  exit 78
}
[[ "$instance" =~ ^i-[a-z0-9]+$ ]] || {
  echo "ECS_INSTANCE_ID 形状非法" >&2
  exit 64
}
[[ "$region" =~ ^[a-z]{2}-[a-z]+$ ]] || {
  echo "ECS_REGION 形状非法" >&2
  exit 64
}

exec "$cli" ssh \
  --instance "$instance" \
  --port 22 \
  --region "$region" \
  --config-path "$config"
