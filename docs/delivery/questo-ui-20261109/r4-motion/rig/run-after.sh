#!/bin/bash
# after-strips on the r4-motion bundle: same scenarios as before, new self-play topic for the ending (the first run is finished)
export RIG=$HOME/rig/docs/delivery/cloud-walk-20261101/rig RIG_SHOTS=$WORK/shots-after
mkdir -p $RIG_SHOTS; cd $WORK/scen
T2=$(grep -o 'TOPIC_ID=[0-9]*' $WORK/fixtures/free2.txt | tail -1 | cut -d= -f2)
for sc in share landing team ainpc-open ainpc settle settle-rank; do
  sed 's/-before"/-after"/' $sc-before.json > $sc-after.json
  echo "=== $sc-after"; timeout 300 node $WORK/rigtools/frames.mjs $sc-after.json 2>&1 | grep -E 'frames captured|metrics|NO FRAMES|Error|skip' | cut -c1-300
done
# ending on the fresh topic (before-run is finished; same steps)
sed "s/-before\"/-after\"/; s/topicId=990014/topicId=$T2/" ending-before.json > ending-after.json
echo "=== ending-after (topic $T2)"; timeout 300 node $WORK/rigtools/frames.mjs ending-after.json 2>&1 | grep -E 'frames captured|metrics|NO FRAMES|Error|skip|^"' | cut -c1-300
# reduced-motion shots
for sc in settle share landing ainpc-open team; do
  echo "=== $sc-reduced"; timeout 300 node $WORK/rigtools/frames.mjs $sc-reduced.json 2>&1 | grep -E 'frames captured|metrics|NO FRAMES|Error' | cut -c1-200
done
ls $RIG_SHOTS | grep -c strip
