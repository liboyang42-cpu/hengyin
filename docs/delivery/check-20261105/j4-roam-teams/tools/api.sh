#!/bin/bash
# usage: api.sh <openid|guest> METHOD path [json]
o=$1; m=$2; p=$3; d=$4
H=()
if [ "$o" != guest ]; then t=$(python3 -c "import json;print(json.load(open('/tmp/j4/tok-$o.json'))['token'])"); H=(-H "Authorization: Bearer $t"); fi
if [ -n "$d" ]; then curl -s -X $m "${H[@]}" -H 'Content-Type: application/json' --data "$d" "http://127.0.0.1:8099$p"; else curl -s -X $m "${H[@]}" "http://127.0.0.1:8099$p"; fi; echo
