#!/usr/bin/env python3
"""Review fixed Git objects; no working-tree baseline assumptions, Java, SQL, or CI execution."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
SOURCE = "66e9e1798bf0b3e1c2b7e6cb2b6ab13a3b3e1329"
PROPOSAL = "5e2803424f4954d48efa07a7176176a289476d30"
PREFIX = "docs/delivery/architecture-day/G/cash-origin-proposal/"


def source(ref, path):
    return subprocess.check_output(["git", "show", ref + ":" + path], cwd=ROOT)


def main():
    evidence = json.loads((OUT / "evidence.json").read_text())
    for path, digest in evidence["current_source_sha256"].items():
        assert hashlib.sha256(source(SOURCE, path)).hexdigest() == digest, path
    for locator, digest in evidence["external_source_sha256"].items():
        ref, path = locator.split(":", 1)
        assert hashlib.sha256(source(ref, path)).hexdigest() == digest, locator
    raw = source(PROPOSAL, PREFIX + "schema-candidate.sql").decode()
    raw = re.sub(r"--[^\n]*", "", raw)
    edges = re.findall(r"CONSTRAINT\s+(\w+)\s+FOREIGN KEY\(([^)]*)\)\s+REFERENCES\s+(\w+)\(([^)]*)\)",
                       raw, re.I)
    assert len(edges) == 11
    assert all("," not in columns for _, columns, _, _ in edges)
    assert not any(columns == "settlement_id" for _, columns, _, _ in edges)
    original = source(SOURCE, "chengyinhub-admin/sql/migration_settlement_claim_20260811.sql").decode()
    adjustment = original.split("CREATE TABLE coop_settlement_adjustment (", 1)[1].split(") ENGINE", 1)[0]
    assert "AUTO_INCREMENT" in adjustment and "FOREIGN KEY" not in adjustment
    assert "status='EXECUTED'" in raw
    assert "executed_debit_amount>0 AND balance_flow_id IS NOT NULL" in raw
    assert "debt_amount>0 AND debt_id IS NOT NULL" in raw
    assert "original_sales_basis>0" in raw and "original_contribution_count>0" in raw
    basis = source(SOURCE, "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicSettlementCashBasis.java").decode()
    assert "Integer.valueOf(1).equals(row.getPaymentStatus())" in basis
    assert "PAYMENT_CONFIRMATION_UNRESOLVED" in basis
    print("PASS: fixed root/G source identities; 11 single-column FKs; no effect settlement target FK in canonical origin/candidate")
    print("PASS: source contains pay1 rejection; normal PAYING was not classified as a proven zero-close bypass")
    print("PASS: exact AUTO_INCREMENT/CHECK/receipt and positive-close conditions inventoried")
    print("NOT_RUN: MySQL constraint admission, DDL, SQL witnesses, Java, lock schedules, financial algorithms, CI")


if __name__ == "__main__":
    main()
