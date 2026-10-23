#!/usr/bin/env python3
"""Proposal-only static checks. Never connects to DB or executes SQL/financial arithmetic."""
import hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[5]
OUT=Path(__file__).resolve().parent
BASE='ee71b74b629539e9a257c308491f8f9d94fe193e'
PREFIX='docs/delivery/architecture-day/G/cash-origin-proposal/'
def git(*args): return subprocess.check_output(['git',*args],cwd=ROOT)
def strip_comments(sql): return re.sub(r'--[^\n]*','',sql)
changed=set(git('diff',BASE,'--name-only').decode().splitlines())
changed.update(git('ls-files','--others','--exclude-standard').decode().splitlines())
assert all(p.startswith(PREFIX) for p in changed),changed
assert git('rev-parse',BASE).decode().strip()==BASE
core=strip_comments((OUT/'schema-candidate.sql').read_text())
option=strip_comments((OUT/'allocation-option-a.sql').read_text())
pre=strip_comments((OUT/'preflight.sql').read_text())
assert set(re.findall(r'CREATE TABLE\s+(\w+)',core,re.I))=={
 'coop_settlement_cash_close','coop_settlement_cash_event','coop_settlement_cash_contribution'}
assert re.findall(r'CREATE TABLE\s+(\w+)',option,re.I)==['coop_settlement_cash_allocation']
assert set(re.findall(r'ALTER TABLE\s+(\w+)',core,re.I))=={'coop_settlement','coop_settlement_adjustment'}
assert not re.search(r'\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|CALL|REPLACE)\b',pre,re.I)
assert not re.search(r'\b(DEFAULT\s+0|IF\s+NOT\s+EXISTS)\b',core,re.I)
for token in ['uk_coop_cash_close_topic(topic_id)','uk_coop_cash_event(cash_close_id,event_type,source_id)',
 'uk_coop_cash_contribution(cash_close_id,registration_id)',
 'uk_coop_cash_effect(cash_event_id,settlement_id,effect_key)',
 'uk_coop_cash_delivery(source_cash_adjustment_id)',
 'uk_coop_cash_global_head(cash_close_id,cash_global_line_type)',
 'unpaid_net_amount+executed_debit_amount+debt_amount=-amount',"effect_key='CASH_HEAD'", "effect_key=CONCAT('CLAIM:',claim_id)",
 "line_type IN ('PLATFORM','DEPOSIT_DEDUCTION')", "event_key=CONCAT('cash_event:',cash_event_id)"]:
 assert token in core,token
assert 'PRIMARY KEY(contribution_id,settlement_id)' in option
for sql in [core,option]:
 # Parenthesis/string shape only. This is NOT MySQL syntax/CHECK validation.
 clean=re.sub(r"'(?:''|[^'])*'",'',sql)
 depth=0
 for char in clean:
  depth+=(char=='(')-(char==')')
  assert depth>=0
 assert depth==0
assert '不默认答案' in (OUT/'money-evidence.md').read_text()
print('PASS: proposal-only scope; production/base schema/old migrations/frozen candidates untouched')
print('PASS: core 3 tables + explicitly conditional allocation table; 2 existing tables extended only in proposal')
print('PASS: preflight is SELECT-only; canonical IDs/unique keys and explicit execution receipt fields present')
print('PASS: candidate parentheses/string shape; not a SQL parser or MySQL verification')
source_paths=[
 'AGENTS.md','sql/base_schema_20260818.sql',
 'chengyinhub-admin/sql/migration_settlement_claim_20260811.sql',
 'chengyinhub-admin/sql/migration_complaint_period_funds_gate_20260917.sql',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CoopSettlementClaimServiceImpl.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RefundServiceImpl.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/PricingServiceImpl.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/CoopSettlementContract.java',
 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/SettlementDebtLedger.java',
 'chengyinhub-system/src/main/resources/mapper/business/CoopSettlementAdjustmentMapper.xml',
 'chengyinhub-system/src/main/resources/mapper/business/CoopSettlementDetailMapper.xml',
 'chengyinhub-system/src/main/resources/mapper/business/SettlementClaimMapper.xml',
 'chengyinhub-system/src/main/resources/mapper/business/UmsMemberSettlementDebtMapper.xml',
 'docs/superpowers/specs/2026-07-12-coop-multi-party-settlement-design.md',
 'docs/superpowers/plans/2026-07-12-coop-multi-party-settlement-p1.md',
]
sources={}
for p in source_paths:
 data=(ROOT/p).read_bytes()
 assert data==git('show',BASE+':'+p),p
 sources[p]={'blob':git('rev-parse',BASE+':'+p).decode().strip(),'sha256':hashlib.sha256(data).hexdigest()}
external={}
for commit,path in [
 ('59eeae487a22803cf5e930ed5ece42c7c7525c1c','docs/delivery/architecture-day/A-funds-fence/PROTOCOL.md'),
 ('9de37f77b4d19389f7dadf6c6a88da4eece09e1b','docs/delivery/architecture-day/H/g-wp04/REVIEW.md')]:
 data=git('show',commit+':'+path)
 external[commit+':'+path]={'blob':git('rev-parse',commit+':'+path).decode().strip(),'sha256':hashlib.sha256(data).hexdigest()}
proposal={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir())
 if p.suffix in ['.sql','.md','.py']}
(OUT/'source-identity.json').write_text(json.dumps({'parent':BASE,'source':sources,'reviewed':external,'proposal':proposal},indent=2)+'\n')
print('PASS: exact 16 source files unchanged; A full protocol/H review/source/proposal SHA256 recorded')
print('NOT_RUN: SQL syntax/DDL/preflight, Java, MySQL, financial algorithms, CI, production; root confirmation required before coding')
