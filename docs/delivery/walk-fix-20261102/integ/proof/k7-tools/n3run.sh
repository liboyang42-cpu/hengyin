id=$1
for i in 1 2 3; do GREP='TEXT\[(game|r|w)|MISS tapx:(梧桐|银杏|提交|继续)|NET.*/api/play/(adv|answer|theme/game)' PRE='[]' OPEN=打开任务 timeout 170 node $HOME/j3t/w.mjs $id 990017 n3c '["tapx:银杏","wait:1500","tapx:提交","wait:6000","say:w1","shot:n3c-wrong","tapx:继续","wait:4000","say:w2","shot:n3c-after-fail","tapx:梧桐","wait:1500","tapx:提交","wait:4000","say:r1","shot:n3c-right","tapx:继续故事","wait:3000","say:r2","shot:n3c-settle-row"]' | cut -c1-600 > $HOME/n3e.log; grep -q "SUBMIT_QA" $HOME/n3e.log && break; done
grep -v "TEXT\[game" $HOME/n3e.log
