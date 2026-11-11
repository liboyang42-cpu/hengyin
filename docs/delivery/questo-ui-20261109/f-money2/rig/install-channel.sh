#!/usr/bin/env bash
# [f-money2 rig] 在 up.sh 生成的 exploded jar 上，编入资金通道模拟（直连退款 + 电商收付通）与 RigPartnerBridge，然后重启应用。
# 只写 $RIG_HOME/work/app；不改仓库、不连外网。用法：
#   RIG_SWITCHES=off|on bash install-channel.sh
#   off：全部新开关保持默认（关，含通行证）；on：通行证 JSAPI + 电商收付通总开关 + 卖家收款路由 + 分账开（合单关）。
set -euo pipefail
export LANG=C.UTF-8 LC_ALL=C.UTF-8
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RIG_HOME="${RIG_HOME:-$HOME/walk-rig}" CAND="${CAND:-$HOME/cand}"; RIG="${RIG:-$HOME/rig/docs/delivery/cloud-walk-20261101/rig}"
APP="$RIG_HOME/work/app"; CLS="$APP/BOOT-INF/classes"; SRC="$RIG_HOME/work/rig-channel-src"
rm -rf "$SRC"; mkdir -p "$SRC/com/chengyinhub/e2e" "$SRC/com/chengyinhub/business/service/impl"
cp "$HERE/E2eWechatPayStub.java.stub" "$SRC/com/chengyinhub/e2e/E2eWechatPayStub.java"
cp "$HERE/RigPartnerBridge.java" "$SRC/com/chengyinhub/business/service/impl/RigPartnerBridge.java"
CP="$CLS:$(ls "$APP"/BOOT-INF/lib/*.jar | tr '\n' ':')"
javac -nowarn -encoding UTF-8 -proc:none -parameters -cp "$CP" -d "$CLS" \
  "$SRC/com/chengyinhub/business/service/impl/RigPartnerBridge.java" "$SRC/com/chengyinhub/e2e/E2eWechatPayStub.java"
echo "channel simulator compiled into $CLS"
# 假的对象存储配置只为让图鉴/封面 URL 能序列化（qx-s0 同款缝），不连任何真桶。
export CHENGYIN_OSS_ENDPOINT=oss-rig.invalid CHENGYIN_OSS_ACCESS_KEY_ID=rig CHENGYIN_OSS_ACCESS_KEY_SECRET=rig CHENGYIN_OSS_BUCKET_NAME=rig
export RIG_CHANNEL_LEDGER="$RIG_HOME/out/channel-ledger.jsonl" RIG_SP_MCHID=1900009001 RIG_SP_APPID=wxrigsp000000001
ARGS=""
if [ "${RIG_SWITCHES:-off}" = on ]; then
  ARGS="--platform-pass.enabled=true --platform-pass.jsapi-enabled=true --payout.ecommerce.enabled=true --payout.ecommerce.seller-routing-enabled=true --payout.ecommerce.profit-sharing-enabled=true"
  ARGS="$ARGS --payout.ecommerce.combined-enabled=false --payout.ecommerce.sp-mchid=$RIG_SP_MCHID --payout.ecommerce.sp-appid=$RIG_SP_APPID"
fi
export RIG_EXTRA_ARGS="$ARGS"
"$RIG_HOME/venv/bin/python" "$RIG/app.py" restart
