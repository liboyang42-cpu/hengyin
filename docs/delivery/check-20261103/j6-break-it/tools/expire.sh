#!/bin/bash
# expire every app session of member $1 on the server (simulates token expiry / revocation)
. /root/j6/env
for t in $(docker exec $R redis-cli -n 9 smembers login_app_member_tokens:$1 | tr -d '"'); do docker exec $R redis-cli -n 9 del login_app_tokens:$t >/dev/null; done
echo "expired sessions of $1"
