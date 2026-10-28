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
    "fixed 1500-second restore application budget": "APP_LIFECYCLE_BUDGET_EXHAUSTED",
    "fixed boot matrix": "APP_BOOT_MATRIX_REJECTED",
    "one isolated source seed": "APP_SEED_ADMISSION_REJECTED",
    "exact current app owner changed": "APP_OWNER_REJECTED",
    "original stopped application evidence changed": "APP_STOP_HISTORY_REJECTED",
    "original private application log changed": "APP_PRIVATE_LOG_REJECTED",
    "DB identity changed": "APP_DB_IDENTITY_REJECTED",
    "reuse original tracing preparation": "APP_TRACE_PREPARATION_REJECTED",
    "original app isolation/budget": "APP_ISOLATION_REJECTED",
    "real env mode missing": "APP_MODE_ENV_REJECTED",
    "exclusive private application log": "APP_LOG_PATH_REJECTED",
    "private symlink forbidden": "APP_PRIVATE_PATH_REJECTED",
    "complete application logs unavailable": "APP_LOG_CAPTURE_REJECTED",
    "complete application logs exceed private evidence bound": "APP_LOG_SIZE_REJECTED",
    "every original app log required before private erasure": "APP_LOG_ROSTER_REJECTED",
    "exact original log CID": "APP_LOG_OWNER_REJECTED",
    "exact original stopped CID": "APP_LOG_OWNER_REJECTED",
    'real CAPTCHA required': 'CAPTCHA_CONTRACT_REJECTED',
    'original private CAPTCHA oracle': 'CAPTCHA_ORACLE_REJECTED',
    'real admin login/filter required': 'ADMIN_LOGIN_REJECTED',
    'app stop/process exit unknown': 'APP_STOP_EXIT_NOT_CONFIRMED',
    'complete Spring child exited before readiness': 'APP_EXITED_BEFORE_READY',
    'bounded complete application startup timeout': 'APP_READINESS_TIMEOUT',
    'actual child boot identity': 'APP_BOOT_IDENTITY_REJECTED',
    'child startup rejected': 'APP_CHILD_STARTUP_REJECTED',
}


STARTUP_STAGES={'ADMISSION','SOURCE_IDENTITY','JAR_IDENTITY','ENVIRONMENT_GUARD','SPRING_RUN',
                'HTTP_JSON_CONTRACT','MYBATIS_OBSERVERS','DATASOURCE_IDENTITY','QUARTZ_PROPERTIES','QUARTZ_FACTORY',
                'SCHEDULER_LEADERSHIP','ORIGINAL_IDENTITIES','READY_PUBLISH','READY'}
STARTUP_REASONS={'NONE','ASSERTION_REJECTED','SCHEDULER_LEADERSHIP_TIMEOUT','UNCLASSIFIED_FAILURE'}
STARTUP_FAILURE_TYPES={'NONE','ILLEGAL_STATE','UNSATISFIED_DEPENDENCY','BEAN_CREATION','SQL','IO',
                       'SCHEDULER','INTERRUPTED','OTHER'}


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



def pid1_jvm_proof(raw):
    """Fixed restore JVM only; no process discovery, PID selection or signal forwarding."""
    require(type(raw) is bytes and b'\x00' in raw, 'actual child boot identity')
    command, stat = raw.rsplit(b'\x00', 1)
    argv = command.split(b'\x00')
    require(argv[0].rsplit(b'/', 1)[-1] == b'java'
            and ('-Dloader.main=' + MAIN).encode() in argv
            and b'-Dloader.path=/restore-http-testclasses' in argv
            and b'/application.jar' in argv
            and b'org.springframework.boot.loader.launch.PropertiesLauncher' in argv,
            'actual child boot identity')
    end = stat.rfind(b') ')
    require(stat.startswith(b'1 (') and end > 3, 'actual child boot identity')
    fields = stat[end + 2:].split()
    require(len(fields) >= 20 and fields[1] == b'0'
            and fields[19].isdigit() and int(fields[19]) > 0, 'actual child boot identity')
    return {'cmdlineSha256': hashlib.sha256(command + b'\x00').hexdigest(),
            'startTicks': fields[19].decode('ascii')}


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
        self.cid=None;self.boots=[];self.removed=[];self.owned=[];self.process=None
        self.launch=None;self.pending_process=None;self.ready_for_current_launch=False;self.startup_observation=None
        self.stop_history=[];self.start_transition=None
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
        tick=time.monotonic()
        self.lifecycle_budget_observation={'budgetSeconds':1500,
                'elapsedMillis':max(0,int((tick-(self.deadline-1500))*1000)),
                'remainingMillis':max(0,int((self.deadline-tick)*1000))}
        require(tick<self.deadline,'fixed 1500-second restore application budget')

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

    def running_launch_identity(self, obj):
        """Independent pre-ready proof. It never manufactures a boot or business quiescence."""
        launch=self.launch
        require(isinstance(launch,dict) and launch['cid']==self.cid and obj['Id']==self.cid
                and obj['Name']=='/'+self.name+'-app'
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and obj['Config']['Image']==JDK and obj['Config']['Entrypoint']==['/trace/strace']
                and obj['Config']['Cmd'][:2]==['-D','-f']
                and 'GITHUB_SHA='+launch['sourceSha'] in obj['Config']['Env']
                and 'CHENGYIN_RESTORE_MODE='+launch['restoreMode'] in obj['Config']['Env']
                and set(obj['NetworkSettings']['Networks'])=={self.name}
                and obj['NetworkSettings']['Networks'][self.name]['NetworkID']==self.source['networkId']
                and obj['State']['Running'] and not obj['State']['Restarting'] and not obj['State']['OOMKilled']
                and type(obj['State']['Pid']) is int and obj['State']['Pid']>0,
                'actual child boot identity')
        require(launch['sourceSha']==os.environ['GITHUB_SHA'] and launch['jarSha256']==self.jar_hash
                and self.jar.is_file() and not self.jar.is_symlink()
                and hashlib.sha256(self.jar.read_bytes()).hexdigest()==self.jar_hash,
                'actual child boot identity')
        config_path=self.root/'config.json'
        require(config_path.is_file() and not config_path.is_symlink()
                and hashlib.sha256(config_path.read_bytes()).hexdigest()==launch['configSha256'],
                'actual child boot identity')
        config=json.loads(config_path.read_bytes())
        require(all(config.get(key)==launch.get(key) for key in
                    ('sourceSha','jarSha256','restoreMode','schema','serverUuid'))
                and config.get('classManifestSha256')==self.manifest_hash
                and config.get('scope')==self.name[8:], 'actual child boot identity')
        raw=self.auth.command(self.auth.DOCKER+['exec',self.cid,'cat','/proc/1/cmdline','/proc/1/stat'],timeout=10)
        return {**pid1_jvm_proof(raw),'cid':self.cid,'hostPid':obj['State']['Pid'],
                'sourceSha':launch['sourceSha'],'jarSha256':self.jar_hash,
                'configSha256':launch['configSha256']}

    def running_child_identity(self, boot, obj):
        require(boot.get('appCid')==self.cid and type(boot.get('javaPid')) is int and boot['javaPid']==1,
                'actual child boot identity')
        proof=self.running_launch_identity(obj)
        ready_path=self.root/'ready.json'
        require(ready_path.is_file() and not ready_path.is_symlink(), 'actual child boot identity')
        ready_raw=ready_path.read_bytes();ready=json.loads(ready_raw)
        require(all(ready.get(key)==boot.get(key) for key in
                    ('bootId','javaPid','sourceSha','jarSha256','schema','restoreMode','serverUuid'))
                and all(boot.get(key)==self.launch.get(key) for key in
                        ('sourceSha','jarSha256','schema','restoreMode','serverUuid')),
                'actual child boot identity')
        return {**proof,'bootId':boot['bootId'],'readySha256':hashlib.sha256(ready_raw).hexdigest()}

    def read_startup_observation(self):
        path=self.root/'startup.json'
        if not path.exists():return None
        require(path.is_file() and not path.is_symlink() and path.stat().st_size<=4096,
                'actual child boot identity')
        with path.open('rb') as stream:raw=stream.read(4097)
        require(len(raw)<=4096,'actual child boot identity')
        value=json.loads(raw);launch=self.launch
        keys={'sourceSha','jarSha256','classManifestSha256','scope','schema','restoreMode',
              'bootId','javaPid','stage','status','reasonCode','failureType'}
        require(isinstance(value,dict) and set(value)==keys and isinstance(launch,dict)
                and all(value.get(key)==launch.get(key) for key in
                        ('sourceSha','jarSha256','schema','restoreMode'))
                and value['classManifestSha256']==self.manifest_hash and value['scope']==self.name[8:]
                and isinstance(value['bootId'],str)
                and re.fullmatch('[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}',value['bootId'])
                and type(value['javaPid']) is int and value['javaPid']==1
                and value['stage'] in STARTUP_STAGES and value['status'] in ('PROGRESS','FAILED','READY')
                and value['reasonCode'] in STARTUP_REASONS and value['failureType'] in STARTUP_FAILURE_TYPES,
                'actual child boot identity')
        require((value['status']=='FAILED' and value['reasonCode']!='NONE' and value['failureType']!='NONE')
                or (value['status']!='FAILED' and value['reasonCode']=='NONE' and value['failureType']=='NONE'),
                'actual child boot identity')
        require(value['status']!='READY' or value['stage']=='READY', 'actual child boot identity')
        self.startup_observation=dict(value)
        return dict(value)

    def read_callback_observation(self):
        path=self.root/'callback-diagnostic.json'
        if not path.exists():return None
        require(path.is_file() and not path.is_symlink(),'actual child boot identity')
        with path.open('rb') as stream:raw=stream.read(4097)
        require(len(raw)<=4096,'actual child boot identity')
        value=json.loads(raw)
        keys={'sourceSha','jarSha256','classManifestSha256','scope','schema','serverUuid','bootId','javaPid',
              'operation','sequence','status','stage','reasonCode','outcome','causeTypes','origin','sqlState','sqlErrorCode'}
        stages={'ADMISSION','ORIGINAL_INPUT','PROVIDER_JOURNAL','RECEIPT_READ','FACT_LOOKUP','FACT_SAVE',
                'REFERENCE_VERIFY','RECEIPT_SAVE','REGISTRATION_CALLBACK','RESPONSE'}
        reasons={'NONE','HELD_REQUIRED','OUTER_TRANSACTION','ORIGINAL_CONTEXT','PROVIDER_IDENTITY',
                 'RECEIPT_INVALID','REFERENCE_CONFLICT','UNCLASSIFIED_FAILURE'}
        types={'FACT_CONFLICT','INBOX_CONFLICT','DATA_ACCESS','ILLEGAL_STATE','UNSATISFIED_DEPENDENCY',
               'BEAN_CREATION','SQL','IO','SCHEDULER','INTERRUPTED','OTHER'}
        origins={'com.chengyinhub.business.it.RestoreHttpApplicationChild',
                 'com.chengyinhub.business.it.RestoreHttpApplicationChild$FixtureEndpoints',
                 'com.chengyinhub.business.service.impl.WechatVerifiedPaymentFactService',
                 'com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl',
                 'com.chengyinhub.business.service.impl.WxPaymentCallbackInboxServiceImpl'}
        require(isinstance(value,dict) and set(value)==keys and self.ready_for_current_launch and self.boots
                and self.cid==self.boots[-1]['appCid']==self.launch['cid']
                and all(value.get(key)==self.launch.get(key) for key in ('sourceSha','jarSha256','schema','serverUuid'))
                and value['classManifestSha256']==self.manifest_hash and value['scope']==self.name[8:]
                and value['bootId']==self.boots[-1]['bootId'] and type(value['javaPid']) is int and value['javaPid']==1
                and value['operation']=='VERIFIED_CALLBACK' and type(value['sequence']) is int and value['sequence']>0
                and value['status'] in ('ENTERED','RETURNED','THREW') and value['stage'] in stages
                and value['reasonCode'] in reasons and value['outcome'] in ('NONE','FAIL','SUCCESS','OTHER')
                and isinstance(value['causeTypes'],list) and len(value['causeTypes'])<=8
                and all(kind in types for kind in value['causeTypes']),'actual child boot identity')
        origin=value['origin']
        require(origin is None or (isinstance(origin,dict) and set(origin)=={'class','line'}
                and origin['class'] in origins and type(origin['line']) is int and 0<origin['line']<=100000),
                'actual child boot identity')
        require((value['sqlState'] is None and value['sqlErrorCode'] is None) or (
                value['sqlState'] in {'08001','08003','08004','08006','08S01','28000','42000','42S01','42S02',
                '42S21','42S22','23000','22001','22003','HY000','HY001','HYT00','HYT01','40001'}
                and type(value['sqlErrorCode']) is int and 0<=value['sqlErrorCode']<=65535),
                'actual child boot identity')
        require((value['status']=='THREW' and value['reasonCode']!='NONE' and value['causeTypes'])
                or (value['status']!='THREW' and value['reasonCode']=='NONE' and not value['causeTypes']),
                'actual child boot identity')
        return value

    def callback_observation(self):
        # Diagnostic only. Neither a stale prior success nor a parse error becomes an ACK.
        try:
            value=self.read_callback_observation()
            return {'result':'VERIFIED','value':value} if value is not None else {'result':'MISSING'}
        except (OSError,ValueError,KeyError,TypeError,AttributeError):
            return {'result':'UNKNOWN','reason':'PRIVATE_OBSERVATION_REJECTED'}

    def record_callback_observation(self,before):
        after=self.callback_observation()
        fresh=('UNKNOWN' if before['result']=='UNKNOWN' or after['result']=='UNKNOWN' else
               after['result']=='VERIFIED' and (before['result']=='MISSING'
               or after['value']['sequence']>before['value']['sequence']))
        self.last_callback_observation={'route':'/__restore_http/verified-callback',
                'newControllerExecution':fresh,'after':after}

    def wait_for_ready(self):
        until=time.monotonic()+180
        while time.monotonic()<until:
            self.budget()
            obj=self.inspect(self.cid)
            require(obj['State']['Running'],'complete Spring child exited before readiness')
            if self.pending_process is None:
                try:self.pending_process=self.running_launch_identity(obj)
                except ValueError as failure:
                    # strace PID1 has not necessarily exec'd Java yet. No proof means no safe stop.
                    if getattr(failure,'restore_reason_code',None)!='APP_BOOT_IDENTITY_REJECTED':raise
            observed=self.read_startup_observation()
            if observed is not None and observed['status']=='FAILED':
                require(False,'child startup rejected')
            if (self.root/'ready.json').is_file() and observed is not None and observed['status']=='READY':return
            time.sleep(1)
        require(False,'bounded complete application startup timeout')

    def stop(self,remove=True):
        if self.cid is None:
            self.absence();return
        obj=self.inspect(self.cid)
        require(obj['Id']==self.cid and obj['Name']=='/'+self.name+'-app'
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and set(obj['NetworkSettings']['Networks'])=={self.name}
                and obj['NetworkSettings']['Networks'][self.name]['NetworkID']==self.source['networkId'],
                'exact current app owner changed')
        failure = None
        try:
            if obj['State']['Running']:
                if self.ready_for_current_launch:
                    require(self.process is not None and self.boots
                            and self.running_child_identity(self.boots[-1],obj)==self.process,
                            'actual child boot identity')
                else:
                    require(self.pending_process is not None
                            and self.running_launch_identity(obj)==self.pending_process,
                            'actual child boot identity')
                # -D makes this verified JVM PID1. Keep the original 20-second TERM/KILL boundary.
                self.auth.command(self.auth.DOCKER+['stop','--time','20',self.cid],timeout=30)
        except BaseException as error:
            failure = error
        try:
            stopped=self.inspect(self.cid)['State']
            # Record current exit state on command/identity failure too; never substitute another boot.
            self.stopped={'cid':self.cid,'running':stopped['Running'],'hostPid':stopped['Pid'],
                          'oomKilled':stopped['OOMKilled'],'exitCode':stopped['ExitCode'],
                          'restarting':stopped['Restarting']}
        except BaseException:
            if failure is not None:raise failure
            raise
        if failure is not None:raise failure
        require(not stopped['Running'] and not stopped['OOMKilled'] and not stopped['Restarting']
                and stopped['Pid']==0 and stopped['ExitCode'] in (0,143),'app stop/process exit unknown')
        require((self.ready_for_current_launch and self.process is not None)
                or (not self.ready_for_current_launch and self.pending_process is not None),
                'actual child boot identity')
        self.stop_proof_kind='READY_JVM_EXIT_CONFIRMED' if self.ready_for_current_launch else 'PRE_READY_JVM_EXIT_CONFIRMED'
        self.preserve_stopped_log()
        if not remove:return # Original outer cleanup must still collect this exact container's logs.
        self.auth.command(self.auth.DOCKER+['rm',self.cid],timeout=15)
        self.absence();self.removed.append(self.cid);self.cid=None;self.process=None;self.pending_process=None

    def read_complete_application_log(self):
        # Same exact-CID Docker log boundary as the original owner; never publish its raw contents.
        value=subprocess.run(self.auth.DOCKER+['logs',self.cid],capture_output=True,timeout=20)
        require(value.returncode==0,'complete application logs unavailable')
        raw=value.stdout+value.stderr
        require(len(raw)<=8*1024*1024,'complete application logs exceed private evidence bound')
        return raw

    def private_log_path(self, cid):
        require(cid in self.owned and len(self.owned)<=5,'exact original log CID')
        return self.root/('boot-log-'+str(self.owned.index(cid))+'.private')

    def verify_private_log(self, record):
        path=self.private_log_path(record['cid'])
        require(path.is_file() and not path.is_symlink() and path.stat().st_mode & 0o777==0o600,
                'original private application log changed')
        with path.open('rb') as stream:raw=stream.read(8*1024*1024+1)
        require(len(raw)<=8*1024*1024 and len(raw)==record['log']['bytes']
                and hashlib.sha256(raw).hexdigest()==record['log']['sha256'],
                'original private application log changed')

    def preserve_stopped_log(self):
        # Repeated source/final stops are read-only verification, not a second capture or removal.
        proof=self.process if self.ready_for_current_launch else self.pending_process
        obj=self.inspect(self.cid)
        require(proof is not None and proof['cid']==self.cid and obj['Id']==self.cid
                and obj['Name']=='/'+self.name+'-app' and obj['Config']['Image']==JDK
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and 'GITHUB_SHA='+proof['sourceSha'] in obj['Config']['Env']
                and 'CHENGYIN_RESTORE_MODE='+self.launch['restoreMode'] in obj['Config']['Env']
                and not obj['State']['Running'] and not obj['State']['Restarting'] and not obj['State']['OOMKilled']
                and obj['State']['Pid']==0 and obj['State']['ExitCode']==self.stopped['exitCode'],
                'actual child boot identity')
        config_path=self.root/'config.json'
        require(self.jar.is_file() and not self.jar.is_symlink()
                and hashlib.sha256(self.jar.read_bytes()).hexdigest()==proof['jarSha256']
                and config_path.is_file() and not config_path.is_symlink()
                and hashlib.sha256(config_path.read_bytes()).hexdigest()==proof['configSha256'],
                'actual child boot identity')
        if self.ready_for_current_launch:
            ready_path=self.root/'ready.json'
            require(ready_path.is_file() and not ready_path.is_symlink()
                    and hashlib.sha256(ready_path.read_bytes()).hexdigest()==proof['readySha256'],
                    'actual child boot identity')
        previous=next((row for row in self.stop_history if row['cid']==self.cid),None)
        if previous is not None:
            require(previous['stopped']==self.stopped and previous['proofKind']==self.stop_proof_kind
                    and previous['identity']==proof,
                    'original stopped application evidence changed')
            self.verify_private_log(previous)
            return previous
        require(self.cid in self.owned and len(self.stop_history)<5,'exact original stopped CID')
        proof=self.process if self.ready_for_current_launch else self.pending_process
        require(proof is not None and proof['cid']==self.cid,'actual child boot identity')
        raw=self.read_complete_application_log()
        path=self.private_log_path(self.cid)
        require(not path.exists() and not path.is_symlink(),'exclusive private application log')
        with path.open('xb') as stream:
            path.chmod(0o600);stream.write(raw);stream.flush();os.fsync(stream.fileno())
        record={'cid':self.cid,'bootId':proof.get('bootId'),'sourceSha':proof['sourceSha'],
                'jarSha256':proof['jarSha256'],'identity':dict(proof),'proofKind':self.stop_proof_kind,
                'stopped':dict(self.stopped),'log':{'result':'COMPLETE_PRIVATE_CAPTURE_VERIFIED',
                'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),
                'summary':summarize_application_log(raw)}}
        self.verify_private_log(record)
        self.stop_history.append(record)
        return record

    def verify_all_stopped_logs(self):
        require([row['cid'] for row in self.stop_history]==self.owned,'every original app log required before private erasure')
        for row in self.stop_history:self.verify_private_log(row)

    def diagnostic_summary(self):
        """Observe the exact existing app only; outer owner still preserves/removes its raw log."""
        if self.cid is None:
            result={'result':'NO_CURRENT_CID_OBSERVATION','startAbsenceProven':False}
            if self.stop_history:result['lastStoppedEvidence']=self.stop_history[-1]
            if getattr(self,'start_transition',None) is not None:result['startTransition']=dict(self.start_transition)
            return result
        obj=self.inspect(self.cid)
        require(obj['Id']==self.cid and obj['Name']=='/'+self.name+'-app'
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and set(obj['NetworkSettings']['Networks'])=={self.name}
                and obj['NetworkSettings']['Networks'][self.name]['NetworkID']==self.source['networkId'],
                'exact diagnostic app owner changed')
        state=obj['State']
        result={'result':'EXACT_APP_OBSERVED','cid':self.cid,
                'state':{key:state[key] for key in ('Running','OOMKilled','Restarting','Pid','ExitCode')}}
        if self.stop_history:result['lastStoppedEvidence']=self.stop_history[-1]
        if getattr(self,'start_transition',None) is not None:result['startTransition']=dict(self.start_transition)
        if getattr(self,'launch',None) is not None:
            observed=self.read_startup_observation()
            if observed is not None:result['childStartup']=observed
        log=subprocess.run(self.auth.DOCKER+['logs','--tail','160',self.cid],
                           stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=10)
        if log.returncode:
            result['logSummary']={'result':'UNAVAILABLE','reasonCode':'APP_LOG_COMMAND_FAILED'}
        else:
            result['logSummary']={'result':'FIXED_MARKERS_ONLY',**summarize_application_log(log.stdout)}
        return result

    def start(self,side,mode,uuid,seed=False):
        self.start_transition={'stage':'BUDGET','side':side if side in ('source','target') else 'UNCLASSIFIED',
                'mode':mode if mode in ('false','true','invalid','') else 'UNCLASSIFIED',
                'previousCid':self.cid,'previousBootId':self.boots[-1]['bootId'] if self.boots else None,
                'previousRemovalConfirmed':False,'newCid':None}
        self.budget()
        self.start_transition['stage']='ADMISSION'
        require(side in ('source','target') and mode in ('true','invalid','','false'),'fixed boot matrix')
        require(not seed or (side=='source' and mode=='false' and not self.boots),'one isolated source seed')
        self.start_transition['stage']='PREVIOUS_STOP_AND_LOG'
        self.stop()
        self.start_transition['stage']='PREVIOUS_REMOVAL_CONFIRMED'
        self.start_transition['previousRemovalConfirmed']=(
                self.start_transition['previousCid'] is not None and self.start_transition['previousCid'] in self.removed)
        self.process=None;self.pending_process=None;self.launch=None
        self.ready_for_current_launch=False;self.startup_observation=None
        if hasattr(self,'stop_proof_kind'):del self.stop_proof_kind
        if hasattr(self,'stopped'):del self.stopped
        self.start_transition['stage']='DATABASE_IDENTITY'
        endpoint=self.source if side=='source' else self.target
        require(self.auth.mysql_endpoint(self.name,suffix='mysql' if side=='source' else 'restore-mysql')==endpoint,'DB identity changed')
        config={'sourceSha':os.environ['GITHUB_SHA'],'schema':self.schema,'scope':self.name[8:],
                'jarSha256':self.jar_hash,'classManifestSha256':self.manifest_hash,
                'jdbcUrl':'jdbc:mysql://'+endpoint['ip']+':3306/'+self.schema+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                'redisHost':self.redis_ip,'tokenSecret':self.token_secret,'serverUuid':uuid,'restoreMode':mode,'seed':seed}
        self.start_transition['stage']='CONFIGURATION'
        write_private(self.root/'config.json',config)
        self.start_transition['stage']='READY_STATUS_RESET'
        (self.root/'ready.json').unlink(missing_ok=True)
        (self.root/'startup.json').unlink(missing_ok=True)
        (self.root/'callback-diagnostic.json').unlink(missing_ok=True)
        (self.root/'callback-diagnostic.next').unlink(missing_ok=True)
        trace='network-'+str(len(self.boots))+'.trace';strace=shutil.which('strace')
        self.start_transition['stage']='TRACE_PREPARATION'
        require(strace and (self.out/'trace-libs').is_dir(),'reuse original tracing preparation')
        # Original immutable app image/network/strace/memory/user/JAR/config. Only the test child and
        # per-process real environment differ. JVM -D properties do not replace System.getenv; no external port.
        self.start_transition['stage']='DOCKER_CREATE'
        raw=self.auth.command(self.auth.DOCKER+['run','-d','--name',self.name+'-app','--label','chengyin.auth-recovery='+self.name,
                '--network',self.name,'--dns','127.0.0.1','--memory=1g','--memory-swap=1g','--cpus=1',
                '--read-only','--tmpfs','/tmp:rw,size=256m','--cap-drop','ALL','--cap-add','SYS_PTRACE',
                '--user',str(os.getuid())+':'+str(os.getgid()),'-e','LD_LIBRARY_PATH=/trace-libs',
                '-e','GITHUB_SHA='+os.environ['GITHUB_SHA'],'-e','CHENGYIN_CI_RESTORE_HTTP_STARTUP=true',
                '-e','CHENGYIN_RESTORE_MODE='+mode,
                '-v',strace+':/trace/strace:ro','-v',str(self.out/'trace-libs')+':/trace-libs:ro',
                '-v',str(self.jar)+':/application.jar:ro','-v',str(self.classes)+':/restore-http-testclasses:ro',
                '-v',str(ROOT/'ci/upgrade-startup')+':/fixture:ro','-v',str(self.root)+':/restore-http-private',
                '--entrypoint','/trace/strace',JDK,'-D','-f','-s','0','-e','trace=connect,sendto,sendmsg',
                '-o','/restore-http-private/'+trace,'java','-Xmx512m',
                '-Dlogging.config=/fixture/logback.xml','-Dlogback.configurationFile=/fixture/logback.xml',
                '-Dloader.path=/restore-http-testclasses','-Dloader.main='+MAIN,
                '-cp','/application.jar','org.springframework.boot.loader.launch.PropertiesLauncher'],timeout=120)
        self.cid=raw.decode().strip();require(re.fullmatch('[0-9a-f]{64}',self.cid),'exact created app CID')
        self.owned.append(self.cid)
        self.start_transition['stage']='CREATED_IDENTITY'
        self.start_transition['newCid']=self.cid
        self.launch={**{key:config[key] for key in ('sourceSha','jarSha256','schema','restoreMode','serverUuid')},
                     'cid':self.cid,'configSha256':hashlib.sha256((self.root/'config.json').read_bytes()).hexdigest()}
        obj=self.inspect(self.name+'-app')
        require(obj['Id']==self.cid and obj['Config']['Image']==JDK
                and obj['Config']['Labels'].get('chengyin.auth-recovery')==self.name
                and obj['HostConfig']['Memory']==1073741824 and obj['HostConfig']['NanoCpus']==1000000000
                and set(obj['NetworkSettings']['Networks'])=={self.name}
                and obj['NetworkSettings']['Networks'][self.name]['NetworkID']==self.source['networkId']
                and not obj['HostConfig'].get('PortBindings'),'original app isolation/budget')
        require('CHENGYIN_RESTORE_MODE='+mode in obj['Config']['Env'],'real env mode missing')
        self.start_transition['stage']='READINESS'
        self.wait_for_ready()
        boot=json.loads((self.root/'ready.json').read_text())
        require(boot['sourceSha']==os.environ['GITHUB_SHA'] and boot['jarSha256']==self.jar_hash
                and boot['serverUuid']==uuid and boot['schema']==self.schema and boot['restoreMode']==mode
                and boot['guardHeld']==(mode!='false') and boot['schedulerStarted'] is True
                and all(boot['bootId']!=old['bootId'] for old in self.boots),'actual child boot identity')
        require(type(boot['javaPid']) is int and boot['javaPid']==1
                and self.startup_observation['bootId']==boot['bootId'],'actual child boot identity')
        boot.update({'appCid':self.cid,'side':side,'trace':trace,'mysqlIp':endpoint['ip']})
        self.process=self.running_child_identity(boot,self.inspect(self.cid))
        self.boots.append(boot);self.ready_for_current_launch=True
        self.start_transition['stage']='READY'
        return boot

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
        tracked=base=='/__restore_http/verified-callback'
        before=self.callback_observation() if tracked else None
        try:
            raw=self.auth.command(self.auth.DOCKER+['exec','-i',self.cid,'curl','--noproxy','*','--silent','--show-error',
                    '--max-time','45','--write-out','\n%{http_code}','--config','-'],text.encode(),timeout=50).decode()
        finally:
            if tracked:self.record_callback_observation(before)
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
