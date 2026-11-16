#!/usr/bin/env python3
"""Run every acceptance flow (f01..f18) against the TEST server and write results.json + results.md.

fixtures.setup() runs first (idempotent TEST reset/seed, see fixtures.py; E2E_SKIP_FIXTURES=1 skips it), and
fixtures.release_for_f18() runs right before F18.

Usage (on the `work` VPS, over loopback):
        E2E_BASE_URL=http://127.0.0.1:8120 \
        E2E_CODE_CMD='docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code' \
        E2E_PLANT_CMD="docker exec be-int-redis redis-cli -n 9 SET sms_{phone}_code '\"{code}\"' EX 300" \
        python3 qa/acceptance/run_all.py [f03 f07 ...]
"""
import glob, importlib, json, os, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import harness  # noqa: E402


def flows(only):
    for path in sorted(glob.glob(os.path.join(HERE, "f[0-9][0-9]_*.py"))):
        mod = os.path.basename(path)[:-3]
        if only and mod[:3] not in only:
            continue
        yield importlib.import_module(mod).flow


def table(results):
    lines = ["| Flow | Step | Request | HTTP | code | ms | Result | Note |", "|---|---|---|---|---|---|---|---|"]
    for f in results:
        for s in f["steps"]:
            lines.append("| %s %s | %s | `%s %s` | %s | %s | %s | %s | %s |" % (
                f["id"], f["name"], s["label"], s["method"], s["path"], s.get("status", ""), s.get("code", ""),
                s.get("ms", ""), s["result"], (s.get("note") or "").replace("|", "/")))
    return "\n".join(lines)


def main():
    only = set(sys.argv[1:])
    fx = None
    if os.environ.get("E2E_SKIP_FIXTURES") != "1":
        import fixtures
        fx = fixtures.setup()
    results = []
    for fn in flows(only):
        if fn.FLOW[0] == "F18" and fx is not None:
            fixtures.release_for_f18()
        results.append(harness.run(fn))
    if fx is not None:
        fixtures.teardown()
    out = dict(base=harness.BASE, run_id=harness.RUN_ID, at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               fixtures=fx, flows=results)
    with open(os.path.join(HERE, "results.json"), "w") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(HERE, "results.md"), "w") as fh:
        fh.write("# Acceptance run %s against %s\n\n" % (out["at"], out["base"]))
        fh.write("| Flow | Verdict | Pass / fail / TEST-SKIP |\n|---|---|---|\n")
        fh.write("\n".join("| %s %s | %s | %d / %d / %d |" % (
            f["id"], f["name"], f["verdict"], sum(s["result"] == "PASS" for s in f["steps"]),
            sum(s["result"] not in ("PASS", "TEST-SKIP") for s in f["steps"]), f.get("test_skips", 0)) for f in results) + "\n\n")
        fh.write(table(results) + "\n")
    for f in results:
        print("%-4s %-40s %s" % (f["id"], f["name"], f["verdict"]))
    sys.exit(0 if all(f["verdict"] == "PASS" for f in results) else 1)


if __name__ == "__main__":
    main()
