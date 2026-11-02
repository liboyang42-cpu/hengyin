#!/usr/bin/env python3
"""Run the candidate's real-HTTP chains (scripts/e2e/chain_*.py) against the running rig app,
using ci/chain_acceptance.py's own run_chains/chain_env/read-back checks.
Usage: $RIG_HOME/venv/bin/python chains.py [through]   (through: platform|merchant_club|sale_close|player|finance; default player)
Note: chains write real rows (a platform topic, merchant/club joins, orders) into cy_e2e_51."""
import json, os, sys
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
sys.path.insert(0, str(CAND / "ci"))
import chain_acceptance as ca  # noqa: E402

through = sys.argv[1] if len(sys.argv) > 1 else "player"
names = list(ca.CHAIN_NAMES[:ca.CHAIN_NAMES.index(through) + 1])
out = RIG_HOME / "out" / "chains-run"
out.mkdir(parents=True, exist_ok=True)
env = ca.chain_env(dict(os.environ), RIG_HOME / "work" / "bin")
results = ca.run_chains(names, str(CAND), env, str(out))
for r in results:
    print("%-14s %-8s topic=%s exit=%s %ss problems=%s" % (r["chain"], r["verdict"], r.get("topic_id"),
          r.get("exit_code"), r.get("duration_seconds"), r.get("problems")))
print("logs:", out / "chains")
sys.exit(0 if all(r["verdict"] == "PASS" for r in results) else 1)
