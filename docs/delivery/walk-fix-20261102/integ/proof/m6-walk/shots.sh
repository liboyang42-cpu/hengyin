#!/usr/bin/env bash
# Rig screens at 390x844 (real mini-program bundle, real backend; not a phone). Usage: shots.sh <tag> <stage>
set -u
TAG=$1; STAGE=$2; W=$HOME/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs
export RIG_SHOTS=$(cd "$(dirname "$0")/../../fix-shots" && pwd)
CLUB=$(python3 -c "import json;print(json.load(open('$(dirname "$0")/state-$TAG.json'))['club'])")
LEAD=m6-lead-$TAG; P=m6-$TAG-$STAGE; OUT=$(dirname "$0")/shots-$TAG-$STAGE.txt; : > "$OUT"
node $W "{\"path\":\"pages/club/settlement/index?clubId=$CLUB\",\"identity\":\"$LEAD\",\"agree\":true}" "[{\"wait\":2500},{\"shot\":\"$P-club-settlement\"},{\"dump\":1}]" >> "$OUT" 2>&1
node $W "{\"path\":\"subpackageA/pages/assetcenter/earnings/index\",\"identity\":\"$LEAD\",\"agree\":true}" "[{\"wait\":2500},{\"shot\":\"$P-lead-earnings\"},{\"dump\":1}]" >> "$OUT" 2>&1
node $W "{\"path\":\"subpackageA/pages/assetcenter/income-detail/income-detail\",\"identity\":\"$LEAD\",\"agree\":true}" "[{\"wait\":2500},{\"shot\":\"$P-lead-income-detail\"},{\"dump\":1}]" >> "$OUT" 2>&1
node $W '{"path":"pages/merchant/ledger/index?view=redemptions","identity":"e2e-openid-9002","agree":true}' "[{\"wait\":2500},{\"shot\":\"$P-merchant9002-redemptions\"},{\"dump\":1}]" >> "$OUT" 2>&1
node $W '{"path":"pages/merchant/ledger/index?view=settlement","identity":"e2e-openid-9002","agree":true}' "[{\"wait\":2500},{\"shot\":\"$P-merchant9002-finance\"},{\"dump\":1}]" >> "$OUT" 2>&1
node $W '{"path":"subpackageA/pages/assetcenter/income-detail/income-detail","identity":"e2e-openid-9001","agree":true}' "[{\"wait\":2500},{\"shot\":\"$P-platform9001-income-detail\"},{\"dump\":1}]" >> "$OUT" 2>&1
ls "$RIG_SHOTS" | grep "$P"
