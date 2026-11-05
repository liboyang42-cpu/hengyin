#!/bin/bash
# run every config of a batch file through sweep.mjs, sequentially (same as lane p3's runbatch.sh)
cd "$(dirname "$0")"; f=$1; n=$(node -e "console.log(require('./$f').length)")
for i in $(seq 0 $((n-1))); do c=$(node -e "console.log(JSON.stringify(require('./$f')[$i]))"); id=$(node -e "console.log(require('./$f')[$i].id)"); timeout 600 node sweep.mjs "$c" > ../logs/$id.txt 2>&1; echo "$id rc=$?"; done
echo BATCH DONE $f
