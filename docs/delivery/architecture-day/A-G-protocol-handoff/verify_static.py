#!/usr/bin/env python3
"""Fixed-object text checks only; no SQL, Java, MySQL or CI execution."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASE = "deb2f19ffd1f60b061234a5366038ead5c7dafba"
G = "5e2803424f4954d48efa07a7176176a289476d30"
REVIEW = "e4830458359e2079a9e40312682a6595dc496184"
PREFIX = "docs/delivery/architecture-day/G/cash-origin-proposal/"


def read(ref, path):
    return subprocess.check_output(["git", "show", ref + ":" + path], cwd=ROOT)


def main():
    paths = {
        "basis": (BASE, "chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/TopicSettlementCashBasis.java"),
        "topic_service": (BASE, "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java"),
        "current_reads": (BASE, "chengyinhub-system/src/main/resources/mapper/business/CoopSettlementEvidenceMapper.xml"),
        "basis_test": (BASE, "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/TopicSettlementCashBasisTest.java"),
        "entry_test": (BASE, "chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/Wp04TopicSettlementEntryGuardsTest.java"),
        "original_adjustment": (BASE, "chengyinhub-admin/sql/migration_settlement_claim_20260811.sql"),
        "candidate": (G, PREFIX + "schema-candidate.sql"),
        "g_contract": (G, PREFIX + "README.md"),
        "sql_witnesses": (REVIEW, "docs/delivery/architecture-day/A-G-cash-protocol-review/negative-controls.sql"),
    }
    raw = {key: read(*locator) for key, locator in paths.items()}
    source = {key: value.decode() for key, value in raw.items()}
    basis = source["basis"]
    assert basis.index('Integer.valueOf(1).equals(row.getPaymentStatus())') < basis.index('basis.totalSales = basis.totalSales.add(amount)')
    assert 'throw invalid("PAYMENT_CONFIRMATION_UNRESOLVED")' in basis
    topic = source["topic_service"]
    assert topic.index('TopicSettlementCashBasis cashBasis = lockTopicCashBasis(topic.getId());') < topic.index('if (totalSales.signum() == 0)')
    assert 'validateZeroCashCoopContracts(topic, effectiveOrders);' in topic
    assert 'if (!"NONE".equals(plan.getRoute()))' in topic
    for selector in ('selectTopicCashRowsForUpdate', 'selectTopicActivityCashRowsForUpdate'):
        match = re.search(r'<select id="' + selector + r'"[^>]*>(.*?)</select>', source['current_reads'], re.S)
        assert match and 'useCache="false"' in match.group(0) and 'flushCache="true"' in match.group(0)
        assert 'for update' in match.group(1).lower()
        assert 'payment_status =' not in match.group(1) and 'del_flag' not in match.group(1)
    assert 'paidUnverifiedActivityAndUnknownPaymentsCannotCloseAsZero' in source['basis_test']
    assert 'PAYMENT_CONFIRMATION_UNRESOLVED' in source['basis_test']
    assert 'trulyEmptyTopicWithoutContractCanStillCloseOnce' in source['entry_test']
    assert 'provenNoneZeroContractPreservesIdentityBeforeDepositRefund' in source['entry_test']
    candidate = re.sub(r'--[^\n]*', '', source['candidate'])
    edges = re.findall(r'CONSTRAINT\s+(\w+)\s+FOREIGN KEY\(([^)]*)\)\s+REFERENCES\s+(\w+)\(([^)]*)\)', candidate, re.I)
    assert len(edges) == 11 and all(',' not in edge[1] for edge in edges)
    assert not any(edge[1] == 'settlement_id' for edge in edges)
    adjustment = source['original_adjustment'].split('CREATE TABLE coop_settlement_adjustment (')[1].split(') ENGINE')[0]
    assert 'FOREIGN KEY' not in adjustment
    assert 'original_sales_basis>0' in candidate and 'original_contribution_count>0' in candidate
    assert 'platform_head_id BIGINT NOT NULL' in candidate and 'cash_close_id BIGINT NOT NULL' in candidate
    assert '所有head必须同close' in source['g_contract']
    assert source['sql_witnesses'].count('START TRANSACTION;') == 5
    anchors = {
        'basis': ['Integer.valueOf(1).equals(row.getPaymentStatus())', 'PAYMENT_CONFIRMATION_UNRESOLVED'],
        'topic_service': ['TopicSettlementCashBasis cashBasis = lockTopicCashBasis(topic.getId());', 'if (totalSales.signum() == 0)', 'private TopicSettlementCashBasis lockTopicCashBasis', 'private void validateZeroCashCoopContracts'],
        'candidate': ['platform_head_id BIGINT NOT NULL', 'original_sales_basis>0', 'cash_close_id BIGINT NOT NULL', 'fk_coop_cash_effect_event', 'fk_coop_cash_delivery_adjustment'],
    }
    result = {
        'baseline': BASE, 'g_proposal': G, 'prior_review': REVIEW,
        'files': {key: {'ref': paths[key][0], 'path': paths[key][1], 'sha256': hashlib.sha256(value).hexdigest()} for key, value in raw.items()},
        'line_anchors': {key: {needle: [number for number, line in enumerate(source[key].splitlines(), 1) if needle in line] for needle in needles} for key, needles in anchors.items()},
        'candidate_foreign_keys': [{'name': name, 'columns': columns, 'parent_table': table, 'parent_columns': parent} for name, columns, table, parent in edges],
        'pay1_guard': 'SOURCE_CONFIRMED; JAVA_NOT_RUN',
        'same_close_examples': 'STATIC_RELATIONSHIP_WITNESSES; MYSQL_NOT_RUN; NO_SERVICE_BYPASS_CLAIM',
        'zero_close_late_success': 'CONDITIONAL_PROTOCOL_CASE; REACHABILITY_UNPROVEN',
        'sql_witnesses_executed': 0, 'java_tests_executed': 0, 'mysql_tests_executed': 0, 'ci_dispatches': 0,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
