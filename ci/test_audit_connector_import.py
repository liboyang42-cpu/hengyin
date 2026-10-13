"""Local import regression check; never connects to a database."""
from pathlib import Path
import runpy
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    referral = runpy.run_path(str(root / "scripts/audit_referral_bounty.py"))
    assert Path(referral["SKILL_SCRIPTS"]) == root / "ci"
    connector = sys.modules["run_migration"]
    assert Path(connector.__file__).resolve() == root / "ci/run_migration.py"
    original = connector.connect
    try:
        connector.connect = lambda config: ("stub", config)
        coop = runpy.run_path(str(root / "scripts/audit_coop_payout_legacy.py"))
        assert coop["connect_production"]("local-test.yml") == ("stub", "local-test.yml")
    finally:
        connector.connect = original
    print("PASS: both audit scripts use the repository connector; no network")


if __name__ == "__main__":
    main()
