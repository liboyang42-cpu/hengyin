#!/bin/bash
export RIG=$HOME/rig/docs/delivery/cloud-walk-20261101/rig RIG_SHOTS=$WORK/shots-before
cd $WORK/scen
for sc in ending-before settle-before chapter-before; do echo "=== $sc"; timeout 300 node $WORK/rigtools/frames.mjs $sc.json 2>&1 | grep -E 'frames captured|NO FRAMES|Error|skip|^\{' | cut -c1-400; done
