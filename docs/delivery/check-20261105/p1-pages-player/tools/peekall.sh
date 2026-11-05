#!/bin/bash
# peekall.sh <identity|guest> <prefix> <pages.tsv>  — runs peek2 for each "id<TAB>path" line, 3 at a time
cd "$(dirname "$0")"; ident=$1; pre=$2; list=$3
cat "$list" | xargs -P 3 -L 1 bash -c 'timeout 120 node peek2.mjs '"$ident"' "$1" '"$pre"'-$0 >> ../logs/_'"$pre"'.out 2>&1 || echo "$0 TIMEOUT/ERR" >> ../logs/_'"$pre"'.out'
echo DONE $pre
