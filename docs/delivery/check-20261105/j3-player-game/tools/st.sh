#!/bin/bash
# st.sh <identity> <topic> <stationIndex> <prefix> '<game steps json array body, comma-joined, no brackets>'
n=$3; lat=$(python3 -c "print('%.6f'%(31.2380+$n*0.0003))"); lng=$(python3 -c "print('%.6f'%(121.4900+$n*0.0004))")
PRE='"wait:2000","tapx:开始游戏","tapx:继续游戏","wait:9000","tapx:出发","wait:3000","tapn:开启定位|1","wait:1500","tapx:继续探索","wait:6000","tapx:继续探索","wait:3000","tapn:开启定位|1","wait:1500","say:pre","shot:'$4'-a","tapx:开始任务","wait:4000","shot:'$4'-b","say:game"'
timeout 500 node /root/j3t/drive.mjs "{\"identity\":\"$1\",\"path\":\"pages/play/index?topicId=$2\",\"lat\":$lat,\"lng\":$lng}" "[$PRE${5:+,$5}]" 2>&1 | grep -E "RLOG|TEXT|MISS|REACT|ERRORS|EVAL|DATA|ROUTENOW|UNKNOWN|STEP_ERR|NET.*/api/(play|orient|game|registration|topic/finish|badge|point)" | grep -v 'MISS tapx:继续游戏\|MISS tapx:出发\|MISS tapx:继续探索\|MISS tapn:开启定位' | cut -c1-1500
