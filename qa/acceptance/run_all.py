#!/usr/bin/env python3
"""Run every acceptance flow (f01..f18) against the TEST server and write results.json + results.md.

Usage:  E2E_CODE_CMD='ssh work docker exec be-int-redis redis-cli -n 9 GET sms_{phone}_code' \
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
    results = [harness.run(fn) for fn in flows(only)]
    out = dict(base=harness.BASE, run_id=harness.RUN_ID, at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               flows=results)
    with open(os.path.join(HERE, "results.json"), "w") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(HERE, "results.md"), "w") as fh:
        fh.write("# Acceptance run %s against %s\n\n" % (out["at"], out["base"]))
        fh.write("| Flow | Verdict |\n|---|---|\n")
        fh.write("\n".join("| %s %s | %s |" % (f["id"], f["name"], f["verdict"]) for f in results) + "\n\n")
        fh.write(table(results) + "\n")
    for f in results:
        print("%-4s %-40s %s" % (f["id"], f["name"], f["verdict"]))
    sys.exit(0 if all(f["verdict"] == "PASS" for f in results) else 1)


if __name__ == "__main__":
    main()
