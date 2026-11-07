# usage: run.sh <verifier_dir> <cand_dir> <out>
V=$1; C=$2; OUT=$3; S=$(dirname $OUT)
CAND=$(git -C $C rev-parse HEAD)
cat > $S/event.json <<J
{"repository":{"full_name":"liboyang42-cpu/hengyin"},"pull_request":{"head":{"sha":"$CAND"},"base":{"sha":"c6eac3b8cebf7311e8f00f208d294c3c8212aaec"}}}
J
B=$(git -C $C merge-base $CAND c6eac3b8cebf7311e8f00f208d294c3c8212aaec)
GITHUB_ACTIONS=true GITHUB_REPOSITORY=liboyang42-cpu/hengyin GITHUB_EVENT_NAME=pull_request GITHUB_SHA=$CAND GITHUB_EVENT_PATH=$S/event.json \
  python3 $V/ci/architecture/gate.py source --source $C --candidate $CAND --compare-base $B > $OUT
echo "exit=$?"
python3 -c "
import json,sys;d=json.load(open('$OUT'));print('verdict',d.get('verdict'),'error',d.get('error'));fs=d.get('findings') or d.get('blockers') or []
print('findings',len(fs))
for f in fs[:20]: print(' ',json.dumps(f,ensure_ascii=False)[:220])
print('keys',list(d.keys()))"
