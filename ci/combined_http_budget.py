#!/usr/bin/env python3
"""Fixed combined-HTTP budget owner SOURCE. No class/command/profile/URL selection or provisioning.

Only the reviewed hosted script calls this adapter. The original workflow owns MySQL/network;
the original fresh-schema and HTTP XML/cleanup verifier remain authoritative for business results.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
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


def absent(kind, target):
    result = subprocess.run(DOCKER + [kind, 'inspect', target], capture_output=True, timeout=15)
    text = result.stderr.decode(errors='replace').lower()
    require(result.returncode != 0 and ('no such '+('network' if kind == 'network' else 'container') in text
            or (kind == 'container' and 'no such object' in text)), 'ABSENCE_NOT_INDEPENDENTLY_PROVEN')


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
        require(host.get('PortBindings') == {'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':'33080'}]}
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
    cache = Path.home()/'.m2/repository'
    require(cache.is_dir() and not cache.is_symlink(), 'EXISTING_OFFLINE_CACHE_REQUIRED')
    require((cache/'org/apache/maven/surefire/surefire-junit-platform/3.5.6/surefire-junit-platform-3.5.6.jar').is_file(),
            'EXISTING_OFFLINE_SUREFIRE_PROVIDER_REQUIRED')
    images = {image:json.loads(command(DOCKER+['image','inspect',image]))[0] for image in (JDK,REDIS)} # Never pull here.
    nonce = uuid.uuid4().hex
    state = {'identity':identity(os.environ),'nonce':nonce,
             'name':'cy-http-combined-'+os.environ['GITHUB_RUN_ID']+'-a'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+nonce[:12],
             'mysql':proof,'mysqlHostPid':mysql['State']['Pid'],'cache':str(cache.resolve()),
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
    (path/'launch/settings.xml').write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n')
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
    backing_mysql(os.environ); state['source']=source_manifest();save(path,state)
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
    require(any(x['role']=='MAVEN' for x in state['javaProcesses']) and any(x['role']=='SUREFIRE' for x in state['javaProcesses']), 'REAL_MAVEN_AND_JUNIT_PID_PROOF_REQUIRED')
    require(not obj['State']['OOMKilled'],'APP_OOM_OR_UNKNOWN_EXIT')
    require(mysql_address_proof(state['mysql']['cid']) == state['mysqlAddressProof'],'MYSQL_ADDRESS_OR_UUID_DRIFT')
    return state['mavenExit']


def mysql_address_proof(cid):
    # Password only on private stdin, never argv/env artifact. Both addresses query the SAME server.
    config = ('[client]\npassword='+os.environ['CHENGYIN_MYBATIS_IT_PASSWORD']+'\n').encode()
    args = ['mysql','--defaults-file=/dev/stdin','--no-login-paths','--protocol=TCP','--host=127.0.0.1','--user=root',
            '--batch','--skip-column-names','--execute','SELECT @@server_uuid,VERSION(),@@port']
    host = command(args+['--port=33080'],data=config).decode().strip().split('\t')
    namespace = command(DOCKER+['exec','-i',cid]+args+['--port=3306'],data=config).decode().strip().split('\t')
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
              'mysqlAddressProof':state.get('mysqlAddressProof'),'namespaceRedisPortFree':port_proof,'redisPort':state['redisPort'],'database':state['schema'],
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
    mysql=report.get('mysql',{})
    require(re.fullmatch('[0-9a-f]{64}',mysql.get('cid','')) and mysql.get('image')==MYSQL
            and mysql.get('memory')==mysql.get('memorySwap')==MEMORY and mysql.get('nanoCpus')==1000000000
            and mysql.get('pids')==256,'SUBJECT_BACKING_BUDGET_PROOF_MISMATCH')
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
    identity_gate(os.environ)
    PUBLIC.mkdir(parents=True,exist_ok=True)
    if not os.environ.get('HTTP_MYSQL_CID'):
        network='cy-http-budget-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']
        name='cy-http-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+CLASS.lower()
        absent('container',name)
        require(re.fullmatch('[0-9a-f]{64}',os.environ.get('HTTP_BUDGET_NETWORK_ID','')),'PARTIAL_NETWORK_ACTUAL_ID_REQUIRED')
        net=json.loads(command(DOCKER+['network','inspect',network]))[0]
        require(net['Id']==os.environ['HTTP_BUDGET_NETWORK_ID'] and net['Internal'] and not net.get('Containers')
                and all(net['Labels'].get(k)==v for k,v in labels(os.environ).items()),'PARTIAL_NETWORK_FOREIGN_OR_NONEMPTY')
        command(DOCKER+['network','rm',net['Id']]);absent('network',net['Id']);absent('network',network)
        write(PUBLIC/'combined-http-storage-cleanup.json',{**identity(os.environ),'result':'NO_MYSQL_CREATED_EXACT_NETWORK_REMOVED',
              'mysqlAbsent':True,'networkAbsent':True})
        return
    obj,proof=backing_mysql(os.environ,cleanup=True); cid=obj['Id'];name=obj['Name'][1:]
    terminal={key:obj['State'][key] for key in ('Running','OOMKilled','ExitCode')}
    network=os.environ['HTTP_BUDGET_NETWORK']; command(DOCKER+['rm','-f',cid]);absent('container',cid);absent('container',name)
    net=json.loads(command(DOCKER+['network','inspect',network]))[0]
    require(net['Id']==proof['networkId'] and not net.get('Containers')
            and all(net['Labels'].get(k)==v for k,v in labels(os.environ).items()),'NETWORK_NOT_EXACT_OR_NOT_EMPTY')
    command(DOCKER+['network','rm',proof['networkId']]);absent('network',network);absent('network',proof['networkId'])
    write(PUBLIC/'combined-http-storage-cleanup.json',{**identity(os.environ),'mysql':proof,'mysqlAbsent':True,'networkAbsent':True,'beforeCleanup':terminal})
    require(terminal['Running'] and not terminal['OOMKilled'],'BACKING_MYSQL_STOPPED_OR_OOM_BEFORE_CLEANUP')


def main():
    os.umask(0o077)
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=('prepare','run','finalize','cleanup-mysql'))
    parser.add_argument('private',nargs='?',type=Path);parser.add_argument('original_exit',nargs='?',type=int)
    args=parser.parse_args()
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
        print('Combined HTTP budget failed: '+type(failure).__name__,file=__import__('sys').stderr)
        raise SystemExit(2)
