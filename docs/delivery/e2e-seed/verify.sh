#!/bin/bash
# E2E TEST verification for be-int (8120). Usage: verify.sh [base_url]   (default loopback)
B=${1:-http://127.0.0.1:8120}
j() { python3 -c "import json,sys;d=json.load(sys.stdin);$1"; }
login() {
  # TEST Redis only: clear the 60s and 10/day send limits so repeated verification runs keep using the real sms/send.
  docker exec be-int-redis redis-cli -n 9 DEL sms_limit_phone_$1 sms_send_phone_day_$1 >/dev/null
  local s=$(curl -s -X POST $B/api/sms/send -d phone=$1 | j 'print(d["code"],d["msg"])')
  local c=$(docker exec be-int-redis redis-cli -n 9 GET sms_$1_code | tr -d '"')
  local r=$(curl -s -X POST $B/api/login/phone -d "phone=$1&code=$c")
  echo "login $1: sms/send=[$s] login=[$(echo "$r" | j 'print(d["code"],"role="+str(d["data"]["role"]))')]" >&2
  echo "$r" | j 'print(d["token"])'
}
T9=$(login 13900000009); T4=$(login 13900000004)
H9="Authorization: Bearer $T9"; H4="Authorization: Bearer $T4"
echo "userInfo 9009: $(curl -s -X POST $B/api/userInfo -H "$H9" | j 'a=d["appUser"];print(d["code"],"role="+str(a.get("role")),"nickName="+str(a.get("nickName")))')"
echo "userInfo 9004: $(curl -s -X POST $B/api/userInfo -H "$H4" | j 'a=d["appUser"];print(d["code"],"role="+str(a.get("role")),"nickName="+str(a.get("nickName")))')"
echo "user/info 9004: $(curl -s -X POST $B/api/user/info -H "$H4" | j 'x=d.get("data") or {};print(d["code"],"role="+str(x.get("role")))')"
echo "topic/info-to-user 992001 (as 9004): $(curl -s -X POST $B/api/topic/info-to-user -H "$H4" -d id=992001 | j 'x=d.get("data") or {};print(d["code"],x.get("name"),"lifecycle="+str(x.get("lifecycle")),"chaptersList="+str(len(x.get("chaptersList") or []))+"/totalChapterCount="+str(x.get("totalChapterCount")),"activities="+str([a.get("id") for a in x.get("activityList") or []]))')"
echo "play/nodes 9009 (signed up): $(curl -s "$B/api/play/nodes?activityId=992031&topicId=992001" -H "$H9" | j 'x=d.get("data") or {};print(d["code"],"registered="+str(x.get("registered")),"playable="+str(x.get("playable")),"total="+str(x.get("total")),"chapters="+str(len(x.get("chapters") or [])))')"
echo "play/nodes 9004 (not signed up): $(curl -s "$B/api/play/nodes?activityId=992031&topicId=992001" -H "$H4" | j 'x=d.get("data") or {};print(d["code"],"registered="+str(x.get("registered")),"total="+str(x.get("total")))')"
Q=$(curl -s -X POST $B/api/registration/quote -H "$H4" -H 'Content-Type: application/json' -d '{"ownerType":2,"ownerId":992031,"ticketId":992041,"tickets":[{"ticketId":992041,"count":1}],"realName":"E2E 玩家小城","phone":"13900000004"}')
echo "registration/quote 9004: $(echo "$Q" | j 'x=d.get("data") or {};print(d["code"],d["msg"],"payAmount="+str(x.get("payAmount")))')"
Q9=$(curl -s -X POST $B/api/registration/quote -H "$H9" -H 'Content-Type: application/json' -d '{"ownerType":2,"ownerId":992031,"ticketId":992041,"tickets":[{"ticketId":992041,"count":1}],"realName":"E2E 玩家二号","phone":"13900000009"}')
SIGN=$(echo "$Q9" | j 'print((d.get("data") or {}).get("quoteSign",""))')
echo "registration/create free 9009 (idempotent replay of the seed sign-up): $(curl -s -X POST $B/api/registration/create -H "$H9" -H 'Content-Type: application/json' -d '{"ownerType":2,"ownerId":992031,"ticketId":992041,"tickets":[{"ticketId":992041,"count":1}],"realName":"E2E 玩家二号","phone":"13900000009","quoteSign":"'$SIGN'","requestId":"e2e-seed-reg-9009-992031"}' | j 'x=d.get("data") or {};print(d["code"],d["msg"],"registrationId="+str(x.get("registrationId")),"payableAmount="+str(x.get("payableAmount")))')"
echo "im/send 9004->9009: $(curl -s -X POST $B/api/im/send -H "$H4" -d 'target_member_id=9009&msg_type=1&content=verify ping' | j 'x=d.get("data") or {};print(d["code"],d["msg"],"checkStatus="+str(x.get("checkStatus")))')"
echo "club/home 9009: $(curl -s -X POST $B/api/club/home -H "$H9" -H 'Content-Type: application/json' -d '{}' | j 'x=d.get("data") or {};print(d["code"],"joined="+str([c.get("id") for c in x.get("joined") or []]))')"
echo "club/post/list 7001: $(curl -s -X POST $B/api/club/post/list -H "$H9" -H 'Content-Type: application/json' -d '{"clubId":7001}' | j 'x=d.get("rows") or d.get("data") or [];x=x.get("list",x) if isinstance(x,dict) else x;print(d["code"],"posts="+str(len(x)))')"
echo "club/chat 7001 (channel): $(curl -s -X POST $B/api/club/chat -H "$H9" -H 'Content-Type: application/json' -d '{"id":7001}' | j 'print(d["code"],d.get("data"))')"
echo "community feed LATEST: $(curl -s "$B/api/v1/community/feeds/LATEST?limit=10" -H "$H9" | j 'x=d.get("data") or {};it=x.get("items") or x.get("list") or x.get("rows") or [];print(d["code"],"items="+str(len(it)))')"
