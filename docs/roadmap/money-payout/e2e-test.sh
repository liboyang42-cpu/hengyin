#!/usr/bin/env bash
# R6b money-payout: end-to-end check for the integrator, TEST host only.
# Needs: BASE_URL (TEST api root, e.g. https://test.example/prod-api), MEMBER_TOKEN (app member session token),
#        ADMIN_TOKEN (admin console token with business:moneyswitch:edit and business:payoutRequest:*).
# Optional: AMOUNT (default 1.00), CLUB_ID (a club the member owns, for the club receiver step).
# Never point it at production; it refuses www.chengyinhub.com. No secret is read from or written to this file.
set -euo pipefail
: "${BASE_URL:?set BASE_URL to the TEST api root}" "${MEMBER_TOKEN:?}" "${ADMIN_TOKEN:?}"
AMOUNT="${AMOUNT:-1.00}"
case "$BASE_URL" in *chengyinhub.com*) echo "refusing the production host"; exit 2;; esac
j() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }
member() { curl -sS -H "Authorization: $MEMBER_TOKEN" -H 'Content-Type: application/json' "$@"; }
admin() { curl -sS -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' "$@"; }
area() { admin -X PUT "$BASE_URL/business/money-switch" -d "{\"area\":\"$1\",\"enabled\":$2,\"reason\":\"R6b e2e $3\"}" >/dev/null; }
step() { printf '\n== %s\n' "$*"; }

step "1. switch closed: every write is refused, nothing is queued"
area withdrawal false close; area payout false close
member "$BASE_URL/api/app/money-switch" | j "d['data']['withdrawal']"                                 # False
member -X POST "$BASE_URL/api/payout/request/submit" -d "{\"amount\":\"$AMOUNT\"}" | j "d['code'], d['msg']" # 500 提现暂未开放
member -X POST "$BASE_URL/api/payout/binding/code" -d '{"purpose":"WALLET"}' | j "d['code'], d['msg']"     # 500 提现暂未开放
admin "$BASE_URL/business/payout-request/list?status=PENDING" | j "d['data']['gate']"                     # withdrawalOpen False

step "2. open the withdrawal area (server ceiling must be on in TEST)"
area withdrawal true open
member "$BASE_URL/api/payout/request/overview" | j "{k: d['data'][k] for k in ('withdrawalOpen','available','wechatReady')}"

step "3. payout WeChat: if wechatReady is False, bind it from the app"
CODE_JSON=$(member -X POST "$BASE_URL/api/payout/binding/code" -d '{"purpose":"WALLET"}')
echo "$CODE_JSON" | j "d['data']['status'], d['data']['ttlSeconds'], d['data']['qrContent']"
echo "Scan the QR in the app (or open the mini-program page subpackageMember/payout-bind/index?bc=<code>) with WeChat,"
echo "tap 用这个微信, then press Enter here."; read -r _
member "$BASE_URL/api/payout/binding/status?purpose=WALLET" | j "d['data']['status']"                    # CLAIMED
member -X POST "$BASE_URL/api/payout/binding/confirm" -d '{"purpose":"WALLET"}' | j "d['data']"           # walletBound True

step "4. request -> review -> payout through the existing transfer"
REQ=$(member -X POST "$BASE_URL/api/payout/request/submit" -d "{\"amount\":\"$AMOUNT\"}")
echo "$REQ" | j "d['code'], d['data'] and d['data']['status']"                                           # 200 PENDING
ID=$(echo "$REQ" | j "d['data']['id']")
member -X POST "$BASE_URL/api/payout/request/submit" -d "{\"amount\":\"$AMOUNT\"}" | j "d['msg']"          # 你已有一笔提现在审核或处理中
admin -X POST "$BASE_URL/business/payout-request/$ID/approve" -d '{"note":""}' | j "d['code'], d['msg']"  # 500 请填写审核说明
admin -X POST "$BASE_URL/business/payout-request/$ID/approve" -d '{"note":"R6b e2e"}' | j "d['data']['status'], d['data'].get('failReason')"
# SENT with cashWithdrawId when withdrawal.fast-withdraw.enabled=true on TEST and the transfer accepted it;
# 微信转账通道未开通 (code 500) when that property is off: the request then stays PENDING and can be rejected.
admin -X POST "$BASE_URL/business/payout-request/$ID/approve" -d '{"note":"again"}' | j "d['msg']"        # 这笔申请已被处理 (if it was sent)

step "5. reject path moves no money"
REQ2=$(member -X POST "$BASE_URL/api/payout/request/submit" -d "{\"amount\":\"$AMOUNT\"}")
ID2=$(echo "$REQ2" | j "(d.get('data') or {}).get('id')")
if [ "$ID2" != "None" ]; then admin -X POST "$BASE_URL/business/payout-request/$ID2/reject" -d '{"note":"R6b e2e reject"}' | j "d['data']['status']"; fi

if [ -n "${CLUB_ID:-}" ]; then
  step "6. club personal receiver (payout area)"
  area payout true open
  member -X POST "$BASE_URL/api/payout/binding/code" -d "{\"purpose\":\"CLUB_RECEIVER\",\"clubId\":$CLUB_ID}" | j "d['data']['status']"
  echo "Scan in WeChat, tap 用这个微信, then press Enter."; read -r _
  member -X POST "$BASE_URL/api/payout/binding/confirm" -d "{\"purpose\":\"CLUB_RECEIVER\",\"clubId\":$CLUB_ID}" | j "d['data']['receiver']"
fi

step "7. close again: writes are refused, the queue can still reject"
area withdrawal false close; area payout false close
member -X POST "$BASE_URL/api/payout/request/submit" -d "{\"amount\":\"$AMOUNT\"}" | j "d['msg']"          # 提现暂未开放
echo "done"
