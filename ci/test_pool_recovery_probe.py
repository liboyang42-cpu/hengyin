"""Pure ownership/profile negative control; never contacts Docker or HTTP."""
import copy,importlib.util,pathlib,unittest
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
if __name__=='__main__':unittest.main()
