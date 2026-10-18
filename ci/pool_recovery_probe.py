#!/usr/bin/env python3
"""One bounded read-only pool probe inside the existing disposable startup fixture."""
import concurrent.futures,hashlib,json,os,re,select,subprocess,sys,time,uuid
from pathlib import Path
SCHEMA='upgrade_startup_probe'
D=['docker','--context','default']
CLIENT_STDERR=[]
def require(ok,why):
 if not ok:raise RuntimeError(why)
def scrub_stderr(data):
 text=data.decode('utf-8',errors='replace')if isinstance(data,bytes)else str(data or '')
 text=re.sub(r'(?im)^.*\b(?:authorization|cookie|set-cookie)\s*[:=].*$', '[REDACTED_HEADER]',text)
 text=re.sub(r'(?i)((?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key)\s*[:=]\s*)[^\s,;]+',r'\1[REDACTED]',text)
 text=re.sub(r'(https?://)[^/\s@]+@',r'\1[REDACTED]@',text)
 return text.encode('utf-8')[:4096].decode('utf-8',errors='ignore')
def native_pool_timeout_wait(log):
 match=re.search(r'com\.alibaba\.druid\.pool\.GetConnectionTimeoutException: wait millis (\d+), active (\d+), maxActive (\d+)(?:,|$)',log,re.M)
 require(match is not None,'native Druid pool-timeout evidence missing')
 wait,active,maximum=map(int,match.groups())
 require(5000<=wait<=6000 and active==20 and maximum==20,'unexpected native Druid timeout wait or pool values')
 return wait
def command(args,timeout=10):
 try:return subprocess.check_output(args,timeout=timeout,text=True,stderr=subprocess.PIPE)
 except (subprocess.CalledProcessError,subprocess.TimeoutExpired)as exc:
  remaining=4096-sum(len(t.encode('utf-8'))for t in CLIENT_STDERR)
  if remaining>0:CLIENT_STDERR.append(scrub_stderr(exc.stderr).encode('utf-8')[:remaining].decode('utf-8',errors='ignore'))
  raise
def validate_targets(name,endpoint,objects,network):
 require(re.fullmatch(r'cy-startup-\d+-\d+',name) is not None,'not an owned startup run')
 require(endpoint=='unix:///var/run/docker.sock','not hosted runner local Docker')
 require(network['Name']==name and network['Internal'] is True,'not owned internal network')
 require(len(objects)==3,'expected three owned objects')
 for suffix,obj in zip(['app','mysql','redis'],objects):
  require(obj['Name']=='/'+name+'-'+suffix and re.fullmatch('[0-9a-f]{64}',obj['Id']) is not None,'wrong owned object')
  require(set(obj['NetworkSettings']['Networks'])=={name},'unexpected object network')
  require(obj['NetworkSettings']['Networks'][name]['NetworkID']==network['Id'],'wrong network identity')
  require(obj['State']['Running'] and not obj['State']['Paused'] and not obj['State']['OOMKilled'],'object not healthy')
 cmd=objects[0]['Config']['Cmd']
 for token in ['--spring.datasource.druid.maxActive=20','--spring.datasource.druid.maxWait=5000','--spring.datasource.druid.socketTimeout=60000']:
  require(cmd.count(token)==1,'missing or duplicate isolated pool profile')
def validate_execution_env(env):
 require(sys.platform.startswith('linux'),'actual Linux host required')
 for key,value in [('GITHUB_ACTIONS','true'),('RUNNER_OS','Linux'),('RUNNER_ENVIRONMENT','github-hosted'),('GITHUB_WORKFLOW','Isolated pool recovery'),('GITHUB_JOB','isolated-pool-recovery'),('GITHUB_REPOSITORY','liboyang42-cpu/hengyin'),('CHENGYIN_CI_POOL_RECOVERY','true')]:
  require(env.get(key)==value,'wrong hosted pool workflow scope: '+key)
 require(not env.get('DOCKER_HOST') and not env.get('DOCKER_CONTEXT'),'Docker endpoint environment overrides forbidden')
 require(env.get('GITHUB_EVENT_NAME') in ('pull_request','workflow_dispatch'),'unsupported pool event')
 require(env.get('GITHUB_WORKFLOW_REF','').startswith('liboyang42-cpu/hengyin/.github/workflows/isolated-pool-recovery.yml@refs/'),'wrong workflow file')
def main():
 validate_execution_env(os.environ)
 name,out=sys.argv[1:];out=Path(out);require(out.is_dir(),'existing startup evidence directory required')
 objects=json.loads(command(D+['inspect',name+'-app',name+'-mysql',name+'-redis']))
 network=json.loads(command(D+['network','inspect',name]))[0]
 endpoint=command(D+['context','inspect','--format','{{.Endpoints.docker.Host}}']).strip()
 validate_targets(name,endpoint,objects,network)
 app,db,redis=[o['Id']for o in objects];initial=[{k:o['State'][k]for k in ['Pid','Running','Paused','OOMKilled']}for o in objects]
 nonce='qa_pool_'+uuid.uuid4().hex+'_';holder=None;connection=None;workers=None;holder_stderr_collected=False;report={'dedicatedWorkflowOnly':True,'eventName':os.environ['GITHUB_EVENT_NAME'],'readOnlyBusinessPath':'POST /api/common/dict','nonce':nonce,'targets':[{'id':o['Id'],'name':o['Name']}for o in objects],'initialState':initial,'profile':{'maxActive':20,'maxWaitMs':5000,'socketTimeoutMs':60000,'connectTimeoutMs':5000},'notCapacityOrLatencySlaAcceptance':True}
 def sql(s):return command(D+['exec',db,'mysql','-uroot','-N','--batch','--raw',SCHEMA,'-e',s],timeout=5).strip()
 def holder_owned_count():
  return sql(f"SELECT COUNT(*) FROM information_schema.processlist WHERE ID={connection} AND USER='root' AND DB='{SCHEMA}' AND LOCATE('{nonce}',COALESCE(INFO,''))>0")
 def snapshot():
  # The original tiny startup fixture contains one row in each table.
  a=sql('SELECT @@server_uuid; CHECKSUM TABLE sys_config,sys_dict_data EXTENDED;')
  return {'uuid':a.splitlines()[0],'mysqlChecksumAndUuidOutputSha256':hashlib.sha256(a.encode()).hexdigest()}
 def read(i):
  start=time.monotonic();key=nonce+str(i)
  raw=command(D+['exec',app,'curl','--noproxy','*','--silent','--show-error','--max-time','20','--data-urlencode','dictType='+key,'--write-out','\n%{http_code}','http://127.0.0.1:8080/api/common/dict'],timeout=23)
  body,http=raw.rsplit('\n',1);data=json.loads(body)
  return {'request':i,'http':int(http),'businessCode':data.get('code'),'emptyData':data.get('data')==[],'elapsedSeconds':round(time.monotonic()-start,3)}
 def same_normal(r):require(r['http']==200 and r['businessCode']==200 and r['emptyData'],'normal independent dictionary read failed')
 def safe_write():
  (out/'pool-recovery.json').write_text(json.dumps(report,indent=2)+'\n')
 try:
  report['before']=snapshot();report['baseline']=read('before');same_normal(report['baseline'])
  require(command(D+['exec',redis,'redis-cli','EXISTS','sys_dict:'+nonce+'before']).strip()=='0','test key unexpectedly cached')
  holder_sql=f"SELECT CONNECTION_ID(); LOCK TABLES sys_dict_data WRITE /* {nonce} */; SELECT 'LOCK_ACQUIRED'; DO SLEEP(12) /* {nonce} */; UNLOCK TABLES; SELECT 'LOCK_RELEASED';\n"
  holder=subprocess.Popen(D+['exec','-i',db,'timeout','18','mysql','-uroot','-N','--batch','--raw','--unbuffered','--comments',SCHEMA],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,bufsize=0)
  holder.stdin.write(holder_sql.encode());holder.stdin.close();holder.stdin=None
  def line():
   data=bytearray();end=time.monotonic()+5
   while len(data)<128:
    require(select.select([holder.stdout],[],[],max(0,end-time.monotonic()))[0],'holder acknowledgement timeout')
    b=os.read(holder.stdout.fileno(),1);require(bool(b),'holder closed before acknowledgement')
    if b==b'\n':return data.decode().strip()
    data.extend(b)
   raise RuntimeError('holder acknowledgement too long')
  first=line();require(first.isdigit(),'invalid owned holder connection ID');connection=int(first)
  require(line()=='LOCK_ACQUIRED','holder did not acquire table lock');report['holderConnectionId']=connection
  ownership=[];deadline=time.monotonic()+2
  while time.monotonic()<deadline:
   ownership.append(int(holder_owned_count()))
   if ownership[-1]==1:break
   time.sleep(.05)
  report['holderOwnershipCounts']=ownership
  require(bool(ownership) and ownership[-1]==1,'owned holder nonce not observable in processlist')
  report['holderOwnershipConfirmedBeforeRequests']=True
  workers=concurrent.futures.ThreadPoolExecutor(max_workers=20);requests=[workers.submit(read,i)for i in range(20)]
  blocked_sql=("SELECT COUNT(*) FROM information_schema.processlist WHERE USER='startup_probe' AND DB='"+SCHEMA+"' AND STATE IN ('Waiting for table metadata lock','Waiting for table level lock') AND LOCATE('"+nonce+"',COALESCE(INFO,''))>0 AND LOCATE('sys_dict_data',COALESCE(INFO,''))>0")
  samples=[];deadline=time.monotonic()+3
  while time.monotonic()<deadline:
   count=int(sql(blocked_sql));samples.append(count)
   if count==20:break
   time.sleep(.1)
  report['blockedSelectCounts']=samples;require(samples[-1]==20,'twenty real SQL reads were not blocked before deadline')
  report['overflowRequest']=read(20)
  require(report['overflowRequest']['businessCode']==500,'overflow did not fail closed with native business error')
  log=command(D+['logs',app],timeout=10)
  report['nativeDruidTimeoutWaitMs']=native_pool_timeout_wait(log)
  report['nativeDruidTimeoutObserved']=True
  rest,err=holder.communicate(timeout=15)
  report['holderStderr']=scrub_stderr(err);holder_stderr_collected=True
  require(holder.returncode==0 and b'LOCK_RELEASED' in rest,'normal holder release not confirmed')
  report['holderReleaseAcknowledged']=True
  report['heldRequests']=[f.result(timeout=3)for f in requests]
  for r in report['heldRequests']:same_normal(r)
  require(int(sql(blocked_sql))==0,'remaining own blocked SQL reads')
  report['recovery']=[read('after'+str(i))for i in range(3)]
  for r in report['recovery']:same_normal(r)
  report['after']=snapshot();require(report['before']==report['after'],'UUID or full table checksum changed')
  after=json.loads(command(D+['inspect',app,db,redis]));states=[{k:o['State'][k]for k in ['Pid','Running','Paused','OOMKilled']}for o in after]
  report['finalStateBeforeOuterCleanup']=states;require(states==initial,'process restart, dependency loss or OOM observed')
  report['result']='PASS_BOUNDED_POOL_TIMEOUT_RELEASE_SAME_PROCESS_RECOVERY'
 except Exception as exc:
  report['failure']={'type':type(exc).__name__,'message':str(exc)[:400]};raise
 finally:
  # Independent root session never borrows an application connection or the locked table.
  if holder is not None:
   try:
    if connection is not None:
     own=holder_owned_count()
     if own=='1':sql(f'KILL CONNECTION {connection}')
    holder.wait(timeout=3)
    if connection is not None:
     require(sql(f'SELECT COUNT(*) FROM information_schema.processlist WHERE ID={connection}')=='0','owned holder still connected')
   except Exception as exc:
    report['holderCleanupUnconfirmed']=True
    report['holderCleanupFailure']={'type':type(exc).__name__,'message':scrub_stderr(str(exc))}
  if workers is not None:workers.shutdown(wait=True,cancel_futures=True)
  if holder is not None and not holder_stderr_collected:
   try:
    if holder.poll() is not None:
     _,err=holder.communicate(timeout=1)
    else:
     os.set_blocking(holder.stderr.fileno(),False)
     try:err=os.read(holder.stderr.fileno(),4096)
     except BlockingIOError:err=b''
    report['holderStderr']=scrub_stderr(err)
   except (OSError,ValueError,subprocess.TimeoutExpired):report['holderStderrUnavailable']=True
  try:
   latest=json.loads(command(D+['inspect',app,db,redis]));report['independentStatesBeforeOuterCleanup']=[{'id':o['Id'],'state':{k:o['State'][k]for k in ['Pid','Running','Paused','OOMKilled','ExitCode','FinishedAt']}}for o in latest]
  except Exception:report['independentStateUnknown']=True
  if report.get('holderCleanupUnconfirmed')or report.get('independentStateUnknown'):report['result']='FAIL_UNKNOWN_CLEANUP_OR_STATE'
  report['clientStderr']=scrub_stderr('\n'.join(CLIENT_STDERR))
  safe_write()
 require(report.get('result')=='PASS_BOUNDED_POOL_TIMEOUT_RELEASE_SAME_PROCESS_RECOVERY','pool probe failed; preserve evidence before outer cleanup')
if __name__=='__main__':
 if sys.argv[1:]==['--check-execution-only']:validate_execution_env(os.environ)
 else:main()
