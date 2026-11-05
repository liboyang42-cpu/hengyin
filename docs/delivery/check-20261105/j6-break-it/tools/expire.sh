#!/bin/bash
. /root/j6/env
n=0; for t in $(docker exec $R redis-cli -n 9 smembers login_app_member_tokens:$1 | tr -d '"'); do docker exec $R redis-cli -n 9 del login_app_tokens:$t >/dev/null; n=$((n+1)); done
echo "expired $n sessions of $1"
