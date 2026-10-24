#!/usr/bin/env python3
"""Static review-patch checks only. Executes pure JSON/input guards on synthetic data.
Never runs Java, Maven, containers, MySQL, actions or a workflow.
"""
import contextlib,hashlib,json,os,re,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import yaml
HERE=Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'AGENTS.md').is_file())
MANIFEST=json.loads((HERE/'source-manifest.json').read_text())
def source(path):return subprocess.check_output(['git','show',MANIFEST['review_base']+':'+path],cwd=ROOT)
def proposed():
    patch_file=HERE/'real-storage-it-m1-pair.review.patch'
    with tempfile.TemporaryDirectory(prefix='c-m1-workflow-review-') as temporary:
        target=Path(temporary,MANIFEST['proposed_workflow']);target.parent.mkdir(parents=True);target.write_bytes(source(MANIFEST['proposed_workflow']))
        for args in [['--check'],[]]:subprocess.run(['git','apply',*args,str(patch_file)],cwd=temporary,check=True,capture_output=True)
        text=Path(temporary,MANIFEST['proposed_workflow']).read_text()
    assert hashlib.sha256(text.encode()).hexdigest()==MANIFEST['proposed_workflow_sha256']
    return text,yaml.load(text,Loader=yaml.BaseLoader)
TEXT,WORKFLOW=proposed()
def step(job,name):return next(v for v in WORKFLOW['jobs'][job]['steps'] if v.get('name')==name)
def python_code(step):
    return step['run'].split("python3 - <<'PY'\n",1)[1].rsplit('\nPY',1)[0]
@contextlib.contextmanager
def cwd(path):
    original=Path.cwd();os.chdir(path)
    try:yield
    finally:os.chdir(original)
OLD='a'*40;NEW='b'*40
CLASSES=['com.chengyinhub.business.it.PlayHintUnlockMySqlIT','com.chengyinhub.business.it.PlayLeadAdmissionFailureMySqlIT']
PLAN=[{'class':cls,'simple':cls.rsplit('.',1)[1]} for cls in CLASSES]
BASE_ENV={'OLD_SOURCE_SHA':OLD,'NEW_SOURCE_SHA':NEW,'GITHUB_RUN_ID':'40050022063','GITHUB_RUN_ATTEMPT':'2','GITHUB_SHA':'c'*40,'TIMEZONE':'UTC','BATCH':'architecture-day-lead-admission','CLASSES':''}
class PatchChecks(unittest.TestCase):
    def test_only_optional_input_on_existing_manual_workflow_and_doc_delta(self):
        self.assertEqual(set(WORKFLOW['on']),{'workflow_dispatch'})
        base=yaml.load(source(MANIFEST['proposed_workflow']).decode(),Loader=yaml.BaseLoader)
        self.assertEqual(set(WORKFLOW['on']['workflow_dispatch']['inputs']),set(base['on']['workflow_dispatch']['inputs'])|{'old_source_sha'})
        for key in base['on']['workflow_dispatch']['inputs']:self.assertEqual(WORKFLOW['on']['workflow_dispatch']['inputs'][key],base['on']['workflow_dispatch']['inputs'][key])
        self.assertEqual(WORKFLOW['permissions'],{'contents':'read'})
        paths=set(subprocess.check_output(['git','diff','--name-only','1c138a000387583e224c9acb5369140fdf2dba18'],cwd=ROOT,text=True).splitlines())
        paths.update(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines())
        self.assertTrue(all(p.startswith(str(HERE.relative_to(ROOT))+'/') for p in paths))
        self.assertTrue((ROOT/MANIFEST['proposed_workflow']).exists())
        self.assertEqual(subprocess.check_output(['git','diff','1c138a000387583e224c9acb5369140fdf2dba18','--',MANIFEST['proposed_workflow']],cwd=ROOT),b'')
    def test_exact_public_base_hashes_and_python_bash_syntax(self):
        for name,digest in MANIFEST['base_file_hashes'].items():self.assertEqual(hashlib.sha256(source(name)).hexdigest(),digest)
        for job in WORKFLOW['jobs'].values():
            for item in job.get('steps',[]):
                if 'run' not in item:continue
                result=subprocess.run(['bash','-n'],input=item['run'],text=True,capture_output=True)
                self.assertEqual(result.returncode,0,result.stderr)
                if "python3 - <<'PY'" in item['run']:compile(python_code(item),'<review-inline-python>','exec')
    def validate_sources(self,old=OLD,new=NEW):
        with patch.dict(os.environ,dict(BASE_ENV,OLD_SOURCE_SHA=old,NEW_SOURCE_SHA=new),clear=True):
            exec(python_code(step('plan','Validate optional M1 pair identities and whole original batch')), {})
    def test_sources_require_distinct_complete_sha(self):
        self.validate_sources()
        for old,new in [(OLD,OLD),('branch-name',NEW),(OLD,'b'*39),(OLD,'B'*40),(OLD,'$(touch /tmp/forbidden)')]:
            with self.assertRaises(SystemExit):self.validate_sources(old,new)
    def build(self,old_plan=PLAN,new_plan=PLAN,pair=True,count_override=None):
        with tempfile.TemporaryDirectory(prefix='c-m1-plan-') as temporary,cwd(temporary):
            Path('ci').mkdir()
            for name in ['ci/real_storage_it.py','ci/real-storage-it-registry.json']:Path(name).write_bytes(source(name))
            if count_override is not None:
                path=Path('ci/real-storage-it-registry.json');registry=json.loads(path.read_text());registry['classes'][CLASSES[0]]['tests']=count_override;path.write_text(json.dumps(registry))
            env=dict(BASE_ENV,OLD_SOURCE_SHA=OLD if pair else '',OLD_PLAN=json.dumps(old_plan) if pair else '',NEW_PLAN=json.dumps(new_plan),GITHUB_OUTPUT=str(Path(temporary,'outputs')))
            with patch.dict(os.environ,env,clear=True):exec(python_code(step('plan','Bind optional four side/source/class jobs')), {})
            outputs=dict(line.split('=',1) for line in Path('outputs').read_text().splitlines())
            return json.loads(outputs['matrix']),json.loads(outputs['sides']),json.loads(Path('out/pair-plan.json').read_text()) if pair else None
    def test_exact_four_jobs_bind_each_source_and_full_original_pair(self):
        matrix,planned,proof=self.build()
        self.assertEqual(len(matrix),4);self.assertEqual({v['side'] for v in planned},{'old','new'});self.assertEqual(proof['tests_per_side'],14)
        self.assertEqual({(v['side'],v['source_sha'],v['class']) for v in matrix},{(side,sha,cls) for side,sha in [('old',OLD),('new',NEW)] for cls in CLASSES})
        storage=WORKFLOW['jobs']['storage_it'];self.assertEqual(storage['runs-on'],'ubuntu-latest')
        self.assertEqual(storage['strategy']['max-parallel'],'20');self.assertEqual(storage['strategy']['fail-fast'],'false')
        self.assertEqual(storage['env']['SOURCE_SHA'],'${{ matrix.source_sha }}')
        self.assertEqual(storage['env']['VALIDATED_SOURCE_SHA'],'${{ matrix.source_sha }}')
        self.assertNotRegex(TEXT,r'(?m)^\s+(?:GITHUB_RUN_ID|GITHUB_RUN_ATTEMPT|CHENGYIN_MYBATIS_IT_DB_SUFFIX):')
        base=yaml.load(source(MANIFEST['proposed_workflow']).decode(),Loader=yaml.BaseLoader)
        for name in ['Static precheck','Start scratch MySQL / Redis','Run class on real storage and read back','Remove scratch containers']:
            before=next(v for v in base['jobs']['storage_it']['steps'] if v.get('name')==name)
            self.assertEqual(step('storage_it',name),before)
    def test_missing_duplicate_wrong_class_or_count_plan_cannot_be_a_pair(self):
        for invalid in [PLAN[:1],[PLAN[0],PLAN[0]],[dict(PLAN[0],simple='Wrong'),PLAN[1]],[dict(PLAN[0],**{'class':'other.Class'}),PLAN[1]]]:
            with self.assertRaises(SystemExit):self.build(old_plan=invalid)
        with self.assertRaises(SystemExit):self.build(count_override=11)
    def test_single_source_matrix_and_jobs_keep_original_projection(self):
        custom=[{'class':'other.AnySuite','simple':'AnySuite'}]
        matrix,sides,proof=self.build(pair=False,new_plan=custom)
        self.assertIsNone(proof);self.assertEqual(sides,[dict(side='single',source_sha=NEW)])
        self.assertEqual([{k:v for k,v in row.items() if k not in {'side','source_sha'}} for row in matrix],custom)
        self.assertEqual(matrix[0]['source_sha'],NEW)
        storage=WORKFLOW['jobs']['storage_it']
        self.assertIn("|| matrix.simple",storage['name'])
        self.assertEqual(storage['env']['B_PAYMENT_INTENT_DDL_APPROVED'],"${{ matrix.simple == 'WechatPaymentIntentMySqlIT' && '1' || '' }}")
        self.assertEqual(WORKFLOW['jobs']['compare']['if'],"always() && needs.plan.result == 'success' && inputs.old_source_sha != ''")
    def test_pair_rejects_other_batch_or_subsets(self):
        code=python_code(step('plan','Validate optional M1 pair identities and whole original batch'))
        for env in [dict(BASE_ENV,BATCH='architecture-day-topic-copy'),dict(BASE_ENV,CLASSES='PlayHintUnlockMySqlIT')]:
            with patch.dict(os.environ,env,clear=True),self.assertRaises(SystemExit):exec(code,{})
    def provenance(self,mutator=None,storage='success',summary='success'):
        with tempfile.TemporaryDirectory(prefix='c-m1-provenance-') as temporary,cwd(temporary):
            Path('gate/ci').mkdir(parents=True);Path('gate/ci/real_storage_it.py').write_bytes(source('ci/real_storage_it.py'))
            # Synthetic provenance records only; they omit XML/trace/CLEAN, cannot pass the frozen comparator.
            records=[]
            for side,sha in [('old',OLD),('new',NEW)]:
                for cls in CLASSES:
                    r=dict(source_sha=sha,batch='architecture-day-lead-admission',class_=cls)
                    r['class']=r.pop('class_');r['workflow']=dict(run_id=BASE_ENV['GITHUB_RUN_ID'],run_attempt='2')
                    r['env_profile']={'CHENGYIN_MYBATIS_IT_DB_SUFFIX':'_gh022063a2'};records.append((side,cls,r))
            if mutator:mutator(records)
            for index,(side,cls,record) in enumerate(records):
                path=Path(side+'-receipts','artifact'+str(index),'receipt.json');path.parent.mkdir(parents=True);path.write_text(json.dumps(record))
            env=dict(BASE_ENV,STORAGE_RESULT=storage,SUMMARY_RESULT=summary)
            with patch.dict(os.environ,env,clear=True):exec(python_code(step('compare','Require four receipts from this exact run/attempt and original suffix')), {})
    def test_exact_same_run_attempt_suffix_provenance(self):self.provenance()
    def test_cross_source_run_attempt_suffix_or_missing_class_is_rejected(self):
        changes=[lambda r:r[0][2].update(source_sha=NEW),lambda r:r[0][2]['workflow'].update(run_id='40050022064'),
                 lambda r:r[0][2]['workflow'].update(run_attempt='1'),lambda r:r[0][2]['env_profile'].update(CHENGYIN_MYBATIS_IT_DB_SUFFIX='_gh022064a2'),lambda r:r.pop()]
        for change in changes:
            with self.assertRaises(SystemExit):self.provenance(change)
        for kwargs in [dict(storage='failure'),dict(summary='failure')]:
            with self.assertRaises(SystemExit):self.provenance(**kwargs)
    def test_artifacts_and_summaries_are_source_side_and_attempt_separate(self):
        for job in ['storage_it','summary']:
            upload=next(s for s in WORKFLOW['jobs'][job]['steps'] if s.get('uses')=='actions/upload-artifact@v4')
            for marker in ['matrix.side','matrix.source_sha','github.run_attempt']:self.assertIn(marker,upload['with']['name'])
        download=next(s for s in WORKFLOW['jobs']['summary']['steps'] if s.get('uses')=='actions/download-artifact@v4')
        for marker in ['matrix.side','matrix.source_sha','github.run_attempt']:self.assertIn(marker,download['with']['pattern'])
        self.assertNotIn('merge-multiple',download['with'])
        env=step('summary','Summarize batch (fail-closed)')['env']
        self.assertEqual(env['SOURCE_SHA'],'${{ matrix.source_sha }}');self.assertEqual(WORKFLOW['jobs']['summary']['strategy']['matrix']['include'],'${{ fromJSON(needs.plan.outputs.sides) }}')
        compare=step('compare','Frozen strict canonical comparison, both original 14 XML/CLEAN sets required')
        self.assertIn('compare_receipts.py',compare['run']);self.assertNotIn('--manifest',compare['run'])
        frozen=next(s for s in WORKFLOW['jobs']['compare']['steps'] if s.get('name','').startswith('Checkout frozen'))
        self.assertEqual(frozen['with']['ref'],'1c138a000387583e224c9acb5369140fdf2dba18')
if __name__=='__main__':unittest.main(verbosity=2)
