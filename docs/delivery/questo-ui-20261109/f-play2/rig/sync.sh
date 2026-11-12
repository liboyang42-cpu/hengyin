#!/bin/bash
# copy changed xcx files from the lane branch into the candidate worktree and rebuild the browser bundle
cd /home/user/hengyin && for f in $(git diff --name-only HEAD -- chengyinhub-xcx; git diff --name-only $(git -C /home/user/cand rev-parse HEAD) HEAD -- chengyinhub-xcx); do [ -f "$f" ] && cp "$f" "/home/user/cand/$f"; done
. /home/user/walk-rig/env.sh; bash $RIG/mpweb/build.sh > /home/user/walk-rig/mpweb-build.log 2>&1 && echo rebuilt || tail -5 /home/user/walk-rig/mpweb-build.log
