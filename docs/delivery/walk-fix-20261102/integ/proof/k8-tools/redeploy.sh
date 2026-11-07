#!/bin/bash
# redeploy.sh <sha> [java]: move rig candidate to <sha>, rebuild bundle; with "java" also rebuild jar + explode + restart app (DB kept)
set -e
export LANG=C.UTF-8 LC_ALL=C.UTF-8
SHA=$1; CAND=$HOME/walk-rig/candidate; R=$HOME/rig/docs/delivery/cloud-walk-20261101/rig; PY=$HOME/walk-rig/venv/bin/python
git -C $CAND checkout -q --detach $SHA
if [ "$2" = java ]; then
  (cd $CAND && mvn -B -q -o -DskipTests package) > $HOME/walk-rig/mvn.log 2>&1 || { tail -30 $HOME/walk-rig/mvn.log; exit 1; }
  $PY $R/app.py stop || true
  W=$HOME/walk-rig/work; rm -rf $W/app
  SOURCE_SHA=$SHA THROUGH=player FAIL_PATH="" $PY $CAND/ci/chain_acceptance.py --out $HOME/walk-rig/out2 --gate $CAND --source $CAND --work $W --app $W/app --jar $CAND/chengyinhub-admin/target/chengyinhub-admin.jar explode
  $PY $R/app.py start
fi
bash $R/mpweb/build.sh > $HOME/walk-rig/mpweb-build.log 2>&1 || { tail -20 $HOME/walk-rig/mpweb-build.log; exit 1; }
echo "redeployed $SHA"
