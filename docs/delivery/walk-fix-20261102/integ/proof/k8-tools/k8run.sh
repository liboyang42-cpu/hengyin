#!/bin/bash
# k8run.sh <identity> <topic> <n> '<steps body>'  open play page at station n's location
n=$3; lat=$(python3 -c "print('%.6f'%(31.2380+$n*0.0003))"); lng=$(python3 -c "print('%.6f'%(121.4900+$n*0.0004))")
timeout ${T:-500} node /root/j3t/drive.mjs "{\"identity\":\"$1\",\"path\":\"pages/play/index?topicId=$2\",\"lat\":$lat,\"lng\":$lng}" "[\"wait:2000\",\"tapx:开始游戏\",\"tapx:继续游戏\",\"wait:8000\",\"tapx:出发\",\"wait:2500\",\"tapn:开启定位|1\",\"wait:1500\",\"tapx:继续探索\",\"wait:5000\",\"tapx:继续探索\",\"wait:2500\"${4:+,$4}]" 2>&1 | grep -E "RLOG|TEXT|MISS|ERRORS|EVAL|DATA|ROUTENOW|UNKNOWN|STEP_ERR|PICKVIEW|NET.*/api/(play|orient|game|registration|topic|badge|point|member)" | grep -v 'MISS tapx:继续游戏\|MISS tapx:出发\|MISS tapx:继续探索\|MISS tapn:开启定位' | cut -c1-1500
