#!/bin/bash
# p.sh <identity> <path> '<steps json>' [lat lng]
lat=${4:-31.2380}; lng=${5:-121.4900}
timeout 400 node /root/j3t/drive.mjs "{\"identity\":\"$1\",\"path\":\"$2\",\"lat\":$lat,\"lng\":$lng}" "$3" 2>&1 | grep -E "TEXT|MISS|ERRORS|EVAL|DATA|ROUTENOW|UNKNOWN|STEP_ERR|NET.*/api/(play|orient|registration|topic/finish|badge|point|member)" | cut -c1-1500
