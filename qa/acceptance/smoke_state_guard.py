#!/usr/bin/env python3
"""Make an api-smoke pass leave TEST as it found it (TEST 8120 / cy_e2e_* only).

Every smoke pass calls POST /api/topic/update_user_status on topic 992001 (shelf on/off) and /api/ugc/block between
fixture players, which breaks the acceptance flows that run afterwards (REPORT "Interference"). This guard snapshots
both before the pass and restores them when the pass ends, also on an error or Ctrl-C.

Installed next to the shared runner (it is not in git): copy this file to /data/workspace/api-smoke/ and end
smoke_runner.py with

    if __name__ == "__main__":
        import smoke_state_guard
        smoke_state_guard.guarded(main)

`python3 smoke_state_guard.py install <runner.py>...` does that patch (idempotent, keeps <runner>.prev-acc-w6).
"""
import os, re, subprocess, sys

DB = os.environ.get("E2E_DB", "cy_e2e_r2")
if not re.match(r"^cy_e2e_[a-z0-9_]+$", DB):
    sys.exit("refusing non-TEST database: %s" % DB)
TOPICS = (992001,)
PLAYERS = "member_id BETWEEN 9001 AND 9009 AND blocked_member_id BETWEEN 9001 AND 9009"


def _sql(text):
    out = subprocess.run(["docker", "exec", "-i", "be-int-mysql", "mysql", "--default-character-set=utf8mb4", "-uroot", "-N", "-B", DB],
                         input=text, capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[:300])
    return [l.split("\t") for l in out.stdout.splitlines() if l.strip()]


def snapshot():
    topics = {int(i): s for i, s in _sql("SELECT id, user_status FROM cms_topic WHERE id IN (%s);" % ",".join(map(str, TOPICS)))}
    blocks = {int(r[0]) for r in _sql("SELECT id FROM im_block WHERE %s;" % PLAYERS)}
    return topics, blocks


def restore(state):
    topics, blocks = state
    for tid, status in topics.items():
        _sql("UPDATE cms_topic SET user_status=%s WHERE id=%d AND user_status<>%s;" % (status, tid, status))
    keep = ",".join(map(str, blocks)) or "0"
    new = _sql("SELECT id FROM im_block WHERE %s AND id NOT IN (%s);" % (PLAYERS, keep))
    _sql("DELETE FROM im_block WHERE %s AND id NOT IN (%s);" % (PLAYERS, keep))
    print("[smoke_state_guard] restored topic shelf %s; removed %d im_block row(s) created by this pass"
          % (topics, len(new)), flush=True)


def guarded(fn):
    state = snapshot()
    try:
        return fn()
    finally:
        restore(state)


def install(paths):
    hook = 'if __name__ == "__main__":\n    import smoke_state_guard\n    smoke_state_guard.guarded(main)\n'
    for p in paths:
        src = open(p, encoding="utf-8").read()
        if "smoke_state_guard.guarded(main)" in src:
            print("already guarded:", p)
            continue
        old = 'if __name__ == "__main__":\n    main()\n'
        if old not in src:
            sys.exit("unexpected runner tail in %s" % p)
        if not os.path.exists(p + ".prev-acc-w6"):
            open(p + ".prev-acc-w6", "w", encoding="utf-8").write(src)
        open(p, "w", encoding="utf-8").write(src.replace(old, hook))
        print("guarded:", p)


if __name__ == "__main__":
    if sys.argv[1:2] == ["install"]:
        install(sys.argv[2:])
    else:
        s = snapshot()
        print(s)
