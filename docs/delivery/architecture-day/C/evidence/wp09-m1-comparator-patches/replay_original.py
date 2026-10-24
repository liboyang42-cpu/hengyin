#!/usr/bin/env python3
"""Read unchanged original CI artifacts and temporarily apply review patches; no Java/DB/CI.
Usage: python3 replay_original.py /path/to/extracted-run --out /path/to/review-output
Expected input: old/<class>/receipt.json+surefire-reports, new/<class>/..., as original artifacts.
"""
import argparse,copy,hashlib,importlib.util,json,subprocess,tempfile,unittest
from pathlib import Path
HERE=Path(__file__).resolve().parent;FROZEN=HERE.parent/'wp09-m1-equivalence'
SOURCES={'old':'ea43bef7b074333b1bc38a52470099368199b3e0','new':'b54acdc549e801c98f13daf9a3f9174416270161'}
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('artifacts');ap.add_argument('--out',required=True);args=ap.parse_args()
 out=Path(args.out).resolve();repo=next(p for p in HERE.parents if (p/'AGENTS.md').is_file())
 if out.is_relative_to(repo):raise SystemExit('Raw artifact review output must stay outside this public repository')
 out.mkdir(parents=True,exist_ok=True);root=Path(args.artifacts)
 frozen=load('frozen',FROZEN/'compare_receipts.py');manifest=json.loads((FROZEN/'inputs-manifest.json').read_text())
 with tempfile.TemporaryDirectory(prefix='c-real-receipt-replay-') as temp:
  target=Path(temp,'docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/compare_receipts.py');target.parent.mkdir(parents=True);target.write_bytes((FROZEN/'compare_receipts.py').read_bytes())
  subprocess.run(['git','apply',str(HERE/'member-fk-join.review.patch')],cwd=temp,check=True,capture_output=True)
  money=load('money_proposal',target)
  subprocess.run(['git','apply',str(HERE/'ledger-rounding-endpoint.review.patch')],cwd=temp,check=True,capture_output=True)
  both=load('both_proposals',target)
  docs={};hashes={};cases=[];first_cases={};precision=[]
  ledger='com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.insertUmsMemberPointsDetail'
  debit='com.chengyinhub.business.mapper.UmsMemberMapper.deductPoints'
  for side,sha in SOURCES.items():
   docs[side],hashes[side]=frozen.read_bundle(root/side,sha,manifest)
   for case in manifest['cases']:
    d=docs[side][case['suite'],case['case']];original=copy.deepcopy(d);statuses={}
    for name,tool in [('frozen',frozen),('fk_proposal',money),('both_proposals',both)]:
     try:tool.canonical(d,case,manifest);statuses[name]='PASS'
     except frozen.EvidenceError as error:statuses[name]=str(error)
     except (money.EvidenceError,both.EvidenceError) as error:statuses[name]=str(error)
    assert d==original,'review tools modified original JSON'
    row=dict(side=side,suite=case['suite'],case=case['case'],caseOrdinal=d['caseOrdinal'],verdicts=statuses,
       initial_balances=[{'id':v['id'],'point':v['point']} for v in d['initial']['tables']['ums_member']['rows']],
       final_balances=[{'id':v['id'],'point':v['point']} for v in d['final']['tables']['ums_member']['rows']],
       complete_final_ledger=d['final']['tables']['ums_member_points_detail']['rows'])
    cases.append(row)
    if case['case']=='legalSelfPlayUsesOneRealDebitAndOriginalEventIdentity':
     first_cases[side]=dict(row,source_sha=sha,raw_financial_and_lock_trace=[e for e in d['rawTrace'] if e.get('statement') in (ledger,debit) or e.get('statement','').endswith('.selectCmsRegistrationByIdForUpdate') or e['kind']=='tx.complete'])
    if statuses['fk_proposal']!='PASS':
     precision.append(dict(side=side,case=case['case'],reason=statuses['fk_proposal'],jvmStartMillis=d['jvmStartMillis'],jvmEndMillis=d['jvmEndMillis'],timestampCodec=d['timestampCodec'],sql_mode=d['environment'].get('sql_mode'),
       column=[v for v in d['initial']['tables']['ums_member_points_detail']['columns'] if v['name']=='create_time'],
       complete_final_ledger=d['final']['tables']['ums_member_points_detail']['rows'],
       actual_insert_trace=[e for e in d['rawTrace'] if e.get('statement')==ledger and e['kind']=='db.end' and e.get('operation')=='update']))
  comparison=both.compare(docs['old'],docs['new'],manifest)
  summary=dict(status='REVIEW_PROPOSAL_OFFLINE_PASS_NOT_ADOPTED',run_id='40061448735',gate_sha='be05256849ef204ced0f6353dc4458e1d2ad6dd7',sources=SOURCES,
    original_artifact_hashes=hashes,raw_data_unchanged=True,cases=cases,first_failure_details=first_cases,precision_counterexamples=precision,
    frozen_failure_count=sum(v['verdicts']['frozen']!='PASS' for v in cases),after_fk_failure_count=sum(v['verdicts']['fk_proposal']!='PASS' for v in cases),after_both_failure_count=sum(v['verdicts']['both_proposals']!='PASS' for v in cases),
    proposed_canonical_results=comparison,approved_or_dispatched=False,java_mysql_ci_executed_here=False)
  (out/'real-corpus-review.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
  controls=load('original16',FROZEN/'test_compare_receipts.py');controls.c=both
  with (out/'proposed-original16.txt').open('w') as stream:
   result=unittest.TextTestRunner(stream=stream,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(controls.ComparatorTests))
  assert result.wasSuccessful() and result.testsRun==16 and not result.skipped
  # Keep source-bound original receipts/XML hash gates in the proposed full comparison output.
  result=dict(status='REVIEW_PROPOSAL_OFFLINE_PASS_NOT_ADOPTED',old_source=SOURCES['old'],new_source=SOURCES['new'],cases=comparison,
      old_artifact_hashes=hashes['old'],new_artifact_hashes=hashes['new'],manifest_sha256=hashlib.sha256((FROZEN/'inputs-manifest.json').read_bytes()).hexdigest())
  (out/'proposed-strict-comparison.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
  print('Original source/JSON/XML/CLEAN bindings validated; frozen failures',summary['frozen_failure_count'],'FK-only failures',summary['after_fk_failure_count'],'both proposals failures',summary['after_both_failure_count'],'canonical cases',len(comparison),'; original16 controls pass; original data/frozen tools unchanged.')
if __name__=='__main__':main()
