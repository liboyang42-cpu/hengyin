"""Pure ownership/profile negative control; never contacts Docker or HTTP."""
import contextlib,copy,hashlib,importlib.util,io,json,os,pathlib,re,subprocess,tempfile,unittest
from unittest import mock
p=pathlib.Path(__file__).with_name('pool_recovery_probe.py')
spec=importlib.util.spec_from_file_location('probe',p);probe=importlib.util.module_from_spec(spec);spec.loader.exec_module(probe)
class OwnedPoolProfileTest(unittest.TestCase):
 def test_only_exact_hosted_objects_and_explicit_profile_are_accepted(self):
  name='cy-startup-12-34';network={'Name':name,'Internal':True,'Id':'n'};objects=[]
  for i,suffix in enumerate(['app','mysql','redis']):
   objects.append({'Id':str(i)*64,'Name':'/'+name+'-'+suffix,'NetworkSettings':{'Networks':{name:{'NetworkID':'n'}}},'State':{'Running':True,'Paused':False,'OOMKilled':False},'Config':{'Cmd':['--spring.datasource.druid.maxActive=20','--spring.datasource.druid.maxWait=5000','--spring.datasource.druid.socketTimeout=60000']}})
  probe.validate_targets(name,'unix:///var/run/docker.sock',objects,network)
  for kind in ['foreign-name','socket-preemption','oom','non-internal']:
   with self.subTest(kind=kind):
    altered=copy.deepcopy(objects);net=copy.deepcopy(network)
    if kind=='foreign-name':altered[1]['Name']='/persistent-uat-mysql'
    elif kind=='socket-preemption':altered[0]['Config']['Cmd'][-1]='--spring.datasource.druid.socketTimeout=5000'
    elif kind=='oom':altered[1]['State']['OOMKilled']=True
    else:net['Internal']=False
    with self.assertRaises(RuntimeError):probe.validate_targets(name,'unix:///var/run/docker.sock',altered,net)
 def test_execution_environment_rejects_endpoint_override_and_wrong_host(self):
  env={'GITHUB_ACTIONS':'true','RUNNER_OS':'Linux','RUNNER_ENVIRONMENT':'github-hosted','GITHUB_WORKFLOW':'Isolated pool recovery','GITHUB_JOB':'isolated-pool-recovery','GITHUB_REPOSITORY':'liboyang42-cpu/hengyin','CHENGYIN_CI_POOL_RECOVERY':'true','GITHUB_EVENT_NAME':'pull_request','GITHUB_WORKFLOW_REF':'liboyang42-cpu/hengyin/.github/workflows/isolated-pool-recovery.yml@refs/pull/69/merge'}
  with mock.patch.object(probe.sys,'platform','linux'):
   probe.validate_execution_env(env)
   probe.validate_execution_env(dict(env,GITHUB_EVENT_NAME='workflow_dispatch'))
   for key,value in [('DOCKER_HOST','ssh://foreign'),('DOCKER_CONTEXT','chengyin-vps-sandbox'),('RUNNER_ENVIRONMENT','self-hosted'),('GITHUB_JOB','backend')]:
    with self.subTest(key=key),self.assertRaises(RuntimeError):probe.validate_execution_env(dict(env,**{key:value}))
 def test_native_stderr_keeps_error_but_is_bounded_and_redacted(self):
  raw='ERROR 1142 LOCK denied\npassword=canary\nAuthorization: Bearer canary\nhttps://user:canary@localhost/path\n'+'x'*9000
  clean=probe.scrub_stderr(raw)
  self.assertIn('ERROR 1142 LOCK denied',clean)
  self.assertNotIn('canary',clean)
  self.assertLessEqual(len(clean.encode('utf-8')),4096)
  probe.CLIENT_STDERR.clear()
  error=probe.subprocess.CalledProcessError(1,['mysql'],stderr=raw)
  with mock.patch.object(probe.subprocess,'check_output',side_effect=error),self.assertRaises(probe.subprocess.CalledProcessError):probe.command(['mysql'])
  self.assertEqual(probe.CLIENT_STDERR,[clean])
 def test_native_druid_exception_requires_expected_pool_and_bounded_wait(self):
  actual='Caused by: com.alibaba.druid.pool.GetConnectionTimeoutException: wait millis 5001, active 20, maxActive 20, creating 0'
  self.assertEqual(probe.native_pool_timeout_wait(actual),5001)
  for bad in [actual.replace('GetConnectionTimeoutException','SQLTimeoutException'),actual.replace('active 20','active 19'),actual.replace('maxActive 20','maxActive 21'),actual.replace('millis 5001','millis 1000'),actual.replace('millis 5001','millis 10000')]:
   with self.subTest(log=bad),self.assertRaises(RuntimeError):probe.native_pool_timeout_wait(bad)
class PoolOuterEvidenceTest(unittest.TestCase):
 """Execute the real embedded protocols with Docker/Git doubles, never Java/DB."""
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
  self.out=pathlib.Path(self.temp.name);self.jar=self.out/'candidate.jar';self.jar.write_bytes(b'fixture-jar-not-compiled')
  self.root=p.parent.parent;self.name='cy-startup-12-34';self.source='a'*40;self.tree='b'*40
  self.env={'GITHUB_SHA':self.source,'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'2'}
  self.shell=p.with_name('upgrade-startup-smoke.sh').read_text()
  self.workflow=(self.root/'.github/workflows/isolated-pool-recovery.yml').read_text()
  self.calls=[];self.inventory_counts={};self.fault=None
  self.objects={}
  for i,suffix in enumerate(['app','mysql','redis','trace-check'],1):
   self.objects[str(i)*64]={'Id':str(i)*64,'Name':'/'+self.name+'-'+suffix,
    'Config':{'Labels':{'com.chengyin.pool-recovery':self.name}},
    'Mounts':[{'Destination':'/application.jar','Type':'bind','Source':str(self.jar.resolve()),'RW':False}]}
  self.networks={'5'*64:{'Id':'5'*64,'Name':self.name,'Internal':True,'Labels':{'com.chengyin.pool-recovery':self.name}}}
  self.objects['6'*64]={'Id':'6'*64,'Name':'/unrelated-service','Config':{'Labels':{}}}
 def block(self,marker):
  return self.shell.split("<<'"+marker+"'\n",1)[1].split('\n'+marker,1)[0]
 def fake_run(self,args,**kwargs):
  self.calls.append(args)
  if args[0]=='git':
   return subprocess.CompletedProcess(args,0,self.tree if args[-1]=='HEAD^{tree}' else self.source,'')
  self.assertEqual(args[:3],['docker','--context','default']);cmd=args[3:]
  if cmd[0]=='exec':
   digest=hashlib.sha256(self.jar.read_bytes()).hexdigest()
   return subprocess.CompletedProcess(args,0,digest+'  /application.jar\n','')
  kind,operation=cmd[:2];objects=self.objects if kind=='container' else self.networks
  if operation=='ls':
   self.inventory_counts[kind]=self.inventory_counts.get(kind,0)+1
   if self.fault=='after-inventory' and kind=='container' and self.inventory_counts[kind]==2:
    raise subprocess.CalledProcessError(1,args,stderr='daemon unavailable')
   key='Names' if kind=='container' else 'Name'
   value='\n'.join(json.dumps({'ID':v['Id'],key:v['Name'].lstrip('/')})for v in objects.values())
  elif operation=='inspect':
   target=cmd[-1];value=json.dumps([v for k,v in objects.items()if k==target or v['Name'].lstrip('/')==target])
  elif operation=='rm':
   target=cmd[-1]
   if self.fault=='remove' and target=='1'*64:raise subprocess.CalledProcessError(1,args,stderr='remove denied')
   del objects[target];value=target
  else:self.fail('unexpected command: '+repr(args))
  return subprocess.CompletedProcess(args,0,value,'')
 def execute(self,marker,argv):
  with mock.patch.object(subprocess,'run',side_effect=self.fake_run),mock.patch.dict(os.environ,self.env),mock.patch.object(__import__('sys'),'argv',argv):
   exec(compile(self.block(marker),marker,'exec'),{'__name__':'__protocol_fixture__'})
 def identity(self,phase):
  self.execute('PY_POOL_IDENTITY',['fixture',str(self.root),str(self.jar),str(self.out),self.name,phase])
  return json.loads((self.out/'pool-execution.json').read_text())
 def cleanup(self,original=0):
  self.execute('PY_POOL_CLEANUP',['fixture',str(self.out),self.name,str(original)])
  return json.loads((self.out/'pool-cleanup.json').read_text())
 def test_runner_and_exact_read_only_mounted_jar_are_bound_before_and_after(self):
  before=self.identity('before');self.identity('running');after=self.identity('after')
  self.assertEqual(after['result'],'PASS_SOURCE_JAR_UNCHANGED')
  self.assertEqual(after['sourceSha'],self.source);self.assertEqual(after['sourceTree'],self.tree)
  self.assertEqual((after['runId'],after['runAttempt']),('123','2'))
  for key in ['jarRunning','jarAfter','jarMountedRunning','jarMountedAfter']:self.assertEqual(after[key],before['jarBefore'])
 def test_changed_source_attempt_jar_mount_or_container_cannot_claim_same_execution(self):
  for fault in ['source','attempt','jar','writable','container']:
   with self.subTest(fault=fault):
    (self.out/'pool-execution.json').unlink(missing_ok=True)
    self.env={'GITHUB_SHA':self.source,'GITHUB_RUN_ID':'123','GITHUB_RUN_ATTEMPT':'2'}
    self.jar.write_bytes(b'fixture-jar-not-compiled');self.objects['1'*64]['Mounts'][0]['RW']=False
    self.identity('before');self.identity('running')
    if fault=='source':self.env['GITHUB_SHA']='c'*40
    elif fault=='attempt':self.env['GITHUB_RUN_ATTEMPT']='3'
    elif fault=='jar':self.jar.write_bytes(b'other-jar')
    elif fault=='writable':self.objects['1'*64]['Mounts'][0]['RW']=True
    else:
     report=json.loads((self.out/'pool-execution.json').read_text());report['appContainerId']='9'*64
     (self.out/'pool-execution.json').write_text(json.dumps(report))
    with self.assertRaises(AssertionError):self.identity('after')
    self.assertEqual(json.loads((self.out/'pool-execution.json').read_text())['result'],'FAIL_EXECUTION_IDENTITY')
 def test_only_owned_ids_are_removed_and_unmeasured_connect_timeout_is_unknown(self):
  receipt=self.out/'pool-recovery.json';receipt.write_text(json.dumps({'profile':{'connectTimeoutMs':5000,'maxWaitMs':5000}}))
  result=self.cleanup(7)
  self.assertEqual(result['originalExitCode'],7);self.assertEqual(result['result'],'PASS_OWNED_RESOURCES_ABSENT')
  self.assertEqual(result['remaining'],[]);self.assertEqual(result['errors'],[])
  self.assertEqual(len(result['removed']),5);self.assertEqual(set(self.objects),{'6'*64});self.assertEqual(self.networks,{})
  value=json.loads(receipt.read_text());self.assertEqual(value['profile'],{'maxWaitMs':5000})
  self.assertEqual(value['connectTimeoutObservation'],'UNKNOWN_NOT_MEASURED')
 def test_cleanup_refuses_foreign_label_and_preserves_foreign_resource(self):
  self.objects['1'*64]['Config']['Labels']['com.chengyin.pool-recovery']='other-task'
  with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):self.cleanup()
  result=json.loads((self.out/'pool-cleanup.json').read_text())
  self.assertEqual(result['result'],'FAIL_OWNED_CLEANUP');self.assertIn('1'*64,self.objects)
  self.assertEqual(set(self.objects),{'1'*64,'6'*64});self.assertEqual(len(result['removed']),4)
 def test_remove_failure_or_failed_after_inventory_cannot_prove_absence(self):
  for fault in ['remove','after-inventory']:
   with self.subTest(fault=fault):
    # Fresh state per fault; no Docker daemon is contacted.
    self.inventory_counts={};self.fault=fault
    if '1'*64 not in self.objects:
     self.objects['1'*64]={'Id':'1'*64,'Name':'/'+self.name+'-app','Config':{'Labels':{'com.chengyin.pool-recovery':self.name}}}
    with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):self.cleanup(9)
    result=json.loads((self.out/'pool-cleanup.json').read_text())
    self.assertEqual(result['result'],'FAIL_OWNED_CLEANUP');self.assertEqual(result['originalExitCode'],9)
    self.assertTrue(result['errors'])
 def test_corrupt_receipt_still_cleans_owned_objects_but_fails_evidence(self):
  (self.out/'pool-recovery.json').write_text('{')
  with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):self.cleanup()
  self.assertEqual(set(self.objects),{'6'*64});self.assertFalse(self.networks)
  result=json.loads((self.out/'pool-cleanup.json').read_text());self.assertEqual(result['result'],'FAIL_OWNED_CLEANUP')
 def test_exit_trap_preserves_original_failure_and_fails_new_cleanup_fault(self):
  body=re.search(r'^cleanup\(\) \{\n.*?^\}',self.shell,re.M|re.S).group(0)
  for original,identity,cleanup,expected in [(0,0,0,0),(0,1,0,1),(0,0,1,1),(7,1,1,7)]:
   with self.subTest(original=original,identity=identity,cleanup=cleanup):
    # Stubs are shell functions; this test never invokes a Docker executable.
    script='set -euo pipefail\nOUT=$1; RUN_NAME=cy-startup-12-34; CHENGYIN_CI_POOL_RECOVERY=true\n'+'docker() { return 0; }\n'+f'pool_execution_evidence() {{ return {identity}; }}\npool_owned_cleanup() {{ echo "$1" > "$OUT/original-exit"; return {cleanup}; }}\n'+body+'\ntrap cleanup EXIT\n'+f'(exit {original})\n'
    result=subprocess.run(['bash','-c',script,'fixture',str(self.out)],capture_output=True,text=True)
    self.assertEqual(result.returncode,expected,result.stderr)
    self.assertEqual((self.out/'original-exit').read_text().strip(),str(original))
 def test_workflow_requires_same_identity_cleanup_and_corrected_profile(self):
  self.identity('before');self.identity('running');identity=self.identity('after');cleanup=self.cleanup()
  receipt={'result':'PASS_BOUNDED_POOL_TIMEOUT_RELEASE_SAME_PROCESS_RECOVERY','profile':{},'connectTimeoutObservation':'UNKNOWN_NOT_MEASURED','nativeDruidTimeoutWaitMs':5001,'overflowRequest':{'http':200,'businessCode':500}}
  block=self.workflow.split("<<'PY'\n",1)[1].split('\n          PY',1)[0]
  code='\n'.join(line[10:]for line in block.splitlines())
  def check():
   with mock.patch.dict(os.environ,self.env),mock.patch.object(__import__('sys'),'argv',['fixture',str(self.out/'pool-recovery.json')]),mock.patch.object(subprocess,'check_output',return_value=self.tree),contextlib.redirect_stdout(io.StringIO()):
    exec(compile(code,'workflow-evidence-check','exec'),{})
  for fault in [None,'attempt','jar','cleanup','missing','profile']:
   with self.subTest(fault=fault):
    i,c,r=copy.deepcopy(identity),copy.deepcopy(cleanup),copy.deepcopy(receipt)
    if fault=='attempt':i['runAttempt']='99'
    elif fault=='jar':i['jarMountedAfter']['sha256']='0'*64
    elif fault=='cleanup':c['remaining']=[{'name':self.name}]
    elif fault=='profile':r['profile']['connectTimeoutMs']=5000
    (self.out/'pool-execution.json').write_text(json.dumps(i));(self.out/'pool-cleanup.json').write_text(json.dumps(c));(self.out/'pool-recovery.json').write_text(json.dumps(r))
    if fault=='missing':(self.out/'pool-cleanup.json').unlink()
    if fault:
     with self.assertRaises((AssertionError,FileNotFoundError)):check()
    else:check()

if __name__=='__main__':unittest.main()
