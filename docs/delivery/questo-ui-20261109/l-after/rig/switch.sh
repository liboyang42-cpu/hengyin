#!/bin/bash
# switch.sh <candidate dir>: rebuild browser bundle + restart web server on that tree
export RIG_HOME=$HOME/walk-rig CAND=$1
R=$HOME/rig/docs/delivery/cloud-walk-20261101/rig
bash $R/mpweb/build.sh >${SCRATCH:-/tmp}/build.log 2>&1 || { tail -20 ${SCRATCH:-/tmp}/build.log; exit 1; }
kill $(cat $RIG_HOME/web.pid) 2>/dev/null; sleep 1
nohup node $R/mpweb/server.mjs >$RIG_HOME/web.log 2>&1 & echo $! >$RIG_HOME/web.pid
sleep 2; curl -s -o /dev/null -w "web %{http_code}\n" http://127.0.0.1:8097/
tail -2 ${SCRATCH:-/tmp}/build.log
