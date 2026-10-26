#!/usr/bin/env python3
"""Fixed combined-HTTP budget owner SOURCE. No class/command/profile/URL selection or provisioning.

Only the reviewed hosted script calls this adapter. The original workflow owns MySQL/network;
the original fresh-schema and HTTP XML/cleanup verifier remain authoritative for business results.
"""
import argparse
import contextlib
import datetime
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import urllib.parse
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CLASS = 'CrossDomainCombinedLoadMySqlIT'
JDK = 'maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237'
MYSQL = 'mysql:8.0.45@sha256:4af1f8815716546f5b12410f7621f37f93db8dd11a184706ef59111930b8c2ff'
REDIS = 'redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
DOCKER = ['docker', '--context', 'default']
MODULES = ('chengyinhub-admin','chengyinhub-framework','chengyinhub-system','chengyinhub-quartz','chengyinhub-generator','chengyinhub-common')
OUT = ROOT / 'chengyinhub-admin/target/http-e2e'
PUBLIC = OUT / 'public'
MEMORY = 1073741824
LIMIT_SECONDS = 1200  # Owner ceiling, not a replacement for the original Java/workload deadlines.
BOOTSTRAP_REPORT = PUBLIC/'combined-http-bootstrap.json'
MYSQL_PRIVATE = OUT/'combined-mysql-private'
EMPTY_SETTINGS='<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n'
CACHE_REPORT=PUBLIC/'combined-http-cache.json'
CORE_VERSION='3.9.11'
REVIEWED_PLUGINS={
    'org.apache.maven.plugins:maven-resources-plugin':{'3.3.1'},
    'org.apache.maven.plugins:maven-jar-plugin':{'3.4.1'},
    'org.apache.maven.plugins:maven-install-plugin':{'3.1.2'},
    'org.apache.maven.plugins:maven-compiler-plugin':{'3.15.0'},
    'org.apache.maven.plugins:maven-surefire-plugin':{'3.5.6'},
    'org.apache.maven.plugins:maven-dependency-plugin':{'3.10.0'},
    'org.apache.maven.plugins:maven-war-plugin':{'3.1.0'},
    'org.springframework.boot:spring-boot-maven-plugin':{'4.1.1'},
    'io.github.git-commit-id:git-commit-id-maven-plugin':{'5.0.0'}}
SAFE_RESOURCE_CODES = frozenset({'FIXED_COMMAND_FAILED','FIXED_HOSTED_CLASS_ONLY','FULL_SOURCE_REQUIRED',
    'RUN_ATTEMPT_REQUIRED','FIXED_BACKING_IDENTITY_REQUIRED','BACKING_MYSQL_FOREIGN','BACKING_NAME_CID_CHANGED',
    'BACKING_MYSQL_BUDGET_MISMATCH','BACKING_LOOPBACK_SCOPE_CHANGED','BACKING_MYSQL_EXACT_EPHEMERAL_STORAGE_REQUIRED',
    'BACKING_ACTUAL_IMAGE_ID_CHANGED','OWNED_INTERNAL_NETWORK_REQUIRED','BACKING_MYSQL_NOT_RUNNING',
    'BACKING_NETWORK_CONFIG_CHANGED','NETWORK_IDENTITY_REQUIRED','FOREIGN_NETWORK','DOCKER_INSPECTION_UNKNOWN',
    'BOOTSTRAP_SOURCE_MISMATCH','BOOTSTRAP_NOT_VERIFIED','MYSQL_PROCESS_EXITED_BEFORE_READY',
    'MYSQL_TCP_READY_BUDGET_EXHAUSTED','MYSQL_TCP_IDENTITY_MISMATCH','ORIGINAL_SYNTHETIC_MYSQL_CREDENTIAL_REQUIRED',
    'LOCAL_DOCKER_ONLY','FOREIGN_CONTAINER_OR_IMAGE','FOREIGN_LABEL','ACTUAL_CID_MISMATCH',
    'ABSENCE_NOT_INDEPENDENTLY_PROVEN','NETWORK_NOT_EXACT_OR_NOT_EMPTY','MYSQL_CREATE_ID_UNKNOWN',
    'SHELL_BOOTSTRAP_FAILED','PRIVATE_RESOURCE_PATH_UNSAFE','NEVER_ADOPT_PREVIOUS_BOOTSTRAP',
    'FIXED_CACHE_TOOLCHAIN_REQUIRED','OFFLINE_CACHE_PREFLIGHT_FAILED','OFFLINE_CACHE_PREFLIGHT_REQUIRED',
    'OFFLINE_CACHE_PROOF_CHANGED','BOUNDED_OFFLINE_CACHE_REQUIRED','EMPTY_OFFLINE_CACHE',
    'SYMLINK_OFFLINE_CACHE_FORBIDDEN','NONREGULAR_OFFLINE_CACHE_FORBIDDEN'})


def require(ok, code):
    if not ok: raise ValueError(code)


def command(argv, timeout=20, data=None):
    result = subprocess.run(argv, input=data, capture_output=True, timeout=timeout)
    require(result.returncode == 0, 'FIXED_COMMAND_FAILED')
    return result.stdout


def write(path, value):
    path = Path(path); require(not path.is_symlink(), 'SYMLINK_REPORT')
    temporary = path.with_suffix(path.suffix + '.next')
    with temporary.open('x') as stream:
        temporary.chmod(0o600); json.dump(value, stream, sort_keys=True); stream.flush(); os.fsync(stream.fileno())
    os.replace(temporary, path)


def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def safe_plugin_resolution(text):
    """Only exact reviewed plugin coordinates in Maven's standard resolution error, never its cause."""
    result={}
    text=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',text[-1024*1024:])
    matches=re.findall(r'\bPlugin ([a-z][a-z0-9_.]{0,99}):([a-z][a-z0-9-]{0,79}):([0-9][A-Za-z0-9_.-]{0,39}) or one of its dependencies could not be resolved:',text)
    for group,artifact,version in reversed(matches):
        key=group+':'+artifact
        if version in REVIEWED_PLUGINS.get(key,set()):
            result={'plugin':key,'pluginVersion':version,'pluginPhase':'RESOLUTION_BEFORE_GOAL'};break
    return result


def cache_manifest(cache):
    require(cache.is_dir() and not cache.is_symlink(),'EXISTING_OFFLINE_CACHE_REQUIRED')
    rows=[];total=0
    for path in sorted(cache.rglob('*')):
        require(not path.is_symlink(),'SYMLINK_OFFLINE_CACHE_FORBIDDEN')
        if path.is_dir():continue
        require(path.is_file(),'NONREGULAR_OFFLINE_CACHE_FORBIDDEN')
        total+=path.stat().st_size;rows.append((str(path.relative_to(cache)),path.stat().st_size,digest(path)))
        require(len(rows)<=100000 and total<=8*1024**3,'BOUNDED_OFFLINE_CACHE_REQUIRED')
    require(rows,'EMPTY_OFFLINE_CACHE')
    return {'files':len(rows),'bytes':total,'sha256':hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest()}


def cache_inputs():
    require(command(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip()==os.environ['SOURCE_SHA'],'WRONG_CHECKOUT')
    require(subprocess.run(['git','-C',str(ROOT),'diff','--quiet','HEAD','--'],capture_output=True).returncode==0,'DIRTY_SOURCE')
    return {str(p.relative_to(ROOT)):digest(p) for p in [ROOT/'pom.xml']+[ROOT/m/'pom.xml' for m in MODULES]}


def cache_maven_arguments():
    return ['-B','-q','--settings','/cache-owner/settings.xml','--global-settings','/cache-owner/settings.xml',
            '-Dmaven.repo.local=/cache/repository']


def fixed_cache_command():
    common=cache_maven_arguments()
    # Original package/get, now run by the SAME pinned core/settings/repository as execution.
    # skipTests belongs only to preparation/preflight. The original real test command never gains it.
    goals=[common+['-pl','chengyinhub-admin','-am','package','-DskipTests'],
           common+['-N','org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get',
                   '-Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.6','-Dtransitive=true'],
           common+['-o','-pl','chengyinhub-admin','-am','test','-DskipTests','-Dtest='+CLASS,
                   '-DfailIfNoTests=false','-Dsurefire.failIfNoSpecifiedTests=false']]
    # Structured argv are passed to a fixed shell without interpolation of source/env/credentials.
    encoded='; '.join('printf "%s\\n" "'+stage+'" >/tmp/cy-cache.phase; '+
                     ' '.join("'"+value+"'" for value in ['mvn']+goal)+' || exit $?'
                     for stage,goal in zip(('ONLINE_PACKAGE','ORIGINAL_PROVIDER_GET','OFFLINE_TEST_PREFLIGHT'),goals))
    return ['-c','mkdir -p /tmp/home /tmp/m2; ('+encoded+'); status=$?; '
            'printf "%s\\n" "$status" >/tmp/cy-cache.exit; '
            'for i in {1..1200}; do [ -f /cache-owner/stop ] && break; sleep .1; done; exit "$status"']


def warm_cache():
    identity_gate(os.environ);PUBLIC.mkdir(parents=True,exist_ok=True)
    private=private_directory(Path(command(['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX']).decode().strip()))
    cache=Path.home()/'.m2/repository'
    report={**resource_envelope(os.environ),'result':'NOT_COMPLETED','originalExit':None,'failures':[],
            'preparationOnly':True,'mavenVersion':CORE_VERSION,'image':JDK,'settingsSha256':hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()}
    state={'nonce':uuid.uuid4().hex,'slots':{}};first=None;cleaned=None
    def publish():
        try:write(CACHE_REPORT,report)
        except Exception as failure:report['failures'].append(resource_failure(failure,'CACHE_REPORT_WRITE'))
    try:
        publish();cache.mkdir(parents=True,exist_ok=True)
        require(not cache.is_symlink() and cache.resolve()==cache,'SYMLINK_OFFLINE_CACHE_FORBIDDEN')
        report['pomSha256']=cache_inputs()
        require(command(DOCKER+['context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
                =='unix:///var/run/docker.sock','LOCAL_DOCKER_ONLY')
        found=subprocess.run(DOCKER+['image','inspect',JDK],capture_output=True,timeout=20)
        if found.returncode:command(DOCKER+['pull',JDK],timeout=300)
        image=json.loads(command(DOCKER+['image','inspect',JDK]))[0];report['imageId']=image['Id']
        (private/'settings.xml').write_text(EMPTY_SETTINGS);os.chmod(private/'settings.xml',0o600)
        name='cy-http-cache-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+state['nonce'][:12]
        absent('container',name);state['slots']['app']={'name':name,'cid':None,'attempted':True}
        args=DOCKER+['create','--name',name,'--network','bridge','--memory=1g','--memory-swap=1g','--cpus=1',
                    '--pids-limit=256','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
                    '--tmpfs','/tmp:rw,size=256m','--user',str(os.getuid())+':'+str(os.getgid()),
                    '--entrypoint','/bin/bash','--workdir',str(ROOT),'--cidfile',str(private/'app.cid')]
        for key,value in labels(os.environ,state['nonce']).items():args+=['--label',key+'='+value]
        mounts=[(ROOT,ROOT,'readonly'),(cache,Path('/cache/repository'),'readonly=false'),(private,Path('/cache-owner'),'readonly')]
        for module in MODULES:
            target=ROOT/module/'target';target.mkdir(parents=True,exist_ok=True)
            require(not target.is_symlink(),'ORIGINAL_TARGET_REQUIRED');mounts.append((target,target,'readonly=false'))
        for source,target,mode in mounts:args+=['--mount','type=bind,src='+str(source)+',dst='+str(target)+','+mode]
        env={'HOME':'/tmp/home','MAVEN_CONFIG':'/tmp/m2','MAVEN_OPTS':'-Xmx512m -Djava.io.tmpdir=/tmp -Duser.home=/tmp/home',
             'SOURCE_SHA':os.environ['SOURCE_SHA'],'GITHUB_SHA':os.environ['SOURCE_SHA']}
        for key,value in env.items():args+=['--env',key+'='+value]
        cid=command(args+[JDK]+fixed_cache_command(),timeout=30).decode().strip()
        require(re.fullmatch('[0-9a-f]{64}',cid) and (private/'app.cid').read_text().strip()==cid,'CREATED_CID_NOT_PROVEN')
        state['slots']['app']['cid']=cid;obj=inspect(cid)
        report['budget']=validate_container(obj,name,JDK,labels(os.environ,state['nonce']),MEMORY,'bridge',cid)
        require(obj['Image']==image['Id'] and obj['Config']['Cmd']==fixed_cache_command()
                and obj['Config']['Entrypoint']==['/bin/bash']
                and obj['Config']['User']==str(os.getuid())+':'+str(os.getgid()),'FIXED_CACHE_TOOLCHAIN_REQUIRED')
        expected_mounts={(str(s),str(t),mode!='readonly') for s,t,mode in mounts}
        require({(m['Source'],m['Destination'],m['RW']) for m in obj['Mounts'] if m['Type']=='bind'}==expected_mounts
                and all(m['Type']=='bind' or (m['Type']=='tmpfs' and m['Destination']=='/tmp') for m in obj['Mounts'])
                and obj['HostConfig']['ReadonlyRootfs'] is True and not obj['HostConfig'].get('Devices')
                and not obj['HostConfig'].get('VolumesFrom'),'PRIVATE_MOUNT_BOUNDARY_CHANGED')
        require(dict(item.split('=',1) for item in obj['Config']['Env'])==
                {**dict(item.split('=',1) for item in image['Config'].get('Env',[])),**env},'PRIVATE_ENV_BOUNDARY_CHANGED')
        publish();command(DOCKER+['start',cid]);deadline=time.monotonic()+LIMIT_SECONDS
        obj=inspect(cid);report['cgroup']=read_cgroup(obj['State']['Pid'],cid);publish()
        while True:
            obj=inspect(cid);validate_owner(obj,name,JDK,labels(os.environ,state['nonce']),cid)
            require(obj['State']['Running'] and time.monotonic()<deadline,'OWNED_EXECUTION_DEADLINE')
            status=command(DOCKER+['exec',cid,'/bin/bash','-c','if [ -f /tmp/cy-cache.exit ]; then cat /tmp/cy-cache.exit; fi']).decode().strip()
            if status:
                require(re.fullmatch('[0-9]{1,3}',status) and int(status)<256,'ACTUAL_MAVEN_EXIT_REQUIRED');break
            time.sleep(.5)
        report['originalExit']=int(status)
        report['cgroup']=read_cgroup(obj['State']['Pid'],cid)
        require(int(report['cgroup']['memoryEvents'].get('oom_kill','-1'))==0,'ACTUAL_CGROUP_CHILD_OOM_KILL')
        core=command(DOCKER+['exec',cid,'sha256sum','/usr/share/maven/lib/maven-core-'+CORE_VERSION+'.jar']).decode().split()[0]
        require(re.fullmatch('[0-9a-f]{64}',core),'FIXED_CACHE_TOOLCHAIN_REQUIRED');report['mavenCoreSha256']=core
        require(report['originalExit']==0,'OFFLINE_CACHE_PREFLIGHT_FAILED')
        report['cache']=cache_manifest(cache);require(cache_inputs()==report['pomSha256'],'COMPILED_POSTIMAGE_DRIFT')
    except Exception as failure:
        first=failure;report['firstFailure']=resource_failure(failure,'PINNED_CACHE_PREPARATION')
        if report['originalExit'] in (None,0):
            report['originalExit']=failure.errno if isinstance(failure,InterruptedError) and failure.errno in (129,130,143) else 2
    finally:
        # Preserve bounded private diagnostics even on timeout/OOM/create failure, BEFORE exact cleanup.
        try:
            slot=state['slots'].get('app',{})
            if slot.get('cid'):
                obj=inspect(slot['cid']);validate_owner(obj,slot['name'],JDK,labels(os.environ,state['nonce']),slot['cid'])
                log=private/'cache.log'
                with log.open('xb') as stream:
                    os.chmod(log,0o600);logs=subprocess.run(DOCKER+['logs',slot['cid']],stdout=stream,stderr=subprocess.STDOUT,timeout=30)
                require(logs.returncode==0 and log.stat().st_size<32*1024**2,'PRIVATE_LOG_NOT_COMPLETE')
                with log.open('rb') as stream:
                    stream.seek(max(0,log.stat().st_size-1024*1024));report['pluginFailure']=safe_plugin_resolution(stream.read().decode(errors='replace'))
                if obj['State']['Running']:
                    phase=command(DOCKER+['exec',slot['cid'],'cat','/tmp/cy-cache.phase']).decode().strip()
                    report['phase']=phase if phase in ('ONLINE_PACKAGE','ORIGINAL_PROVIDER_GET','OFFLINE_TEST_PREFLIGHT') else 'UNKNOWN'
        except Exception as failure:report['failures'].append(resource_failure(failure,'CACHE_PRIVATE_LOG'))
        try:
            cleaned=cleanup_slot(private,state,'app');report['cleanup']=cleaned
            if cleaned['result']=='EXACT_CID_REMOVED_AND_ABSENT':
                require(not cleaned['terminal']['OOMKilled'],'APP_OOM_OR_UNKNOWN_EXIT')
                require(cleaned['terminal']['ExitCode'] in (report['originalExit'],143),'ACTUAL_MAVEN_EXIT_REQUIRED')
        except Exception as failure:report['failures'].append(resource_failure(failure,'CACHE_CONTAINER_CLEANUP'))
        accepted=first is None and report['originalExit']==0 and not report['failures'] and cleaned is not None and cleaned['result']=='EXACT_CID_REMOVED_AND_ABSENT'
        report['result']='PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN' if accepted else 'NOT_ACCEPTED';publish()
        # A failed preparation retains its own private diagnostic journal; public never gets raw logs/settings.
        if accepted and not report['failures']:shutil.rmtree(private_directory(private))
    require(report['result']=='PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN' and not report['failures'],'OFFLINE_CACHE_PREFLIGHT_FAILED')


def verified_cache(cache):
    require(CACHE_REPORT.is_file() and not CACHE_REPORT.is_symlink() and CACHE_REPORT.stat().st_size<2*1024*1024,'OFFLINE_CACHE_PREFLIGHT_REQUIRED')
    report=json.loads(CACHE_REPORT.read_text())
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')}==identity(os.environ)
            and report.get('result')=='PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN' and report.get('originalExit')==0
            and not report.get('failures') and report.get('image')==JDK and report.get('mavenVersion')==CORE_VERSION
            and report.get('settingsSha256')==hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()
            and report.get('pomSha256')==cache_inputs() and report.get('cache')==cache_manifest(cache)
            and report.get('cleanup',{}).get('result')=='EXACT_CID_REMOVED_AND_ABSENT'
            and report['cleanup'].get('terminal',{}).get('OOMKilled') is False
            and re.fullmatch('sha256:[0-9a-f]{64}',report.get('imageId',''))
            and re.fullmatch('[0-9a-f]{64}',report.get('mavenCoreSha256',''))
            and report.get('budget',{}).get('memory')==report['budget'].get('memorySwap')==MEMORY
            and report['budget'].get('nanoCpus')==1000000000 and report['budget'].get('pids')==256,'OFFLINE_CACHE_PREFLIGHT_REQUIRED')
    validate_cgroup_limits(report['cgroup']['limits'])
    return {key:report[key] for key in ('image','imageId','mavenVersion','mavenCoreSha256','settingsSha256','cache')} | {'reportSha256':digest(CACHE_REPORT)}


def cache_stop_signal(number,_frame):
    require(number in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP),'FIXED_CACHE_SIGNAL_ONLY')
    raise InterruptedError(128+number,'FIXED_CACHE_PREPARATION_STOP')


def resource_failure(failure, phase):
    value = failure.args[0] if isinstance(failure,ValueError) and failure.args else None
    return {'phase':phase,'type':type(failure).__name__,
            'reasonCode':value if isinstance(value,str) and value in SAFE_RESOURCE_CODES else 'UNCLASSIFIED_RESOURCE_FAILURE'}


def resource_envelope(env):
    # A failed identity gate still gets bounded UNKNOWN evidence, never untrusted raw env values.
    return {'sourceSha':env.get('SOURCE_SHA') if re.fullmatch('[0-9a-f]{40}',env.get('SOURCE_SHA','')) else None,
            'runId':env.get('GITHUB_RUN_ID') if re.fullmatch('[1-9][0-9]*',env.get('GITHUB_RUN_ID','')) else None,
            'attempt':env.get('GITHUB_RUN_ATTEMPT') if re.fullmatch('[1-9][0-9]*',env.get('GITHUB_RUN_ATTEMPT','')) else None,
            'class':CLASS,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'businessExecution':'NOT_ATTESTED_BY_STORAGE_OWNER','wholeSystemCapacityAccepted':False,'q4q8Complete':False}


def mysql_names(env):
    return ('cy-http-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT']+'-'+CLASS.lower(),
            'cy-http-budget-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT'])


def exact_absence(kind,target,result):
    """One native inspect, one exact original target; never substring 'not found'."""
    require(kind in ('container','network') and isinstance(target,str) and
            re.fullmatch('[A-Za-z0-9][A-Za-z0-9_.-]*',target),'DOCKER_INSPECTION_UNKNOWN')
    escaped=re.escape(target)
    patterns=[r'Error: No such '+kind+r': '+escaped]
    if kind=='container':
        patterns += [r'Error: No such object: '+escaped,r'Error response from daemon: No such container: '+escaped]
    else:patterns += [r'Error response from daemon: network '+escaped+r' not found']
    return (result.returncode==1 and re.fullmatch('|'.join(patterns),result.stderr.decode(errors='replace').strip()) is not None
            and result.stdout.strip() in (b'',b'[]'))


def native_operation(kind,target,result):
    # Safe result classification: no stdout/stderr or untrusted daemon message enters the public report.
    text=result.stderr.decode(errors='replace').lower()
    category=('OK' if result.returncode==0 else 'EXACT_NATIVE_TARGET_ABSENT' if exact_absence(kind,target,result)
              else 'DOCKER_UNAVAILABLE' if 'cannot connect to the docker daemon' in text
              else 'ACTIVE_ENDPOINTS' if 'has active endpoints' in text
              else 'PERMISSION_REJECTED' if 'permission denied' in text
              else 'UNCLASSIFIED_NATIVE_FAILURE')
    return {'exit':result.returncode,'category':category}


def object_or_absent(kind,target,observations=None):
    result=subprocess.run(DOCKER+[kind,'inspect',target],capture_output=True,timeout=15)
    if observations is not None:observations.append({'operation':'INSPECT','kind':kind,**native_operation(kind,target,result)})
    if result.returncode:
        require(exact_absence(kind,target,result),'DOCKER_INSPECTION_UNKNOWN')
        return None
    value=json.loads(result.stdout);require(len(value)==1,'DOCKER_INSPECTION_UNKNOWN');return value[0]


def owned_mysql(env):
    name,network=mysql_names(env)
    require(env.get('HTTP_MYSQL_CONTAINER')==name and env.get('HTTP_BUDGET_NETWORK')==network
            and re.fullmatch('[0-9a-f]{64}',env.get('HTTP_MYSQL_CID','')),'FIXED_BACKING_IDENTITY_REQUIRED')
    obj=object_or_absent('container',env['HTTP_MYSQL_CID'])
    if obj is None:
        absent('container',name);return None
    validate_owner(obj,name,MYSQL,labels(env),env['HTTP_MYSQL_CID'])
    require(inspect(name)['Id']==obj['Id'],'BACKING_NAME_CID_CHANGED')
    modes={network}
    if re.fullmatch('[0-9a-f]{64}',env.get('HTTP_BUDGET_NETWORK_ID','')):modes.add(env['HTTP_BUDGET_NETWORK_ID'])
    require(obj['HostConfig']['NetworkMode'] in modes,'BACKING_NETWORK_CONFIG_CHANGED')
    require(re.fullmatch('sha256:[0-9a-f]{64}',obj.get('Image','')),'BACKING_ACTUAL_IMAGE_ID_CHANGED')
    return obj


def owned_network(env,observations=None):
    _,name=mysql_names(env)
    require(env.get('HTTP_BUDGET_NETWORK')==name and re.fullmatch('[0-9a-f]{64}',env.get('HTTP_BUDGET_NETWORK_ID','')),
            'NETWORK_IDENTITY_REQUIRED')
    obj=object_or_absent('network',env['HTTP_BUDGET_NETWORK_ID'],observations)
    if obj is None:
        absent('network',name,observations);return None
    require(obj['Id']==env['HTTP_BUDGET_NETWORK_ID'] and obj['Name']==name
            and all(obj.get('Labels',{}).get(k)==v for k,v in labels(env).items()),'FOREIGN_NETWORK')
    require(json.loads(command(DOCKER+['network','inspect',name]))[0]['Id']==obj['Id'],'FOREIGN_NETWORK')
    return obj


def original_auth_owner():
    """Load the existing transport owner by fixed reviewed source path; no profile/URL/plugin selector."""
    spec=importlib.util.spec_from_file_location('combined_original_auth_transport',ROOT/'ci/authenticated_redis_recovery_probe.py')
    owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
    return owner


def mysql_relay_endpoint():
    obj,proof=backing_mysql(os.environ)
    network=json.loads(command(DOCKER+['network','inspect',proof['networkId']]))[0]
    require(network['Id']==proof['networkId'] and network['Driver']=='bridge'
            and network.get('Options',{}).get('com.docker.network.bridge.gateway_mode_ipv4','nat')!='isolated',
            'OWNED_INTERNAL_NETWORK_REQUIRED')
    attachment=obj['NetworkSettings']['Networks'][mysql_names(os.environ)[1]]
    address=ipaddress.ip_address(attachment['IPAddress'])
    require(address.version==4 and address.is_private and not address.is_loopback
            and any(address in ipaddress.ip_network(c['Subnet']) for c in network['IPAM']['Config'] if ':' not in c['Subnet'])
            and obj['State']['Pid']>0 and not obj['State'].get('Paused') and not obj['State'].get('Restarting')
            and not obj['State'].get('OOMKilled'),'OWNED_INTERNAL_NETWORK_REQUIRED')
    return {'id':obj['Id'],'networkId':proof['networkId'],'ip':str(address),'pid':obj['State']['Pid']}


@contextlib.contextmanager
def mysql_host_path():
    identity_gate(os.environ)
    expected=mysql_relay_endpoint()
    owner=original_auth_owner()
    # The original owner rechecks the full CID/network/IP/PID before bind, every connection, and after use;
    # it closes and joins its exact sockets/threads. The shared review.patch adds only this fixed bind port.
    with owner._owned_mysql_relay(expected,mysql_relay_endpoint,loopback_port=33080) as server:
        require(server.server_address==('127.0.0.1',33080),'ORIGINAL_LOOPBACK_MYSQL_REQUIRED')
        yield {'result':'ORIGINAL_OWNED_MYSQL_RELAY','hostEndpoint':'127.0.0.1:33080',
               'mysqlCid':expected['id'],'networkId':expected['networkId'],'mysqlPid':expected['pid']}


def mysql_client_environment():
    env=dict(os.environ)
    for key in ('MYSQL_PWD','MYSQL_HOME','MYSQL_TEST_LOGIN_FILE'):env.pop(key,None)
    env['MYSQL_TEST_LOGIN_FILE']='/dev/null'
    return env


def mysql_client_arguments():
    # 8.0.45 supports MYSQL_TEST_LOGIN_FILE, not the 8.2+ --no-login-paths switch.
    return ['mysql','--defaults-file=/dev/stdin','--protocol=TCP','--host=127.0.0.1','--user=root',
            '--connect-timeout=1','--batch','--skip-column-names','--execute','SELECT @@server_uuid,VERSION(),@@port']


def namespace_mysql_arguments(cid,arguments):
    return DOCKER+['exec','-i','--env','MYSQL_TEST_LOGIN_FILE=/dev/null','--env','MYSQL_PWD=',
                   '--env','MYSQL_HOME=',cid]+arguments+['--port=3306']


def mysql_proof(obj,network_id):
    host=obj['HostConfig']
    return {'cid':obj['Id'],'image':obj['Config']['Image'],'imageId':obj['Image'],'networkId':network_id,
            'memory':host.get('Memory'),'memorySwap':host.get('MemorySwap'),'nanoCpus':host.get('NanoCpus'),'pids':host.get('PidsLimit')}


def safe_mysql_state(obj):
    state=obj['State']; health=state.get('Health',{})
    return {**{k:state.get(k) for k in ('Status','Running','Paused','Restarting','OOMKilled','Dead','Pid','ExitCode','StartedAt','FinishedAt')},
            'runtimeError':'PRESENT' if state.get('Error') else 'NONE',
            'health':{'status':health.get('Status') if health.get('Status') in ('starting','healthy','unhealthy') else 'NOT_CONFIGURED_OR_UNKNOWN',
                      'failingStreak':health.get('FailingStreak') if type(health.get('FailingStreak')) is int else None}}


def mysql_private_directory():
    OUT.mkdir(parents=True,exist_ok=True)
    if not MYSQL_PRIVATE.exists(): MYSQL_PRIVATE.mkdir(mode=0o700)
    require(not MYSQL_PRIVATE.is_symlink() and MYSQL_PRIVATE.is_dir()
            and MYSQL_PRIVATE.stat().st_mode & 0o777==0o700 and MYSQL_PRIVATE.stat().st_uid==os.getuid(),
            'PRIVATE_RESOURCE_PATH_UNSAFE')
    return MYSQL_PRIVATE


def classify_mysql_stderr(raw):
    # Emit only these categories, never client error text/usernames/queries or daemon messages.
    text=raw.decode(errors='replace').lower()
    for marker,code in (('error 1045','AUTHENTICATION_REJECTED'),('error 1040','CONNECTION_LIMIT'),
                        ('error 2003','TCP_CONNECT_FAILED'),('error 2002','CONNECT_FAILED'),
                        ('unknown option','CLIENT_OPTION_REJECTED'),('unknown variable','CLIENT_OPTION_REJECTED'),
                        ('cannot connect to the docker daemon','DOCKER_UNAVAILABLE'),('no such container','CONTAINER_NOT_FOUND')):
        if marker in text:return code
    return 'STDERR_PRESENT_UNCLASSIFIED' if raw else 'NO_STDERR'


def mysql_diagnostic(obj):
    declared=mysql_names(os.environ)[1];runtime=obj.get('NetworkSettings',{}).get('Networks',{})
    tmpfs=obj['HostConfig'].get('Tmpfs',{})
    result={'state':safe_mysql_state(obj),'configuredNetwork':obj['HostConfig'].get('NetworkMode'),
            'declaredNetworkEndpoint':{key:runtime.get(declared,{}).get(key) for key in ('NetworkID','EndpointID','IPAddress')},
            'additionalNetworkCount':len(set(runtime)-{declared}),
            'hostBudget':{key:obj['HostConfig'].get(key) for key in ('Memory','MemorySwap','NanoCpus','PidsLimit')},
            'mysqlTmpfs':{'present':'/var/lib/mysql' in tmpfs,'matchesConfigured768m':tmpfs.get('/var/lib/mysql')=='rw,size=768m'},
            'noDockerPublishedPorts':not obj['HostConfig'].get('PortBindings'),
            'rootCause':'UNDETERMINED_REQUIRES_OBSERVED_DIAGNOSTICS'}
    try:
        private=mysql_private_directory();log=private/'mysql.log'
        require(not log.is_symlink(),'PRIVATE_RESOURCE_PATH_UNSAFE')
        with log.open('wb') as stream:
            os.chmod(log,0o600)
            status=subprocess.run(DOCKER+['logs','--tail','2000',obj['Id']],stdout=stream,stderr=subprocess.STDOUT,timeout=15)
        with log.open('rb') as stream:
            size=log.stat().st_size;stream.seek(max(0,size-2*1024*1024));raw=stream.read()
        text=raw.decode(errors='replace')
        markers=('Starting temporary server','Temporary server started','Temporary server stopped',
                 'MySQL init process done. Ready for start up.','ready for connections',
                 'No space left on device','Cannot allocate memory','Out of memory')
        result['log']={'result':'PRIVATE_TAIL_SAVED' if status.returncode==0 else 'PRIVATE_LOG_COMMAND_FAILED',
                       'tailOnly':True,'sampleBytes':len(raw),'sampleSha256':hashlib.sha256(raw).hexdigest(),
                       'markers':{marker:marker in text for marker in markers},
                       'mysqlErrorCodes':sorted(set(re.findall(r'\bMY-[0-9]{6}\b',text)))[:64]}
    except Exception as failure:result['log']={'result':'UNKNOWN','type':type(failure).__name__}
    if obj['State'].get('Running') and obj['State'].get('Pid',0)>0:
        try:
            source_hash=command(DOCKER+['exec',obj['Id'],'sha256sum','/usr/local/bin/docker-entrypoint.sh']).decode().split()[0]
            require(re.fullmatch('[0-9a-f]{64}',source_hash),'UNCLASSIFIED_RESOURCE_FAILURE')
            result['entrypointSha256']=source_hash
        except Exception as failure:result['entrypointSha256']={'result':'UNKNOWN','type':type(failure).__name__}
        try:result['cgroup']=read_cgroup(obj['State']['Pid'],obj['Id'])
        except Exception as failure:result['cgroup']={'result':'UNKNOWN','type':type(failure).__name__}
        try:
            output=command(DOCKER+['exec',obj['Id'],'df','-Pk','/var/lib/mysql']).decode().splitlines()[-1].split()
            require(len(output)>=6 and all(x.isdigit() for x in output[1:4]),'UNCLASSIFIED_RESOURCE_FAILURE')
            result['disk']={'blocksKiB':int(output[1]),'usedKiB':int(output[2]),'availableKiB':int(output[3])}
        except Exception as failure:result['disk']={'result':'UNKNOWN','type':type(failure).__name__}
    else:
        result['cgroup']={'result':'UNKNOWN_PROCESS_NOT_RUNNING'};result['disk']={'result':'UNKNOWN_PROCESS_NOT_RUNNING'}
    result['hostDiskFreeBytes']=shutil.disk_usage(ROOT).free
    return result


def read_bootstrap(required=True):
    if not BOOTSTRAP_REPORT.is_file() or BOOTSTRAP_REPORT.is_symlink():
        require(not required,'BOOTSTRAP_NOT_VERIFIED');return None
    require(BOOTSTRAP_REPORT.stat().st_size<2*1024*1024,'BOOTSTRAP_NOT_VERIFIED')
    report=json.loads(BOOTSTRAP_REPORT.read_text())
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')}==identity(os.environ),'BOOTSTRAP_SOURCE_MISMATCH')
    return report


def require_mysql_bootstrap():
    report=read_bootstrap()
    require(report.get('result')=='READY' and report.get('originalExit')==0 and not report.get('firstFailure')
            and report.get('readiness',{}).get('result')=='BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED'
            and report['readiness'].get('cid')==os.environ.get('HTTP_MYSQL_CID')
            and report.get('hostPath',{}).get('result')=='ORIGINAL_OWNED_MYSQL_RELAY'
            and report['hostPath'].get('mysqlCid')==report['readiness']['cid']
            and report['hostPath'].get('hostEndpoint')=='127.0.0.1:33080'
            and report['hostPath'].get('closedAfterReadiness') is True,'BOOTSTRAP_NOT_VERIFIED')
    return {'sourceSha':report['sourceSha'],'runId':report['runId'],'attempt':report['attempt'],'class':report['class'],
            'result':report['result'],'originalExit':report['originalExit'],'readiness':report['readiness'],'hostPath':report['hostPath'],
            'reportSha256':digest(BOOTSTRAP_REPORT)}


def bootstrap_init():
    PUBLIC.mkdir(parents=True,exist_ok=True)
    report={**resource_envelope(os.environ),'result':'NOT_COMPLETED','phase':'BOOTSTRAP_INIT',
            'readiness':{'result':'UNKNOWN'},'originalExit':None,'businessExecution':'NOT_ENTERED_BY_BOOTSTRAP'}
    try:
        identity_gate(os.environ)
        require(not BOOTSTRAP_REPORT.exists() and not BOOTSTRAP_REPORT.is_symlink(),'NEVER_ADOPT_PREVIOUS_BOOTSTRAP')
        write(BOOTSTRAP_REPORT,report)
        require(command(DOCKER+['context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
                =='unix:///var/run/docker.sock','LOCAL_DOCKER_ONLY')
        mysql_private_directory();report['hostDiskFreeBytes']=shutil.disk_usage(ROOT).free
        report['phase']='SHELL_RESOURCE_CREATE';write(BOOTSTRAP_REPORT,report)
    except Exception as failure:
        report['firstFailure']=resource_failure(failure,'BOOTSTRAP_INIT');report['originalExit']=2
        report['result']='NOT_READY';write(BOOTSTRAP_REPORT,report);raise


def bootstrap_failure(original_exit):
    require(type(original_exit) is int and 1<=original_exit<=255,'ORIGINAL_SHELL_EXIT_REQUIRED')
    PUBLIC.mkdir(parents=True,exist_ok=True)
    report=read_bootstrap(required=False) or {**resource_envelope(os.environ),'readiness':{'result':'UNKNOWN'}}
    report.setdefault('firstFailure',{'phase':'SHELL_RESOURCE_CREATE','type':'ShellExit','reasonCode':'SHELL_BOOTSTRAP_FAILED'})
    if report.get('originalExit') in (None,0):report['originalExit']=original_exit
    report['result']='NOT_READY'
    write(BOOTSTRAP_REPORT,report) # Persist the original failure before slower private forensic capture.
    try:
        identity_gate(os.environ);net=owned_network(os.environ)
        if net is not None:report['network']={'cid':net['Id'],'name':net['Name']}
    except Exception as failure:report['networkCaptureFailure']=resource_failure(failure,'BOOTSTRAP_NETWORK_CAPTURE')
    write(BOOTSTRAP_REPORT,report)
    try:
        identity_gate(os.environ);obj=owned_mysql(os.environ)
        if obj is not None:
            report['mysql']=mysql_proof(obj,os.environ.get('HTTP_BUDGET_NETWORK_ID'))
            report['diagnostic']={'state':safe_mysql_state(obj)};write(BOOTSTRAP_REPORT,report)
            report['diagnostic']=mysql_diagnostic(obj)
    except Exception as failure:report['captureFailure']=resource_failure(failure,'BOOTSTRAP_FAILURE_CAPTURE')
    write(BOOTSTRAP_REPORT,report)


def mysql_ready_probe(cid,deadline,before_host=None):
    require(re.fullmatch('[0-9a-f]{48}',os.environ.get('CHENGYIN_MYBATIS_IT_PASSWORD',''))
            and os.environ.get('CHENGYIN_MYBATIS_IT_USER')=='root','ORIGINAL_SYNTHETIC_MYSQL_CREDENTIAL_REQUIRED')
    config=('[client]\npassword='+os.environ['CHENGYIN_MYBATIS_IT_PASSWORD']+'\n').encode()
    base=mysql_client_arguments()
    answers={};observed={};private=mysql_private_directory()
    # Do not open the original relay until MySQL is really accepting namespace TCP; expected init
    # connection-refused retries must not poison the relay owner's successful-phase invariant.
    for side,args in (('namespace',namespace_mysql_arguments(cid,base)),('host',base+['--port=33080'])):
        if side=='host' and 'namespace' not in answers:
            observed[side]={'result':'WAITING_FOR_NAMESPACE_TCP'};continue
        if side=='host' and before_host is not None:before_host()
        remaining=deadline-time.monotonic()
        if remaining<=0: observed[side]={'result':'DEADLINE_EXHAUSTED'};continue
        try:
            result=subprocess.run(args,input=config,capture_output=True,timeout=min(3,remaining),env=mysql_client_environment())
            observed[side]={'exit':result.returncode,'stderrCategory':classify_mysql_stderr(result.stderr)}
            code=re.search(rb'\bERROR\s+([0-9]{3,5})\b',result.stderr)
            if code:observed[side]['mysqlClientCode']=int(code[1])
            error_path=private/(side+'-probe.stderr');require(not error_path.is_symlink(),'PRIVATE_RESOURCE_PATH_UNSAFE')
            with error_path.open('wb') as stream:os.chmod(stream.name,0o600);stream.write(result.stderr)
            if result.returncode==0:
                values=result.stdout.decode().strip().split('\t')
                if len(values)==3 and re.fullmatch('[0-9a-f-]{36}',values[0]) and values[1].split('-')[0]=='8.0.45' and values[2]=='3306': answers[side]=values
                else:observed[side]['result']='INVALID_METADATA_RESPONSE'
        except subprocess.TimeoutExpired:observed[side]={'result':'CLIENT_TIMEOUT'}
    verified=bool(len(answers)==2 and answers['host']==answers['namespace'])
    return observed,({'cid':cid,'serverUuid':answers['host'][0],'version':answers['host'][1],
                      'hostEndpoint':'127.0.0.1:33080','executionEndpoint':'127.0.0.1:3306'} if verified else None)


def bootstrap_ready():
    report=read_bootstrap();deadline=time.monotonic()+90;report['phase']='MYSQL_TCP_READINESS';report['probes']=[]
    host_path=contextlib.ExitStack();host_proof=None
    def before_host():
        nonlocal host_proof
        if host_proof is None:host_proof=host_path.enter_context(mysql_host_path());report['hostPath']=host_proof
    try:
        identity_gate(os.environ)
        for attempt in range(1,91):
            obj,proof=backing_mysql(os.environ);report['mysql']=proof
            observed,address=mysql_ready_probe(obj['Id'],deadline,before_host)
            report['probes'].append({'attempt':attempt,'elapsedSeconds':90-max(0,deadline-time.monotonic()),'endpoints':observed})
            if address is not None:
                require(time.monotonic()<=deadline,'MYSQL_TCP_READY_BUDGET_EXHAUSTED')
                report.update(result='READY',originalExit=0,readiness={'result':'BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED',**address})
                write(BOOTSTRAP_REPORT,report)
                current=owned_mysql(os.environ)
                require(current is not None and current['State'].get('Running') is True,'MYSQL_PROCESS_EXITED_BEFORE_READY')
                report['diagnostic']={'state':safe_mysql_state(current)}
                try:report['diagnostic']=mysql_diagnostic(current)
                except Exception as capture:report['captureFailure']=resource_failure(capture,'MYSQL_READY_DIAGNOSTIC')
                host_path.close();report['hostPath']['closedAfterReadiness']=True
                write(BOOTSTRAP_REPORT,report);return 0
            write(BOOTSTRAP_REPORT,report)
            remaining=deadline-time.monotonic()
            if remaining<=0:break
            time.sleep(min(1,remaining))
        raise ValueError('MYSQL_TCP_READY_BUDGET_EXHAUSTED')
    except Exception as failure:
        report.update(result='NOT_READY',originalExit=1)
        report.setdefault('firstFailure',resource_failure(failure,'MYSQL_TCP_READINESS'))
        write(BOOTSTRAP_REPORT,report)
        try:
            obj=owned_mysql(os.environ)
            if obj is not None:
                report['diagnostic']={'state':safe_mysql_state(obj)};write(BOOTSTRAP_REPORT,report)
                report['diagnostic']=mysql_diagnostic(obj)
        except Exception as capture:report['captureFailure']=resource_failure(capture,'MYSQL_FAILURE_CAPTURE')
        write(BOOTSTRAP_REPORT,report)
        print('Combined MySQL bootstrap: '+json.dumps(report['firstFailure'],sort_keys=True),file=__import__('sys').stderr)
        return 1
    finally:
        # No listener/relay survives a workflow step. Errors remain failures, never a fallback endpoint.
        host_path.close()


def run_host_path():
    identity_gate(os.environ);bootstrap=require_mysql_bootstrap()
    report={**resource_envelope(os.environ),'result':'NOT_COMPLETED','originalExit':None}
    def publish():
        try:write(PUBLIC/'combined-http-host-path.json',report)
        except Exception as failure:
            print('Combined host path evidence: '+json.dumps(resource_failure(failure,'HOST_PATH_EVIDENCE_WRITE'),sort_keys=True),
                  file=__import__('sys').stderr)
            if report['originalExit'] in (None,0):raise
    try:
        with mysql_host_path() as transport:
            report['transport']=transport
            require(transport['mysqlCid']==bootstrap['readiness']['cid'],'BOOTSTRAP_NOT_VERIFIED')
            require(mysql_address_proof(transport['mysqlCid'])=={key:value for key,value in bootstrap['readiness'].items() if key!='result'},
                    'BOOTSTRAP_NOT_VERIFIED')
            env=dict(os.environ,MYSQL_TEST_LOGIN_FILE='/dev/null');env.pop('MYSQL_HOME',None)
            result=subprocess.run(['bash',str(ROOT/'chengyinhub-admin/src/test/tools/generic-gameplay-http-e2e.sh')],env=env)
            # Original fixed shell keeps creation/test/finalize/schema cleanup inside this same path.
            report['originalExit']=result.returncode if result.returncode>=0 else 128-result.returncode
        report['result']='ORIGINAL_SHELL_AND_HOST_PATH_CLOSED' if report['originalExit']==0 else 'ORIGINAL_SHELL_FAILED'
        publish()
        return report['originalExit']
    except Exception as failure:
        report.update(result='NOT_ACCEPTED',failure=resource_failure(failure,'FIXED_HOST_PATH'))
        publish()
        if report['originalExit'] not in (None,0):return report['originalExit']
        raise


def identity_gate(env):
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and env.get('GITHUB_REPOSITORY') == 'liboyang42-cpu/hengyin'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('HTTP_SCOPE') == 'controlled-combination'
            and env.get('CHENGYIN_HTTP_E2E_PROFILE') == 'github-hosted'
            and env.get('CHENGYIN_HTTP_E2E_TESTS') == CLASS and not env.get('CHENGYIN_VPS_TEST_RUN'), 'FIXED_HOSTED_CLASS_ONLY')
    require(re.fullmatch('[0-9a-f]{40}', env.get('SOURCE_SHA','')), 'FULL_SOURCE_REQUIRED')
    require(all(re.fullmatch('[1-9][0-9]*', env.get(k,'')) for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')), 'RUN_ATTEMPT_REQUIRED')


def gate(env):
    identity_gate(env)
    require(env.get('CHENGYIN_MYBATIS_IT_HOST') == '127.0.0.1' and env.get('CHENGYIN_MYBATIS_IT_PORT') == '33080', 'ORIGINAL_LOOPBACK_MYSQL_REQUIRED')
    require(env.get('B_PAYMENT_FACT_DDL_APPROVED') == '1', 'ORIGINAL_DDL_GATE_REQUIRED')
    require(env.get('CHENGYIN_AI_REDIS_IT_DATABASE') == '15', 'ORIGINAL_COMBINED_REDIS_DB_REQUIRED')
    require(env.get('CHENGYIN_MYBATIS_IT_USER') == 'root' and re.fullmatch('[0-9a-f]{48}',env.get('CHENGYIN_MYBATIS_IT_PASSWORD','')),
            'ORIGINAL_SYNTHETIC_MYSQL_CREDENTIAL_REQUIRED')
    port=env.get('CHENGYIN_HTTP_E2E_REDIS_PORT','')
    require(re.fullmatch('[0-9]{1,5}',port) and 1<=int(port)<=65535
            and env.get('CHENGYIN_AI_REDIS_IT_PORT') == port, 'SAME_EXPLICIT_PRIVATE_REDIS_REQUIRED')
    require(not env.get('CHENGYIN_AI_REDIS_IT_PASSWORD'), 'ORIGINAL_CASHLESS_PRIVATE_REDIS_ONLY')


def identity(env):
    return {'sourceSha':env['SOURCE_SHA'], 'runId':env['GITHUB_RUN_ID'], 'attempt':env['GITHUB_RUN_ATTEMPT'], 'class':CLASS}


def labels(env, nonce=None):
    result = {'chengyin.http.run':env['GITHUB_RUN_ID'], 'chengyin.http.attempt':env['GITHUB_RUN_ATTEMPT'],
              'chengyin.http.source':env['SOURCE_SHA'], 'chengyin.http.class':CLASS}
    if nonce: result['chengyin.http.budget'] = nonce
    return result


def inspect(target):
    value = json.loads(command(DOCKER + ['inspect', target]))
    require(len(value) == 1 and re.fullmatch('[0-9a-f]{64}', value[0].get('Id','')), 'ACTUAL_ID_REQUIRED')
    return value[0]


def absent(kind, target, observations=None):
    result = subprocess.run(DOCKER + [kind, 'inspect', target], capture_output=True, timeout=15)
    if observations is not None:observations.append({'operation':'ABSENCE_INSPECT','kind':kind,**native_operation(kind,target,result)})
    require(exact_absence(kind,target,result),'ABSENCE_NOT_INDEPENDENTLY_PROVEN')
    return {'exit':result.returncode,'category':'EXACT_NATIVE_TARGET_ABSENT'}


def validate_container(obj, name, image, expected_labels, memory, network, cid=None):
    validate_owner(obj, name, image, expected_labels, cid)
    host = obj['HostConfig']
    require(host['Memory'] == memory and host['MemorySwap'] == memory and host['NanoCpus'] == 1000000000
            and host['PidsLimit'] == 256, 'HARD_BUDGET_MISMATCH')
    require(not host.get('Privileged') and not host.get('PidMode') and not host.get('CapAdd')
            and set(host.get('CapDrop',[])) == {'ALL'} and not host.get('PortBindings'), 'UNSAFE_CONTAINER_CAPABILITY')
    require(host['NetworkMode'] == network, 'WRONG_OWNED_NETWORK_NAMESPACE')
    return {'cid':obj['Id'], 'image':obj['Config']['Image'], 'imageId':obj['Image'],
            'memory':host['Memory'], 'memorySwap':host['MemorySwap'], 'nanoCpus':host['NanoCpus'],
            'pids':host['PidsLimit'], 'networkMode':host['NetworkMode']}


def validate_owner(obj, name, image, expected_labels, cid=None):
    require(re.fullmatch('[0-9a-f]{64}', obj.get('Id','')) and (cid is None or obj['Id'] == cid), 'ACTUAL_CID_MISMATCH')
    require(obj.get('Name') == '/'+name and obj['Config']['Image'] == image, 'FOREIGN_CONTAINER_OR_IMAGE')
    require(all(obj['Config'].get('Labels',{}).get(k) == v for k,v in expected_labels.items()), 'FOREIGN_LABEL')


def backing_mysql(env, cleanup=False):
    expected_name = 'cy-http-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT']+'-'+CLASS.lower()
    network_name = 'cy-http-budget-'+env['GITHUB_RUN_ID']+'-'+env['GITHUB_RUN_ATTEMPT']
    require(env.get('HTTP_MYSQL_CONTAINER') == expected_name and env.get('HTTP_BUDGET_NETWORK') == network_name
            and re.fullmatch('[0-9a-f]{64}',env.get('HTTP_MYSQL_CID','')), 'FIXED_BACKING_IDENTITY_REQUIRED')
    obj = inspect(env['HTTP_MYSQL_CID']); host = obj['HostConfig']
    require(obj['Name'] == '/'+expected_name and obj['Config']['Image'] == MYSQL
            and all(obj['Config']['Labels'].get(k) == v for k,v in labels(env).items()), 'BACKING_MYSQL_FOREIGN')
    require(inspect(expected_name)['Id'] == obj['Id'], 'BACKING_NAME_CID_CHANGED')
    require(json.loads(command(DOCKER+['image','inspect',MYSQL]))[0]['Id']==obj['Image'],'BACKING_ACTUAL_IMAGE_ID_CHANGED')
    if not cleanup:
        require(host['Memory'] == MEMORY and host['MemorySwap'] == MEMORY and host['NanoCpus'] == 1000000000
                and host['PidsLimit'] == 256 and not host.get('Privileged'), 'BACKING_MYSQL_BUDGET_MISMATCH')
        require(not host.get('PortBindings')
                and set(obj['NetworkSettings']['Networks']) == {network_name}, 'BACKING_LOOPBACK_SCOPE_CHANGED')
        require(host.get('Tmpfs',{}).get('/var/lib/mysql')=='rw,size=768m'
                and not any(m.get('Type')=='volume' for m in obj.get('Mounts',[])),'BACKING_MYSQL_EXACT_EPHEMERAL_STORAGE_REQUIRED')
    net = json.loads(command(DOCKER + ['network','inspect',network_name]))[0]
    require(net['Internal'] is True and all(net['Labels'].get(k) == v for k,v in labels(env).items())
            and net['Id']==env.get('HTTP_BUDGET_NETWORK_ID')
            and obj['NetworkSettings']['Networks'][network_name]['NetworkID'] == net['Id'], 'OWNED_INTERNAL_NETWORK_REQUIRED')
    if not cleanup: require(obj['State']['Running'] is True, 'BACKING_MYSQL_NOT_RUNNING')
    return obj, {'cid':obj['Id'],'image':MYSQL,'imageId':obj['Image'],'networkId':net['Id'],
                 'memory':host['Memory'],'memorySwap':host['MemorySwap'],'nanoCpus':host['NanoCpus'],'pids':host['PidsLimit']}


def private_directory(path):
    path = Path(path)
    require(path.is_absolute() and path.parent == Path('/tmp') and not path.is_symlink()
            and path.resolve() == path and re.fullmatch(r'cy-http-budget\.[A-Za-z0-9]{8}',path.name)
            and path.is_dir() and path.stat().st_mode & 0o777 == 0o700
            and path.stat().st_uid == os.getuid(), 'EXACT_PRIVATE_DIRECTORY_REQUIRED')
    return path


def source_manifest():
    require(command(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip() == os.environ['SOURCE_SHA'], 'WRONG_CHECKOUT')
    require(subprocess.run(['git','-C',str(ROOT),'diff','--quiet','HEAD','--'],capture_output=True).returncode == 0, 'DIRTY_SOURCE')
    props = ROOT/'chengyinhub-admin/target/classes/git.properties'
    values = dict(line.split('=',1) for line in props.read_text().splitlines() if '=' in line and not line.startswith('#'))
    require(values.get('git.commit.id',values.get('git.commit.id.full')) == os.environ['SOURCE_SHA'], 'COMPILED_SOURCE_MISMATCH')
    selected = ROOT/'chengyinhub-admin/target/test-classes/com/chengyinhub/business/it'/ (CLASS+'.class')
    require(selected.is_file() and not selected.is_symlink(), 'PRECOMPILED_FIXED_CLASS_REQUIRED')
    jar = ROOT/'chengyinhub-admin/target/chengyinhub-admin.jar'
    require(jar.is_file() and not jar.is_symlink(),'ORIGINAL_SAME_SOURCE_PACKAGED_JAR_REQUIRED')
    with zipfile.ZipFile(jar) as archive:
        properties = dict(line.split('=',1) for line in archive.read('BOOT-INF/classes/git.properties').decode().splitlines()
                          if '=' in line and not line.startswith('#'))
    require(properties.get('git.commit.id',properties.get('git.commit.id.full')) == os.environ['SOURCE_SHA'],'PACKAGED_SOURCE_MISMATCH')
    trees = {}; artifacts = {}
    for module in MODULES:
        paths = sorted(p for area in ('classes','test-classes') for p in (ROOT/module/'target'/area).rglob('*.class'))
        require(paths, 'PRECOMPILED_REACTOR_REQUIRED')
        rows = []
        for path in paths:
            require(not path.is_symlink(), 'SYMLINK_COMPILED_CLASS')
            rows.append((str(path.relative_to(ROOT)),digest(path)))
        trees[module] = {'classes':len(rows),'sha256':hashlib.sha256(json.dumps(rows,separators=(',',':')).encode()).hexdigest()}
        jars = sorted((ROOT/module/'target').glob('*.jar')); require(jars,'ORIGINAL_PACKAGED_REACTOR_REQUIRED')
        for artifact in jars:
            require(not artifact.is_symlink(),'SYMLINK_REACTOR_ARTIFACT');artifacts[str(artifact.relative_to(ROOT))]=digest(artifact)
    return {'classTrees':trees,'reactorJars':artifacts,'selectedClassSha256':digest(selected),'gitPropertiesSha256':digest(props),'artifactJarSha256':digest(jar),
            'freshSchemaReportSha256':digest(OUT/'fresh-schema.json'),
            'execution':'ORIGINAL_SUREFIRE_TEST_CLASSPATH_NOT_PACKAGED_APPLICATION_JAR'}


def child_environment(env):
    # No host PATH/HOME, settings.xml, tokens, SDK credentials, JAVA_TOOL_OPTIONS or arbitrary env.
    child = {k:env[k] for k in ('GITHUB_ACTIONS','GITHUB_REPOSITORY','GITHUB_EVENT_NAME','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT',
             'RUNNER_ENVIRONMENT','SOURCE_SHA','CHENGYIN_HTTP_E2E_PROFILE','CHENGYIN_HTTP_E2E_TESTS',
             'CHENGYIN_MYBATIS_IT_USER','CHENGYIN_MYBATIS_IT_PASSWORD','CHENGYIN_HTTP_E2E_DATABASE',
             'CHENGYIN_HTTP_E2E_SCHEMA_REPORT','B_PAYMENT_FACT_DDL_APPROVED','CHENGYIN_AI_REDIS_IT_DATABASE')}
    require(re.fullmatch(r'cy_e2e_w1_http_[0-9_]+',child['CHENGYIN_HTTP_E2E_DATABASE']), 'ORIGINAL_SCHEMA_SCOPE_REQUIRED')
    require(Path(child['CHENGYIN_HTTP_E2E_SCHEMA_REPORT']) == OUT/'fresh-schema.json','ORIGINAL_SCHEMA_REPORT_REQUIRED')
    child.update(CHENGYIN_MYBATIS_IT_HOST='127.0.0.1',CHENGYIN_MYBATIS_IT_PORT='3306',
                 CHENGYIN_HTTP_E2E_REDIS_PORT=env['CHENGYIN_HTTP_E2E_REDIS_PORT'],CHENGYIN_AI_REDIS_IT_PORT=env['CHENGYIN_HTTP_E2E_REDIS_PORT'],
                 SERVER_ADDRESS='127.0.0.1',TZ='Asia/Shanghai',HOME='/tmp/home',MAVEN_CONFIG='/tmp/m2',
                 GITHUB_SHA=env['SOURCE_SHA'],
                 MAVEN_OPTS='-Xmx128m -Djava.io.tmpdir=/tmp -Duser.home=/tmp/home')
    require(all('\n' not in value and '\r' not in value and '\0' not in value for value in child.values()), 'INVALID_PRIVATE_ENV')
    return child


def load_private(path):
    path = private_directory(path); state = path/'budget.json'
    require(state.is_file() and not state.is_symlink() and state.stat().st_mode & 0o777 == 0o600, 'PRIVATE_STATE_REQUIRED')
    value = json.loads(state.read_text()); require(value['identity'] == identity(os.environ), 'WRONG_SOURCE_RUN_STATE')
    require(value['redisPort']==int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT'])
            and value['schema']==os.environ['CHENGYIN_HTTP_E2E_DATABASE'],'WRONG_PRIVATE_RUNTIME_SCOPE')
    return path,value


def save(path,value): write(path/'budget.json',value)


def create_slot(path,state,role,image,argv,mounts=(),env_file=None):
    name = state['name']+'-'+role; absent('container',name)
    state['slots'][role] = {'name':name,'attempted':True,'cid':None}; save(path,state)
    args = DOCKER+['create','--name',name,'--network','container:'+state['mysql']['cid'],
            '--memory='+('64m' if role == 'redis' else '1g'),'--memory-swap='+('64m' if role == 'redis' else '1g'),
            '--cpus=1','--pids-limit=256','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
            '--tmpfs','/tmp:rw,size='+('32m' if role == 'redis' else '256m'),
            '--user',str(os.getuid())+':'+str(os.getgid()),'--cidfile',str(path/(role+'.cid'))]
    for key,value in labels(os.environ,state['nonce']).items(): args += ['--label',key+'='+value]
    for source,target,mode in mounts: args += ['--mount','type=bind,src='+str(source)+',dst='+str(target)+','+mode]
    if env_file: args += ['--env-file',str(env_file)]
    if role == 'app': args += ['--workdir',str(ROOT),'--entrypoint','/bin/bash']
    else: args += ['--entrypoint','redis-server']
    cid = command(args+[image]+argv,timeout=30).decode().strip()
    require(re.fullmatch('[0-9a-f]{64}',cid) and (path/(role+'.cid')).read_text().strip() == cid, 'CREATED_CID_NOT_PROVEN')
    state['slots'][role]['cid'] = cid; save(path,state)
    obj = inspect(cid)
    state['slots'][role]['budget'] = validate_container(obj,name,image,labels(os.environ,state['nonce']),
            67108864 if role == 'redis' else MEMORY,'container:'+state['mysql']['cid'],cid)
    require(obj['Image']==state['imageIds'][role],'ACTUAL_PINNED_IMAGE_ID_CHANGED')
    require(inspect(name)['Id'] == cid,'CREATED_NAME_ID_CHANGED')
    save(path,state); command(DOCKER+['start',cid]); return cid


def prepare(path):
    gate(os.environ); path = private_directory(path)
    require(command(DOCKER+['context','inspect','--format','{{.Endpoints.docker.Host}}']).decode().strip()
            == 'unix:///var/run/docker.sock','LOCAL_DOCKER_ONLY')
    require(not (path/'budget.json').exists(),'NEVER_ADOPT_PREVIOUS_STATE')
    require(not (OUT/'run.log').exists() and not any((PUBLIC/name).exists() for name in
            ('combined-http-budget.json','combined-http-storage-cleanup.json')),'NEVER_ADOPT_PREVIOUS_OUTPUT')
    mysql,proof = backing_mysql(os.environ)
    bootstrap=require_mysql_bootstrap()
    address=mysql_address_proof(mysql['Id'])
    require(all(address.get(key)==bootstrap['readiness'].get(key) for key in
            ('cid','serverUuid','version','hostEndpoint','executionEndpoint')),'BOOTSTRAP_NOT_VERIFIED')
    cache = Path.home()/'.m2/repository'
    require(cache.is_dir() and not cache.is_symlink(), 'EXISTING_OFFLINE_CACHE_REQUIRED')
    cache_proof=verified_cache(cache)
    require((cache/'org/apache/maven/surefire/surefire-junit-platform/3.5.6/surefire-junit-platform-3.5.6.jar').is_file(),
            'EXISTING_OFFLINE_SUREFIRE_PROVIDER_REQUIRED')
    images = {image:json.loads(command(DOCKER+['image','inspect',image]))[0] for image in (JDK,REDIS)} # Never pull here.
    require(images[JDK]['Id']==cache_proof['imageId'],'FIXED_CACHE_TOOLCHAIN_REQUIRED')
    nonce = uuid.uuid4().hex
    state = {'identity':identity(os.environ),'nonce':nonce,
             'name':'cy-http-combined-'+os.environ['GITHUB_RUN_ID']+'-a'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+nonce[:12],
             'mysql':proof,'mysqlHostPid':mysql['State']['Pid'],'cache':str(cache.resolve()),'cacheProof':cache_proof,
             'mysqlBootstrap':bootstrap,
             'redisPort':int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT']),'schema':os.environ['CHENGYIN_HTTP_E2E_DATABASE'],
             'source':None,'slots':{},'javaProcesses':[],'result':'NOT_ACCEPTED','phase':'PREPARE',
             'imageIds':{'app':images[JDK]['Id'],'redis':images[REDIS]['Id']},
             'jdkEnvironment':dict(item.split('=',1) for item in images[JDK]['Config'].get('Env',[]))}
    save(path,state)
    require(command(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip()==os.environ['SOURCE_SHA'],'WRONG_PREPARATION_SOURCE')
    environment = child_environment(os.environ)
    with (path/'private.env').open('x') as stream:
        os.chmod(path/'private.env',0o600);stream.write(''.join(k+'='+v+'\n' for k,v in sorted(environment.items())))
    (path/'launch').mkdir(mode=0o700)
    probe = ROOT/'ci/CombinedHttpNamespacePortProbe.java'
    (path/'launch/CombinedHttpNamespacePortProbe.java').write_bytes(probe.read_bytes())
    os.chmod(path/'launch/CombinedHttpNamespacePortProbe.java',0o600)
    state['namespaceProbeSourceSha256']=digest(probe);save(path,state)
    (path/'launch/settings.xml').write_text(EMPTY_SETTINGS)
    os.chmod(path/'launch/settings.xml',0o600)
    cid = create_slot(path,state,'redis',REDIS,['--bind','127.0.0.1','--port',str(state['redisPort']),'--protected-mode','yes',
                                              '--save','','--appendonly','no','--dir','/tmp'])
    ready = False
    for _ in range(50):
        result = subprocess.run(DOCKER+['exec',cid,'redis-cli','-h','127.0.0.1','-p',str(state['redisPort']),'-n','15','PING'],capture_output=True,timeout=5)
        if result.returncode == 0 and result.stdout.strip() == b'PONG': ready=True;break
        require(inspect(cid)['State']['Running'],'PRIVATE_REDIS_EXITED')
        time.sleep(.1)
    require(ready,'PRIVATE_BOUNDED_REDIS_NOT_READY'); state['phase']='PREPARED';save(path,state)


def read_cgroup(pid, cid):
    text = Path('/proc',str(pid),'cgroup').read_text()
    lines = text.splitlines(); require(len(lines) == 1 and lines[0].startswith('0::/'), 'CGROUP_V2_REQUIRED')
    relative = lines[0][3:]; require(cid in relative and '..' not in Path(relative).parts, 'PID_OUTSIDE_EXACT_CGROUP')
    root = Path('/sys/fs/cgroup')/relative.lstrip('/')
    require(root.resolve().is_relative_to(Path('/sys/fs/cgroup')), 'FOREIGN_CGROUP_PATH')
    limits = {name:(root/name).read_text().strip() for name in ('memory.max','memory.swap.max','cpu.max','pids.max')}
    validate_cgroup_limits(limits)
    events = dict(line.split() for line in (root/'memory.events').read_text().splitlines())
    require('oom_kill' in events and all(value.isdigit() for value in events.values()),'ACTUAL_OOM_COUNTER_REQUIRED')
    return {'pathSha256':hashlib.sha256(relative.encode()).hexdigest(),'limits':limits,'memoryEvents':events}


def validate_cgroup_limits(limits):
    require(limits['memory.max'] == str(MEMORY) and limits['memory.swap.max'] == '0'
            and limits['pids.max'] == '256','ACTUAL_CGROUP_MEMORY_SWAP_PIDS_MISMATCH')
    cpu = limits['cpu.max'].split()
    require(len(cpu) == 2 and all(x.isdigit() for x in cpu) and int(cpu[0]) == int(cpu[1]) > 0,
            'ACTUAL_CGROUP_CPU_MISMATCH')


def booter_binding(argv, cache):
    require('-jar' in argv and argv.index('-jar')+1 < len(argv), 'ORIGINAL_SUREFIRE_FORK_REQUIRED')
    path = Path(argv[argv.index('-jar')+1])
    require(path.resolve().is_relative_to(ROOT/'chengyinhub-admin/target') and path.name.startswith('surefirebooter-')
            and path.suffix == '.jar' and path.is_file() and not path.is_symlink() and path.stat().st_size < 16*1024*1024,
            'SAME_TARGET_BOOTER_REQUIRED')
    with zipfile.ZipFile(path) as archive:
        raw = archive.read('META-INF/MANIFEST.MF')
        require(len(raw) < 1024*1024,'BOUNDED_BOOTER_MANIFEST_REQUIRED')
    # Surefire folds manifest lines. Decode only its fixed local Class-Path, never a remote URL.
    text = raw.decode().replace('\r\n','\n').replace('\n ','')
    line = next((x[len('Class-Path: '):] for x in text.splitlines() if x.startswith('Class-Path: ')), '')
    require(str(ROOT/'chengyinhub-admin/target/test-classes') in line
            and str(ROOT/'chengyinhub-admin/target/classes') in line, 'REAL_TEST_CLASSPATH_BINDING_REQUIRED')
    for entry in line.split():
        parsed = urllib.parse.urlsplit(entry); require(parsed.scheme in ('','file') and not parsed.netloc,'REMOTE_BOOTER_CLASSPATH_FORBIDDEN')
        local = Path(urllib.parse.unquote(parsed.path))
        if not local.is_absolute(): local = path.parent/local
        local = local.resolve()
        require(local.is_relative_to(Path('/cache/repository')) or
                any(local.is_relative_to(ROOT/module/'target') for module in MODULES),'FOREIGN_BOOTER_CLASSPATH')
    agents = [x[len('-javaagent:'):] for x in argv if x.startswith('-javaagent:')]
    require(len(agents) == 1 and re.fullmatch(r'/cache/repository/org/mockito/mockito-core/[A-Za-z0-9_.-]+/mockito-core-[A-Za-z0-9_.-]+\.jar',agents[0]),
            'ORIGINAL_MOCKITO_STARTUP_AGENT_REQUIRED')
    agent = cache/Path(agents[0]).relative_to('/cache/repository')
    require(agent.is_file() and not agent.is_symlink(),'CACHED_ORIGINAL_AGENT_REQUIRED')
    return {'booterJarSha256':digest(path),'manifestSha256':hashlib.sha256(raw).hexdigest(),'mockitoAgentSha256':digest(agent)}


def sample_java(state, obj):
    cid = obj['Id']; init = obj['State']['Pid']; require(init > 0,'ACTUAL_APP_HOST_PID_REQUIRED')
    base = read_cgroup(init,cid)
    state['cgroupMemoryEvents'] = base['memoryEvents']
    # Shared network namespace must still belong to the same current MySQL process.
    mysql = inspect(state['mysql']['cid'])
    require(mysql['State']['Running'] and mysql['State']['Pid'] == state['mysqlHostPid'], 'MYSQL_PROCESS_REPLACED')
    rows = command(DOCKER+['top',cid,'-eo','pid,ppid,comm']).decode().splitlines()[1:]
    for row in rows:
        fields = row.split(); require(len(fields) == 3,'BOUNDED_PROCESS_IDENTITY_REQUIRED')
        if fields[2] != 'java': continue
        pid = int(fields[0]); proc = Path('/proc',str(pid))
        try:
            stat = (proc/'stat').read_text(); tail = stat[stat.rfind(')')+2:].split()
            start = int(tail[19]); raw = (proc/'cmdline').read_bytes(); require(len(raw)<1024*1024,'BOUNDED_JAVA_IDENTITY_REQUIRED')
            argv = [x.decode() for x in raw.split(b'\0') if x]
            cg = read_cgroup(pid,cid); require(cg['pathSha256'] == base['pathSha256'] and cg['limits']==base['limits'],'FORK_ESCAPED_FIXED_BUDGET')
            namespace_pids = next(x.split()[1:] for x in (proc/'status').read_text().splitlines() if x.startswith('NSpid:'))
            require(namespace_pids and int(namespace_pids[0]) == pid,'ACTUAL_NAMESPACE_PID_REQUIRED')
            role = 'MAVEN' if any('org.codehaus.plexus.classworlds.launcher.Launcher' == x for x in argv) else (
                    'SUREFIRE' if any('surefirebooter-' in x and x.endswith('.jar') for x in argv) else 'DESCENDANT_JVM')
            binding = booter_binding(argv,Path(state['cache'])) if role == 'SUREFIRE' else {}
            if role == 'MAVEN': require('-Dtest='+CLASS in argv,'FIXED_CLASS_NOT_ON_ACTUAL_MAVEN_PROCESS')
            proof = {'hostPid':pid,'parentHostPid':int(fields[1]),'namespacePid':int(namespace_pids[-1]),'startTicks':start,
                     'role':role,'argvSha256':hashlib.sha256(raw).hexdigest(),'cgroup':cg,**binding}
            key = (pid,start)
            if not any((x['hostPid'],x['startTicks']) == key for x in state['javaProcesses']): state['javaProcesses'].append(proof)
        except FileNotFoundError:
            # A process can exit between top/proc reads. It supplies no identity proof.
            continue


def fixed_maven_command():
    return ['-c','mkdir -p /tmp/home /tmp/m2 || exit 98; while [ ! -f /budget/release ]; do sleep .1; done; '
            'mvn "$@" & child=$!; wait "$child"; status=$?; printf "%s\\n" "$status" > /tmp/cy-budget.exit.next; '
            'mv /tmp/cy-budget.exit.next /tmp/cy-budget.exit; '
            'for i in {1..1200}; do [ -f /budget/stop ] && break; sleep .1; done; exit "$status"','--',
            '-o','-q','--settings','/budget/settings.xml','--global-settings','/budget/settings.xml',
            '-Dmaven.repo.local=/cache/repository','-pl','chengyinhub-admin','-am','test',
            '-DargLine=-Xmx512m -Djava.security.manager=allow --add-opens=java.base/java.lang=ALL-UNNAMED -Djava.io.tmpdir=/tmp -Djava.awt.headless=true',
            '-Dtest='+CLASS,'-DforkCount=1','-DreuseForks=true','-DfailIfNoTests=false','-Dsurefire.failIfNoSpecifiedTests=false']


def validate_app_inputs(obj,path,state):
    host = obj['HostConfig']; require(host['ReadonlyRootfs'] is True and not host.get('VolumesFrom')
            and not host.get('Devices') and obj['Config']['User'] == str(os.getuid())+':'+str(os.getgid()), 'PRIVATE_APP_IDENTITY_OR_ROOTFS_CHANGED')
    require(obj['Config']['Entrypoint'] == ['/bin/bash'] and obj['Config']['Cmd'] == fixed_maven_command(),'FIXED_OFFLINE_MAVEN_COMMAND_CHANGED')
    expected = {(str(ROOT),str(ROOT),False),(state['cache'],'/cache/repository',False),(str(path/'launch'),'/budget',False)}
    expected.update((str(ROOT/module/'target'),str(ROOT/module/'target'),True) for module in MODULES)
    binds = {(m['Source'],m['Destination'],m['RW']) for m in obj['Mounts'] if m['Type']=='bind'}
    require(binds == expected and all(m['Type']=='bind' or (m['Type']=='tmpfs' and m['Destination']=='/tmp')
            for m in obj['Mounts']), 'PRIVATE_MOUNT_BOUNDARY_CHANGED')
    actual = dict(item.split('=',1) for item in obj['Config']['Env'])
    expected_env = {**state['jdkEnvironment'],**child_environment(os.environ)}
    require(actual == expected_env,'PRIVATE_ENV_BOUNDARY_CHANGED')
    return {'sourceReadOnly':True,'dependencyCacheReadOnly':True,'hostSettingsMounted':False,
            'privateLaunchOnly':True,'forwardedEnvironmentNames':sorted(child_environment(os.environ)),
            'writableTargets':list(MODULES),'forkCount':1,'offline':True}


def run(path):
    gate(os.environ); path,state = load_private(path); require(state['phase'] == 'PREPARED','PREPARED_OWNER_REQUIRED')
    backing_mysql(os.environ);require(verified_cache(Path(state['cache']))==state['cacheProof'],'OFFLINE_CACHE_PROOF_CHANGED')
    state['source']=source_manifest();save(path,state)
    state['mysqlAddressProof'] = mysql_address_proof(state['mysql']['cid'])
    mounts = [(ROOT,ROOT,'readonly'),(Path(state['cache']),Path('/cache/repository'),'readonly'),(path/'launch',Path('/budget'),'readonly')]
    for module in MODULES:
        target = ROOT/module/'target'; require(target.is_dir() and not target.is_symlink(),'ORIGINAL_TARGET_REQUIRED')
        mounts.append((target,target,'readonly=false'))
    state['phase']='APP_CREATE';save(path,state)
    cid = create_slot(path,state,'app',JDK,fixed_maven_command(),mounts,path/'private.env')
    obj = inspect(cid); read_cgroup(obj['State']['Pid'],cid) # actual limits before releasing Maven/test.
    state['launchProof'] = validate_app_inputs(obj,path,state)
    app_namespace = command(DOCKER+['exec',cid,'readlink','/proc/self/ns/net']).strip()
    mysql_namespace = command(DOCKER+['exec',state['mysql']['cid'],'readlink','/proc/self/ns/net']).strip()
    require(app_namespace == mysql_namespace and re.fullmatch(rb'net:\[[0-9]+\]',app_namespace),'NOT_SAME_OWNED_LOOPBACK_NAMESPACE')
    state['networkNamespaceSha256'] = hashlib.sha256(app_namespace).hexdigest();save(path,state)
    (path/'launch/release').write_text('fixed-source-budget-verified\n')
    deadline = time.monotonic()+LIMIT_SECONDS; state['phase']='APP_EXECUTION';save(path,state)
    while True:
        obj = inspect(cid)
        validate_container(obj,state['slots']['app']['name'],JDK,labels(os.environ,state['nonce']),MEMORY,'container:'+state['mysql']['cid'],cid)
        require(obj['State']['Running'],'APP_EXIT_BEFORE_MAVEN_STATUS')
        require(time.monotonic()<deadline,'OWNED_EXECUTION_DEADLINE')
        sample_java(state,obj);save(path,state)
        require(int(state['cgroupMemoryEvents']['oom_kill']) == 0,'ACTUAL_CGROUP_CHILD_OOM_KILL')
        status = command(DOCKER+['exec',cid,'/bin/bash','-c','if [ -f /tmp/cy-budget.exit ]; then cat /tmp/cy-budget.exit; fi']).decode().strip()
        if status:
            require(re.fullmatch('[0-9]{1,3}',status) and int(status)<256,'ACTUAL_MAVEN_EXIT_REQUIRED');break
        time.sleep(.5)
    state['mavenExit'] = int(status);state['phase']='MAVEN_EXITED_AWAIT_CLEANUP';save(path,state)
    with (OUT/'run.log').open('xb') as stream:
        result = subprocess.run(DOCKER+['logs',cid],stdout=stream,stderr=subprocess.STDOUT,timeout=30)
    require(result.returncode == 0 and (OUT/'run.log').stat().st_size < 32*1024*1024,'PRIVATE_LOG_NOT_COMPLETE')
    require(source_manifest() == state['source'],'COMPILED_POSTIMAGE_DRIFT')
    require(verified_cache(Path(state['cache']))==state['cacheProof'],'OFFLINE_CACHE_PROOF_CHANGED')
    require(any(x['role']=='MAVEN' for x in state['javaProcesses']) and any(x['role']=='SUREFIRE' for x in state['javaProcesses']), 'REAL_MAVEN_AND_JUNIT_PID_PROOF_REQUIRED')
    require(not obj['State']['OOMKilled'],'APP_OOM_OR_UNKNOWN_EXIT')
    require(mysql_address_proof(state['mysql']['cid']) == state['mysqlAddressProof'],'MYSQL_ADDRESS_OR_UUID_DRIFT')
    return state['mavenExit']


def mysql_address_proof(cid):
    # Password only on private stdin, never argv/env artifact. Both addresses query the SAME server.
    config = ('[client]\npassword='+os.environ['CHENGYIN_MYBATIS_IT_PASSWORD']+'\n').encode()
    args=mysql_client_arguments()
    responses=[]
    for argv in (args+['--port=33080'],namespace_mysql_arguments(cid,args)):
        result=subprocess.run(argv,input=config,capture_output=True,timeout=20,env=mysql_client_environment())
        require(result.returncode==0,'MYSQL_TCP_IDENTITY_MISMATCH')
        responses.append(result.stdout.decode().strip().split('\t'))
    host,namespace=responses
    require(host == namespace and len(host)==3 and re.fullmatch('[0-9a-f-]{36}',host[0])
            and host[1].split('-')[0]=='8.0.45' and host[2]=='3306','SAME_MYSQL_UUID_BOTH_ADDRESSES_REQUIRED')
    return {'cid':cid,'serverUuid':host[0],'version':host[1],'hostEndpoint':'127.0.0.1:33080','executionEndpoint':'127.0.0.1:3306'}


def cleanup_slot(path,state,role):
    slot = state['slots'].get(role)
    if slot is None: return {'role':role,'result':'NOT_ATTEMPTED'}
    cid = slot.get('cid')
    if cid is None:
        # Recover a lost create response only through its private cidfile and original unique labels.
        candidate = path/(role+'.cid')
        if candidate.is_file() and not candidate.is_symlink(): cid = candidate.read_text().strip()
        else:
            # A unique attempted name/private nonce can recover an actual object, never a foreign one.
            obj = inspect(slot['name']);validate_owner(obj,slot['name'],JDK if role == 'app' else REDIS,labels(os.environ,state['nonce']))
            cid = obj['Id']
        require(re.fullmatch('[0-9a-f]{64}',cid),'CREATE_IDENTITY_UNKNOWN')
    obj = inspect(cid); name = slot['name']; image = JDK if role == 'app' else REDIS
    # Budget rejection does not prevent removal of an independently proven OWN object.
    validate_owner(obj,name,image,labels(os.environ,state['nonce']),cid)
    require(inspect(name)['Id'] == cid,'CLEANUP_NAME_CID_CHANGED')
    slot['cid']=cid # Preserve the recovered actual ID in the public cleanup postimage.
    if obj['State']['Running']: command(DOCKER+['stop','--time','20',cid],timeout=30)
    obj = inspect(cid); require(not obj['State']['Running'] and obj['State']['Pid']==0,'OWNED_PROCESS_STOP_UNKNOWN')
    terminal = {k:obj['State'][k] for k in ('ExitCode','OOMKilled','Running','Pid')}
    command(DOCKER+['rm',cid]);absent('container',cid);absent('container',name)
    return {'role':role,'cid':cid,'result':'EXACT_CID_REMOVED_AND_ABSENT','terminal':terminal}


def finalize(path, original_exit):
    gate(os.environ); path,state = load_private(path); failures=[]; cleaned=[]
    # Recover private original logs before removing the app, including timeout/create failures.
    app = state['slots'].get('app',{})
    if app.get('cid') and not (OUT/'run.log').exists():
        try:
            obj=inspect(app['cid']);validate_owner(obj,app['name'],JDK,labels(os.environ,state['nonce']),app['cid'])
            with (OUT/'run.log').open('xb') as stream:
                result=subprocess.run(DOCKER+['logs',app['cid']],stdout=stream,stderr=subprocess.STDOUT,timeout=30)
            require(result.returncode==0,'PRIVATE_LOG_RECOVERY_FAILED')
        except Exception as failure: failures.append({'phase':'PRIVATE_LOG','type':type(failure).__name__})
    order = ('redis','app') if 'mavenExit' in state else ('app','redis')
    port_proof = {'result':'UNKNOWN'}
    for role in order:
        if role == 'app' and 'mavenExit' in state:
            try:
                obj=inspect(app['cid']);validate_owner(obj,app['name'],JDK,labels(os.environ,state['nonce']),app['cid'])
                require(obj['State']['Running'],'ORIGINAL_EXECUTION_NAMESPACE_NOT_LIVE')
                mysql,_=backing_mysql(os.environ)
                require(mysql['Id']==state['mysql']['cid'] and mysql['State']['Pid']==state['mysqlHostPid'],'MYSQL_PROCESS_REPLACED_BEFORE_PORT_PROOF')
                namespace=command(DOCKER+['exec',mysql['Id'],'readlink','/proc/self/ns/net']).strip()
                require(hashlib.sha256(namespace).hexdigest()==state['networkNamespaceSha256']
                        and command(DOCKER+['exec',app['cid'],'readlink','/proc/self/ns/net']).strip()==namespace,'PORT_PROBE_NAMESPACE_CHANGED')
                require(digest(path/'launch/CombinedHttpNamespacePortProbe.java')==state['namespaceProbeSourceSha256'],
                        'NAMESPACE_PROBE_POSTIMAGE_CHANGED')
                result = command(DOCKER+['exec',app['cid'],'java','-Xmx32m','/budget/CombinedHttpNamespacePortProbe.java',str(state['redisPort'])])
                require(result == ('NAMESPACE_REDIS_PORT_FREE '+str(state['redisPort'])+'\n').encode(),'ACTUAL_NAMESPACE_BIND_PROOF_REQUIRED')
                port_proof = {'result':'ACTUAL_OWNER_NAMESPACE_BIND_FREE','executionCid':app['cid'],
                              'mysqlCid':state['mysql']['cid'],'namespaceSha256':state['networkNamespaceSha256'],
                              'probeSourceSha256':state['namespaceProbeSourceSha256'],'port':state['redisPort']}
                state['cgroupMemoryEvents']=read_cgroup(obj['State']['Pid'],app['cid'])['memoryEvents']
            except Exception as failure: failures.append({'phase':'NAMESPACE_PORT_PROOF','type':type(failure).__name__})
            try:
                (path/'launch/stop').write_text('original-maven-exit-only\n')
                for _ in range(50):
                    obj=inspect(app['cid'])
                    if not obj['State']['Running']:break
                    time.sleep(.1)
                require(not obj['State']['Running'],'BOUNDED_ORIGINAL_EXIT_NOT_OBSERVED')
                state['appExit']={key:obj['State'][key] for key in ('ExitCode','OOMKilled','Running','Pid')}
            except Exception as failure: failures.append({'phase':'ORIGINAL_APP_EXIT','type':type(failure).__name__})
        try: cleaned.append(cleanup_slot(path,state,role))
        except Exception as failure: failures.append({'phase':'CLEANUP_'+role.upper(),'type':type(failure).__name__})
    successful = accepted_outcome(state,original_exit,failures,cleaned,port_proof)
    report = {**state['identity'],'result':'FIXED_COMBINED_BUDGET_PROCESS_PROVEN' if successful else 'NOT_ACCEPTED',
              'originalExit':original_exit,'mavenExit':state.get('mavenExit'),'appExit':state.get('appExit'),'mysql':state['mysql'],
              'mysqlAddressProof':state.get('mysqlAddressProof'),'mysqlBootstrap':state['mysqlBootstrap'],'cacheProof':state['cacheProof'],
              'namespaceRedisPortFree':port_proof,'redisPort':state['redisPort'],'database':state['schema'],
              'source':state['source'],'slots':{role:{k:v for k,v in value.items() if k in ('name','cid','budget')} for role,value in state['slots'].items()},
              'javaProcesses':state['javaProcesses'],'cleanup':cleaned,'failures':failures,'firstFailure':state.get('firstFailure'),
              'networkNamespaceSha256':state.get('networkNamespaceSha256'),
              'launchProof':state.get('launchProof'),
              'cgroupMemoryEvents':state.get('cgroupMemoryEvents'),
              'wholeSystemCapacityAccepted':False,'q4q8Complete':False}
    write(PUBLIC/'combined-http-budget.json',report)
    require(successful,'ORIGINAL_EXIT_OR_BUDGET_OR_CLEANUP_NOT_ACCEPTED')


def accepted_outcome(state,original_exit,failures,cleaned,port_proof):
    successful = original_exit == 0 and not failures and not state.get('firstFailure') and state.get('appExit') == {'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0}
    successful = successful and all(row['result']=='EXACT_CID_REMOVED_AND_ABSENT' for row in cleaned) and len(cleaned)==2
    successful = successful and {row.get('role') for row in cleaned}=={'app','redis'}
    successful = successful and {'MAVEN','SUREFIRE'} <= {x['role'] for x in state['javaProcesses']}
    successful = successful and bool(state.get('launchProof')) and bool(state.get('networkNamespaceSha256'))
    successful = successful and state.get('mavenExit')==0 and port_proof['result']=='ACTUAL_OWNER_NAMESPACE_BIND_FREE'
    successful = successful and int(state.get('cgroupMemoryEvents',{}).get('oom_kill','-1'))==0
    successful = successful and all(not row.get('terminal',{}).get('OOMKilled',True)
            and row['terminal']['ExitCode'] in (0,143) for row in cleaned)
    return successful


def check_subject(report, subject_identity, java_pid, jar_sha256):
    """F/root pass their already-sanitized original identity and javaPid; no JSON nesting guessed."""
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')} == subject_identity,
            'SUBJECT_SOURCE_RUN_CLASS_MISMATCH')
    require(subject_identity.get('class')==CLASS and re.fullmatch('[0-9a-f]{40}',subject_identity.get('sourceSha',''))
            and re.fullmatch('[0-9a-f]{64}',jar_sha256),'FIXED_SUBJECT_ARTIFACT_REQUIRED')
    require(report.get('result') == 'FIXED_COMBINED_BUDGET_PROCESS_PROVEN'
            and isinstance(report.get('source'),dict) and report.get('originalExit')==report.get('mavenExit')==0
            and report.get('appExit')=={'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0}
            and report.get('source',{}).get('artifactJarSha256') == jar_sha256
            and type(java_pid) is int and java_pid > 0
            and any(p['role']=='SUREFIRE' and p['namespacePid']==java_pid for p in report.get('javaProcesses',[])),
            'ACTUAL_JUNIT_SUBJECT_PID_NOT_PROVEN')
    for role,image,memory in (('app',JDK,MEMORY),('redis',REDIS,67108864)):
        slot=report.get('slots',{}).get(role,{}); proof=slot.get('budget',{})
        require(re.fullmatch('[0-9a-f]{64}',slot.get('cid','')) and proof.get('cid')==slot['cid']
                and proof.get('image')==image and proof.get('memory')==proof.get('memorySwap')==memory
                and proof.get('nanoCpus')==1000000000 and proof.get('pids')==256,'SUBJECT_RESOURCE_PROOF_MISMATCH')
    for process in report['javaProcesses']: validate_cgroup_limits(process['cgroup']['limits'])
    cache_proof=report.get('cacheProof',{})
    require(cache_proof.get('image')==JDK and cache_proof.get('mavenVersion')==CORE_VERSION
            and cache_proof.get('imageId')==report['slots']['app']['budget'].get('imageId')
            and cache_proof.get('settingsSha256')==hashlib.sha256(EMPTY_SETTINGS.encode()).hexdigest()
            and all(re.fullmatch('[0-9a-f]{64}',cache_proof.get(k,'')) for k in ('reportSha256','mavenCoreSha256'))
            and type(cache_proof.get('cache',{}).get('files')) is int and cache_proof['cache']['files']>0
            and re.fullmatch('[0-9a-f]{64}',cache_proof['cache'].get('sha256','')),'OFFLINE_CACHE_PREFLIGHT_REQUIRED')
    mysql=report.get('mysql',{})
    require(re.fullmatch('[0-9a-f]{64}',mysql.get('cid','')) and mysql.get('image')==MYSQL
            and mysql.get('memory')==mysql.get('memorySwap')==MEMORY and mysql.get('nanoCpus')==1000000000
            and mysql.get('pids')==256,'SUBJECT_BACKING_BUDGET_PROOF_MISMATCH')
    bootstrap=report.get('mysqlBootstrap',{})
    require({k:bootstrap.get(k) for k in ('sourceSha','runId','attempt','class')}==subject_identity
            and bootstrap.get('result')=='READY' and bootstrap.get('originalExit')==0
            and bootstrap.get('readiness',{}).get('result')=='BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED'
            and bootstrap['readiness'].get('cid')==mysql['cid']
            and re.fullmatch('[0-9a-f]{64}',bootstrap.get('reportSha256','')),'BOOTSTRAP_NOT_VERIFIED')
    require(bootstrap.get('hostPath',{}).get('result')=='ORIGINAL_OWNED_MYSQL_RELAY'
            and bootstrap['hostPath'].get('mysqlCid')==mysql['cid']
            and bootstrap['hostPath'].get('hostEndpoint')=='127.0.0.1:33080'
            and bootstrap['hostPath'].get('closedAfterReadiness') is True,'BOOTSTRAP_NOT_VERIFIED')
    require(all(report.get('mysqlAddressProof',{}).get(key)==bootstrap['readiness'].get(key) for key in
                ('cid','serverUuid','version','hostEndpoint','executionEndpoint'))
            and re.fullmatch('[0-9a-f-]{36}',bootstrap['readiness'].get('serverUuid','')),'BOOTSTRAP_NOT_VERIFIED')
    redis_cleanup_proven(report,subject_identity)


def check_storage_cleanup(report,storage):
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')} ==
            {k:storage.get(k) for k in ('sourceSha','runId','attempt','class')}
            and storage.get('mysqlAbsent') is True and storage.get('networkAbsent') is True
            and storage.get('mysql')==report.get('mysql')
            and storage.get('beforeCleanup',{}).get('Running') is True
            and storage.get('beforeCleanup',{}).get('OOMKilled') is False,'EXACT_BACKING_STORAGE_CLEANUP_REQUIRED')


def redis_cleanup_proven(report, expected_identity):
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')} == expected_identity,'CLEANUP_SOURCE_IDENTITY_MISMATCH')
    rows=[row for row in report.get('cleanup',[]) if row.get('role')=='redis']
    proof=report.get('namespaceRedisPortFree',{})
    require(len(rows)==1 and rows[0].get('result')=='EXACT_CID_REMOVED_AND_ABSENT'
            and rows[0].get('cid')==report.get('slots',{}).get('redis',{}).get('cid')
            and proof.get('result')=='ACTUAL_OWNER_NAMESPACE_BIND_FREE' and type(report.get('redisPort')) is int
            and 1<=report['redisPort']<=65535 and proof.get('port')==report['redisPort']
            and proof.get('executionCid')==report.get('slots',{}).get('app',{}).get('cid')
            and proof.get('mysqlCid')==report.get('mysql',{}).get('cid')
            and proof.get('namespaceSha256')==report.get('networkNamespaceSha256'), 'ACTUAL_INTERNAL_REDIS_CLEANUP_REQUIRED')


def read_redis_cleanup_proof():
    """Exact shell cleanup marker adapter, independently usable even when business result is red."""
    gate(os.environ)
    report=json.loads((PUBLIC/'combined-http-budget.json').read_text())
    require(report.get('redisPort')==int(os.environ['CHENGYIN_HTTP_E2E_REDIS_PORT']),'WRONG_ORIGINAL_REDIS_PORT_PROOF')
    redis_cleanup_proven(report, identity(os.environ))


def private_cleanup_proven(report,expected_identity):
    require({k:report.get(k) for k in ('sourceSha','runId','attempt','class')}==expected_identity,'PRIVATE_CLEANUP_IDENTITY_MISMATCH')
    rows=report.get('cleanup',[])
    require(len(rows)==2 and {row.get('role') for row in rows}=={'app','redis'}
            and all(row.get('result')=='EXACT_CID_REMOVED_AND_ABSENT'
                    and row.get('cid')==report.get('slots',{}).get(row['role'],{}).get('cid') for row in rows),
            'PRIVATE_STATE_MUST_BE_RETAINED')


def read_private_cleanup_proof():
    gate(os.environ)
    private_cleanup_proven(json.loads((PUBLIC/'combined-http-budget.json').read_text()),identity(os.environ))


def require_subject_budget(receipt_directory, subject_identity, java_pid, jar_sha256, database):
    """F entry finalize: after original XML/schema/Redis + bounded subject projection, before outer SQL removal."""
    path=Path(receipt_directory)/'combined-http-budget.json'
    require(path.is_file() and not path.is_symlink() and path.stat().st_size<8*1024*1024,'BOUNDED_BUDGET_PROOF_REQUIRED')
    report=json.loads(path.read_text())
    check_subject(report,subject_identity,java_pid,jar_sha256)
    require(report.get('database')==database and re.fullmatch(r'cy_e2e_w1_http_[0-9_]+',database),
            'ORIGINAL_HTTP_SCHEMA_BUDGET_BINDING_REQUIRED')
    return report


def require_final_budget(receipt_directory, subject_identity, java_pid, jar_sha256, database):
    """F/root summary ONLY: after the workflow's exact MySQL/network cleanup has published its proof."""
    report=require_subject_budget(receipt_directory,subject_identity,java_pid,jar_sha256,database)
    path=Path(receipt_directory)/'combined-http-storage-cleanup.json'
    require(path.is_file() and not path.is_symlink() and path.stat().st_size<8*1024*1024,'BOUNDED_STORAGE_PROOF_REQUIRED')
    check_storage_cleanup(report,json.loads(path.read_text()))


def cleanup_mysql():
    PUBLIC.mkdir(parents=True,exist_ok=True)
    report={**resource_envelope(os.environ),'result':'UNKNOWN','phase':'CLEANUP_IDENTITY',
            'mysqlAbsent':None,'networkAbsent':None,'failures':[],'nativeOperations':[],'originalExit':None}
    path=PUBLIC/'combined-http-storage-cleanup.json'
    def publish():
        try:write(path,report)
        except Exception as failure:
            report['failures'].append(resource_failure(failure,'CLEANUP_EVIDENCE_WRITE'))
            print('Combined MySQL cleanup evidence: UNKNOWN',file=__import__('sys').stderr)
    publish() # Evidence failure must not prevent native cleanup of independently proven own objects.
    try:identity_gate(os.environ)
    except Exception as failure:
        report['failures'].append(resource_failure(failure,'CLEANUP_IDENTITY'));publish();raise
    cleanup_env=dict(os.environ)
    try:
        original=read_bootstrap(required=False)
        if original:
            report['originalExit']=original.get('originalExit')
            if original.get('firstFailure'):report['firstFailure']=original['firstFailure']
            # Only source-bound, previously owner-verified IDs can fill a lost GITHUB_ENV write.
            # Never adopt by name alone, never replace a conflicting provided identity.
            recovered=[]
            for key,value in (('HTTP_MYSQL_CID',original.get('mysql',{}).get('cid')),
                              ('HTTP_BUDGET_NETWORK_ID',original.get('network',{}).get('cid') or original.get('mysql',{}).get('networkId'))):
                if not cleanup_env.get(key) and isinstance(value,str) and re.fullmatch('[0-9a-f]{64}',value):
                    cleanup_env[key]=value;recovered.append(key)
            if recovered:
                name,network=mysql_names(cleanup_env)
                cleanup_env.setdefault('HTTP_MYSQL_CONTAINER',name);cleanup_env.setdefault('HTTP_BUDGET_NETWORK',network)
                report['recoveredIdentityFields']=recovered
    except Exception as failure:
        report['bootstrapEvidenceFailure']=resource_failure(failure,'BOOTSTRAP_PROVENANCE')
    try:
        name,network=mysql_names(cleanup_env)
        if not cleanup_env.get('HTTP_MYSQL_CID'):
            absent('container',name);report['mysqlAbsent']=True
            report['mysqlIdentity']='NO_ACTUAL_CID_NAME_INDEPENDENTLY_ABSENT'
        else:
            obj=owned_mysql(cleanup_env)
            if obj is None:
                report['mysqlAbsent']=True;report['mysqlIdentity']='EXPECTED_CID_AND_NAME_INDEPENDENTLY_ABSENT'
            else:
                report['phase']='MYSQL_DIAGNOSTIC_BEFORE_REMOVE'
                report['mysql']=mysql_proof(obj,cleanup_env.get('HTTP_BUDGET_NETWORK_ID'))
                report['beforeCleanup']={key:obj['State'].get(key) for key in ('Running','OOMKilled','ExitCode')}
                report['diagnostic']={'state':safe_mysql_state(obj)};publish()
                try:report['diagnostic']=mysql_diagnostic(obj)
                except Exception as capture:report['diagnostic']={'result':'UNKNOWN','type':type(capture).__name__}
                publish()
                # Ownership recheck, not readiness/budget/live-IP validation. A bad OWN object still must exit.
                current=owned_mysql(cleanup_env)
                if current is not None:command(DOCKER+['rm','-f',current['Id']])
                absent('container',obj['Id']);absent('container',name);report['mysqlAbsent']=True
    except Exception as failure:report['failures'].append(resource_failure(failure,report['phase']))
    report['phase']='NETWORK_OWNER_READ';publish()
    try:
        if not cleanup_env.get('HTTP_BUDGET_NETWORK_ID'):
            report['phase']='NETWORK_ABSENCE_NAME';publish()
            _,name=mysql_names(cleanup_env);absent('network',name,report['nativeOperations'])
            report['networkIdentity']='NO_ACTUAL_ID_NAME_INDEPENDENTLY_ABSENT'
        else:
            net=owned_network(cleanup_env,report['nativeOperations'])
            if net is not None:
                require(not net.get('Containers'),'NETWORK_NOT_EXACT_OR_NOT_EMPTY')
                report['phase']='NETWORK_REMOVE';publish()
                # Recheck the original network immediately before deletion; no endpoint is adopted or detached.
                current=owned_network(cleanup_env,report['nativeOperations'])
                require(current is not None and not current.get('Containers'),'NETWORK_NOT_EXACT_OR_NOT_EMPTY')
                result=subprocess.run(DOCKER+['network','rm',net['Id']],capture_output=True,timeout=20)
                report['nativeOperations'].append({'operation':'NETWORK_REMOVE',**native_operation('network',net['Id'],result)})
                publish();require(result.returncode==0,'FIXED_COMMAND_FAILED')
                report['phase']='NETWORK_ABSENCE_CID';publish();absent('network',net['Id'],report['nativeOperations'])
                report['phase']='NETWORK_ABSENCE_NAME';publish();absent('network',net['Name'],report['nativeOperations'])
        report['networkAbsent']=True
    except Exception as failure:report['failures'].append(resource_failure(failure,report['phase']))
    complete=report['mysqlAbsent'] is True and report['networkAbsent'] is True and not report['failures']
    report['phase']='RESOURCE_CLEANUP_TERMINAL';report['result']='EXACT_OWN_STORAGE_REMOVED' if complete else 'UNKNOWN'
    publish()
    # This exit only certifies resource cleanup. Original bootstrap exit/cause remain in the receipt;
    # the original failed workflow step and missing application/XML/PID evidence cannot become success.
    require(complete and not report['failures'],'ABSENCE_NOT_INDEPENDENTLY_PROVEN')


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=('prepare','run','finalize','cleanup-mysql','bootstrap-init','bootstrap-ready','bootstrap-failure','run-host-path','warm-cache'))
    parser.add_argument('private',nargs='?',type=Path);parser.add_argument('original_exit',nargs='?',type=int)
    args=parser.parse_args()
    if args.phase=='warm-cache':
        require(args.private is None and args.original_exit is None,'FIXED_BOOTSTRAP_ARGS')
        previous={number:signal.signal(number,cache_stop_signal) for number in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP)}
        try:warm_cache();return 0
        finally:
            for number,handler in previous.items():signal.signal(number,handler)
    if args.phase=='run-host-path':
        require(args.private is None and args.original_exit is None,'FIXED_BOOTSTRAP_ARGS');return run_host_path()
    if args.phase in ('bootstrap-init','bootstrap-ready'):
        require(args.private is None and args.original_exit is None,'FIXED_BOOTSTRAP_ARGS')
        if args.phase=='bootstrap-init':bootstrap_init();return 0
        return bootstrap_ready()
    if args.phase=='bootstrap-failure':
        require(args.private is not None and args.original_exit is None and re.fullmatch('[0-9]{1,3}',str(args.private)), 'ORIGINAL_SHELL_EXIT_REQUIRED')
        bootstrap_failure(int(str(args.private)));return 0
    if args.phase=='cleanup-mysql': require(args.private is None and args.original_exit is None,'FIXED_CLEANUP_ARGS');cleanup_mysql();return 0
    require(args.private is not None,'PRIVATE_DIRECTORY_REQUIRED')
    if args.phase=='finalize': require(args.original_exit is not None,'ORIGINAL_EXIT_REQUIRED');finalize(args.private,args.original_exit);return 0
    require(args.original_exit is None,'FIXED_PHASE_ARGS')
    if args.phase=='prepare': prepare(args.private);return 0
    return run(args.private)


if __name__=='__main__':
    try: raise SystemExit(main())
    except Exception as failure:
        try:
            if len(__import__('sys').argv)>2 and __import__('sys').argv[1] in ('prepare','run'):
                path,state=load_private(Path(__import__('sys').argv[2]))
                state.setdefault('firstFailure',{'phase':state['phase'],'type':type(failure).__name__});save(path,state)
        except Exception:
            print('Combined HTTP budget failure evidence: UNKNOWN',file=__import__('sys').stderr)
        # Never publish exception text, inspect payload, argv, environment or raw SQL/logs.
        phase=__import__('sys').argv[1] if len(__import__('sys').argv)>1 and __import__('sys').argv[1] in (
                'prepare','run','finalize','cleanup-mysql','bootstrap-init','bootstrap-ready','bootstrap-failure','run-host-path','warm-cache') else 'FIXED_ARGUMENTS'
        print('Combined HTTP budget failed: '+json.dumps(resource_failure(failure,phase),sort_keys=True),file=__import__('sys').stderr)
        raise SystemExit(2)
