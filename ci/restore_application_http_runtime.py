"""Fixed application-slot reuse for the authenticated owner, not a provisioner.

No network/MySQL/Redis creation, published port, configurable command/profile/image or cleanup
of resources. The outer authenticated owner provisions/removes its exact four slots.
"""
from __future__ import annotations
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
CHILD = 'com/chengyinhub/business/it/RestoreHttpApplicationChild'
MAIN = CHILD.replace('/', '.')
JDK = 'maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237'
PATHS = {'/captchaImage','/login','/getInfo','/api/wallet/balance','/api/wallet/withdraw',
         '/api/wallet/withdraws','/business/refund','/monitor/job/run','/api/club/lead/unlock-chapter',
         '/api/club/lead/unlock-chapter-receipt','/__restore_http/evidence','/__restore_http/notice',
         '/__restore_http/verified-callback'}
LOG_MARKERS = ('Application run failed', 'Started RuoYiApplication', 'RECOVERY_DISPATCH_BLOCKED',
               'CONTROLLED_PROVIDER_NO_SEND')
LOG_EXCEPTION_TYPES = ('org.springframework.beans.factory.BeanCreationException',
        'org.springframework.beans.factory.UnsatisfiedDependencyException',
        'org.springframework.jdbc.BadSqlGrammarException', 'java.sql.SQLSyntaxErrorException',
        'com.mysql.cj.jdbc.exceptions.CommunicationsException', 'java.lang.IllegalStateException',
        'java.lang.IllegalArgumentException', 'java.lang.NoSuchMethodError')
REASON_CODES = {
    'real CAPTCHA required': 'CAPTCHA_CONTRACT_REJECTED',
    'original private CAPTCHA oracle': 'CAPTCHA_ORACLE_REJECTED',
    'real admin login/filter required': 'ADMIN_LOGIN_REJECTED',
    'app stop/process exit unknown': 'APP_STOP_EXIT_NOT_CONFIRMED',
    'complete Spring child exited before readiness': 'APP_EXITED_BEFORE_READY',
    'bounded complete application startup timeout': 'APP_READINESS_TIMEOUT',
    'actual child boot identity': 'APP_BOOT_IDENTITY_REJECTED',
}


def require(ok, reason):
    if not ok:
        failure=ValueError(reason)
        failure.restore_reason_code=REASON_CODES.get(reason,'INVARIANT_REJECTED')
        raise failure


def summarize_application_log(raw):
    """Only fixed markers/types, never lines, exception messages or HTTP/SQL contents."""
    sample=raw[-1024*1024:];text=sample.decode(errors='replace')
    return {'sampleBytes':len(sample),'sampleTruncated':len(raw)>len(sample),
            'sampleSha256':hashlib.sha256(sample).hexdigest(),
            'markers':{marker:marker in text for marker in LOG_MARKERS},
            'exceptionTypes':[kind for kind in LOG_EXCEPTION_TYPES if kind in text]}


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


def gate(env):
    auth = load('restore_http_original_auth_gate', 'ci/authenticated_redis_recovery_probe.py')
    auth.validate_execution_env(env)
    require(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('CHENGYIN_CI_RESTORE_HTTP_STARTUP') == 'true', 'manual explicit restore HTTP mode only')
    require(all(env.get(k) in (None, 'false') for k in
                ('CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY','CHENGYIN_CI_CAPACITY_MIXED')), 'exclusive original mode')
    require(re.fullmatch('[0-9a-f]{40}',env.get('GITHUB_SHA','')), 'full source required')
    require(all(re.fullmatch('[1-9][0-9]*',env.get(k,'')) for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')), 'run/attempt required')


def write_private(path, value):
    path = Path(path)
    require(not path.is_symlink(), 'private symlink forbidden')
    temp = path.with_suffix(path.suffix + '.next')
    with temp.open('x') as stream:
        temp.chmod(0o600);json.dump(value,stream,sort_keys=True);stream.flush();os.fsync(stream.fileno())
    os.replace(temp,path)


class ApplicationSlot:
    """Replaces only this task's original app slot between fixed child boots. No resource creation."""
    runtime_report_write=staticmethod(write_private)

    def __init__(self,name,schema,out,credentials):
        gate(os.environ)
        require(re.fullmatch('cy-auth-[0-9a-f]{32}',name) and schema=='cy_e2e_auth_'+name[8:], 'exact original scope')
        self.auth=load('restore_http_original_owner','ci/authenticated_redis_recovery_probe.py')
        self.network=load('restore_http_original_network','ci/upgrade_startup_network.py')
        self.name,self.schema,self.out=name,schema,Path(out).resolve()
        self.credentials=Path(credentials).resolve()
        self.private=self.credentials.parent
        require(not Path(credentials).is_symlink() and Path(credentials).stat().st_mode & 0o777==0o600
                and self.private.stat().st_mode & 0o777==0o700, 'original private credentials')
        self.saved=json.loads(Path(credentials).read_text());require(self.saved['schema']==schema,'original credential scope')
        self.root=self.private/'restore-http';self.root.mkdir(mode=0o700)
        self.jar=ROOT/'chengyinhub-admin/target/chengyinhub-admin.jar'
        self.jar_hash=hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.token_secret=__import__('secrets').token_urlsafe(48)
        self.deadline=time.monotonic()+1500
        self.cid=None;self.boots=[];self.removed=[];self.owned=[]
        self.source=self.auth.mysql_endpoint(name,suffix='mysql')
        self.target=self.auth.mysql_endpoint(name,suffix='restore-mysql')
        require(self.source['id']!=self.target['id'] and self.source['networkId']==self.target['networkId'], 'distinct same-network DBs')
        self.redis=self.inspect(name+'-redis')
        require(self.redis['Config']['Labels'].get('chengyin.auth-recovery')==name
                and set(self.redis['NetworkSettings']['Networks'])=={name}
                and self.redis['NetworkSettings']['Networks'][name]['NetworkID']==self.source['networkId']
                and not self.redis['HostConfig'].get('PortBindings'), 'original Redis ownership')
        self.redis_ip=self.redis['NetworkSettings']['Networks'][name]['IPAddress']
        self.classes=self.root/'classes';destination=self.classes/Path(CHILD).parent;destination.mkdir(parents=True)
        compiled=ROOT/'chengyinhub-admin/target/test-classes'/Path(CHILD).parent
        files=sorted(compiled.glob('RestoreHttpApplicationChild*.class'))
        require(files and any(p.name=='RestoreHttpApplicationChild.class' for p in files), 'same-source compiled child missing')
        self.class_hashes={}
        for path in files:
            require(path.is_file() and not path.is_symlink(), 'compiled child must be regular')
            target=destination/path.name;shutil.copyfile(path,target);target.chmod(0o444)
            self.class_hashes[path.name]=hashlib.sha256(path.read_bytes()).hexdigest()
        self.manifest_hash=hashlib.sha256(json.dumps(self.class_hashes,sort_keys=True).encode()).hexdigest()

    def budget(self):
        require(time.monotonic()<self.deadline,'fixed 1500-second restore application budget')

    def inspect(self,target):
        return json.loads(self.auth.command(self.auth.DOCKER+['inspect',target],timeout=10))[0]

    @contextlib.contextmanager
    def relay(self,side):
        require(side in ('source','target'),'fixed DB side')
        endpoint=self.source if side=='source' else self.target
        suffix='mysql' if side=='source' else 'restore-mysql'
        with self.auth.mysql_relay(self.name,{'endpoint':endpoint},suffix=suffix) as port:
            yield port

    def absence(self):
        result=subprocess.run(self.auth.DOCKER+['container','inspect',self.name+'-app'],capture_output=True,timeout=15)
        self.auth.require_absent('container',self.name+'-app',result.returncode,result.stderr.decode())

    def stop(self,remove=True):
        if self.cid is None:
            self.absence();return
        obj=self.inspect(self.cid)
        require(obj['Name']=='/'+self.name+'-app' and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and set(obj['NetworkSettings']['Networks'])=={self.name},'exact current app owner changed')
        if obj['State']['Running']:
            self.auth.command(self.auth.DOCKER+['stop','--time','20',self.cid],timeout=30)
        stopped=self.inspect(self.cid)['State']
        # Preserve the actual exit observation even when the strict success predicate rejects it.
        self.stopped={'cid':self.cid,'running':stopped['Running'],'hostPid':stopped['Pid'],
                      'oomKilled':stopped['OOMKilled'],'exitCode':stopped['ExitCode'],
                      'restarting':stopped['Restarting']}
        require(not stopped['Running'] and not stopped['OOMKilled'] and not stopped['Restarting']
                and stopped['Pid']==0 and stopped['ExitCode'] in (0,143),'app stop/process exit unknown')
        if not remove:return # Original outer cleanup must still collect this exact container's logs.
        self.auth.command(self.auth.DOCKER+['rm',self.cid],timeout=15)
        self.absence();self.removed.append(self.cid);self.cid=None

    def diagnostic_summary(self):
        """Observe the exact existing app only; outer owner still preserves/removes its raw log."""
        if self.cid is None:
            return {'result':'NO_CURRENT_CID_OBSERVATION','startAbsenceProven':False}
        obj=self.inspect(self.cid)
        require(obj['Id']==self.cid and obj['Name']=='/'+self.name+'-app'
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and set(obj['NetworkSettings']['Networks'])=={self.name}
                and obj['NetworkSettings']['Networks'][self.name]['NetworkID']==self.source['networkId'],
                'exact diagnostic app owner changed')
        state=obj['State']
        result={'result':'EXACT_APP_OBSERVED','cid':self.cid,
                'state':{key:state[key] for key in ('Running','OOMKilled','Restarting','Pid','ExitCode')}}
        log=subprocess.run(self.auth.DOCKER+['logs','--tail','160',self.cid],
                           stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=10)
        if log.returncode:
            result['logSummary']={'result':'UNAVAILABLE','reasonCode':'APP_LOG_COMMAND_FAILED'}
        else:
            result['logSummary']={'result':'FIXED_MARKERS_ONLY',**summarize_application_log(log.stdout)}
        return result

    def start(self,side,mode,uuid,seed=False):
        self.budget();require(side in ('source','target') and mode in ('true','invalid','','false'),'fixed boot matrix')
        require(not seed or (side=='source' and mode=='false' and not self.boots),'one isolated source seed')
        self.stop()
        endpoint=self.source if side=='source' else self.target
        require(self.auth.mysql_endpoint(self.name,suffix='mysql' if side=='source' else 'restore-mysql')==endpoint,'DB identity changed')
        config={'sourceSha':os.environ['GITHUB_SHA'],'schema':self.schema,'scope':self.name[8:],
                'jarSha256':self.jar_hash,'classManifestSha256':self.manifest_hash,
                'jdbcUrl':'jdbc:mysql://'+endpoint['ip']+':3306/'+self.schema+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                'redisHost':self.redis_ip,'tokenSecret':self.token_secret,'serverUuid':uuid,'restoreMode':mode,'seed':seed}
        write_private(self.root/'config.json',config)
        (self.root/'ready.json').unlink(missing_ok=True)
        trace='network-'+str(len(self.boots))+'.trace';strace=shutil.which('strace')
        require(strace and (self.out/'trace-libs').is_dir(),'reuse original tracing preparation')
        # Original immutable app image/network/strace/memory/user/JAR/config. Only the test child and
        # per-process real environment differ. No -D substitute for System.getenv and no external port.
        raw=self.auth.command(self.auth.DOCKER+['run','-d','--name',self.name+'-app','--label','chengyin.auth-recovery='+self.name,
                '--network',self.name,'--dns','127.0.0.1','--memory=1g','--memory-swap=1g','--cpus=1',
                '--read-only','--tmpfs','/tmp:rw,size=256m','--cap-drop','ALL','--cap-add','SYS_PTRACE',
                '--user',str(os.getuid())+':'+str(os.getgid()),'-e','LD_LIBRARY_PATH=/trace-libs',
                '-e','GITHUB_SHA='+os.environ['GITHUB_SHA'],'-e','CHENGYIN_CI_RESTORE_HTTP_STARTUP=true',
                '-e','CHENGYIN_RESTORE_MODE='+mode,
                '-v',strace+':/trace/strace:ro','-v',str(self.out/'trace-libs')+':/trace-libs:ro',
                '-v',str(self.jar)+':/application.jar:ro','-v',str(self.classes)+':/restore-http-testclasses:ro',
                '-v',str(ROOT/'ci/upgrade-startup')+':/fixture:ro','-v',str(self.root)+':/restore-http-private',
                '--entrypoint','/trace/strace',JDK,'-f','-s','0','-e','trace=connect,sendto,sendmsg',
                '-o','/restore-http-private/'+trace,'java','-Xmx512m',
                '-Dlogging.config=/fixture/logback.xml','-Dlogback.configurationFile=/fixture/logback.xml',
                '-Dloader.path=/restore-http-testclasses','-Dloader.main='+MAIN,
                '-cp','/application.jar','org.springframework.boot.loader.launch.PropertiesLauncher'],timeout=120)
        self.cid=raw.decode().strip();require(re.fullmatch('[0-9a-f]{64}',self.cid),'exact created app CID')
        self.owned.append(self.cid)
        obj=self.inspect(self.name+'-app')
        require(obj['Id']==self.cid and obj['Config']['Image']==JDK
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and obj['HostConfig']['Memory']==1073741824 and obj['HostConfig']['NanoCpus']==1000000000
                and set(obj['NetworkSettings']['Networks'])=={self.name}
                and obj['NetworkSettings']['Networks'][self.name]['NetworkID']==self.source['networkId']
                and not obj['HostConfig'].get('PortBindings'),'original app isolation/budget')
        require('CHENGYIN_RESTORE_MODE='+mode in obj['Config']['Env'],'real env mode missing')
        until=time.monotonic()+180
        while time.monotonic()<until:
            self.budget()
            if (self.root/'ready.json').exists():break
            require(self.inspect(self.cid)['State']['Running'],'complete Spring child exited before readiness')
            time.sleep(1)
        require((self.root/'ready.json').is_file(),'bounded complete application startup timeout')
        boot=json.loads((self.root/'ready.json').read_text())
        require(boot['sourceSha']==os.environ['GITHUB_SHA'] and boot['jarSha256']==self.jar_hash
                and boot['serverUuid']==uuid and boot['schema']==self.schema and boot['restoreMode']==mode
                and boot['guardHeld']==(mode!='false') and boot['schedulerStarted'] is True
                and all(boot['bootId']!=old['bootId'] for old in self.boots),'actual child boot identity')
        require(isinstance(boot['javaPid'],int) and boot['javaPid']>0,'child JVM PID')
        cmdline=self.auth.command(self.auth.DOCKER+['exec',self.cid,'cat','/proc/'+str(boot['javaPid'])+'/cmdline'])
        require(MAIN.encode() in cmdline and b'org.springframework.boot.loader.launch.PropertiesLauncher' in cmdline,'actual JVM entry identity')
        boot.update({'appCid':self.cid,'side':side,'trace':trace,'mysqlIp':endpoint['ip']})
        self.boots.append(boot);return boot

    def verify_network(self):
        boot=self.boots[-1]
        self.network.verify((self.root/boot['trace']).read_text(),boot['mysqlIp'],self.redis_ip)

    def http(self,path,token=None,payload=None,form=False,method=None):
        self.budget();base=path.split('?',1)[0]
        require((base in PATHS or re.fullmatch(r'/business/cashwithdraw/(?:requery/)?[1-9][0-9]*',base)) and (not '?' in path or (base.endswith('/unlock-chapter-receipt')
                and re.fullmatch(r'activityId=[0-9]+&commandId=[A-Za-z0-9:_-]{1,80}',path.split('?',1)[1]))),'fixed same-app HTTP paths only')
        method=method or ('POST' if payload is not None else 'GET')
        require(method in ('GET','POST','PUT'),'fixed HTTP methods')
        route=base if base in PATHS else '/business/cashwithdraw/:originalId'
        self.last_http={'route':route,'method':method,'result':'ATTEMPTED'}
        text='url = "http://127.0.0.1:8080'+path+'"\nrequest = "'+method+'"\n'
        if token:text+='header = "Authorization: Bearer '+token+'"\n'
        if payload is not None:
            text+='header = "Content-Type: '+('application/x-www-form-urlencoded' if form else 'application/json')+'"\n'
            data=urllib.parse.urlencode(payload) if form else json.dumps(payload)
            text+='data = '+json.dumps(data)+'\n'
        raw=self.auth.command(self.auth.DOCKER+['exec','-i',self.cid,'curl','--noproxy','*','--silent','--show-error',
                '--max-time','45','--write-out','\n%{http_code}','--config','-'],text.encode(),timeout=50).decode()
        try:
            body,status=raw.rsplit('\n',1);status=int(status)
        except ValueError as failure:
            failure.restore_reason_code='HTTP_STATUS_MALFORMED';raise
        self.last_http.update(status=status,result='STATUS_RECEIVED')
        try:value=json.loads(body)
        except ValueError as failure:
            failure.restore_reason_code='HTTP_RESPONSE_NOT_JSON';raise
        if not isinstance(value,dict):
            failure=ValueError('HTTP response must be an object')
            failure.restore_reason_code='HTTP_RESPONSE_NOT_OBJECT';raise failure
        self.last_http['result']='JSON_OBJECT_RECEIVED'
        if type(value.get('code')) is int:self.last_http['businessCode']=value['code']
        return status,value

    def login_admin(self):
        status,value=self.http('/captchaImage')
        require(status==200 and value.get('code')==200 and value.get('captchaEnabled') is True,'real CAPTCHA required')
        key=self.auth.captcha_key(value.get('uuid'))
        answer=json.loads(self.auth.command(self.auth.DOCKER+['exec',self.redis['Id'],'redis-cli','--raw','GET',key]).decode())
        require(isinstance(answer,str) and answer,'original private CAPTCHA oracle')
        status,value=self.http('/login',payload={'username':'admin','password':self.saved['password'],'uuid':value['uuid'],'code':answer})
        require(status==200 and value.get('code')==200 and value.get('token'),'real admin login/filter required')
        self.admin=value['token'];self.redactions(self.admin,self.token_secret);return self.admin

    def redactions(self,*values):
        self.saved.setdefault('redactions',[]).extend(v for v in values if v not in self.saved.get('redactions',[]))
        write_private(self.credentials,self.saved)
