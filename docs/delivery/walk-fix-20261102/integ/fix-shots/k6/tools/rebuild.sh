#!/bin/bash
# rebuild candidate jar + exploded app + mini-program bundle, restart app keeping DB (rig-only)
set -e
export LANG=C.UTF-8 LC_ALL=C.UTF-8
. /root/k6tools/oss.env
RIG=/root/rig/docs/delivery/cloud-walk-20261101/rig; RH=/root/walk-rig; CAND=$RH/candidate; PY=$RH/venv/bin/python
[ -n "$SKIP_MVN" ] || (cd $CAND && mvn -B -q -DskipTests package) > $RH/mvn.log 2>&1 || { tail -30 $RH/mvn.log; exit 1; }
$PY $RIG/app.py stop || true
CA="$PY $CAND/ci/chain_acceptance.py --out $RH/out --gate $CAND --source $CAND --work $RH/work --app $RH/work/app --jar $CAND/chengyinhub-admin/target/chengyinhub-admin.jar"
export SOURCE_SHA=$(git -C $CAND rev-parse HEAD) THROUGH=player FAIL_PATH=""
$CA plan
$CA explode
$PY $RIG/app.py start
bash $RIG/mpweb/build.sh > $RH/mpweb-build.log 2>&1 || { tail -20 $RH/mpweb-build.log; exit 1; }
echo REBUILT $(git -C $CAND rev-parse --short HEAD)
