#!/usr/bin/env bash
# Screens in the rig browser runtime (real mini-program bundle at 390x844, real backend). Usage: s8_shots.sh <tag>
set -u
TAG=$1; W=$HOME/rigsrc/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs
export RIG_SHOTS=$(dirname "$0")/shots; mkdir -p "$RIG_SHOTS"
CLUB=$(python3 -c "import json;print(json.load(open('$(dirname "$0")/state.json'))['club'])")
node $W "{\"path\":\"pages/club/settlement/index?clubId=$CLUB\",\"identity\":\"k5-lead\",\"agree\":true}" \
  "[{\"wait\":2500},{\"shot\":\"k5-$TAG-club-settlement\"},{\"scroll\":500},{\"wait\":800},{\"shot\":\"k5-$TAG-club-settlement-2\"},{\"dump\":1}]" > "$RIG_SHOTS/k5-$TAG-club.txt" 2>&1
node $W '{"path":"pages/merchant/ledger/index?view=redemptions","identity":"e2e-openid-9002","agree":true}' \
  "[{\"wait\":2500},{\"shot\":\"k5-$TAG-merchant-redemptions\"},{\"dump\":1},{\"tap\":\"外滩钟声 · 领队场\"},{\"wait\":2500},{\"shot\":\"k5-$TAG-merchant-session-fee-detail\"},{\"dump\":1}]" > "$RIG_SHOTS/k5-$TAG-redemptions.txt" 2>&1
node $W '{"path":"pages/merchant/ledger/index?view=settlement","identity":"e2e-openid-9002","agree":true}' \
  "[{\"wait\":2500},{\"shot\":\"k5-$TAG-merchant-finance\"},{\"scroll\":600},{\"wait\":800},{\"shot\":\"k5-$TAG-merchant-finance-2\"},{\"dump\":1}]" > "$RIG_SHOTS/k5-$TAG-finance.txt" 2>&1
ls "$RIG_SHOTS" | grep "k5-$TAG"
