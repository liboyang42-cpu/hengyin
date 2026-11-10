#!/bin/bash
export RIG=$HOME/rig/docs/delivery/cloud-walk-20261101/rig RIG_SHOTS=$WORK/shots-before
mkdir -p $RIG_SHOTS; cd $WORK/scen
for sc in share-before ending-before settle-before landing-before team-before ainpc-open-before ainpc-before; do
  echo "=== $sc"; timeout 240 node $WORK/rigtools/frames.mjs $sc.json 2>&1 | tail -4
done
ls $RIG_SHOTS | grep strip
