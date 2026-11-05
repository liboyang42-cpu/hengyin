#!/bin/bash
# run every config of a batch file through sweep.mjs, sequentially
f=$1; n=$(node -e "console.log(require('./$f').length)")
for i in $(seq 0 $((n-1))); do c=$(node -e "console.log(JSON.stringify(require('./$f')[$i]))"); timeout 900 node sweep.mjs "$c" > ../logs/$(node -e "console.log(require('./$f')[$i].id)").txt 2>&1; done
echo BATCH DONE $f
