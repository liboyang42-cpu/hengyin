#!/bin/bash
# r6: every package's curl list against TEST 8120 (0185418cf). Clears the TEST loopback SMS IP windows between scripts.
B=/data/workspace/be-int; L=$B/r6; PUB=https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech
export LANG=C.UTF-8
clear() { docker exec be-int-redis redis-cli -n 9 DEL sms_send_ip_window_127.0.0.1 sms_verify_ip_window_127.0.0.1 > /dev/null; }
run() { name=$1; shift; clear; echo "== $name: $*" ; timeout 600 python3 "$@" > $L/smoke-$name.log 2>&1; echo "exit=$?" >> $L/smoke-$name.log; }
{
run r2 $B/hengyin/docs/delivery/backend-app-r2/smoke_r2.py http://127.0.0.1:8120
run r2-public $B/hengyin/docs/delivery/backend-app-r2/smoke_r2.py $PUB
run im $B/r3/smoke_r3_im.py http://127.0.0.1:8120
run club $B/r4/smoke_club_8120.py http://127.0.0.1:8120
run rw $B/r4/smoke_rw_8120.py
run rw-workshop $B/r4/smoke_rw_workshop_8120.py
run import $B/r4/smoke_import_8120.py
run misc $B/r5/smoke_misc_8120.py http://127.0.0.1:8120
run merchant $B/r5/smoke_merchant_8120.py http://127.0.0.1:8120
run merchant-r3 $B/r6/smoke_r3_merchant_8120.py http://127.0.0.1:8120
run restart-token "$(cd "$(dirname "$0")/.." && pwd)/smoke_restart_token.py" http://127.0.0.1:8120
echo SMOKEDONE
} > $L/smoke-all.out 2>&1
