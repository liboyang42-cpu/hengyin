"""Pure budget-owner control SOURCE; NOT_RUN. No Java, Docker, database or network calls."""
import copy
import contextlib
import importlib.util
import json
import os
from pathlib import Path
from types import SimpleNamespace
import unittest
import tempfile
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('combined_http_budget_controls',Path(__file__).with_name('combined_http_budget.py'))
B=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(B)


class CombinedBudgetControls(unittest.TestCase):
    def env(self,port='6391'):
        return {'GITHUB_ACTIONS':'true','RUNNER_ENVIRONMENT':'github-hosted','GITHUB_REPOSITORY':'liboyang42-cpu/hengyin',
                'GITHUB_EVENT_NAME':'workflow_dispatch','HTTP_SCOPE':'controlled-combination','CHENGYIN_HTTP_E2E_PROFILE':'github-hosted',
                'CHENGYIN_HTTP_E2E_TESTS':B.CLASS,'SOURCE_SHA':'a'*40,'GITHUB_RUN_ID':'41','GITHUB_RUN_ATTEMPT':'2',
                'CHENGYIN_MYBATIS_IT_HOST':'127.0.0.1','CHENGYIN_MYBATIS_IT_PORT':'33080',
                'CHENGYIN_MYBATIS_IT_USER':'root','CHENGYIN_MYBATIS_IT_PASSWORD':'b'*48,'B_PAYMENT_FACT_DDL_APPROVED':'1',
                'CHENGYIN_AI_REDIS_IT_DATABASE':'15','CHENGYIN_AI_REDIS_IT_PORT':port,'CHENGYIN_HTTP_E2E_REDIS_PORT':port,
                'CHENGYIN_HTTP_E2E_DATABASE':'cy_e2e_w1_http_20261026_12_345',
                'CHENGYIN_HTTP_E2E_SCHEMA_REPORT':str(B.OUT/'fresh-schema.json')}

    def obj(self):
        return {'Id':'1'*64,'Name':'/own-app','Image':'sha256:'+'d'*64,
                'Config':{'Image':B.JDK,'Labels':B.labels(self.env(),'nonce')},
                'HostConfig':{'Memory':B.MEMORY,'MemorySwap':B.MEMORY,'NanoCpus':1000000000,'PidsLimit':256,
                              'CapDrop':['ALL'],'NetworkMode':'container:'+'2'*64},
                'State':{'Running':False,'Pid':0,'ExitCode':0,'OOMKilled':False}}

    def test_only_explicit_hosted_fixed_combination_enters(self):
        B.gate(self.env())
        for key,value in [('GITHUB_EVENT_NAME','pull_request'),('HTTP_SCOPE','all-three'),
                          ('CHENGYIN_HTTP_E2E_TESTS','GenericGameplayHttpE2eIT'),('SOURCE_SHA','a'*7),
                          ('B_PAYMENT_FACT_DDL_APPROVED',''),('CHENGYIN_AI_REDIS_IT_DATABASE','0'),
                          ('CHENGYIN_AI_REDIS_IT_DATABASE',''),('CHENGYIN_VPS_TEST_RUN','1')]:
            with self.subTest(key=key,value=value):
                env=self.env();env[key]=value
                with self.assertRaises(ValueError):B.gate(env)

    def test_original_configurable_port_and_db15_are_preserved(self):
        for port in ('6387','6391','65001'):
            env=self.env(port);B.gate(env);child=B.child_environment(env)
            self.assertEqual(child['CHENGYIN_HTTP_E2E_REDIS_PORT'],port)
            self.assertEqual(child['CHENGYIN_AI_REDIS_IT_PORT'],port);self.assertEqual(child['CHENGYIN_AI_REDIS_IT_DATABASE'],'15')
            self.assertEqual(child['CHENGYIN_MYBATIS_IT_PORT'],'3306');self.assertEqual(env['CHENGYIN_MYBATIS_IT_PORT'],'33080')
        for port in ('0','65536','bad'):
            with self.assertRaises(ValueError):B.gate(self.env(port))
        env=self.env();env['CHENGYIN_AI_REDIS_IT_PORT']='6392'
        with self.assertRaises(ValueError):B.gate(env)

    def test_host_secret_and_settings_environment_are_not_forwarded(self):
        env=self.env();env.update(GITHUB_TOKEN='private-token',AWS_SECRET_ACCESS_KEY='private-aws',HOME='/secret/home',
                                 MAVEN_OPTS='-Dhost.secret=raw',JAVA_TOOL_OPTIONS='host-agent',JDK_JAVA_OPTIONS='host-options')
        child=B.child_environment(env)
        self.assertTrue({'GITHUB_TOKEN','AWS_SECRET_ACCESS_KEY','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'}.isdisjoint(child))
        self.assertEqual(child['HOME'],'/tmp/home');self.assertNotIn('host.secret',child['MAVEN_OPTS'])

    def test_actual_launch_mounts_and_env_cannot_include_host_settings_or_extra_options(self):
        path=Path('/tmp/cy-http-budget.abcdefgh');state={'cache':'/existing/repository','jdkEnvironment':{'PATH':'/trusted-image'}}
        obj={'HostConfig':{'ReadonlyRootfs':True},'Config':{'User':str(os.getuid())+':'+str(os.getgid()),
             'Entrypoint':['/bin/bash'],'Cmd':B.fixed_maven_command()},'Mounts':[]}
        for source,target,rw in [(str(B.ROOT),str(B.ROOT),False),('/existing/repository','/cache/repository',False),
                                 (str(path/'launch'),'/budget',False),
                                 (str(B.ROOT/'chengyinhub-admin/target/controlled-content-security'),str(B.ROOT/'chengyinhub-admin/target/controlled-content-security'),False)]+[(str(B.ROOT/m/'target'),str(B.ROOT/m/'target'),True) for m in B.MODULES]:
            obj['Mounts'].append({'Source':source,'Destination':target,'RW':rw,'Type':'bind'})
        obj['Config']['Env']=[k+'='+v for k,v in {**state['jdkEnvironment'],**B.child_environment(self.env())}.items()]
        with patch.dict(os.environ,self.env(),clear=True):
            B.validate_app_inputs(obj,path,state)
            wrong=copy.deepcopy(obj);wrong['Config']['Env'].append('JAVA_TOOL_OPTIONS=host-agent')
            with self.assertRaisesRegex(ValueError,'PRIVATE_ENV_BOUNDARY_CHANGED'):B.validate_app_inputs(wrong,path,state)
            wrong=copy.deepcopy(obj);wrong['Mounts'].append({'Source':'/secret/settings.xml','Destination':'/tmp/settings.xml','RW':False,'Type':'bind'})
            with self.assertRaisesRegex(ValueError,'PRIVATE_MOUNT_BOUNDARY_CHANGED'):B.validate_app_inputs(wrong,path,state)

    def test_actual_container_budget_requires_no_swap_cpu_and_pids(self):
        obj=self.obj();B.validate_container(obj,'own-app',B.JDK,B.labels(self.env(),'nonce'),B.MEMORY,'container:'+'2'*64,'1'*64)
        for key,value in [('Memory',2*B.MEMORY),('MemorySwap',2*B.MEMORY),('NanoCpus',2000000000),('PidsLimit',512)]:
            wrong=copy.deepcopy(obj);wrong['HostConfig'][key]=value
            with self.assertRaisesRegex(ValueError,'HARD_BUDGET_MISMATCH'):
                B.validate_container(wrong,'own-app',B.JDK,B.labels(self.env(),'nonce'),B.MEMORY,'container:'+'2'*64,'1'*64)

    def test_foreign_or_missing_actual_identity_is_rejected(self):
        for field,value in [('Id',''),('Id','3'*64),('Name','/foreign')]:
            wrong=self.obj();wrong[field]=value
            with self.assertRaises(ValueError):B.validate_owner(wrong,'own-app',B.JDK,B.labels(self.env(),'nonce'),'1'*64)
        wrong=self.obj();wrong['Config']['Labels']['chengyin.http.budget']='foreign'
        with self.assertRaisesRegex(ValueError,'FOREIGN_LABEL'):B.validate_owner(wrong,'own-app',B.JDK,B.labels(self.env(),'nonce'),'1'*64)

    def test_cgroup_values_not_heap_or_stats_are_the_proof(self):
        good={'memory.max':str(B.MEMORY),'memory.swap.max':'0','cpu.max':'100000 100000','pids.max':'256'}
        B.validate_cgroup_limits(good)
        for key,value in [('memory.max','max'),('memory.swap.max','1024'),('cpu.max','max 100000'),('pids.max','max')]:
            wrong={**good,key:value}
            with self.assertRaises(ValueError):B.validate_cgroup_limits(wrong)

    def test_original_surefire_startup_agent_and_offline_contract_are_retained(self):
        args=B.fixed_maven_command();self.assertIn('-o',args);self.assertIn('-DforkCount=1',args)
        self.assertNotIn('-DforkCount=0',args);self.assertIn('-Dtest='+B.CLASS,args)
        self.assertIn('-Dmaven.repo.local=/cache/repository',args)
        self.assertIn('test',args);self.assertNotIn('package',args)
        self.assertIn('-javaagent:${org.mockito:mockito-core:jar}',(B.ROOT/'chengyinhub-admin/pom.xml').read_text())

    def test_bad_budget_still_removes_only_a_proven_own_cid(self):
        obj=self.obj();obj['HostConfig']['Memory']=123;calls=[]
        state={'nonce':'nonce','mysql':{'cid':'2'*64},'slots':{'app':{'cid':'1'*64,'name':'own-app'}}}
        with patch.dict(os.environ,self.env(),clear=True),patch.object(B,'inspect',return_value=obj),\
             patch.object(B,'command',side_effect=lambda argv,**kw:calls.append(argv) or b''),\
             patch.object(B,'absent',side_effect=lambda kind,target:calls.append((kind,target))):
            result=B.cleanup_slot(Path('/unused'),state,'app')
        self.assertEqual(result['result'],'EXACT_CID_REMOVED_AND_ABSENT')
        self.assertEqual(calls,[B.DOCKER+['rm','1'*64],('container','1'*64),('container','own-app')])

    def test_foreign_cleanup_never_reaches_stop_or_remove(self):
        obj=self.obj();obj['Config']['Labels']['chengyin.http.source']='c'*40
        state={'nonce':'nonce','mysql':{'cid':'2'*64},'slots':{'app':{'cid':'1'*64,'name':'own-app'}}}
        with patch.dict(os.environ,self.env(),clear=True),patch.object(B,'inspect',return_value=obj),patch.object(B,'command') as mutation:
            with self.assertRaisesRegex(ValueError,'FOREIGN_LABEL'):B.cleanup_slot(Path('/unused'),state,'app')
        mutation.assert_not_called()

    def test_daemon_error_is_not_resource_absence(self):
        with patch.object(B.subprocess,'run',return_value=SimpleNamespace(returncode=1,stderr=b'Cannot connect to Docker daemon')):
            with self.assertRaisesRegex(ValueError,'ABSENCE_NOT_INDEPENDENTLY_PROVEN'):B.absent('container','1'*64)

    def outcome(self):
        state={'appExit':{'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0},'mavenExit':0,
               'javaProcesses':[{'role':'MAVEN'},{'role':'SUREFIRE'}],'launchProof':{'offline':True},
               'networkNamespaceSha256':'e'*64,'cgroupMemoryEvents':{'oom_kill':'0'}}
        cleaned=[{'role':role,'result':'EXACT_CID_REMOVED_AND_ABSENT','terminal':{'OOMKilled':False,'ExitCode':0}} for role in ('app','redis')]
        return state,cleaned,{'result':'ACTUAL_OWNER_NAMESPACE_BIND_FREE'}

    def test_nonzero_oom_unknown_and_cleanup_failure_cannot_turn_green(self):
        state,cleaned,port=self.outcome();self.assertTrue(B.accepted_outcome(state,0,[],cleaned,port))
        for code in (1,137,143):self.assertFalse(B.accepted_outcome(state,code,[],cleaned,port))
        for change in ('oom','child-oom','missing-junit','first-failure'):
            wrong=copy.deepcopy(state)
            if change=='oom':wrong['appExit']['OOMKilled']=True
            elif change=='child-oom':wrong['cgroupMemoryEvents']['oom_kill']='1'
            elif change=='missing-junit':wrong['javaProcesses']=[{'role':'MAVEN'}]
            else:wrong['firstFailure']={'type':'TimeoutExpired'}
            self.assertFalse(B.accepted_outcome(wrong,0,[],cleaned,port))
        self.assertFalse(B.accepted_outcome(state,0,[{'phase':'CLEANUP_APP'}],cleaned,port))
        self.assertFalse(B.accepted_outcome(state,0,[],cleaned,{'result':'UNKNOWN'}))

    def cleanup_report(self):
        return {**B.identity(self.env()),'redisPort':6391,'slots':{'app':{'cid':'1'*64},'redis':{'cid':'3'*64}},
                'mysql':{'cid':'2'*64},'networkNamespaceSha256':'e'*64,
                'namespaceRedisPortFree':{'result':'ACTUAL_OWNER_NAMESPACE_BIND_FREE','executionCid':'1'*64,
                    'mysqlCid':'2'*64,'namespaceSha256':'e'*64,'port':6391},
                'cleanup':[{'role':'redis','cid':'3'*64,'result':'EXACT_CID_REMOVED_AND_ABSENT'}]}

    def test_host_bind_or_wrong_namespace_cannot_sign_internal_port_free(self):
        good=self.cleanup_report();B.redis_cleanup_proven(good,B.identity(self.env()))
        for key,value in [('result','HOST_SOCKET_BIND_OK'),('executionCid','4'*64),('namespaceSha256','f'*64),('port',6387)]:
            wrong=copy.deepcopy(good);wrong['namespaceRedisPortFree'][key]=value
            with self.assertRaisesRegex(ValueError,'ACTUAL_INTERNAL_REDIS_CLEANUP_REQUIRED'):B.redis_cleanup_proven(wrong,B.identity(self.env()))

    def test_wrong_source_or_redis_cid_cannot_reuse_cleanup(self):
        good=self.cleanup_report();wrong=copy.deepcopy(good);wrong['sourceSha']='f'*40
        with self.assertRaisesRegex(ValueError,'CLEANUP_SOURCE_IDENTITY_MISMATCH'):B.redis_cleanup_proven(wrong,B.identity(self.env()))
        wrong=copy.deepcopy(good);wrong['cleanup'][0]['cid']='4'*64
        with self.assertRaisesRegex(ValueError,'ACTUAL_INTERNAL_REDIS_CLEANUP_REQUIRED'):B.redis_cleanup_proven(wrong,B.identity(self.env()))

    def test_wrong_subject_source_pid_or_jar_never_becomes_budget_acceptance(self):
        report={**B.identity(self.env()),'result':'FIXED_COMBINED_BUDGET_PROCESS_PROVEN','source':{'artifactJarSha256':'f'*64},
                'originalExit':0,'mavenExit':0,'appExit':{'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0},
                'javaProcesses':[{'role':'SUREFIRE','namespacePid':41}]}
        with self.assertRaisesRegex(ValueError,'ACTUAL_JUNIT_SUBJECT_PID_NOT_PROVEN'):B.check_subject(report,B.identity(self.env()),42,'f'*64)
        with self.assertRaisesRegex(ValueError,'ACTUAL_JUNIT_SUBJECT_PID_NOT_PROVEN'):B.check_subject(report,B.identity(self.env()),41,'e'*64)
        with self.assertRaisesRegex(ValueError,'SUBJECT_SOURCE_RUN_CLASS_MISMATCH'):B.check_subject(report,{**B.identity(self.env()),'attempt':'3'},41,'f'*64)

    def test_private_journal_is_not_erased_without_both_exact_native_absences(self):
        report=self.cleanup_report()
        with self.assertRaisesRegex(ValueError,'PRIVATE_STATE_MUST_BE_RETAINED'):B.private_cleanup_proven(report,B.identity(self.env()))
        report['cleanup'].append({'role':'app','cid':'1'*64,'result':'EXACT_CID_REMOVED_AND_ABSENT'})
        B.private_cleanup_proven(report,B.identity(self.env()))
        report['cleanup'][1]['cid']='f'*64
        with self.assertRaisesRegex(ValueError,'PRIVATE_STATE_MUST_BE_RETAINED'):B.private_cleanup_proven(report,B.identity(self.env()))

    def subject_report(self):
        report=self.cleanup_report()
        report.update(result='FIXED_COMBINED_BUDGET_PROCESS_PROVEN',source={'artifactJarSha256':'f'*64,'controlledContentSecurity':self.content_receipt()},
                      originalExit=0,mavenExit=0,appExit={'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0},
                      database=self.env()['CHENGYIN_HTTP_E2E_DATABASE'])
        limits={'memory.max':str(B.MEMORY),'memory.swap.max':'0','cpu.max':'100000 100000','pids.max':'256'}
        report['javaProcesses']=[{'role':'MAVEN','namespacePid':2,'cgroup':{'limits':limits},
                'jvmMemory':B.jvm_memory_binding(B.RUNTIME_MAVEN_OPTIONS.split(),'MAVEN')},
                {'role':'SUREFIRE','namespacePid':41,'cgroup':{'limits':limits},
                'jvmMemory':B.jvm_memory_binding(B.SUREFIRE_MEMORY_ARGUMENTS.split(),'SUREFIRE')}]
        for role,image,memory in (('app',B.JDK,B.MEMORY),('redis',B.REDIS,67108864)):
            report['slots'][role]['budget']={'cid':report['slots'][role]['cid'],'image':image,
                    'imageId':'sha256:'+'d'*64,'memory':memory,'memorySwap':memory,'nanoCpus':1000000000,'pids':256}
        report['cacheProof']={'image':B.JDK,'imageId':'sha256:'+'d'*64,'mavenVersion':'3.9.11','mavenCoreSha256':'c'*64,
                             'settingsSha256':B.hashlib.sha256(B.EMPTY_SETTINGS.encode()).hexdigest(),
                             'cache':{'files':1,'bytes':1,'sha256':'e'*64},'reportSha256':'a'*64,'controlledContentSecurity':self.content_receipt()}
        report['mysql'].update(image=B.MYSQL,memory=B.MEMORY,memorySwap=B.MEMORY,nanoCpus=1000000000,pids=256)
        report['mysqlBootstrap']={**B.identity(self.env()),'result':'READY','originalExit':0,'reportSha256':'a'*64,
                'hostPath':{'result':'ORIGINAL_OWNED_MYSQL_RELAY','mysqlCid':'2'*64,'hostEndpoint':'127.0.0.1:33080','closedAfterReadiness':True},
                'readiness':{'result':'BOTH_LOOPBACK_TCP_IDENTITIES_VERIFIED','cid':'2'*64,
                    'serverUuid':'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa','version':'8.0.45',
                    'hostEndpoint':'127.0.0.1:33080','executionEndpoint':'127.0.0.1:3306'}}
        report['mysqlAddressProof']={key:value for key,value in report['mysqlBootstrap']['readiness'].items() if key!='result'}
        return report

    def test_subject_finalize_does_not_demand_outer_storage_proof_before_it_exists(self):
        # These are synthetic verifier inputs, never a Docker/JVM acceptance claim.
        with tempfile.TemporaryDirectory() as name:
            directory=Path(name);report=self.subject_report()
            (directory/'combined-http-budget.json').write_text(json.dumps(report))
            B.require_subject_budget(directory,B.identity(self.env()),41,'f'*64,report['database'])
            with self.assertRaisesRegex(ValueError,'BOUNDED_STORAGE_PROOF_REQUIRED'):
                B.require_final_budget(directory,B.identity(self.env()),41,'f'*64,report['database'])

    def test_final_summary_requires_exact_later_mysql_cleanup_and_original_database(self):
        with tempfile.TemporaryDirectory() as name:
            directory=Path(name);report=self.subject_report()
            storage={**B.identity(self.env()),'mysql':report['mysql'],'mysqlAbsent':True,'networkAbsent':True,
                     'beforeCleanup':{'Running':True,'OOMKilled':False}}
            (directory/'combined-http-budget.json').write_text(json.dumps(report))
            (directory/'combined-http-storage-cleanup.json').write_text(json.dumps(storage))
            B.require_final_budget(directory,B.identity(self.env()),41,'f'*64,report['database'])
            with self.assertRaisesRegex(ValueError,'ORIGINAL_HTTP_SCHEMA_BUDGET_BINDING_REQUIRED'):
                B.require_final_budget(directory,B.identity(self.env()),41,'f'*64,'cy_e2e_w1_http_999_1_1')
            storage['sourceSha']='c'*40
            (directory/'combined-http-storage-cleanup.json').write_text(json.dumps(storage))
            with self.assertRaisesRegex(ValueError,'EXACT_BACKING_STORAGE_CLEANUP_REQUIRED'):
                B.require_final_budget(directory,B.identity(self.env()),41,'f'*64,report['database'])
    def resource_env(self):
        env=self.env();name,network=B.mysql_names(env)
        env.update(HTTP_MYSQL_CONTAINER=name,HTTP_MYSQL_CID='1'*64,
                   HTTP_BUDGET_NETWORK=network,HTTP_BUDGET_NETWORK_ID='2'*64)
        return env

    def mysql_object(self):
        env=self.resource_env()
        return {'Id':'1'*64,'Name':'/'+env['HTTP_MYSQL_CONTAINER'],'Image':'sha256:'+'d'*64,
                'Config':{'Image':B.MYSQL,'Labels':B.labels(env)},
                'HostConfig':{'NetworkMode':env['HTTP_BUDGET_NETWORK'],'Memory':B.MEMORY,'MemorySwap':B.MEMORY,
                              'NanoCpus':1000000000,'PidsLimit':256},
                'State':{'Status':'exited','Running':False,'OOMKilled':True,'ExitCode':137,'Pid':0},
                'NetworkSettings':{'Networks':{}}}

    def network_object(self):
        env=self.resource_env()
        return {'Id':'2'*64,'Name':env['HTTP_BUDGET_NETWORK'],'Labels':B.labels(env),'Internal':False,'Containers':{}}

    def cleanup_control(self,obj=None,net=None,write_fails=False,diagnostic_fails=False,lost_ids=False,network_exit=0):
        # Native owner transport doubles only; no application service or business response is mocked.
        obj=obj or self.mysql_object();net=net or self.network_object();reports=[];mutations=[]
        env=self.resource_env();env.pop('B_PAYMENT_FACT_DDL_APPROVED');env.pop('CHENGYIN_AI_REDIS_IT_DATABASE')
        def object_read(kind,target,observations=None):return obj if kind=='container' else net
        def native(argv,**kwargs):
            mutations.append(argv)
            return SimpleNamespace(returncode=network_exit,stdout=b'',stderr=b'opaque daemon message' if network_exit else b'')
        def command(argv,**kwargs):
            if argv[B.DOCKER.__len__():][:2]==['network','inspect']:return json.dumps([net]).encode()
            mutations.append(argv);return b''
        def publish(path,value):
            reports.append(copy.deepcopy(value))
            if write_fails:raise OSError('private write failure must not become public text')
        original={**B.identity(env),'originalExit':1,'firstFailure':{'phase':'MYSQL_TCP_READINESS','type':'ValueError','reasonCode':'MYSQL_TCP_READY_BUDGET_EXHAUSTED'}}
        if lost_ids:
            original.update(mysql={'cid':env.pop('HTTP_MYSQL_CID'),'networkId':env.pop('HTTP_BUDGET_NETWORK_ID')})
        with tempfile.TemporaryDirectory() as name,patch.dict(os.environ,env,clear=True),\
             patch.object(B,'PUBLIC',Path(name)),patch.object(B,'object_or_absent',side_effect=object_read),\
             patch.object(B,'inspect',return_value=obj),patch.object(B,'command',side_effect=command),\
             patch.object(B.subprocess,'run',side_effect=native),\
             patch.object(B,'absent'),patch.object(B,'read_bootstrap',return_value=original),\
             patch.object(B,'write',side_effect=publish),patch.object(B,'gate',side_effect=AssertionError('business gate forbidden in cleanup')),\
             patch.object(B,'mysql_diagnostic',side_effect=OSError('private diagnostic failed') if diagnostic_fails else None,
                          return_value={'state':B.safe_mysql_state(obj)}) as diagnostic:
            failure=None
            try:B.cleanup_mysql()
            except Exception as error:failure=error
        return reports,mutations,failure,diagnostic.call_count

    def test_stopped_oom_object_and_missing_live_endpoint_do_not_block_own_cleanup(self):
        obj=self.mysql_object();obj['HostConfig']['Memory']=0
        reports,mutations,failure,_=self.cleanup_control(obj=obj)
        self.assertIsNone(failure)
        self.assertEqual(mutations,[B.DOCKER+['rm','-f','1'*64],B.DOCKER+['network','rm','2'*64]])
        result=reports[-1];self.assertEqual(result['result'],'EXACT_OWN_STORAGE_REMOVED')
        self.assertTrue(result['mysqlAbsent']);self.assertTrue(result['networkAbsent'])
        self.assertEqual(result['beforeCleanup']['OOMKilled'],True)
        self.assertEqual(result['originalExit'],1)
        self.assertEqual(result['firstFailure']['reasonCode'],'MYSQL_TCP_READY_BUDGET_EXHAUSTED')
        self.assertFalse(result['q4q8Complete'])

    def test_wrong_owner_is_neither_logged_nor_removed(self):
        obj=self.mysql_object();net=self.network_object()
        obj['Config']['Labels']['chengyin.http.source']='f'*40;net['Labels']['chengyin.http.source']='f'*40
        reports,mutations,failure,diagnostics=self.cleanup_control(obj=obj,net=net)
        self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,[]);self.assertEqual(diagnostics,0)
        self.assertIsNone(reports[-1]['mysqlAbsent']);self.assertIsNone(reports[-1]['networkAbsent'])

    def test_write_failure_cannot_prevent_exact_native_cleanup_or_turn_green(self):
        _,mutations,failure,_=self.cleanup_control(write_fails=True)
        self.assertEqual(mutations,[B.DOCKER+['rm','-f','1'*64],B.DOCKER+['network','rm','2'*64]])
        self.assertIsInstance(failure,ValueError)

    def test_private_diagnostic_failure_does_not_prevent_owned_resource_removal(self):
        reports,mutations,failure,_=self.cleanup_control(diagnostic_fails=True)
        self.assertIsNone(failure);self.assertEqual(len(mutations),2)
        self.assertEqual(reports[-1]['diagnostic']['result'],'UNKNOWN')

    def test_attached_unidentified_container_prevents_network_deletion(self):
        net=self.network_object();net['Containers']={'3'*64:{'Name':'not-attested'}}
        reports,mutations,failure,_=self.cleanup_control(net=net)
        self.assertIsInstance(failure,ValueError)
        self.assertEqual(mutations,[B.DOCKER+['rm','-f','1'*64]])
        self.assertTrue(reports[-1]['mysqlAbsent']);self.assertIsNone(reports[-1]['networkAbsent'])

    def test_missing_actual_mysql_cid_is_not_adopted_from_a_foreign_name(self):
        env=self.resource_env();env.pop('HTTP_MYSQL_CID')
        with patch.dict(os.environ,env,clear=True),patch.object(B,'object_or_absent') as read:
            with self.assertRaisesRegex(ValueError,'FIXED_BACKING_IDENTITY_REQUIRED'):B.owned_mysql(env)
        read.assert_not_called()

    def test_owner_accepts_only_declared_network_name_or_exact_known_id(self):
        env=self.resource_env();obj=self.mysql_object();obj['HostConfig']['NetworkMode']='2'*64
        with patch.object(B,'object_or_absent',return_value=obj),patch.object(B,'inspect',return_value=obj):
            self.assertIs(B.owned_mysql(env),obj)
            obj['HostConfig']['NetworkMode']='host'
            with self.assertRaisesRegex(ValueError,'BACKING_NETWORK_CONFIG_CHANGED'):B.owned_mysql(env)

    def test_daemon_failure_is_not_an_empty_resource_object(self):
        with patch.object(B.subprocess,'run',return_value=SimpleNamespace(returncode=1,stderr=b'Cannot connect to Docker daemon')):
            with self.assertRaisesRegex(ValueError,'DOCKER_INSPECTION_UNKNOWN'):B.object_or_absent('container','1'*64)

    def test_stderr_and_exception_diagnostics_never_publish_secret_text(self):
        private=b'ERROR 1045 Access denied for secret-user password=secret-value SQL=raw-body'
        self.assertEqual(B.classify_mysql_stderr(private),'AUTHENTICATION_REJECTED')
        diagnostic=B.resource_failure(ValueError('secret-value SQL=raw-body'),'MYSQL_TCP_READINESS')
        self.assertEqual(diagnostic['reasonCode'],'UNCLASSIFIED_RESOURCE_FAILURE')
        self.assertNotIn('secret',json.dumps(diagnostic));self.assertNotIn('SQL',json.dumps(diagnostic['reasonCode']))

    def test_safe_state_excludes_runtime_error_and_health_raw_output(self):
        obj=self.mysql_object();obj['State'].update(Error='secret-token argv raw',Health={'Status':'unhealthy','FailingStreak':2,
                                                                                     'Log':[{'Output':'secret SQL password'}]})
        state=B.safe_mysql_state(obj)
        self.assertTrue(state['OOMKilled']);self.assertEqual(state['ExitCode'],137)
        self.assertEqual(state['health'],{'status':'unhealthy','failingStreak':2})
        self.assertNotIn('secret',json.dumps(state));self.assertNotIn('Output',json.dumps(state))

    def test_tcp_probe_requires_same_uuid_and_keeps_password_only_on_stdin(self):
        env=self.resource_env();calls=[]
        good=b'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\t8.0.45\t3306\n'
        def response(argv,**kwargs):calls.append((argv,kwargs));return SimpleNamespace(returncode=0,stdout=good,stderr=b'')
        with tempfile.TemporaryDirectory() as name,patch.dict(os.environ,env,clear=True),\
             patch.object(B,'mysql_private_directory',return_value=Path(name)),patch.object(B.subprocess,'run',side_effect=response):
            observed,proof=B.mysql_ready_probe('1'*64,B.time.monotonic()+90)
        self.assertEqual(proof['serverUuid'],'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')
        self.assertEqual(len(observed),2)
        for argv,kwargs in calls:
            self.assertNotIn('b'*48,' '.join(argv));self.assertIn(('b'*48).encode(),kwargs['input'])
            self.assertIn('--defaults-file=/dev/stdin',argv);self.assertNotIn('--no-login-paths',argv)
            self.assertEqual(kwargs['env']['MYSQL_TEST_LOGIN_FILE'],'/dev/null')
            self.assertNotIn('MYSQL_PWD',kwargs['env']);self.assertNotIn('MYSQL_HOME',kwargs['env'])
        self.assertIn('MYSQL_TEST_LOGIN_FILE=/dev/null',calls[0][0])
        self.assertIn('--port=3306',calls[0][0]);self.assertIn('--port=33080',calls[1][0])

    def test_mismatched_tcp_endpoints_cannot_be_ready(self):
        env=self.resource_env()
        replies=[SimpleNamespace(returncode=0,stdout=(letter*8+'-'+letter*4+'-'+letter*4+'-'+letter*4+'-'+letter*12+'\t8.0.45\t3306\n').encode(),stderr=b'') for letter in ('a','c')]
        with tempfile.TemporaryDirectory() as name,patch.dict(os.environ,env,clear=True),\
             patch.object(B,'mysql_private_directory',return_value=Path(name)),patch.object(B.subprocess,'run',side_effect=replies):
            _,proof=B.mysql_ready_probe('1'*64,B.time.monotonic()+90)
        self.assertIsNone(proof)

    def test_ready_budget_cannot_accept_late_success_or_invent_business_execution(self):
        env=self.resource_env();report={**B.identity(env),'result':'NOT_COMPLETED'};writes=[]
        with patch.dict(os.environ,env,clear=True),patch.object(B,'read_bootstrap',return_value=report),\
             patch.object(B.time,'monotonic',side_effect=[0,91,92]),patch.object(B,'backing_mysql',return_value=(self.mysql_object(),{'cid':'1'*64})),\
             patch.object(B,'mysql_ready_probe',return_value=({}, {'cid':'1'*64})),patch.object(B,'owned_mysql',return_value=self.mysql_object()),\
             patch.object(B,'mysql_diagnostic',return_value={'state':{'Running':False}}),patch.object(B,'write',side_effect=lambda p,v:writes.append(copy.deepcopy(v))):
            self.assertEqual(B.bootstrap_ready(),1)
        self.assertEqual(writes[-1]['result'],'NOT_READY');self.assertEqual(writes[-1]['originalExit'],1)
        self.assertEqual(writes[-1]['firstFailure']['reasonCode'],'MYSQL_TCP_READY_BUDGET_EXHAUSTED')

    def test_application_prepare_cannot_use_unknown_bootstrap(self):
        with patch.object(B,'read_bootstrap',return_value={**B.identity(self.env()),'result':'NOT_READY','originalExit':1,
                                                          'readiness':{'result':'UNKNOWN'}}):
            with self.assertRaisesRegex(ValueError,'BOOTSTRAP_NOT_VERIFIED'):B.require_mysql_bootstrap()

    def test_failure_exit_is_persisted_before_optional_private_forensics(self):
        env=self.resource_env();writes=[];original={**B.identity(env),'result':'READY','originalExit':0}
        def private_capture(obj):
            self.assertTrue(any(row.get('originalExit')==143 and row.get('firstFailure') for row in writes))
            raise OSError('private forensic error must not erase original termination')
        with patch.dict(os.environ,env,clear=True),patch.object(B,'read_bootstrap',return_value=original),\
             patch.object(B,'owned_network',return_value=self.network_object()),patch.object(B,'owned_mysql',return_value=self.mysql_object()),\
             patch.object(B,'mysql_diagnostic',side_effect=private_capture),patch.object(B,'write',side_effect=lambda p,v:writes.append(copy.deepcopy(v))):
            B.bootstrap_failure(143)
        self.assertEqual(writes[-1]['originalExit'],143);self.assertEqual(writes[-1]['result'],'NOT_READY')
        self.assertEqual(writes[-1]['firstFailure']['reasonCode'],'SHELL_BOOTSTRAP_FAILED')
        self.assertEqual(writes[-1]['captureFailure']['type'],'OSError')

    def test_lost_env_ids_are_recovered_only_as_rechecked_actual_owner_ids(self):
        reports,mutations,failure,_=self.cleanup_control(lost_ids=True)
        self.assertIsNone(failure)
        self.assertEqual(set(reports[-1]['recoveredIdentityFields']),{'HTTP_MYSQL_CID','HTTP_BUDGET_NETWORK_ID'})
        self.assertEqual(mutations,[B.DOCKER+['rm','-f','1'*64],B.DOCKER+['network','rm','2'*64]])

    def test_fixed_client_environment_cannot_read_original_login_file_or_password_env(self):
        with patch.dict(os.environ,{'MYSQL_TEST_LOGIN_FILE':'/private/login','MYSQL_PWD':'private-pass','MYSQL_HOME':'/private/conf'},clear=True):
            env=B.mysql_client_environment()
        self.assertEqual(env,{'MYSQL_TEST_LOGIN_FILE':'/dev/null'})
        self.assertNotIn('--no-login-paths',B.mysql_client_arguments())
        self.assertEqual(B.mysql_client_arguments()[1],'--defaults-file=/dev/stdin')

    def test_namespace_not_ready_never_claims_host_ready_or_opens_relay(self):
        calls=[]
        def response(argv,**kwargs):calls.append(argv);return SimpleNamespace(returncode=2,stdout=b'',stderr=b'unknown option')
        with tempfile.TemporaryDirectory() as name,patch.dict(os.environ,self.resource_env(),clear=True),\
             patch.object(B,'mysql_private_directory',return_value=Path(name)),patch.object(B.subprocess,'run',side_effect=response):
            before=unittest.mock.Mock()
            observed,proof=B.mysql_ready_probe('1'*64,B.time.monotonic()+90,before)
        before.assert_not_called();self.assertEqual(len(calls),1);self.assertIsNone(proof)
        self.assertEqual(observed['host'],{'result':'WAITING_FOR_NAMESPACE_TCP'})
        self.assertEqual(observed['namespace']['stderrCategory'],'CLIENT_OPTION_REJECTED')

    def test_successful_namespace_tcp_enters_original_host_path_before_host_query(self):
        stages=[]
        good=b'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\t8.0.45\t3306\n'
        def response(argv,**kwargs):stages.append('namespace' if argv[:2]==B.DOCKER[:2] else 'host');return SimpleNamespace(returncode=0,stdout=good,stderr=b'')
        with tempfile.TemporaryDirectory() as name,patch.dict(os.environ,self.resource_env(),clear=True),\
             patch.object(B,'mysql_private_directory',return_value=Path(name)),patch.object(B.subprocess,'run',side_effect=response):
            _,proof=B.mysql_ready_probe('1'*64,B.time.monotonic()+90,lambda:stages.append('enter-owned-relay'))
        self.assertEqual(stages,['namespace','enter-owned-relay','host'])
        self.assertEqual(proof['cid'],'1'*64)

    def test_post_boot_identity_reads_use_same_supported_client_on_both_addresses(self):
        calls=[];good=b'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\t8.0.45\t3306\n'
        def response(argv,**kwargs):calls.append((argv,kwargs));return SimpleNamespace(returncode=0,stdout=good,stderr=b'')
        with patch.dict(os.environ,self.resource_env(),clear=True),patch.object(B.subprocess,'run',side_effect=response):
            proof=B.mysql_address_proof('1'*64)
        self.assertEqual(proof['hostEndpoint'],'127.0.0.1:33080');self.assertEqual(proof['executionEndpoint'],'127.0.0.1:3306')
        self.assertEqual(len(calls),2)
        for argv,kwargs in calls:
            self.assertNotIn('--no-login-paths',argv);self.assertNotIn('b'*48,' '.join(argv))
            self.assertIn(('b'*48).encode(),kwargs['input']);self.assertEqual(kwargs['env']['MYSQL_TEST_LOGIN_FILE'],'/dev/null')

    def test_original_relay_core_receives_fixed_port_and_live_exact_endpoint_callback(self):
        endpoint={'id':'1'*64,'networkId':'2'*64,'ip':'172.20.0.3','pid':41};calls=[]
        @contextlib.contextmanager
        def relay(expected,current,*,loopback_port):
            self.assertEqual(expected,endpoint);self.assertEqual(current(),endpoint)
            calls.append(loopback_port);yield SimpleNamespace(server_address=('127.0.0.1',33080))
            self.assertEqual(current(),endpoint);calls.append('closed')
        with patch.dict(os.environ,self.resource_env(),clear=True),patch.object(B,'mysql_relay_endpoint',return_value=endpoint),\
             patch.object(B,'original_auth_owner',return_value=SimpleNamespace(_owned_mysql_relay=relay)):
            with B.mysql_host_path() as proof:self.assertEqual(proof['mysqlCid'],'1'*64)
        self.assertEqual(calls,[33080,'closed'])

    def test_relay_endpoint_requires_same_owned_bridge_subnet_and_live_process(self):
        env=self.resource_env();obj=self.mysql_object();obj['State'].update(Running=True,Pid=41,OOMKilled=False)
        obj['NetworkSettings']['Networks']={env['HTTP_BUDGET_NETWORK']:{'IPAddress':'172.20.0.3'}}
        net=self.network_object();net.update(Driver='bridge',Internal=True,Options={},IPAM={'Config':[{'Subnet':'172.20.0.0/24'}]})
        with patch.dict(os.environ,env,clear=True),patch.object(B,'backing_mysql',return_value=(obj,{'networkId':'2'*64})),\
             patch.object(B,'command',side_effect=lambda *a,**k:json.dumps([net]).encode()):
            self.assertEqual(B.mysql_relay_endpoint(),{'id':'1'*64,'networkId':'2'*64,'ip':'172.20.0.3','pid':41})
            obj['State']['Pid']=0
            with self.assertRaisesRegex(ValueError,'OWNED_INTERNAL_NETWORK_REQUIRED'):B.mysql_relay_endpoint()
            obj['State']['Pid']=41;net['Options']['com.docker.network.bridge.gateway_mode_ipv4']='isolated'
            with self.assertRaisesRegex(ValueError,'OWNED_INTERNAL_NETWORK_REQUIRED'):B.mysql_relay_endpoint()
            net['Options']={};obj['NetworkSettings']['Networks'][env['HTTP_BUDGET_NETWORK']]['IPAddress']='172.21.0.3'
            with self.assertRaisesRegex(ValueError,'OWNED_INTERNAL_NETWORK_REQUIRED'):B.mysql_relay_endpoint()

    def test_foreign_or_changed_endpoint_cannot_reach_original_relay(self):
        with patch.dict(os.environ,self.resource_env(),clear=True),\
             patch.object(B,'mysql_relay_endpoint',side_effect=ValueError('BACKING_MYSQL_FOREIGN')),patch.object(B,'original_auth_owner') as owner:
            with self.assertRaisesRegex(ValueError,'BACKING_MYSQL_FOREIGN'):
                with B.mysql_host_path():self.fail('foreign target entered relay')
        owner.assert_not_called()

    def host_path_control(self,exit_code=0,wrong_uuid=False,close_fails=False,write_fails=False):
        bootstrap=self.subject_report()['mysqlBootstrap'];bootstrap['readiness']['cid']='1'*64
        events=[];writes=[]
        @contextlib.contextmanager
        def relay():
            events.append('relay-open');yield {'mysqlCid':'1'*64}
            events.append('relay-close')
            if close_fails:raise ValueError('private raw message must not be published')
        address={key:value for key,value in bootstrap['readiness'].items() if key!='result'}
        if wrong_uuid:address['serverUuid']='cccccccc-cccc-cccc-cccc-cccccccccccc'
        def execute(argv,**kwargs):
            self.assertEqual(argv,['bash',str(B.ROOT/'chengyinhub-admin/src/test/tools/generic-gameplay-http-e2e.sh')])
            self.assertEqual(kwargs['env']['MYSQL_TEST_LOGIN_FILE'],'/dev/null')
            events.append('original-schema-test-cleanup-shell');return SimpleNamespace(returncode=exit_code)
        def publish(path,value):
            writes.append(copy.deepcopy(value))
            if write_fails:raise OSError('private evidence write error')
        with patch.dict(os.environ,self.resource_env(),clear=True),patch.object(B,'require_mysql_bootstrap',return_value=bootstrap),\
             patch.object(B,'mysql_host_path',side_effect=relay),patch.object(B,'mysql_address_proof',return_value=address),\
             patch.object(B.subprocess,'run',side_effect=execute),patch.object(B,'write',side_effect=publish):
            failure=None;status=None
            try:status=B.run_host_path()
            except Exception as error:failure=error
        return events,writes,status,failure

    def test_original_schema_test_cleanup_all_stay_within_one_bound_host_path(self):
        events,writes,status,failure=self.host_path_control()
        self.assertEqual(events,['relay-open','original-schema-test-cleanup-shell','relay-close'])
        self.assertIsNone(failure);self.assertEqual(status,0)
        self.assertEqual(writes[-1]['result'],'ORIGINAL_SHELL_AND_HOST_PATH_CLOSED')
        self.assertFalse(writes[-1]['q4q8Complete'])

    def test_changed_uuid_never_runs_original_business_shell(self):
        events,writes,_,failure=self.host_path_control(wrong_uuid=True)
        self.assertIsInstance(failure,ValueError);self.assertNotIn('original-schema-test-cleanup-shell',events)
        self.assertEqual(writes[-1]['result'],'NOT_ACCEPTED')

    def test_original_nonzero_survives_late_relay_cleanup_failure(self):
        _,writes,status,failure=self.host_path_control(exit_code=7,close_fails=True)
        self.assertIsNone(failure);self.assertEqual(status,7);self.assertEqual(writes[-1]['originalExit'],7)
        self.assertEqual(writes[-1]['result'],'NOT_ACCEPTED');self.assertNotIn('private raw',json.dumps(writes))

    def test_host_path_evidence_failure_preserves_original_nonzero_and_does_not_green_zero(self):
        _,_,status,failure=self.host_path_control(exit_code=7,write_fails=True)
        self.assertIsNone(failure);self.assertEqual(status,7)
        _,_,status,failure=self.host_path_control(exit_code=0,write_fails=True)
        self.assertIsNone(status);self.assertIsInstance(failure,OSError)

    def test_exact_native_network_not_found_and_legacy_container_absence(self):
        for kind,target,message in [('network','2'*64,'Error response from daemon: network '+'2'*64+' not found'),
                                    ('network','owned-net','Error: No such network: owned-net'),
                                    ('container','1'*64,'Error response from daemon: No such container: '+'1'*64)]:
            result=SimpleNamespace(returncode=1,stdout=b'[]\n',stderr=(message+'\n').encode())
            self.assertTrue(B.exact_absence(kind,target,result))
            with patch.object(B.subprocess,'run',return_value=result):
                self.assertIsNone(B.object_or_absent(kind,target));B.absent(kind,target)

    def test_wrong_target_daemon_error_and_generic_not_found_remain_unknown(self):
        for code,out,error in [(1,b'[]',b'Error response from daemon: network other-id not found'),
                               (1,b'[]',b'permission denied: network owned-net not found'),
                               (1,b'[]',b'Error response from daemon: network owned-net not found\nconnection failed'),
                               (1,b'[]',b'Cannot connect to the Docker daemon'),
                               (0,b'[]',b'Error response from daemon: network owned-net not found'),
                               (1,b'[{}]',b'Error response from daemon: network owned-net not found')]:
            result=SimpleNamespace(returncode=code,stdout=out,stderr=error)
            self.assertFalse(B.exact_absence('network','owned-net',result))
            with patch.object(B.subprocess,'run',return_value=result):
                with self.assertRaisesRegex(ValueError,'ABSENCE_NOT_INDEPENDENTLY_PROVEN'):B.absent('network','owned-net')

    def test_network_remove_error_keeps_native_stage_and_original_first_failure(self):
        reports,_,failure,_=self.cleanup_control(network_exit=1)
        self.assertIsInstance(failure,ValueError);last=reports[-1]
        self.assertEqual(last['result'],'UNKNOWN');self.assertIsNone(last['networkAbsent'])
        self.assertEqual(last['failures'][-1]['phase'],'NETWORK_REMOVE')
        self.assertEqual(last['nativeOperations'][-1],{'operation':'NETWORK_REMOVE','exit':1,'category':'UNCLASSIFIED_NATIVE_FAILURE'})
        self.assertEqual(last['originalExit'],1);self.assertEqual(last['firstFailure']['reasonCode'],'MYSQL_TCP_READY_BUDGET_EXHAUSTED')

    def test_abi_does_not_accept_missing_or_foreign_host_transport_binding(self):
        for transport in ({},{'result':'ORIGINAL_OWNED_MYSQL_RELAY','mysqlCid':'4'*64,'hostEndpoint':'127.0.0.1:33080','closedAfterReadiness':True}):
            report=self.subject_report();report['mysqlBootstrap']['hostPath']=transport
            with self.assertRaisesRegex(ValueError,'BOOTSTRAP_NOT_VERIFIED'):B.check_subject(report,B.identity(self.env()),41,'f'*64)

    def test_shared_relay_fixed_port_extension_rejects_arbitrary_port_and_wrong_scope_before_socket(self):
        owner=B.original_auth_owner()
        for value in (True,-1,3306,33081):
            with self.assertRaisesRegex(RuntimeError,'unreviewed relay listener'):
                with owner._owned_mysql_relay({},lambda:self.fail('endpoint read before port gate'),loopback_port=value):
                    self.fail('unexpected listener')
        env=self.env();env['HTTP_SCOPE']='all-three'
        with patch.dict(os.environ,env,clear=True):
            with self.assertRaisesRegex(RuntimeError,'fixed combined relay requires original manual owned scope'):
                with owner._owned_mysql_relay({},lambda:self.fail('endpoint read before scope gate'),loopback_port=33080):
                    self.fail('unexpected listener')

    def test_original_ephemeral_relay_scope_is_unchanged_and_identity_still_precedes_bind(self):
        owner=B.original_auth_owner()
        with patch.dict(os.environ,{},clear=True):
            with self.assertRaisesRegex(RuntimeError,'MySQL relay identity changed before bind'):
                with owner._owned_mysql_relay({'id':'original'},lambda:{'id':'changed'}):self.fail('changed target bound')

    def test_cache_warmup_and_execution_use_same_core_repository_and_empty_settings(self):
        warm=B.fixed_cache_command()[1];execution=B.fixed_maven_command()
        self.assertEqual(B.CORE_VERSION,'3.9.11');self.assertIn('maven:3.9.11-eclipse-temurin-21@sha256:',B.JDK)
        for flag in ('--settings','--global-settings','-Dmaven.repo.local=/cache/repository'):
            self.assertIn(flag,warm);self.assertIn(flag,execution)
        self.assertIn("'package' '-DskipTests'",warm);self.assertIn("'-o'",warm)
        self.assertIn('OFFLINE_TEST_PREFLIGHT',warm);self.assertIn('ORIGINAL_PROVIDER_GET',warm)
        self.assertIn('org.apache.maven.plugins:maven-dependency-plugin:3.10.0:get',warm)
        self.assertIn('org.apache.maven.surefire:surefire-junit-platform:3.5.6',warm)
        self.assertIn('-o',execution);self.assertNotIn('-DskipTests',execution);self.assertNotIn('-Dmaven.test.skip=true',execution)
        self.assertEqual(B.EMPTY_SETTINGS,'<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n')

    def compilation_receipt(self):
        # Synthetic transport binding only; actual sources/classes are checked separately below.
        return {'profile':B.TEST_PROFILE,'sources':{'com/chengyinhub/business/it/'+B.CLASS+'.java':'d'*64},
                'classes':{'com/chengyinhub/business/it/'+B.CLASS+'.class':'e'*64}}

    def provider_receipt(self):
        return {'purpose':'PREPARATION_PROVIDER_RUNTIME_ONLY','businessExecuted':False,
                'online':{'proofSha256':'1'*64,'testClasspath':{'fileName':'online.classpath','sha256':'1'*64,'byteLength':40,'entryCount':2}},
                'offline':{'proofSha256':'2'*64,'testClasspath':{'fileName':'offline.classpath','sha256':'1'*64,'byteLength':40,'entryCount':2}}}

    def content_receipt(self):
        # Synthetic protocol binding only, not a generated compiler artifact.
        return {'purpose':'SAME_SOURCE_PRECOMPILED_CONTENT_SECURITY','sourceSha':self.env()['SOURCE_SHA'],
                'sourceSha256':'3'*64,'classSha256':'4'*64,'proofSha256':'5'*64}

    def compiler_receipt(self):
        return {'purpose':'PREPARATION_COMPILER_ONLY','exitObserved':True,'exit':0,
                'testClasspath':self.provider_receipt()['online']['testClasspath'],'proof':self.content_receipt()}

    def configuration_receipt(self):
        # Transport binding only. Real fixed XML/source/class controls are below.
        return {'purpose':'CONFIG_ASSEMBLY_ONLY','businessExecuted':False,'tests':11,'failures':0,'errors':0,'skipped':0,
                'xmlSha256':'3'*64,'sourceSha256':'4'*64,'testClassSha256':'5'*64}

    def cache_receipt(self):
        proof=self.subject_report()['cacheProof']
        return {**B.identity(self.env()),**{k:v for k,v in proof.items() if k!='reportSha256'},
                'result':'PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN','originalExit':0,'failures':[],
                'mavenExitObserved':True,'mavenExit':0,'offlinePackageProbeExit':0,
                'testCompilation':self.compilation_receipt(),
                'providerRuntime':self.provider_receipt(),
                'configurationUnit':self.configuration_receipt(),
                'controlledContentSecurity':self.content_receipt(),'controlledContentSecurityCompiler':self.compiler_receipt(),
                'pomSha256':{'pom.xml':'f'*64},'budget':{'memory':B.MEMORY,'memorySwap':B.MEMORY,'nanoCpus':1000000000,'pids':256},
                'cgroup':{'limits':{'memory.max':str(B.MEMORY),'memory.swap.max':'0','cpu.max':'100000 100000','pids.max':'256'}},
                'cleanup':{'result':'EXACT_CID_REMOVED_AND_ABSENT','terminal':{'OOMKilled':False,'ExitCode':143}}}

    def check_cache_receipt(self,report,**changes):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'cache.json';path.write_text(json.dumps(report))
            with patch.dict(os.environ,self.env(),clear=True),patch.object(B,'CACHE_REPORT',path),\
                 patch.object(B,'cache_inputs',return_value=changes.get('poms',{'pom.xml':'f'*64})),\
                 patch.object(B,'fixed_test_compilation_inputs',return_value={k:v for k,v in self.compilation_receipt().items() if k!='classes'}),\
                 patch.object(B,'fixed_test_compilation_outputs',return_value=changes.get('compilation',self.compilation_receipt())),\
                 patch.object(B,'provider_runtime_proof',return_value=changes.get('provider',self.provider_receipt())),\
                 patch.object(B,'configuration_unit_proof',return_value=changes.get('configuration',self.configuration_receipt())),\
                 patch.object(B,'controlled_content_proof',return_value=changes.get('content',self.content_receipt())),\
                 patch.object(B,'cache_manifest',return_value=changes.get('cache',self.subject_report()['cacheProof']['cache'])):
                return B.verified_cache(Path(directory))

    def test_preparation_receipt_binds_source_core_settings_poms_cache_and_cleanup(self):
        result=self.check_cache_receipt(self.cache_receipt())
        self.assertEqual(result['mavenVersion'],'3.9.11');self.assertEqual(result['image'],B.JDK)
        self.assertEqual(len(result['reportSha256']),64)
        for key,value in [('sourceSha','b'*40),('runId','99'),('attempt','9'),('class','GenericGameplayHttpE2eIT'),
                          ('mavenVersion','3.9.12'),('image','maven:latest'),('settingsSha256','0'*64),
                          ('originalExit',1),('result','NOT_ACCEPTED'),('mavenExitObserved',False),('mavenExit',None),
                          ('offlinePackageProbeExit',None),('offlinePackageProbeExit',-1),('offlinePackageProbeExit',256)]:
            report=self.cache_receipt();report[key]=value
            with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):self.check_cache_receipt(report)
        report=self.cache_receipt();report['cleanup']['terminal']['OOMKilled']=True
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):self.check_cache_receipt(report)
        report=self.cache_receipt();report['cleanup']['terminal']['ExitCode']=137
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):self.check_cache_receipt(report)

    def test_changed_pom_or_repository_tracking_metadata_cannot_reuse_preflight(self):
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):
            self.check_cache_receipt(self.cache_receipt(),poms={'pom.xml':'0'*64})
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):
            self.check_cache_receipt(self.cache_receipt(),cache={'files':1,'bytes':1,'sha256':'0'*64})

    def test_cache_manifest_includes_repository_tracking_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'artifact.jar').write_bytes(b'synthetic control artifact')
            tracking=root/'_remote.repositories';tracking.write_text('artifact.jar>central=\n')
            original=B.cache_manifest(root);self.assertEqual(original['files'],2)
            tracking.write_text('artifact.jar>foreign=\n')
            self.assertNotEqual(original['sha256'],B.cache_manifest(root)['sha256'])
            (root/'link').symlink_to(root/'artifact.jar')
            with self.assertRaisesRegex(ValueError,'SYMLINK_OFFLINE_CACHE_FORBIDDEN'):B.cache_manifest(root)

    def test_application_budget_rejects_missing_or_other_toolchain_cache_proof(self):
        for key,value in [('mavenVersion','3.9.12'),('imageId','sha256:'+'0'*64),('settingsSha256','0'*64)]:
            report=self.subject_report();report['cacheProof'][key]=value
            with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):B.check_subject(report,B.identity(self.env()),41,'f'*64)
        report=self.subject_report()
        # Keep the independent legal stub prerequisite while removing ALL legacy offline/core proof.
        # Otherwise this scenario only retests missing controlled-content evidence before its target gate.
        report['cacheProof']={'controlledContentSecurity':report['cacheProof']['controlledContentSecurity']}
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):B.check_subject(report,B.identity(self.env()),41,'f'*64)

    def test_standard_plugin_resolution_projects_only_exact_reviewed_coordinates(self):
        for coordinate,versions in B.REVIEWED_PLUGINS.items():
            for version in versions:
                text='Plugin '+coordinate+':'+version+' or one of its dependencies could not be resolved: password=PRIVATE argv=PRIVATE'
                self.assertEqual(B.safe_plugin_resolution(text),{'plugin':coordinate,'pluginVersion':version,'pluginPhase':'RESOLUTION_BEFORE_GOAL'})
        for coordinate in ('unknown.group:plugin:1.0','org.apache.maven.plugins:maven-resources-plugin:1-PRIVATE',
                           'org.apache.maven.plugins:maven-resources-plugin:3.3.1-PRIVATE'):
            self.assertEqual(B.safe_plugin_resolution('Plugin '+coordinate+' or one of its dependencies could not be resolved: PRIVATE'),{})
        self.assertEqual(B.safe_plugin_resolution('PluginResolutionException PRIVATE offline mode'),{})

    def test_hyphenated_reviewed_group_keeps_exact_group_version_and_suffix_boundaries(self):
        coordinate='io.github.git-commit-id:git-commit-id-maven-plugin:5.0.0'
        suffix=' or one of its dependencies could not be resolved:'
        expected={'plugin':'io.github.git-commit-id:git-commit-id-maven-plugin','pluginVersion':'5.0.0','pluginPhase':'RESOLUTION_BEFORE_GOAL'}
        self.assertEqual(B.safe_plugin_resolution('\x1b[31mPlugin '+coordinate+suffix+' PRIVATE_TOKEN\x1b[0m'),expected)
        for altered in (
                coordinate.replace('io.github.git-commit-id','io.github.git_commit_id'),
                coordinate.replace('io.github.git-commit-id','io.github.git-commit-id-foreign'),
                coordinate.replace('io.github.git-commit-id','xio.github.git-commit-id'),
                coordinate.replace('io.github.git-commit-id','a'*101),
                coordinate.replace('5.0.0','5.0.0-PRIVATE_TOKEN'),
                coordinate.replace('5.0.0','5.0.0:extra')):
            self.assertEqual(B.safe_plugin_resolution('Plugin '+altered+suffix+' PRIVATE_TOKEN'),{})
        self.assertEqual(B.safe_plugin_resolution('Plugin '+coordinate+'X'+suffix),{})
        self.assertEqual(B.safe_plugin_resolution('Plugin '+coordinate+' or one of its dependencies could not be resolvedX: PRIVATE_TOKEN'),{})
        self.assertNotIn('PRIVATE_TOKEN',json.dumps(expected))

    def test_shared_diagnostic_consumes_hyphenated_group_without_exporting_private_cause(self):
        spec=importlib.util.spec_from_file_location('hyphenated_plugin_diagnostic',B.ROOT/'ci/test_http_e2e_entry.py')
        owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'run.log'
            suffix='Cannot access central in offline mode\nhttps://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException'
            path.write_text('Plugin io.github.git-commit-id:git-commit-id-maven-plugin:5.0.0 or one of its dependencies could not be resolved: PRIVATE_TOKEN\n'+suffix)
            result=owner.safe_maven_failure(path)
            self.assertEqual(result,{'category':'offline-resolution','helpException':'PluginResolutionException',
                                    'plugin':'io.github.git-commit-id:git-commit-id-maven-plugin','pluginVersion':'5.0.0','pluginPhase':'RESOLUTION_BEFORE_GOAL'})
            self.assertNotIn('PRIVATE_TOKEN',json.dumps(result));self.assertNotIn('goal',result)
            path.write_text(suffix)
            self.assertEqual(owner.safe_maven_failure(path),{'category':'offline-resolution','helpException':'PluginResolutionException'})

    def test_shared_diagnostic_preserves_missing_plugin_unknown_and_no_goal_resolution(self):
        spec=importlib.util.spec_from_file_location('cache_diagnostic_entry',B.ROOT/'ci/test_http_e2e_entry.py')
        owner=importlib.util.module_from_spec(spec);spec.loader.exec_module(owner)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'run.log'
            suffix='Cannot access central in offline mode\nhttps://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException'
            path.write_text('Plugin org.apache.maven.plugins:maven-resources-plugin:3.3.1 or one of its dependencies could not be resolved: PRIVATE\n'+suffix)
            result=owner.safe_maven_failure(path)
            self.assertEqual(result,{'category':'offline-resolution','helpException':'PluginResolutionException',
                                    'plugin':'org.apache.maven.plugins:maven-resources-plugin','pluginVersion':'3.3.1','pluginPhase':'RESOLUTION_BEFORE_GOAL'})
            self.assertNotIn('PRIVATE',json.dumps(result));self.assertNotIn('goal',result)
            path.write_text(suffix)
            self.assertEqual(owner.safe_maven_failure(path),{'category':'offline-resolution','helpException':'PluginResolutionException'})

    def warm_transport_control(self,exit_code=0,foreign=False,write_fails=False,
                               failure_point=None,terminal_exit=143,no_plugin=False,probe_exit=0,
                               preparation_phase='OFFLINE_TEST_PREFLIGHT',log_error=False,compiler_exit=0,compiler_log=None):
        # Transport/file protocol doubles only: not Maven/Java execution or dependency acceptance evidence.
        reports=[];mutations=[];obj={};image_id='sha256:'+'d'*64
        with tempfile.TemporaryDirectory() as directory,contextlib.ExitStack() as own_cleanup:
            owned=B.private_directory(Path(B.command(['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX']).decode().strip()))
            own_cleanup.callback(lambda:B.shutil.rmtree(B.private_directory(owned)) if owned.exists() else None)
            private=str(owned);root=Path(directory);cache=root/'home/.m2/repository';cache.mkdir(parents=True)
            allocations=[];inspections=[];ticks=[];content_compiled=[False]
            (cache/'synthetic-control.jar').write_bytes(b'not an actual dependency')
            def native(argv,**kwargs):
                if 'logs' in argv:
                    if log_error:raise OSError('PRIVATE_LOG_VALUE')
                    kwargs['stdout'].write(compiler_log.encode() if compiler_log is not None else
                        b'Plugin org.apache.maven.plugins:maven-resources-plugin:3.3.1 or one of its dependencies could not be resolved: PRIVATE\n' if exit_code and not no_plugin else b'SYNTHETIC_TRANSPORT_CONTROL_ONLY')
                return SimpleNamespace(returncode=0,stdout=b'',stderr=b'')
            def command(argv,**kwargs):
                if argv==['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX']:
                    allocations.append(argv);return (private+'\n').encode()
                if argv[-1]=='{{.Endpoints.docker.Host}}':return b'unix:///var/run/docker.sock'
                if 'image' in argv and 'inspect' in argv:return json.dumps([{'Id':image_id,'Config':{'Env':[]}}]).encode()
                if 'create' in argv:
                    name=argv[argv.index('--name')+1];cid='1'*64
                    Path(argv[argv.index('--cidfile')+1]).write_text(cid)
                    labels=dict(argv[i+1].split('=',1) for i,x in enumerate(argv) if x=='--label')
                    if foreign:labels['chengyin.http.source']='f'*40
                    env=[argv[i+1] for i,x in enumerate(argv) if x=='--env']
                    mounts=[]
                    for i,x in enumerate(argv):
                        if x!='--mount':continue
                        pieces=argv[i+1].split(',');values=dict(v.split('=',1) for v in pieces if '=' in v)
                        mounts.append({'Type':'bind','Source':values['src'],'Destination':values['dst'],'RW':'readonly' not in pieces})
                    obj.update(Id=cid,Name='/'+name,Image=image_id,
                               Config={'Image':B.JDK,'Labels':labels,'Cmd':B.fixed_cache_command(),'Env':env,
                                       'Entrypoint':['/bin/bash'],'User':str(os.getuid())+':'+str(os.getgid())},Mounts=mounts,
                               HostConfig={'Memory':B.MEMORY,'MemorySwap':B.MEMORY,'NanoCpus':1000000000,'PidsLimit':256,
                                           'CapDrop':['ALL'],'NetworkMode':'bridge','ReadonlyRootfs':True},
                               State={'Running':True,'Pid':17,'ExitCode':0,'OOMKilled':False})
                    return cid.encode()
                if 'sha256sum' in argv:return ('c'*64+'  fixed-core.jar\n').encode()
                if '/tmp/cy-cache.phase' in argv[-1]:
                    if failure_point=='INVALID_RECEIPT':return b'' # No earlier proven runtime stage in this scenario.
                    return (preparation_phase if content_compiled[0] or exit_code else 'CONTENT_SECURITY_COMPILE').encode()
                if argv[-1]=='/tmp/cy-cache.runtime-maven.exit':return b'0'
                if 'exec' in argv and '/tmp/cy-cache.exit' in argv[-1]:
                    if failure_point=='INVALID_RECEIPT' or (failure_point=='INVALID_AFTER_CONTENT' and content_compiled[0]):
                        return b'PRIVATE_BAD_RECEIPT'
                    if exit_code==0 and not content_compiled[0]:return b''
                    if content_compiled[0] and compiler_exit:return str(compiler_exit).encode()
                if 'exec' in argv and '/tmp/cy-cache.offline.exit' in argv[-1]:
                    return b'' if probe_exit is None else str(probe_exit).encode()
                if 'exec' in argv:return str(exit_code).encode()
                if 'stop' in argv:obj['State'].update(Running=False,Pid=0,ExitCode=terminal_exit);mutations.append('stop')
                if 'rm' in argv:mutations.append('remove')
                return b''
            def publish(path,value):
                reports.append(copy.deepcopy(value))
                if write_fails:raise OSError('PRIVATE evidence write problem')
            def inspect(identity):
                inspections.append(identity)
                if failure_point=='STOPPED' and len(inspections)==3:
                    obj['State'].update(Running=False,Pid=0,ExitCode=1)
                return obj
            def clock():
                ticks.append(1)
                return B.LIMIT_SECONDS if failure_point=='DEADLINE' and len(ticks)>1 else 0
            def compile_content(*args):
                content_compiled[0]=True;receipt=self.compiler_receipt();receipt['exit']=compiler_exit
                if compiler_exit:receipt.pop('proof')
                return receipt
            with contextlib.ExitStack() as patches:
                patches.enter_context(patch.dict(os.environ,self.env(),clear=True))
                patches.enter_context(patch.object(B,'ROOT',root))
                patches.enter_context(patch.object(B,'PUBLIC',root/'public'))
                patches.enter_context(patch.object(B.Path,'home',return_value=root/'home'))
                patches.enter_context(patch.object(B,'cache_inputs',return_value={'pom.xml':'f'*64}))
                patches.enter_context(patch.object(B,'inspect',side_effect=inspect))
                patches.enter_context(patch.object(B,'fixed_test_compilation_inputs',return_value={k:v for k,v in self.compilation_receipt().items() if k!='classes'}))
                patches.enter_context(patch.object(B,'fixed_test_compilation_outputs',return_value=self.compilation_receipt()))
                patches.enter_context(patch.object(B,'provider_runtime_proof',return_value=self.provider_receipt()))
                patches.enter_context(patch.object(B,'configuration_unit_proof',return_value=self.configuration_receipt()))
                patches.enter_context(patch.object(B,'controlled_content_proof',return_value=self.content_receipt()))
                patches.enter_context(patch.object(B,'compile_controlled_content',side_effect=compile_content))
                patches.enter_context(patch.object(B,'command',side_effect=command))
                patches.enter_context(patch.object(B.subprocess,'run',side_effect=native))
                patches.enter_context(patch.object(B.time,'monotonic',side_effect=clock))
                patches.enter_context(patch.object(B,'absent'))
                patches.enter_context(patch.object(B,'read_cgroup',return_value={'limits':self.cache_receipt()['cgroup']['limits'],'memoryEvents':{'oom_kill':'0'}}))
                patches.enter_context(patch.object(B,'write',side_effect=publish))
                failure=None
                try:B.warm_cache()
                except Exception as error:failure=error
            self.assertEqual(allocations,[['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX']])
            return reports,mutations,failure,owned.exists()

    def test_warm_transport_attests_same_image_core_settings_budget_and_exact_cleanup(self):
        reports,mutations,failure,private_exists=self.warm_transport_control()
        self.assertIsNone(failure);self.assertEqual(mutations,['stop','remove']);self.assertFalse(private_exists)
        report=reports[-1];self.assertEqual(report['result'],'PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN')
        self.assertEqual(report['mavenCoreSha256'],'c'*64);self.assertEqual(report['budget']['memory'],B.MEMORY)
        self.assertTrue(report['preparationOnly']);self.assertFalse(report['wholeSystemCapacityAccepted'])
        self.assertIs(report['mavenExitObserved'],True);self.assertEqual(report['mavenExit'],0)

    def test_warm_nonzero_preserves_plugin_failure_and_private_journal_after_exact_cleanup(self):
        reports,mutations,failure,private_exists=self.warm_transport_control(exit_code=1)
        self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,['stop','remove']);self.assertTrue(private_exists)
        report=reports[-1];self.assertEqual(report['result'],'NOT_ACCEPTED');self.assertEqual(report['originalExit'],1)
        self.assertEqual(report['pluginFailure']['plugin'],'org.apache.maven.plugins:maven-resources-plugin')
        self.assertNotIn('PRIVATE',json.dumps(report))

    def test_warm_foreign_owner_never_stops_or_removes_and_cannot_sign_cache(self):
        reports,mutations,failure,_=self.warm_transport_control(foreign=True)
        self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,[])
        self.assertEqual(reports[-1]['result'],'NOT_ACCEPTED')

    def test_warm_evidence_write_failure_still_cleans_exact_owner_and_stays_failed(self):
        reports,mutations,failure,_=self.warm_transport_control(write_fails=True)
        self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,['stop','remove'])
        self.assertEqual(reports[-1]['result'],'NOT_ACCEPTED')

    def test_cache_stop_signal_is_a_typed_nonzero_unwind_not_a_success_or_process_kill(self):
        for number,code in ((B.signal.SIGINT,130),(B.signal.SIGTERM,143),(B.signal.SIGHUP,129)):
            with self.assertRaises(InterruptedError) as raised:B.cache_stop_signal(number,None)
            self.assertEqual(raised.exception.errno,code)

    def test_private_allocator_failure_precedes_cache_or_container_creation(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),\
             patch.object(B,'PUBLIC',Path(directory)),patch.object(B,'command',side_effect=ValueError('FIXED_COMMAND_FAILED')) as create,\
             patch.object(B.Path,'home') as home,patch.object(B,'cache_inputs') as inputs,\
             patch.object(B,'inspect') as inspect,patch.object(B,'cleanup_slot') as cleanup:
            with self.assertRaisesRegex(ValueError,'FIXED_COMMAND_FAILED'):B.warm_cache()
        create.assert_called_once_with(['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX'])
        home.assert_not_called();inputs.assert_not_called();inspect.assert_not_called();cleanup.assert_not_called()

    def test_bad_allocator_result_cannot_relax_original_private_path_fence(self):
        for path in ('/tmp/cy-http-budget.bad_name','/elsewhere/cy-http-budget.Ab12Cd34'):
            with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),\
                 patch.object(B,'PUBLIC',Path(directory)),patch.object(B,'command',return_value=(path+'\n').encode()) as create,\
                 patch.object(B.Path,'home') as home,patch.object(B,'cache_inputs') as inputs,\
                 patch.object(B,'inspect') as inspect,patch.object(B,'cleanup_slot') as cleanup:
                with self.assertRaisesRegex(ValueError,'EXACT_PRIVATE_DIRECTORY_REQUIRED'):B.warm_cache()
            create.assert_called_once_with(['mktemp','-d','/tmp/cy-http-budget.XXXXXXXX'])
            home.assert_not_called();inputs.assert_not_called();inspect.assert_not_called();cleanup.assert_not_called()

    def test_cache_shell_has_atomic_receipt_and_fixed_signal_unwind_without_changing_goals_or_budget(self):
        shell=B.fixed_cache_command()[1]
        self.assertIn('wait "$child"; status=$?',shell)
        self.assertIn('mv /tmp/cy-cache.exit.next /tmp/cy-cache.exit',shell)
        self.assertIn('kill -TERM "$child"',shell);self.assertIn('trap \'exit "$status"\' TERM INT HUP',shell)
        self.assertEqual(1,shell.count("'package' '-DskipTests'"))
        self.assertEqual(1,shell.count('maven-dependency-plugin:3.10.0:get'))
        self.assertEqual(1,shell.count('OFFLINE_TEST_PREFLIGHT'))
        self.assertEqual(B.LIMIT_SECONDS,1200);self.assertEqual(B.MEMORY,1073741824)

    def test_deadline_is_a_fixed_predicate_not_unknown_plugin_or_cleanup_failure(self):
        reports,mutations,failure,private_exists=self.warm_transport_control(failure_point='DEADLINE',terminal_exit=137)
        report=reports[-1];self.assertIsInstance(failure,ValueError);self.assertTrue(private_exists)
        self.assertEqual(mutations,['stop','remove']);self.assertEqual(report['result'],'NOT_ACCEPTED')
        first=report['firstFailure'];self.assertEqual(first['substage'],'WAIT_EXIT')
        self.assertEqual(first['reasonCode'],'CACHE_EXECUTION_DEADLINE')
        self.assertEqual(first['origin']['function'],'warm_cache');self.assertGreater(first['origin']['line'],0)
        self.assertFalse(report['mavenExitObserved']);self.assertIsNone(report['mavenExit'])
        self.assertEqual(report['cleanup']['result'],'EXACT_CID_REMOVED_AND_ABSENT')
        self.assertEqual(report['cleanup']['terminal']['ExitCode'],137)
        self.assertIs(report['cleanup']['terminal']['OOMKilled'],False)
        self.assertEqual(report['pluginFailure'],{});self.assertEqual(report['failures'],[])

    def test_stopped_workload_is_not_a_deadline_or_a_maven_exit_receipt(self):
        reports,mutations,failure,_=self.warm_transport_control(failure_point='STOPPED')
        report=reports[-1];self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,['remove'])
        self.assertEqual(report['firstFailure']['reasonCode'],'CACHE_PROCESS_EXIT_BEFORE_RECEIPT')
        self.assertEqual(report['lastContainerState'],{'Running':False,'OOMKilled':False,'ExitCode':1,'pidPresent':False})
        self.assertFalse(report['mavenExitObserved']);self.assertEqual(report['result'],'NOT_ACCEPTED')

    def test_malformed_receipt_does_not_become_observed_exit_or_publish_its_value(self):
        reports,mutations,failure,_=self.warm_transport_control(failure_point='INVALID_RECEIPT')
        report=reports[-1];self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,['stop','remove'])
        self.assertEqual(report['firstFailure']['reasonCode'],'ACTUAL_MAVEN_EXIT_REQUIRED')
        self.assertFalse(report['mavenExitObserved']);self.assertIsNone(report['mavenExit'])
        self.assertEqual(report['phase'],'UNKNOWN')
        self.assertNotIn('controlledContentSecurityCompiler',report)
        self.assertNotIn('PRIVATE_BAD_RECEIPT',json.dumps(report));self.assertEqual(report['result'],'NOT_ACCEPTED')
        # A later malformed final receipt must not erase the independently observed ONLINE zero,
        # nor turn the malformed bytes into a new Maven/compiler exit or a successful preflight.
        reports,mutations,failure,_=self.warm_transport_control(failure_point='INVALID_AFTER_CONTENT')
        report=reports[-1];self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,['stop','remove'])
        self.assertEqual(report['firstFailure']['reasonCode'],'ACTUAL_MAVEN_EXIT_REQUIRED')
        self.assertIs(report['mavenExitObserved'],True);self.assertEqual(report['mavenExit'],0)
        self.assertEqual(report['controlledContentSecurityCompiler']['exit'],0)
        self.assertIs(report['controlledContentSecurityCompiler']['exitObserved'],True)
        self.assertEqual(report['originalExit'],2);self.assertEqual(report['result'],'NOT_ACCEPTED')
        self.assertNotIn('PRIVATE_BAD_RECEIPT',json.dumps(report))

    def test_maven_nonzero_without_plugin_stays_known_exit_and_unknown_plugin_after_forced_cleanup(self):
        reports,mutations,failure,_=self.warm_transport_control(exit_code=1,terminal_exit=137,no_plugin=True)
        report=reports[-1];self.assertIsInstance(failure,ValueError);self.assertEqual(mutations,['stop','remove'])
        self.assertIs(report['mavenExitObserved'],True);self.assertEqual(report['mavenExit'],1)
        self.assertEqual(report['originalExit'],1);self.assertEqual(report['pluginFailure'],{})
        self.assertEqual(report['firstFailure']['reasonCode'],'OFFLINE_CACHE_PREFLIGHT_FAILED')
        self.assertEqual(report['cleanup']['result'],'EXACT_CID_REMOVED_AND_ABSENT');self.assertEqual(report['failures'],[])
        self.assertEqual(report['result'],'NOT_ACCEPTED')

    def test_successful_preflight_cannot_sign_a_forced_or_oom_shell_terminal(self):
        reports,_,failure,_=self.warm_transport_control(terminal_exit=137)
        report=reports[-1];self.assertIsInstance(failure,ValueError)
        self.assertEqual(report['cleanup']['result'],'EXACT_CID_REMOVED_AND_ABSENT')
        self.assertEqual(report['failures'][0]['phase'],'CACHE_PROCESS_TERMINAL')
        self.assertEqual(report['failures'][0]['reasonCode'],'CACHE_COMPLETED_SHELL_EXIT_MISMATCH')
        self.assertEqual(report['result'],'NOT_ACCEPTED')
        with self.assertRaisesRegex(ValueError,'APP_OOM_OR_UNKNOWN_EXIT'):
            B.cache_terminal({'originalExit':0,'mavenExit':0,'mavenExitObserved':True},
                {'result':'EXACT_CID_REMOVED_AND_ABSENT','terminal':{'OOMKilled':True,'ExitCode':137}},True)

    def test_cache_diagnostic_does_not_copy_arbitrary_type_message_stage_or_private_trace(self):
        try:raise ValueError('PRIVATE_TOKEN SQL argv /private/path')
        except ValueError as failure: result=B.cache_failure(failure,'PINNED_CACHE_PREPARATION','PRIVATE_TOKEN')
        self.assertEqual(result,{'phase':'PINNED_CACHE_PREPARATION','type':'ValueError',
            'reasonCode':'UNCLASSIFIED_RESOURCE_FAILURE','substage':'UNKNOWN'})
        class PrivateTokenFailure(Exception):pass
        result=B.cache_failure(PrivateTokenFailure('PRIVATE_TOKEN'),'PINNED_CACHE_PREPARATION','WAIT_EXIT')
        self.assertEqual(result['type'],'UNKNOWN');self.assertNotIn('PRIVATE_TOKEN',json.dumps(result))

    def test_offline_package_uses_the_original_fixed_goal_and_only_central_miss_can_enter_online_repair(self):
        shell=B.fixed_cache_command()[1]
        self.assertIn('package=(\'mvn\'',shell)
        self.assertEqual(shell.count("'package' '-DskipTests'"),1)
        self.assertIn('"${package[@]}" -o 2>&1 | tee /tmp/cy-cache.offline.log',shell)
        self.assertIn('codes=("${PIPESTATUS[@]}"); probe=${codes[0]}',shell)
        self.assertIn('[ "${codes[1]}" -eq 0 ] || exit 98',shell)
        self.assertIn('"OFFLINE_PACKAGE_PROBE"',shell)
        self.assertIn('if grep -Eq',shell)
        self.assertIn(r'Cannot access central \(https://repo\.maven\.apache\.org/maven2\) in offline mode and the artifact',shell)
        self.assertIn('has not been downloaded from it before',shell)
        self.assertIn('else exit "$probe"; fi; fi;',shell)
        self.assertEqual(shell.count('"${package[@]}"'),2)
        self.assertEqual(shell.count('maven-dependency-plugin:3.10.0:get'),1)
        self.assertEqual(shell.count('OFFLINE_TEST_PREFLIGHT'),1)
        self.assertIn('--no-transfer-progress',shell);self.assertIn('-Dstyle.color=never',shell)
        self.assertNotIn("'-q'",shell)
        self.assertEqual(B.LIMIT_SECONDS,1200);self.assertEqual(B.MEMORY,1073741824)

    def test_reused_and_repaired_closures_both_require_a_current_fixed_toolchain_receipt(self):
        # Transport doubles only. They do not execute the shell, Maven, download, compiler or business workload.
        for probe_exit in (0,1):
            with self.subTest(probe_exit=probe_exit):
                reports,mutations,failure,private_exists=self.warm_transport_control(probe_exit=probe_exit)
                report=reports[-1]
                self.assertIsNone(failure);self.assertFalse(private_exists);self.assertEqual(mutations,['stop','remove'])
                self.assertEqual(report['offlinePackageProbeExit'],probe_exit)
                self.assertEqual(report['mavenExit'],0);self.assertIs(report['mavenExitObserved'],True)
                self.assertEqual(report['result'],'PINNED_OFFLINE_TEST_PREFLIGHT_PROVEN')
                self.assertEqual(report['image'],B.JDK);self.assertEqual(report['mavenCoreSha256'],'c'*64)

    def test_missing_or_bad_offline_package_probe_receipt_cannot_be_replaced_by_a_final_zero_exit(self):
        for probe_exit in (None,256):
            with self.subTest(probe_exit=probe_exit):
                reports,mutations,failure,private_exists=self.warm_transport_control(probe_exit=probe_exit)
                report=reports[-1]
                self.assertIsInstance(failure,ValueError);self.assertTrue(private_exists)
                self.assertEqual(mutations,['stop','remove']);self.assertEqual(report['mavenExit'],0)
                self.assertIs(report['mavenExitObserved'],True)
                self.assertEqual(report['firstFailure']['reasonCode'],'CACHE_OFFLINE_PACKAGE_PROBE_EXIT_REQUIRED')
                self.assertEqual(report['result'],'NOT_ACCEPTED')

    def test_last_reviewed_goal_is_bounded_not_a_failure_claim_or_a_private_message(self):
        header='[INFO] --- compiler:3.15.0:testCompile (PRIVATE_TOKEN) @ chengyinhub-admin ---\n'
        expected={'plugin':'org.apache.maven.plugins:maven-compiler-plugin','pluginVersion':'3.15.0',
                  'goal':'testCompile','module':'chengyinhub-admin'}
        self.assertEqual(B.safe_cache_progress('\x1b[32m'+header+'\x1b[0m'),expected)
        self.assertEqual(B.safe_cache_progress(header+'PRIVATE_TOKEN URL SQL password=PRIVATE_TOKEN'),expected)
        for changed in (header.replace('compiler:','foreign:'),header.replace('3.15.0','3.15.0-PRIVATE_TOKEN'),
                        header.replace('testCompile','foreignGoal'),header.replace('chengyinhub-admin','private-module')):
            self.assertEqual(B.safe_cache_progress(changed),{})
        self.assertEqual(B.safe_cache_progress('PRIVATE_TOKEN before any goal'),{})
        last='[INFO] --- spring-boot:4.1.1:repackage (repackage) @ chengyinhub-admin ---'
        self.assertEqual(B.safe_cache_progress(header+last)['goal'],'repackage')
        self.assertNotIn('PRIVATE_TOKEN',json.dumps(B.safe_cache_progress(header)))

    def compilation_sources(self,root,includes=None,implicit='none'):
        if includes is None:includes=list(B.TEST_SOURCES)
        pom=root/'chengyinhub-admin/pom.xml';pom.parent.mkdir(parents=True,exist_ok=True)
        rows=''.join('<testInclude>'+value+'</testInclude>' for value in includes)
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><profiles><profile><id>'+B.TEST_PROFILE+
            '</id><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><executions><execution>'+
            '<id>default-testCompile</id><configuration><implicit>'+implicit+'</implicit><testIncludes>'+rows+
            '</testIncludes></configuration></execution></executions></plugin></plugins></build></profile></profiles></project>')
        for relative in includes:
            if '*' in relative or '..' in relative:continue
            path=root/'chengyinhub-admin/src/test/java'/relative;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text('// Synthetic SOURCE input, never an actual compiled class\n')
        return pom

    def test_fixed_profile_is_required_and_cannot_be_an_arbitrary_include_or_implicit_fallback(self):
        selected='com/chengyinhub/business/it/'+B.CLASS+'.java'
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)):
            root=Path(directory)
            with self.assertRaisesRegex(ValueError,'FIXED_TEST_COMPILATION_PROFILE_REQUIRED'):B.fixed_test_compilation_inputs()
            self.compilation_sources(root)
            inputs=B.fixed_test_compilation_inputs();self.assertEqual(inputs['profile'],B.TEST_PROFILE)
            self.assertEqual(set(inputs['sources']),set(B.TEST_SOURCES));self.assertEqual(len(inputs['sources'][selected]),64)
            for includes,implicit in (([selected,selected],'none'),([selected,'**/*.java'],'none'),
                    ([selected,'../outside.java'],'none'),(['com/chengyinhub/business/it/Other.java'],'none'),([selected],'class')):
                self.compilation_sources(root,includes,implicit)
                with self.subTest(includes=includes,implicit=implicit),self.assertRaises(ValueError):B.fixed_test_compilation_inputs()

    def test_fixed_test_output_reset_removes_only_admin_test_state_and_refuses_symlink_parents(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)):
            root=Path(directory);target=root/'chengyinhub-admin/target';target.mkdir(parents=True)
            preserved=[]
            for relative in ('classes/main.class','original.jar','http-e2e/public/receipt.json','maven-status/maven-compiler-plugin/compile/state'):
                path=target/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('preserve');preserved.append(path)
            for relative in ('test-classes/stale.class','generated-test-sources/stale/Other.java','maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst','provider-runtime/old.json','controlled-content-security/classes/old.class'):
                path=target/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('stale')
            B.reset_fixed_test_outputs()
            self.assertFalse((target/'test-classes').exists());self.assertFalse((target/'maven-status/maven-compiler-plugin/testCompile').exists())
            self.assertFalse((target/'generated-test-sources').exists())
            self.assertFalse((target/'provider-runtime').exists())
            self.assertFalse((target/'controlled-content-security').exists())
            self.assertTrue(all(p.read_text()=='preserve' for p in preserved))
            (target/'test-classes').symlink_to(target/'classes',target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'STALE_TEST_OUTPUT_BOUNDARY_CHANGED'):B.reset_fixed_test_outputs()
            self.assertEqual((target/'classes/main.class').read_text(),'preserve')
            (target/'test-classes').unlink();(target/'test-classes').mkdir()
            witness=target/'test-classes/not-yet-removed.class';witness.write_bytes(b'owned witness')
            (target/'generated-test-sources').symlink_to(target/'classes',target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'STALE_TEST_OUTPUT_BOUNDARY_CHANGED'):B.reset_fixed_test_outputs()
            self.assertTrue(witness.exists());self.assertEqual((target/'classes/main.class').read_text(),'preserve')

    def test_required_sources_and_reflected_inner_classes_cannot_be_replaced_by_a_prior_test_output(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)):
            root=Path(directory);self.compilation_sources(root);inputs=B.fixed_test_compilation_inputs()
            with self.assertRaisesRegex(ValueError,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED'):B.fixed_test_compilation_outputs(inputs)
            classes=B.REQUIRED_TEST_CLASSES
            for name in classes:
                path=root/'chengyinhub-admin/target/test-classes'/(name.replace('.','/')+'.class')
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'synthetic class-control bytes; not javac evidence')
            self.provider_class(root)
            self.compiler_input_status(root)
            proof=B.fixed_test_compilation_outputs(inputs);self.assertEqual(len(proof['classes']),len(classes))
            required=root/'chengyinhub-admin/target/test-classes/com/chengyinhub/business/it/CombinedControlledProviders$1.class'
            required.unlink()
            with self.assertRaisesRegex(ValueError,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED'):B.fixed_test_compilation_outputs(inputs)
            other=root/'outside-control.class';other.write_bytes(b'foreign');required.symlink_to(other)
            with self.assertRaisesRegex(ValueError,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED'):B.fixed_test_compilation_outputs(inputs)

    def test_package_preflight_and_real_test_share_one_profile_and_drifted_compilation_cannot_reuse_cache(self):
        shell=B.fixed_cache_command()[1];args=B.fixed_maven_command()
        # Five fixed profile-bearing command templates. The original package array is reused
        # by its offline probe/conditional online repair; dependency:get is not test compilation.
        calls=(('PACKAGE',shell.split('package=(',1)[1].split(');',1)[0]),
               ('ONLINE_PROVIDER_RUNTIME',shell.split('"ONLINE_PROVIDER_RUNTIME"',1)[1].split('"CONTENT_SECURITY_COMPILE"',1)[0]),
               ('ONLINE_CONFIGURATION_UNITS',shell.split('"ONLINE_CONFIGURATION_UNITS"',1)[1].split('"OFFLINE_PROVIDER_RUNTIME"',1)[0]),
               ('OFFLINE_PROVIDER_RUNTIME',shell.split('"OFFLINE_PROVIDER_RUNTIME"',1)[1].split('"OFFLINE_TEST_PREFLIGHT"',1)[0]),
               ('OFFLINE_TEST_PREFLIGHT',shell.split('"OFFLINE_TEST_PREFLIGHT"',1)[1]))
        self.assertEqual(tuple(stage for stage,_ in calls),('PACKAGE','ONLINE_PROVIDER_RUNTIME','ONLINE_CONFIGURATION_UNITS',
                                                         'OFFLINE_PROVIDER_RUNTIME','OFFLINE_TEST_PREFLIGHT'))
        for stage,command in calls:
            with self.subTest(stage=stage):
                self.assertEqual(command.count("'-P"+B.TEST_PROFILE+"'"),1)
                self.assertIn("'-pl' 'chengyinhub-admin' '-am'",command)
        self.assertEqual(shell.count('-P'+B.TEST_PROFILE),len(calls));self.assertIn('-P'+B.TEST_PROFILE,args)
        self.assertEqual(args.count('-P'+B.TEST_PROFILE),1)
        self.assertIn('-o',args);self.assertNotIn('-DskipTests',args);self.assertNotIn('-Dmaven.test.skip=true',args)
        changed=self.compilation_receipt();changed['classes'][next(iter(changed['classes']))]='0'*64
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):
            self.check_cache_receipt(self.cache_receipt(),compilation=changed)

    def compiler_input_status(self,root,extra=None):
        status=root/'chengyinhub-admin/target/maven-status/maven-compiler-plugin/testCompile/default-testCompile/inputFiles.lst'
        status.parent.mkdir(parents=True,exist_ok=True)
        values=[str(root/'chengyinhub-admin/src/test/java'/relative) for relative in B.TEST_SOURCES]
        if extra:values.append(str(root/extra))
        status.write_text('\n'.join(values)+'\n');return status

    def test_all_37_are_required_but_additional_inner_outputs_are_kept_and_unreviewed_sources_stop(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)):
            root=Path(directory);self.compilation_sources(root);inputs=B.fixed_test_compilation_inputs()
            output=root/'chengyinhub-admin/target/test-classes'
            for name in B.REQUIRED_TEST_CLASSES:
                path=output/(name.replace('.','/')+'.class');path.parent.mkdir(parents=True,exist_ok=True)
                path.write_bytes(b'synthetic protocol control, not javac evidence')
            self.provider_class(root)
            self.compiler_input_status(root)
            extra=output/'com/chengyinhub/business/it/GenericGameplayHttpE2eIT$99.class';extra.write_bytes(b'keep anonymous output')
            proof=B.fixed_test_compilation_outputs(inputs)
            self.assertEqual(len(proof['classes']),37);self.assertEqual(len(proof['sources'])-3,18)
            self.assertEqual(proof['classTree']['classes'],41)
            self.assertEqual(proof['compilerInputs'],sorted(B.TEST_SOURCES));self.assertTrue(extra.exists())
            for name in B.REQUIRED_TEST_CLASSES:
                path=output/(name.replace('.','/')+'.class');saved=path.read_bytes();path.unlink()
                with self.subTest(name=name),self.assertRaisesRegex(ValueError,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED'):
                    B.fixed_test_compilation_outputs(inputs)
                path.write_bytes(saved)
            self.compiler_input_status(root,'chengyinhub-admin/src/test/java/Unreviewed.java')
            with self.assertRaisesRegex(ValueError,'UNREVIEWED_TEST_COMPILATION_SOURCE'):B.fixed_test_compilation_outputs(inputs)
            self.compiler_input_status(root)
            foreign=output/'com/chengyinhub/business/it/Unreviewed.class';foreign.write_bytes(b'never prune to pass')
            with self.assertRaisesRegex(ValueError,'UNREVIEWED_TEST_COMPILATION_OUTPUT'):B.fixed_test_compilation_outputs(inputs)
            self.assertTrue(foreign.exists());foreign.unlink()
            generated=root/'chengyinhub-admin/target/generated-test-sources/Unreviewed.java'
            generated.parent.mkdir(parents=True,exist_ok=True);generated.write_text('// unknown processor source')
            with self.assertRaisesRegex(ValueError,'UNREVIEWED_TEST_COMPILATION_SOURCE'):B.fixed_test_compilation_outputs(inputs)

    def provider_class(self,root):
        path=root/'chengyinhub-admin/target/test-classes'/(B.PROVIDER_PROBE.replace('.','/')+'.class')
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'synthetic provider class control, not javac evidence')
        configuration=path.parent/'CombinedContextConfigurationTest.class'
        configuration.write_bytes(b'synthetic configuration class control, not javac evidence')
        photo=root/'chengyinhub-admin/target/test-classes'/(B.PHOTO_UNIT.replace('.','/')+'.class')
        photo.parent.mkdir(parents=True,exist_ok=True);photo.write_bytes(b'synthetic photo unit class control, not javac evidence')
        return path

    def provider_scope(self,root):
        # All rows/JARs here are synthetic protocol inputs. This never runs Maven, Java or a provider.
        directory=root/'chengyinhub-admin/target/provider-runtime';directory.mkdir(parents=True,mode=0o700)
        cache=root/'cache';cache.mkdir();probe=self.provider_class(root)
        families={'launcher':('org/junit/platform/junit-platform-launcher','junit-platform-launcher'),
                  'engine':('org/junit/jupiter/junit-jupiter-engine','junit-jupiter-engine'),
                  'platformEngine':('org/junit/platform/junit-platform-engine','junit-platform-engine'),
                  'api':('org/junit/jupiter/junit-jupiter-api','junit-jupiter-api'),
                  'mockito':('org/mockito/mockito-core','mockito-core')}
        runtime={}
        classpath=[str(root/'chengyinhub-admin/target/test-classes'),str(root/'chengyinhub-admin/target/classes')]
        (root/'chengyinhub-admin/target/classes').mkdir(exist_ok=True)
        for key,(relative,artifact) in families.items():
            jar=cache/relative/'1.0.0'/(artifact+'-1.0.0.jar');jar.parent.mkdir(parents=True)
            jar.write_bytes(('synthetic '+key+' artifact, not a dependency').encode())
            runtime[key]={'version':'1.0.0','sha256':B.digest(jar)}
            classpath.append(str(Path('/cache/repository')/jar.relative_to(cache)))
        cp=(':'.join(classpath)+':').encode()
        for i,phase in enumerate(('ONLINE','OFFLINE')):
            proof={'sourceSha':self.env()['SOURCE_SHA'],'runId':self.env()['GITHUB_RUN_ID'],'attempt':self.env()['GITHUB_RUN_ATTEMPT'],
                'phase':phase,'javaFeature':21,
                'pid':100+i,'parentPid':90+i,'startedAt':'2026-01-01T00:00:0'+str(i)+'Z',
                'forkedBooter':True,'commandSha256':'b'*64,'testClassSha256':B.digest(probe),
                'runtime':runtime,'businessExecuted':False,
                'testClasspath':{'fileName':phase.lower()+'.classpath','sha256':B.hashlib.sha256(cp).hexdigest(),'byteLength':len(cp),'entryCount':len(classpath)}}
            cp_file=directory/(phase.lower()+'.classpath');cp_file.write_bytes(cp);cp_file.chmod(0o600)
            file=directory/(phase.lower()+'.json');file.write_text(json.dumps(proof));file.chmod(0o600)
            xml=directory/(phase.lower()+'.xml')
            xml.write_text('<testsuite name="'+B.PROVIDER_PROBE+'" tests="1" failures="0" errors="0" skipped="0">'
                '<properties><property name="PRIVATE_TOKEN" value="PRIVATE_TOKEN"/></properties>'
                '<testcase classname="'+B.PROVIDER_PROBE+'" name="bootsActualProjectPlatformRuntime"/></testsuite>')
            xml.chmod(0o600)
        return directory,cache

    def test_provider_runtime_preflight_boots_real_forks_without_skipping_the_original_business_test(self):
        shell=B.fixed_cache_command()[1];actual=B.fixed_maven_command()
        self.assertEqual(shell.count('-Dtest='+B.PROVIDER_PROBE),2)
        self.assertIn('ONLINE_PROVIDER_RUNTIME',shell);self.assertIn('OFFLINE_PROVIDER_RUNTIME',shell)
        self.assertIn('-Dcombined.provider.phase=ONLINE',shell);self.assertIn('-Dcombined.provider.phase=OFFLINE',shell)
        self.assertEqual(shell.count(B.SUREFIRE_FORK_ARGUMENTS),3)
        self.assertEqual(shell.count('-DforkCount=1'),3);self.assertEqual(shell.count('-DreuseForks=true'),3)
        self.assertIn('-Dtest='+B.CLASS,actual);self.assertIn('-DargLine='+B.SUREFIRE_FORK_ARGUMENTS,actual)
        self.assertIn('-o',actual);self.assertNotIn('-DskipTests',actual);self.assertNotIn('-Dmaven.test.skip=true',actual)
        self.assertEqual(B.LIMIT_SECONDS,1200);self.assertEqual(B.MEMORY,1073741824)

    def test_provider_xml_fork_and_runtime_identity_are_required_without_exporting_xml_properties(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'ROOT',Path(directory)):
            root=Path(directory);_,cache=self.provider_scope(root);proof=B.provider_runtime_proof(cache)
            self.assertEqual(proof['purpose'],'PREPARATION_PROVIDER_RUNTIME_ONLY');self.assertIs(proof['businessExecuted'],False)
            self.assertEqual(proof['online']['runtime'],proof['offline']['runtime'])
            self.assertEqual(len(proof['online']['runtime']),5)
            self.assertNotEqual(proof['online']['forkIdentitySha256'],proof['offline']['forkIdentitySha256'])
            self.assertNotIn('PRIVATE_TOKEN',json.dumps(proof));self.assertNotIn(str(root),json.dumps(proof))

    def test_missing_skipped_failed_or_wrong_probe_xml_never_proves_runtime_closure(self):
        for kind in ('missing','skipped','failed','case','method','doctype'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'ROOT',Path(directory)):
                root=Path(directory);private,cache=self.provider_scope(root);xml=private/'offline.xml'
                if kind=='missing':xml.unlink()
                else:
                    content=xml.read_text()
                    if kind=='skipped':content=content.replace('skipped="0"','skipped="1"')
                    if kind=='failed':content=content.replace('failures="0"','failures="1"')
                    if kind=='case':content=content.replace('classname="'+B.PROVIDER_PROBE+'"','classname="Other"')
                    if kind=='method':content=content.replace('bootsActualProjectPlatformRuntime','Other')
                    if kind=='doctype':content='<!DOCTYPE testsuite>'+content
                    xml.write_text(content)
                with self.assertRaises(ValueError):B.provider_runtime_proof(cache)

    def test_foreign_source_or_runtime_artifact_and_reused_fork_receipt_cannot_warm_cache(self):
        for field in ('sourceSha','runId','attempt','phase','pid','forkedBooter','businessExecuted','runtime','duplicateBoot'):
            with self.subTest(field=field),tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'ROOT',Path(directory)):
                root=Path(directory);private,cache=self.provider_scope(root);path=private/'offline.json';proof=json.loads(path.read_text())
                if field=='sourceSha':proof[field]='0'*40
                if field in ('runId','attempt'):proof[field]='999999'
                if field=='phase':proof[field]='ONLINE'
                if field=='pid':proof[field]=proof['parentPid']
                if field=='forkedBooter':proof[field]=False
                if field=='businessExecuted':proof[field]=True
                if field=='runtime':proof[field]['launcher']['sha256']='0'*64
                if field=='duplicateBoot':
                    online=json.loads((private/'online.json').read_text());proof['pid']=online['pid'];proof['startedAt']=online['startedAt']
                path.write_text(json.dumps(proof))
                with self.assertRaises(ValueError):B.provider_runtime_proof(cache)

    def test_skip_only_or_changed_provider_runtime_proof_cannot_reuse_the_cache_receipt(self):
        report=self.cache_receipt();report.pop('providerRuntime')
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):self.check_cache_receipt(report)
        changed=self.provider_receipt();changed['offline']['proofSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):
            self.check_cache_receipt(self.cache_receipt(),provider=changed)


    def booter_fixture(self,directory):
        # Synthetic manifest/JAR bytes for URI contract controls; never Maven/JVM evidence.
        root=Path(directory)/'checkout with space';root.mkdir()
        cache=Path(directory)/'repository';cache.mkdir()
        target=root/'chengyinhub-admin/target';booter=target/'surefire/surefirebooter-control.jar'
        booter.parent.mkdir(parents=True)
        for relative in ('classes','test-classes'):(target/relative).mkdir()
        relative='org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar'
        agent=cache/relative;agent.parent.mkdir(parents=True);agent.write_bytes(b'synthetic agent control')
        local=[target/'test-classes',target/'classes',Path('/cache/repository')/relative]
        entries=[B.urllib.parse.quote(os.path.relpath(path,booter.parent),safe='/') for path in local]
        self.write_booter(booter,entries)
        argv=['java','-javaagent:/cache/repository/'+relative]+B.SUREFIRE_FORK_ARGUMENTS.split()+['-jar',str(booter)]
        return root,cache,booter,argv,entries

    def write_booter(self,path,entries,main='org.apache.maven.surefire.booter.ForkedBooter'):
        line='Class-Path: '+' '.join(entries)
        folded='\r\n '.join(line[index:index+55] for index in range(0,len(line),55))
        raw=('Manifest-Version: 1.0\r\nMain-Class: '+main+'\r\n'+folded+'\r\n\r\n').encode()
        with B.zipfile.ZipFile(path,'w') as archive:archive.writestr('META-INF/MANIFEST.MF',raw)
        return raw

    def test_relative_folded_manifest_uris_bind_real_targets_and_mapped_readonly_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root,cache,booter,argv,entries=self.booter_fixture(directory)
            self.assertNotIn(str(root),booter.read_bytes().decode(errors='ignore'))
            with patch.object(B,'ROOT',root):proof=B.booter_binding(argv,cache)
            self.assertEqual(proof['booterJarSha256'],B.digest(booter));self.assertEqual(proof['classpathEntries'],3)
            self.assertEqual(proof['mockitoAgentSha256'],B.digest(next(cache.rglob('*.jar'))))
            self.assertNotIn(str(root),json.dumps(proof));self.assertEqual(len(proof['resolvedClasspathSha256']),64)

    def test_absolute_and_relative_manifest_uris_have_the_same_canonical_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            root,cache,booter,argv,entries=self.booter_fixture(directory)
            with patch.object(B,'ROOT',root):
                relative=B.booter_binding(argv,cache)
                local=[root/'chengyinhub-admin/target/test-classes',root/'chengyinhub-admin/target/classes',
                       Path('/cache/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar')]
                self.write_booter(booter,[path.as_uri() for path in local])
                absolute=B.booter_binding(argv,cache)
            self.assertEqual(relative['resolvedClasspathSha256'],absolute['resolvedClasspathSha256'])
            self.assertNotEqual(relative['manifestSha256'],absolute['manifestSha256'])

    def test_remote_foreign_missing_or_substring_only_manifest_entries_are_rejected(self):
        for kind in ('remote','authority','foreign','traversal','query','missing-entry','missing-test','missing-both','substring','main','symlink'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                root,cache,booter,argv,entries=self.booter_fixture(directory);main='org.apache.maven.surefire.booter.ForkedBooter'
                if kind=='remote':entries.append('https://example.invalid/PRIVATE_VALUE.jar')
                if kind=='authority':entries.append('file://example.invalid/cache/repository/PRIVATE_VALUE.jar')
                if kind=='foreign':entries.append('file:///tmp/PRIVATE_VALUE.jar')
                if kind=='traversal':
                    foreign=Path(directory)/'foreign.jar';foreign.write_bytes(b'synthetic foreign file')
                    entries.append(B.urllib.parse.quote(os.path.relpath(foreign,booter.parent),safe='/'))
                if kind=='query':entries[0]+='?PRIVATE_VALUE'
                if kind=='missing-entry':entries.append('../missing.jar')
                if kind=='missing-test':entries=entries[1:]
                if kind=='missing-both':entries=entries[2:]
                if kind=='substring':
                    for index,name in enumerate(('test-classes','classes')):
                        shadow=root/'chengyinhub-admin/target'/(name+'-shadow');shadow.mkdir();entries[index]=shadow.as_uri()
                if kind=='main':main='PRIVATE_VALUE'
                if kind=='symlink':
                    classes=root/'chengyinhub-admin/target/classes';classes.rmdir();classes.symlink_to(cache,target_is_directory=True)
                self.write_booter(booter,entries,main)
                with patch.object(B,'ROOT',root),self.assertRaises(ValueError):B.booter_binding(argv,cache)

    def test_original_agent_and_exact_jar_manifest_remain_mandatory(self):
        for kind in ('extra-agent','foreign-agent','missing-agent','wrong-manifest-case','missing-jar','wrong-name','wrong-target'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                root,cache,booter,argv,entries=self.booter_fixture(directory)
                if kind=='extra-agent':argv.insert(1,argv[1])
                if kind=='foreign-agent':argv[1]='-javaagent:/tmp/PRIVATE_VALUE.jar'
                if kind=='missing-agent':next(cache.rglob('*.jar')).unlink()
                if kind=='wrong-manifest-case':
                    # A differently cased path cannot replace the one exact main manifest entry.
                    with B.zipfile.ZipFile(booter,'w') as archive:archive.writestr('meta-inf/manifest.mf',b'PRIVATE_VALUE')
                if kind=='missing-jar':booter.unlink()
                if kind in ('wrong-name','wrong-target'):
                    other=booter.with_name('not-surefire.jar') if kind=='wrong-name' else Path(directory)/booter.name
                    booter.rename(other);argv[-1]=str(other)
                with patch.object(B,'ROOT',root),self.assertRaises(ValueError):B.booter_binding(argv,cache)

    def test_literal_plus_is_not_space_and_percent_encoding_is_decoded_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root,cache,booter,argv,entries=self.booter_fixture(directory)
            plus=root/'chengyinhub-admin/target/literal+plus';plus.mkdir()
            entries+=['../literal+plus/','../literal%2Bplus/'];self.write_booter(booter,entries)
            with patch.object(B,'ROOT',root):proof=B.booter_binding(argv,cache)
            self.assertEqual(proof['classpathEntries'],5)
            self.assertFalse((plus.parent/'literal plus').exists())

    def test_actual_candidate_binding_controls_acceptance_without_claiming_no_fork_on_rejection(self):
        for reject in (False,True):
            with self.subTest(reject=reject),tempfile.TemporaryDirectory() as directory:
                root,cache,booter,argv,entries=self.booter_fixture(directory)
                if reject:self.write_booter(booter,entries[1:])
                proc=Path(directory)/'synthetic-proc';proc.mkdir()
                raw=b'\0'.join(value.encode() for value in argv+['PRIVATE_PROPERTIES_MARKER'])+b'\0'
                (proc/'cmdline').write_bytes(raw);(proc/'status').write_text('NSpid:\t100\t2\n')
                (proc/'stat').write_text('100 (java) '+' '.join(['S']+['0']*18+['123'])+'\n')
                def local_path(*parts):return proc if parts==('/proc','100') else Path(*parts)
                state={'mysql':{'cid':'2'*64},'mysqlHostPid':555,'cache':str(cache),'javaProcesses':[]}
                obj={'Id':'1'*64,'State':{'Pid':120}}
                cg={'pathSha256':'a'*64,'limits':{'synthetic':'control'},'memoryEvents':{'oom_kill':'0'}}
                with patch.object(B,'ROOT',root),patch.object(B,'Path',side_effect=local_path),\
                     patch.object(B,'read_cgroup',return_value=cg),patch.object(B,'inspect',return_value={'State':{'Running':True,'Pid':555}}),\
                     patch.object(B,'command',return_value=b'PID PPID COMMAND\n100 90 java\n'):
                    if reject:
                        with self.assertRaisesRegex(ValueError,'REAL_TEST_CLASSPATH_BINDING_REQUIRED'):B.sample_java(state,obj)
                        self.assertFalse(state['javaProcesses']);self.assertIs(state['rejectedJavaCandidate']['accepted'],False)
                        self.assertEqual(state['rejectedJavaCandidate']['role'],'SUREFIRE')
                        self.assertEqual(state['rejectedJavaCandidate']['namespacePid'],2)
                    else:
                        B.sample_java(state,obj);self.assertEqual(state['javaProcesses'][0]['role'],'SUREFIRE')
                        self.assertIn('booterJarSha256',state['javaProcesses'][0]);self.assertNotIn('rejectedJavaCandidate',state)
                self.assertNotIn('PRIVATE_PROPERTIES_MARKER',json.dumps(state))

    def test_execution_failure_exports_only_reviewed_reason_stage_and_source_location(self):
        try:B.booter_binding(['java'],Path('/not-used'))
        except ValueError as failure:proof=B.execution_failure(failure,'APP_EXECUTION','SUREFIRE_BOOTER_BINDING')
        self.assertEqual(proof['reasonCode'],'ORIGINAL_SUREFIRE_FORK_REQUIRED')
        self.assertEqual(proof['origin']['function'],'booter_binding');self.assertGreater(proof['origin']['line'],0)
        self.assertEqual(proof['substage'],'SUREFIRE_BOOTER_BINDING')
        for failure in (ValueError('PRIVATE_PASSWORD raw argv SQL'),RuntimeError('PRIVATE_PASSWORD')):
            unknown=B.execution_failure(failure,'PRIVATE_PASSWORD','PRIVATE_PASSWORD')
            self.assertEqual(unknown['reasonCode'],'UNCLASSIFIED_RESOURCE_FAILURE');self.assertEqual(unknown['phase'],'UNKNOWN')
            self.assertNotIn('PRIVATE_PASSWORD',json.dumps(unknown));self.assertNotIn('origin',unknown)

    def test_run_failure_saves_current_partial_sample_and_preserves_original_first_failure(self):
        state={'phase':'PREPARED','javaProcesses':[]};saved=[]
        def failing_owner(path,current):
            current['phase']='APP_EXECUTION';current['executionSubstage']='SUREFIRE_BOOTER_BINDING'
            current['javaProcesses'].append({'role':'MAVEN','argvSha256':'a'*64})
            B.booter_binding(['java'],Path('/not-used'))
        with patch.dict(os.environ,self.env(),clear=True),patch.object(B,'load_private',return_value=(Path('/not-used'),state)),\
             patch.object(B,'run_owned_app',side_effect=failing_owner),patch.object(B,'save',side_effect=lambda path,value:saved.append(copy.deepcopy(value))):
            with self.assertRaisesRegex(ValueError,'ORIGINAL_SUREFIRE_FORK_REQUIRED'):B.run(Path('/not-used'))
            first=copy.deepcopy(state['firstFailure'])
            state['phase']='PREPARED' # Synthetic handler replay; no real admission or process restart.
            with self.assertRaises(ValueError):B.run(Path('/not-used'))
        self.assertEqual(saved[0]['javaProcesses'][0]['role'],'MAVEN')
        self.assertEqual(first['origin']['function'],'booter_binding');self.assertEqual(state['firstFailure'],first)
        self.assertNotIn('mavenExit',state);self.assertNotIn('appExit',state)

    def namespace_fixture(self,directory):
        path=Path(directory)/'private';(path/'launch').mkdir(parents=True)
        source=path/'launch/CombinedHttpNamespacePortProbe.java';source.write_bytes(b'synthetic source-control bytes, not Java execution')
        namespace=b'net:[12345]';obj=self.obj();obj['State'].update(Running=True,Pid=120)
        mysql={'Id':'2'*64,'State':{'Running':True,'Pid':555}}
        state={**B.identity(self.env()),'identity':B.identity(self.env()),'nonce':'nonce','mysql':{'cid':'2'*64},
               'mysqlHostPid':555,'networkNamespaceSha256':B.hashlib.sha256(namespace).hexdigest(),
               'namespaceProbeSourceSha256':B.digest(source),'redisPort':6391,'schema':self.env()['CHENGYIN_HTTP_E2E_DATABASE'],
               'source':None,'mysqlBootstrap':{},'cacheProof':{},'phase':'APP_EXECUTION','javaProcesses':[{'role':'MAVEN'}],
               'slots':{'app':{'cid':'1'*64,'name':'own-app'},'redis':{'cid':'3'*64,'name':'own-redis'}},
               'firstFailure':{'phase':'APP_EXECUTION','type':'ValueError','reasonCode':'REAL_TEST_CLASSPATH_BINDING_REQUIRED'}}
        return path,state,obj,mysql,namespace

    def test_failed_execution_removes_redis_then_attempts_real_namespace_bind_before_app_cleanup(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True):
            path,state,obj,mysql,namespace=self.namespace_fixture(directory);events=[];reports=[]
            out=Path(directory)/'out';out.mkdir();(out/'run.log').write_bytes(b'private existing log')
            state['runLogCaptured']=True # This old namespace-only fixture already owns its completed log capture.
            def command(argv,**kw):
                if argv[-2:]==['readlink','/proc/self/ns/net']:return namespace
                self.assertEqual(argv,B.DOCKER+['exec','1'*64,'java','-Xmx32m','/budget/CombinedHttpNamespacePortProbe.java','6391'])
                events.append('REAL_BIND_COMMAND');return b'NAMESPACE_REDIS_PORT_FREE 6391\n'
            def cleanup(path,current,role):
                events.append('CLEANUP_'+role)
                return {'role':role,'cid':current['slots'][role]['cid'],'result':'EXACT_CID_REMOVED_AND_ABSENT',
                        'terminal':{'ExitCode':143 if role=='app' else 0,'OOMKilled':False,'Running':False,'Pid':0}}
            with patch.object(B,'load_private',return_value=(path,state)),patch.object(B,'OUT',out),\
                 patch.object(B,'inspect',return_value=obj),patch.object(B,'backing_mysql',return_value=(mysql,{})),\
                 patch.object(B,'command',side_effect=command),patch.object(B,'read_cgroup',return_value={'memoryEvents':{'oom_kill':'0'}}),\
                 patch.object(B,'cleanup_slot',side_effect=cleanup),patch.object(B,'write',side_effect=lambda path,value:reports.append(copy.deepcopy(value))):
                with self.assertRaisesRegex(ValueError,'ORIGINAL_EXIT_OR_BUDGET_OR_CLEANUP_NOT_ACCEPTED'):B.finalize(path,2)
            report=reports[-1]
            self.assertEqual(events,['CLEANUP_redis','REAL_BIND_COMMAND','CLEANUP_app'])
            self.assertEqual(report['namespaceRedisPortFree']['result'],'ACTUAL_OWNER_NAMESPACE_BIND_FREE')
            self.assertEqual(report['result'],'NOT_ACCEPTED');self.assertEqual(report['firstFailure'],state['firstFailure'])
            self.assertIsNone(report['mavenExit']);self.assertIsNone(report['appExit']);self.assertEqual(report['originalExit'],2)
            B.redis_cleanup_proven(report,B.identity(self.env()))

    def test_failed_namespace_bind_retains_first_failure_and_still_cleans_the_exact_app(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True):
            path,state,obj,mysql,namespace=self.namespace_fixture(directory);events=[];reports=[]
            out=Path(directory)/'out';out.mkdir();(out/'run.log').write_bytes(b'private existing log')
            state['runLogCaptured']=True # Keep this control scoped to its original namespace failure.
            def cleanup(path,current,role):
                events.append(role)
                return {'role':role,'cid':current['slots'][role]['cid'],'result':'EXACT_CID_REMOVED_AND_ABSENT',
                        'terminal':{'ExitCode':143,'OOMKilled':False,'Running':False,'Pid':0}}
            with patch.object(B,'load_private',return_value=(path,state)),patch.object(B,'OUT',out),\
                 patch.object(B,'namespace_port_proof',side_effect=ValueError('ACTUAL_NAMESPACE_BIND_PROOF_REQUIRED')),\
                 patch.object(B,'cleanup_slot',side_effect=cleanup),patch.object(B,'write',side_effect=lambda path,value:reports.append(copy.deepcopy(value))):
                with self.assertRaisesRegex(ValueError,'ORIGINAL_EXIT_OR_BUDGET_OR_CLEANUP_NOT_ACCEPTED'):B.finalize(path,2)
            report=reports[-1];self.assertEqual(events,['redis','app']);self.assertEqual(report['result'],'NOT_ACCEPTED')
            self.assertEqual(report['namespaceRedisPortFree'],{'result':'UNKNOWN'})
            self.assertEqual(report['firstFailure'],state['firstFailure'])
            self.assertEqual(report['failures'][0]['reasonCode'],'ACTUAL_NAMESPACE_BIND_PROOF_REQUIRED')
            with self.assertRaisesRegex(ValueError,'ACTUAL_INTERNAL_REDIS_CLEANUP_REQUIRED'):B.redis_cleanup_proven(report,B.identity(self.env()))

    def test_changed_mysql_namespace_probe_or_bind_result_never_certifies_redis_cleanup(self):
        for kind in ('mysql-pid','namespace','source','bind','budget'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True):
                path,state,obj,mysql,namespace=self.namespace_fixture(directory);calls=[]
                if kind=='mysql-pid':mysql['State']['Pid']+=1
                if kind=='namespace':state['networkNamespaceSha256']='0'*64
                if kind=='source':(path/'launch/CombinedHttpNamespacePortProbe.java').write_bytes(b'changed')
                if kind=='budget':obj['HostConfig']['Memory']=123
                def command(argv,**kw):
                    calls.append(argv)
                    if argv[-2:]==['readlink','/proc/self/ns/net']:return namespace
                    return b'HOST_SOCKET_BIND_OK PRIVATE_VALUE\n'
                with patch.object(B,'inspect',return_value=obj),patch.object(B,'backing_mysql',return_value=(mysql,{})),\
                     patch.object(B,'command',side_effect=command):
                    with self.assertRaises(ValueError):B.namespace_port_proof(path,state)
                if kind!='bind':self.assertFalse(any('java' in argv for argv in calls))

    def test_already_stopped_app_cannot_substitute_container_absence_for_namespace_bind(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True):
            path,state,obj,mysql,namespace=self.namespace_fixture(directory);obj['State'].update(Running=False,Pid=0)
            with patch.object(B,'inspect',return_value=obj),patch.object(B,'command') as command,patch.object(B,'backing_mysql') as backing:
                with self.assertRaisesRegex(ValueError,'ORIGINAL_EXECUTION_NAMESPACE_NOT_LIVE'):B.namespace_port_proof(path,state)
            command.assert_not_called();backing.assert_not_called()


    def configuration_scope(self,root):
        # Synthetic protocol inputs only; not javac, real JUnit or datasource evidence.
        self.compilation_sources(root);self.provider_class(root)
        source=root/'chengyinhub-admin/src/test/java'/B.CONFIGURATION_SOURCE
        test_class=root/'chengyinhub-admin/target/test-classes'/(B.CONFIGURATION_UNIT.replace('.','/')+'.class')
        photo_source=root/'chengyinhub-admin/src/test/java'/B.PHOTO_SOURCE
        photo_class=root/'chengyinhub-admin/target/test-classes'/(B.PHOTO_UNIT.replace('.','/')+'.class')
        compilation={'sources':{B.CONFIGURATION_SOURCE:B.digest(source),B.PHOTO_SOURCE:B.digest(photo_source)},
                     'configurationTestClassSha256':B.digest(test_class),'photoServiceTestClassSha256':B.digest(photo_class)}
        directory=root/'chengyinhub-admin/target/provider-runtime';directory.mkdir(mode=0o700)
        xml=directory/'configuration.xml'
        xml.write_text('<testsuite name="'+B.CONFIGURATION_UNIT+'" tests="'+str(len(B.CONFIGURATION_METHODS))+'" failures="0" errors="0" skipped="0">'
            '<properties><property name="PRIVATE_VALUE" value="PRIVATE_VALUE"/></properties>'+
            ''.join('<testcase classname="'+B.CONFIGURATION_UNIT+'" name="'+method+'"/>' for method in B.CONFIGURATION_METHODS)+'</testsuite>')
        photo_xml=directory/'photo-service.xml'
        photo_xml.write_text('<testsuite name="'+B.PHOTO_UNIT+'" tests="'+str(len(B.PHOTO_METHODS))+'" failures="0" errors="0" skipped="0">'+
            ''.join('<testcase classname="'+B.PHOTO_UNIT+'" name="'+method+'"/>' for method in B.PHOTO_METHODS)+'</testsuite>')
        photo_xml.chmod(0o600)
        xml.chmod(0o600);return xml,source,test_class,compilation

    def test_configuration_unit_runs_once_online_without_replacing_either_provider_fork_or_business(self):
        shell=B.fixed_cache_command()[1]
        online=shell.split('"ONLINE_PROVIDER_RUNTIME"',1)[1].split('"CONTENT_SECURITY_COMPILE"',1)[0]
        units=shell.split('"ONLINE_CONFIGURATION_UNITS"',1)[1].split('"OFFLINE_PROVIDER_RUNTIME"',1)[0]
        offline=shell.split('"OFFLINE_PROVIDER_RUNTIME"',1)[1].split('"OFFLINE_TEST_PREFLIGHT"',1)[0]
        self.assertIn("'-Dtest="+B.PROVIDER_PROBE+"'",online)
        self.assertNotIn("'-Dtest="+B.CONFIGURATION_UNIT,online)
        self.assertIn("'-Dtest="+B.CONFIGURATION_UNIT+','+B.PHOTO_UNIT+"'",units)
        self.assertNotIn(B.PROVIDER_PROBE,units)
        self.assertIn("'-Dtest="+B.PROVIDER_PROBE+"'",offline);self.assertNotIn(B.CONFIGURATION_UNIT,offline)
        self.assertIn('rm -f',online);self.assertIn('TEST-'+B.CONFIGURATION_UNIT+'.xml',online)
        self.assertIn('rm -f',units);self.assertIn('TEST-'+B.CONFIGURATION_UNIT+'.xml',units)
        self.assertIn('provider-runtime/configuration.xml',units)
        self.assertIn('provider-runtime/photo-service.xml',units);self.assertNotIn(B.PHOTO_UNIT,offline)
        self.assertNotIn('provider-runtime/configuration.xml',online);self.assertNotIn('provider-runtime/photo-service.xml',online)
        self.assertNotIn('provider-runtime/configuration.xml',offline);self.assertNotIn('provider-runtime/photo-service.xml',offline)
        for test_class,output in ((B.CONFIGURATION_UNIT,'configuration.xml'),(B.PHOTO_UNIT,'photo-service.xml')):
            copy="cp 'chengyinhub-admin/target/surefire-reports/TEST-"+test_class+".xml' 'chengyinhub-admin/target/provider-runtime/"+output+"'"
            self.assertEqual(shell.count(copy),1);self.assertIn(copy,units)
        self.assertIn(B.PHOTO_SOURCE,B.TEST_SOURCES)
        for phase in (online,units,offline):
            self.assertNotIn('-DskipTests',phase);self.assertNotIn('-Dmaven.test.skip=true',phase)
            self.assertIn('-DforkCount=1',phase);self.assertIn('-DreuseForks=true',phase)
        self.assertIn('-Dtest='+B.CLASS,B.fixed_maven_command())
        self.assertNotIn(B.CONFIGURATION_UNIT,B.fixed_maven_command())
        self.assertEqual(B.TEST_SOURCES[-1],B.CONFIGURATION_SOURCE);self.assertEqual(len(B.TEST_SOURCES)-2,19)
        self.assertEqual(len(B.REQUIRED_TEST_CLASSES),37);self.assertEqual(B.LIMIT_SECONDS,1200);self.assertEqual(B.MEMORY,1073741824)

    def test_configuration_xml_requires_both_original_methods_no_retry_and_private_source_class_binding(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'ROOT',Path(directory)):
            xml,source,test_class,compilation=self.configuration_scope(Path(directory));proof=B.configuration_unit_proof(compilation)
            self.assertEqual(proof['purpose'],'CONFIG_ASSEMBLY_ONLY');self.assertIs(proof['businessExecuted'],False)
            self.assertEqual({key:proof[key] for key in ('tests','failures','errors','skipped')},{'tests':11,'failures':0,'errors':0,'skipped':0})
            self.assertEqual(proof['methods'],list(B.CONFIGURATION_METHODS));self.assertEqual(proof['xmlSha256'],B.digest(xml))
            self.assertEqual(proof['sourceSha256'],B.digest(source));self.assertEqual(proof['testClassSha256'],B.digest(test_class))
            self.assertEqual(proof['sourceSha'],self.env()['SOURCE_SHA']);self.assertEqual(proof['runId'],self.env()['GITHUB_RUN_ID'])
            self.assertEqual(proof['attempt'],self.env()['GITHUB_RUN_ATTEMPT'])
            self.assertNotIn('PRIVATE_VALUE',json.dumps(proof));self.assertNotIn(directory,json.dumps(proof))
            photo=proof['relatedPhotoUnit'];self.assertEqual(photo['purpose'],'PHOTO_STATE_UNIT_ONLY')
            self.assertIs(photo['businessExecuted'],False);self.assertEqual(photo['testClass'],B.PHOTO_UNIT)
            self.assertEqual(photo['methods'],list(B.PHOTO_METHODS));self.assertEqual(photo['tests'],len(B.PHOTO_METHODS))
            self.assertEqual(photo['sourceSha256'],B.digest(Path(directory)/'chengyinhub-admin/src/test/java'/B.PHOTO_SOURCE))
            self.assertEqual(photo['testClassSha256'],compilation['photoServiceTestClassSha256'])
        for kind in ('missing','count','old-two','old-three','old-five','old-seven','old-nine','duplicate','case','method','failure','error','skip','retry','doctype','source','class','mode'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'ROOT',Path(directory)):
                xml,source,test_class,compilation=self.configuration_scope(Path(directory));raw=xml.read_text()
                if kind=='missing':xml.unlink()
                elif kind=='source':source.write_text('changed source')
                elif kind=='class':test_class.write_bytes(b'changed class')
                elif kind=='mode':xml.chmod(0o644)
                else:
                    current='tests="'+str(len(B.CONFIGURATION_METHODS))+'"'
                    if kind=='count':raw=raw.replace(current,'tests="'+str(len(B.CONFIGURATION_METHODS)-1)+'"')
                    if kind in ('old-two','old-three','old-five','old-seven','old-nine'):
                        retained={'old-two':2,'old-three':3,'old-five':5,'old-seven':7,'old-nine':9}[kind]
                        raw=raw.replace(current,'tests="'+str(retained)+'"')
                        for method in B.CONFIGURATION_METHODS[retained:]:
                            raw=raw.replace('<testcase classname="'+B.CONFIGURATION_UNIT+'" name="'+method+'"/>','')
                    if kind=='duplicate':raw=raw.replace(B.CONFIGURATION_METHODS[1],B.CONFIGURATION_METHODS[0])
                    if kind=='case':raw=raw.replace('classname="'+B.CONFIGURATION_UNIT+'"','classname="Other"')
                    if kind=='method':raw=raw.replace(B.CONFIGURATION_METHODS[0],'Other')
                    if kind in ('failure','error','skip','retry'):
                        tag={'failure':'failure','error':'error','skip':'skipped','retry':'rerunFailure'}[kind]
                        raw=raw.replace('/></testsuite>','><'+tag+'/></testcase></testsuite>')
                    if kind=='doctype':raw='<!DOCTYPE testsuite>'+raw
                    xml.write_text(raw)
                with self.assertRaises(ValueError):B.configuration_unit_proof(compilation)

        for kind in ('missing','count','method','source','class','mode','skip','retry'):
            with self.subTest(photo=kind),tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'ROOT',Path(directory)):
                xml,source,test_class,compilation=self.configuration_scope(Path(directory))
                photo=xml.parent/'photo-service.xml';raw=photo.read_text()
                if kind=='missing':photo.unlink()
                elif kind=='mode':photo.chmod(0o644)
                elif kind=='source':(Path(directory)/'chengyinhub-admin/src/test/java'/B.PHOTO_SOURCE).write_text('changed')
                elif kind=='class':(Path(directory)/'chengyinhub-admin/target/test-classes'/(B.PHOTO_UNIT.replace('.','/')+'.class')).write_bytes(b'changed')
                else:
                    if kind=='count':raw=raw.replace('tests="'+str(len(B.PHOTO_METHODS))+'"','tests="0"')
                    if kind=='method':raw=raw.replace(B.PHOTO_METHODS[0],'Unreviewed')
                    if kind in ('skip','retry'):
                        tag='skipped' if kind=='skip' else 'rerunFailure'
                        raw=raw.replace('/></testsuite>','><'+tag+'/></testcase></testsuite>')
                    photo.write_text(raw)
                with self.assertRaises(ValueError):B.configuration_unit_proof(compilation)

    def test_configuration_class_and_fresh_receipt_are_required_on_cache_creation_and_reuse(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)):
            root=Path(directory);self.compilation_sources(root);inputs=B.fixed_test_compilation_inputs()
            for name in B.REQUIRED_TEST_CLASSES:
                path=root/'chengyinhub-admin/target/test-classes'/(name.replace('.','/')+'.class')
                path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'synthetic original required class')
            self.provider_class(root);self.compiler_input_status(root)
            proof=B.fixed_test_compilation_outputs(inputs)
            self.assertEqual(len(proof['classes'])+3,40);self.assertEqual(len(proof['sources']),21)
            configuration=root/'chengyinhub-admin/target/test-classes'/(B.CONFIGURATION_UNIT.replace('.','/')+'.class')
            self.assertEqual(proof['configurationTestClassSha256'],B.digest(configuration))
            configuration.unlink()
            with self.assertRaisesRegex(ValueError,'FIXED_TEST_COMPILATION_OUTPUT_REQUIRED'):B.fixed_test_compilation_outputs(inputs)
        report=self.cache_receipt();report.pop('configurationUnit')
        with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):self.check_cache_receipt(report)
        for key in ('xmlSha256','sourceSha256','testClassSha256','purpose','tests'):
            changed=self.configuration_receipt();changed[key]='changed'
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):
                self.check_cache_receipt(self.cache_receipt(),configuration=changed)


    def test_runtime_memory_profiles_are_identical_in_warm_probes_and_actual_consumers(self):
        shell=B.fixed_cache_command()[1];child=B.child_environment(self.env())
        self.assertEqual(child['MAVEN_OPTS'],B.RUNTIME_MAVEN_OPTIONS)
        self.assertEqual(shell.count("MAVEN_OPTS='"+B.RUNTIME_MAVEN_OPTIONS+"'"),3)
        self.assertEqual(shell.count(B.SUREFIRE_FORK_ARGUMENTS),3)
        self.assertIn('-DargLine='+B.SUREFIRE_FORK_ARGUMENTS,B.fixed_maven_command())
        for role,options,heap,direct,code in (('MAVEN',B.RUNTIME_MAVEN_OPTIONS,128,16,32),
                ('SUREFIRE',B.SUREFIRE_MEMORY_ARGUMENTS,384,64,64)):
            proof=B.jvm_memory_binding(options.split(),role)
            self.assertEqual(proof['maxHeapBytes'],heap*1024**2);self.assertEqual(proof['maxDirectMemoryBytes'],direct*1024**2)
            self.assertEqual(proof['reservedCodeCacheBytes'],code*1024**2);self.assertEqual(proof['collector'],'SerialGC')
        self.assertEqual(B.LIMIT_SECONDS,1200);self.assertEqual(B.MEMORY,1073741824)
        self.assertIn('-o',B.fixed_maven_command());self.assertIn('-Dtest='+B.CLASS,B.fixed_maven_command())
        self.assertEqual(len(B.TEST_SOURCES),21);self.assertEqual(len(B.REQUIRED_TEST_CLASSES),37)

    def test_missing_duplicate_or_conflicting_jvm_allocations_are_rejected_before_acceptance(self):
        for role,options in (('MAVEN',B.RUNTIME_MAVEN_OPTIONS),('SUREFIRE',B.SUREFIRE_MEMORY_ARGUMENTS),
                            ('DESCENDANT_JVM',B.SCHEDULER_MEMORY_ARGUMENTS)):
            original=options.split()+([B.SCHEDULER_PROBE] if role=='DESCENDANT_JVM' else [])
            self.assertIsNotNone(B.jvm_memory_binding(original,role))
            kinds=('heap','missing-direct','duplicate','collector','percentage') if role!='DESCENDANT_JVM' else ('heap','unexpected-direct','duplicate','collector','percentage')
            for kind in kinds:
                argv=list(original)
                if kind=='heap':argv=[('-Xmx1024m' if value.startswith('-Xmx') else value) for value in argv]
                if kind=='missing-direct':argv=[value for value in argv if not value.startswith('-XX:MaxDirectMemorySize=')]
                if kind=='unexpected-direct':argv.append('-XX:MaxDirectMemorySize=128m')
                if kind=='duplicate':argv.append(next(value for value in argv if value.startswith('-Xmx')))
                if kind=='collector':argv.append('-XX:+UseG1GC')
                if kind=='percentage':argv.append('-XX:MaxRAMPercentage=90')
                with self.subTest(role=role,kind=kind),self.assertRaisesRegex(ValueError,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED'):
                    B.jvm_memory_binding(argv,role)
        self.assertIsNone(B.jvm_memory_binding(['java','UnreviewedDescendant'],'DESCENDANT_JVM'))

    def test_scope_memory_counters_are_bounded_private_and_do_not_override_oom_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'memory.current').write_text('12345\n');(root/'memory.peak').write_text('45678\n')
            (root/'memory.stat').write_text('anon 10\nfile 20\nshmem 5\nkernel 4\nkernel_stack 3\npagetables 2\nsock 1\nPRIVATE_VALUE 999\n')
            proof=B.cgroup_memory_usage(root);self.assertEqual(proof['state'],'OBSERVED')
            self.assertEqual(proof['currentBytes'],12345);self.assertEqual(proof['peakBytes'],45678)
            self.assertEqual(proof['stat']['file'],20);self.assertEqual(proof['stat']['shmem'],5)
            self.assertNotIn('PRIVATE_VALUE',json.dumps(proof))
            (root/'memory.peak').unlink();self.assertEqual(B.cgroup_memory_usage(root)['state'],'PARTIAL_OR_UNKNOWN')
        measured=B.process_memory('NSpid:\t100\t2\nVmRSS:\t10 kB\nRssAnon:\t6 kB\nRssFile:\t3 kB\nRssShmem:\t1 kB\nVmSwap:\t0 kB\nThreads:\t7\nPRIVATE_VALUE: secret\n')
        self.assertEqual(measured['rssBytes'],10240);self.assertEqual(measured['threads'],7)
        self.assertNotIn('PRIVATE_VALUE',json.dumps(measured));self.assertIsNone(B.process_memory('')['rssBytes'])
        state,cleaned,port=self.outcome();state['cgroupMemoryEvents']['oom_kill']='1'
        state['cgroupMemoryUsage']={'currentBytes':0,'state':'OBSERVED'}
        self.assertFalse(B.accepted_outcome(state,0,[],cleaned,port))

    def test_subject_requires_the_same_fixed_memory_profiles_as_the_observed_runtime(self):
        report=self.subject_report();expected=B.identity(self.env())
        B.check_subject(report,expected,41,'f'*64)
        for role in ('MAVEN','SUREFIRE'):
            for kind in ('missing','changed'):
                wrong=copy.deepcopy(report);process=next(row for row in wrong['javaProcesses'] if row['role']==role)
                if kind=='missing':process.pop('jvmMemory')
                else:process['jvmMemory']['maxHeapBytes']=B.MEMORY*2
                with self.subTest(role=role,kind=kind),self.assertRaisesRegex(ValueError,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED'):
                    B.check_subject(wrong,expected,41,'f'*64)

    def test_scheduler_heap_binding_and_third_configuration_method_preserve_original_two_methods(self):
        self.assertEqual(B.SCHEDULER_MEMORY_ARGUMENTS,'-Xmx64m')
        argv=B.SCHEDULER_MEMORY_ARGUMENTS.split()+['-cp','unchanged-classpath',B.SCHEDULER_PROBE]
        proof=B.jvm_memory_binding(argv,'DESCENDANT_JVM');self.assertEqual(proof['maxHeapBytes'],64*1024**2)
        self.assertIsNone(proof['maxDirectMemoryBytes']);self.assertIsNone(proof['reservedCodeCacheBytes'])
        self.assertEqual(proof['collector'],'DEFAULT_UNATTESTED')
        self.assertEqual(B.CONFIGURATION_METHODS[:2],('boundedPoolOverridesBothIncompatibleInheritedFloorsWithoutChangingOtherOwners',
                         'originalDruidPropertiesAssemblesAValidUninitializedThreeConnectionPool'))
        self.assertEqual(B.CONFIGURATION_METHODS[2:3],('schedulerChildHeapIsFixedOnlyForOriginalCombinedScope',))
        self.assertEqual(B.CONFIGURATION_METHODS[3:5],('combinationIdentityFileRetainsFrozenBytesAcrossIntegerNodeWidths',
                         'combinationIdentityFileRejectsExistingAndPendingFilesBeforeSql'))
        self.assertEqual(B.CONFIGURATION_METHODS[5:7],('protectedFactDigestPreservesJdbcScalarAndTimeFacts',
                         'protectedFactDigestRetainsQueryAndSerializationFailures'))
        self.assertEqual(B.CONFIGURATION_METHODS[7:9],('photoNeverReadRetainsConnectionAcrossIndependentTransaction',
                         'photoPrepareFailureProjectionKeepsOnlyReviewedCodesAndCause'))
        self.assertEqual(B.CONFIGURATION_METHODS[9:],('precompiledContentSecurityFixtureKeepsOriginalChecksAndRejectsUnprovenOutputs',
                         'providerClasspathCapturePreservesExactBytesAndRejectsUnownedEntries'))
        self.assertEqual(len(B.CONFIGURATION_METHODS),11)
        self.assertEqual(len(B.PHOTO_METHODS),23)


    def preparation_xml(self,root,test_class,methods,outcomes=None):
        # Synthetic JUnit transport shape, not execution evidence. Private text must never project.
        outcomes=outcomes or {};counts={'failures':0,'errors':0,'skipped':0}
        for nodes in outcomes.values():
            for tag,kind,text in nodes:
                if tag in ('failure','error','skipped'):counts[{'failure':'failures','error':'errors','skipped':'skipped'}[tag]]+=1
        suite=B.ET.Element('testsuite',name=test_class,tests=str(len(methods)),**{key:str(value) for key,value in counts.items()})
        props=B.ET.SubElement(suite,'properties');B.ET.SubElement(props,'property',name='PRIVATE_ENV',value='PRIVATE_VALUE')
        for method in methods:
            case=B.ET.SubElement(suite,'testcase',classname=test_class,name=method)
            for tag,kind,text in outcomes.get(method,[]):
                node=B.ET.SubElement(case,tag,type=kind,message='PRIVATE_MESSAGE SQL URL private-id')
                node.text=text
        B.ET.SubElement(suite,'system-out').text='PRIVATE_STDOUT'
        path=root/'chengyinhub-admin/target/surefire-reports'/('TEST-'+test_class+'.xml')
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(B.ET.tostring(suite));path.chmod(0o644)
        return path

    def preparation_scope(self,root):
        self.compilation_sources(root)
        for test_class,source_path,methods,key in B.preparation_report_specs():
            source=root/'chengyinhub-admin/src/test/java'/source_path
            source.write_text('// Synthetic public source line, not a javac/VM claim\n'*40)
        for name in B.REQUIRED_TEST_CLASSES:
            path=root/'chengyinhub-admin/target/test-classes'/(name.replace('.','/')+'.class')
            path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'synthetic original class')
        self.provider_class(root);self.compiler_input_status(root)
        inputs=B.fixed_test_compilation_inputs();expected=B.fixed_test_compilation_outputs(inputs)
        obj=self.obj();obj['HostConfig']['NetworkMode']='bridge';obj['State'].update(Running=True,Pid=17)
        obj['Config'].update(Cmd=B.fixed_cache_command(),Entrypoint=['/bin/bash'],User=str(os.getuid())+':'+str(os.getgid()),
            Env=[key+'='+value for key,value in {'SOURCE_SHA':self.env()['SOURCE_SHA'],'GITHUB_SHA':self.env()['SOURCE_SHA'],
                'GITHUB_RUN_ID':self.env()['GITHUB_RUN_ID'],'GITHUB_RUN_ATTEMPT':self.env()['GITHUB_RUN_ATTEMPT']}.items()])
        state={'nonce':'nonce','slots':{'app':{'cid':obj['Id'],'name':'own-app'}}}
        report={**B.identity(self.env()),'phase':'ONLINE_CONFIGURATION_UNITS','mavenExitObserved':True,'mavenExit':1,
            'originalExit':1,'result':'NOT_ACCEPTED','imageId':obj['Image'],'mavenCoreSha256':'c'*64,'pomSha256':{'pom.xml':'f'*64},
            'budget':B.validate_container(obj,'own-app',B.JDK,B.labels(self.env(),'nonce'),B.MEMORY,'bridge',obj['Id']),
            'firstFailure':{'phase':'PINNED_CACHE_PREPARATION','type':'ValueError','reasonCode':'OFFLINE_CACHE_PREFLIGHT_FAILED'}}
        files={}
        for test_class,source_path,methods,key in B.preparation_report_specs():
            outcomes={}
            if test_class==B.CONFIGURATION_UNIT:
                text='org.opentest4j.AssertionFailedError: PRIVATE_VALUE\n\tat '+test_class+'.'+methods[0]+'('+test_class.rsplit('.',1)[1]+'.java:12)\nPRIVATE_SQL PRIVATE_TOKEN'
                outcomes={methods[0]:[('failure','org.opentest4j.AssertionFailedError',text)]}
            files[test_class]=self.preparation_xml(root,test_class,methods,outcomes)
        return report,inputs,expected,state,obj,files

    def preparation_scan(self,scope):
        report,inputs,expected,state,obj,files=scope
        with patch.object(B,'cache_inputs',return_value={'pom.xml':'f'*64}):
            return B.preparation_test_evidence(report,inputs,expected,state,obj,report['phase'])

    def test_preparation_failure_projection_binds_compilation_and_exports_only_fixed_failure_facts(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)),patch.dict(os.environ,self.env(),clear=True):
            scope=self.preparation_scope(Path(directory));before=copy.deepcopy(scope[0]);proof=self.preparation_scan(scope)
            self.assertEqual(proof['state'],'OBSERVED');self.assertIs(proof['businessAccepted'],False)
            self.assertEqual(proof['purpose'],'PREPARATION_JUNIT_DIAGNOSTIC_ONLY');self.assertEqual(proof['mavenExit'],1)
            self.assertEqual(len(proof['compilationSha256']),64)
            rows={row['testClass']:row for row in proof['suites']};failed=rows[B.CONFIGURATION_UNIT]
            self.assertEqual(failed['counts'],{'tests':11,'failures':1,'errors':0,'skipped':0,'retries':0})
            self.assertEqual(failed['nonPassing'],[{'method':B.CONFIGURATION_METHODS[0],'kind':'FAILURE',
                'type':'org.opentest4j.AssertionFailedError','origin':{'class':B.CONFIGURATION_UNIT,'method':B.CONFIGURATION_METHODS[0],'line':12}}])
            self.assertEqual(set(rows),{B.CONFIGURATION_UNIT,B.PHOTO_UNIT});self.assertEqual(rows[B.PHOTO_UNIT]['nonPassing'],[])
            self.assertEqual(scope[0],before)
            for private in ('PRIVATE_VALUE','PRIVATE_MESSAGE','PRIVATE_SQL','PRIVATE_TOKEN','PRIVATE_STDOUT',directory):
                self.assertNotIn(private,json.dumps(proof))
            # A separate synthetic ERROR stays ERROR; passing siblings never acquire a failure.
            method=B.PHOTO_METHODS[2]
            self.preparation_xml(Path(directory),B.PHOTO_UNIT,B.PHOTO_METHODS,{method:[('error','java.lang.IllegalStateException',
                '\tat '+B.PHOTO_UNIT+'.'+method+'(PhotoCheckIntentServiceTest.java:13)\nPRIVATE_SQL')]})
            photo=next(row for row in self.preparation_scan(scope)['suites'] if row['testClass']==B.PHOTO_UNIT)
            self.assertEqual(photo['counts']['errors'],1);self.assertEqual(photo['counts']['failures'],0)
            self.assertEqual(photo['nonPassing'][0]['kind'],'ERROR');self.assertEqual(photo['nonPassing'][0]['origin']['line'],13)
            scope[0]['phase']='ONLINE_PROVIDER_RUNTIME'
            self.preparation_xml(Path(directory),B.PROVIDER_PROBE,('bootsActualProjectPlatformRuntime',),
                {'bootsActualProjectPlatformRuntime':[('failure','java.lang.AssertionError',
                    '\tat '+B.PROVIDER_PROBE+'.bootsActualProjectPlatformRuntime(CombinedSurefireRuntimeProbeTest.java:14)')]})
            online=self.preparation_scan(scope);self.assertEqual(online['state'],'OBSERVED')
            self.assertEqual([row['testClass'] for row in online['suites']],[B.PROVIDER_PROBE])
            self.assertEqual(online['suites'][0]['counts'],{'tests':1,'failures':1,'errors':0,'skipped':0,'retries':0})
            self.assertEqual(online['suites'][0]['nonPassing'][0]['origin']['line'],14)

    def test_missing_bad_mixed_and_unreviewed_preparation_reports_remain_unknown_without_raw_content(self):
        for kind in ('missing','symlink','doctype','malformed','oversize','mixed','foreign-case','unknown-method','duplicate','counts','writable'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)),patch.dict(os.environ,self.env(),clear=True):
                scope=self.preparation_scope(Path(directory));path=scope[-1][B.CONFIGURATION_UNIT];raw=path.read_text()
                if kind=='missing':path.unlink()
                elif kind=='symlink':
                    path.unlink();foreign=Path(directory)/'foreign.xml';foreign.write_text('PRIVATE_VALUE');path.symlink_to(foreign)
                elif kind=='writable':path.chmod(0o666)
                else:
                    if kind=='doctype':raw='<!DOCTYPE testsuite [<!ENTITY secret SYSTEM "file:///PRIVATE_VALUE">]>'+raw
                    if kind=='malformed':raw='<PRIVATE_VALUE'
                    if kind=='oversize':raw='PRIVATE_VALUE'+'x'*(2*1024*1024)
                    if kind=='mixed':raw=raw.replace('</testsuite>','<testsuite name="PRIVATE_VALUE"/></testsuite>')
                    if kind=='foreign-case':raw=raw.replace('classname="'+B.CONFIGURATION_UNIT+'"','classname="PRIVATE_VALUE"',1)
                    if kind=='unknown-method':raw=raw.replace(B.CONFIGURATION_METHODS[0],'PRIVATE_VALUE')
                    if kind=='duplicate':raw=raw.replace(B.CONFIGURATION_METHODS[1],B.CONFIGURATION_METHODS[0])
                    if kind=='counts':raw=raw.replace('failures="1"','failures="2"')
                    path.write_text(raw)
                proof=self.preparation_scan(scope);row=next(row for row in proof['suites'] if row['testClass']==B.CONFIGURATION_UNIT)
                self.assertEqual(row['state'],'UNKNOWN');self.assertNotIn('nonPassing',row)
                self.assertEqual(proof['state'],'PARTIAL_OR_UNKNOWN');self.assertNotIn('PRIVATE_VALUE',json.dumps(proof))

    def test_foreign_source_class_compile_snapshot_owner_or_phase_cannot_project_preparation_failures(self):
        for kind in ('sourceSha','runId','attempt','source','class','missing-class','snapshot','owner','budget','command','phase','unobserved','zero','env'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)),patch.dict(os.environ,self.env(),clear=True):
                report,inputs,expected,state,obj,files=scope=self.preparation_scope(Path(directory))
                if kind in ('sourceSha','runId','attempt'):report[kind]='PRIVATE_VALUE'
                if kind=='source':(Path(directory)/'chengyinhub-admin/src/test/java'/B.PHOTO_SOURCE).write_text('changed')
                compiled=Path(directory)/'chengyinhub-admin/target/test-classes'/(B.PHOTO_UNIT.replace('.','/')+'.class')
                if kind=='class':compiled.write_bytes(b'changed')
                if kind=='missing-class':compiled.unlink()
                if kind=='snapshot':expected['photoServiceTestClassSha256']='0'*64
                if kind=='owner':obj['Config']['Labels']['chengyin.http.source']='PRIVATE_VALUE'
                if kind=='budget':obj['HostConfig']['Memory']=B.MEMORY*2
                if kind=='command':obj['Config']['Cmd'].append('PRIVATE_VALUE')
                if kind=='phase':report['phase']='OFFLINE_PROVIDER_RUNTIME'
                if kind=='unobserved':report['mavenExitObserved']=False
                if kind=='zero':report['mavenExit']=0
                if kind=='env':obj['Config']['Env'].append('SOURCE_SHA=PRIVATE_VALUE')
                proof=self.preparation_scan(scope);self.assertEqual(proof['state'],'UNKNOWN')
                self.assertNotIn('suites',proof);self.assertNotIn('PRIVATE_VALUE',json.dumps(proof))

    def test_skips_retries_and_all_pass_units_never_fabricate_a_failure_or_business_acceptance(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)),patch.dict(os.environ,self.env(),clear=True):
            scope=self.preparation_scope(Path(directory));root=Path(directory)
            self.preparation_xml(root,B.CONFIGURATION_UNIT,B.CONFIGURATION_METHODS)
            self.preparation_xml(root,B.PHOTO_UNIT,B.PHOTO_METHODS,{B.PHOTO_METHODS[0]:[('skipped','PRIVATE_TYPE','PRIVATE_SKIP')],
                B.PHOTO_METHODS[1]:[('flakyFailure','java.lang.NullPointerException','PRIVATE_RETRY')]})
            proof=self.preparation_scan(scope);photo=next(row for row in proof['suites'] if row['testClass']==B.PHOTO_UNIT)
            self.assertEqual(photo['counts'],{'tests':23,'failures':0,'errors':0,'skipped':1,'retries':1})
            self.assertEqual({row['kind'] for row in photo['nonPassing']},{'SKIPPED','RETRY'})
            self.assertTrue(all(row['kind'] not in ('FAILURE','ERROR') for row in photo['nonPassing']))
            self.assertEqual(proof['mavenExit'],1);self.assertIs(proof['businessAccepted'],False)
            self.assertNotIn('PRIVATE_',json.dumps(proof));self.assertEqual(scope[0]['result'],'NOT_ACCEPTED')

    def test_exception_types_and_reported_frames_are_exact_whitelists_not_message_projection(self):
        for kind in ('type','other-method','line-range','file'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.object(B,'ROOT',Path(directory)),patch.dict(os.environ,self.env(),clear=True):
                scope=self.preparation_scope(Path(directory));path=scope[-1][B.CONFIGURATION_UNIT];raw=path.read_text()
                if kind=='type':raw=raw.replace('type="org.opentest4j.AssertionFailedError"','type="PRIVATE_TYPE"')
                if kind=='other-method':raw=raw.replace('.'+B.CONFIGURATION_METHODS[0]+'(','.'+B.CONFIGURATION_METHODS[1]+'(')
                if kind=='line-range':raw=raw.replace('.java:12)','.java:99999)')
                if kind=='file':raw=raw.replace('CombinedContextConfigurationTest.java:12','PRIVATE_FILE.java:12')
                path.write_text(raw);proof=self.preparation_scan(scope)
                row=next(row for row in proof['suites'] if row['testClass']==B.CONFIGURATION_UNIT)['nonPassing'][0]
                if kind=='type':self.assertEqual(row['type'],'UNKNOWN')
                else:self.assertNotIn('origin',row)
                self.assertNotIn('PRIVATE_',json.dumps(proof))

    def test_failed_preparation_finally_collects_before_cleanup_even_when_raw_log_capture_fails(self):
        scan=B.preparation_test_evidence;cleanup=B.cleanup_slot;events=[]
        def observe(*args):
            events.append('SCAN');self.assertIs(args[0]['mavenExitObserved'],True);self.assertEqual(args[0]['mavenExit'],1)
            return scan(*args) # The transport fixture has no real compiler/class proof, so this remains UNKNOWN.
        def remove(*args):events.append('CLEANUP');return cleanup(*args)
        with patch.object(B,'preparation_test_evidence',side_effect=observe),patch.object(B,'cleanup_slot',side_effect=remove):
            reports,mutations,failure,private=self.warm_transport_control(exit_code=1,preparation_phase='ONLINE_PROVIDER_RUNTIME',log_error=True)
        report=reports[-1];self.assertEqual(events,['SCAN','CLEANUP']);self.assertEqual(mutations,['stop','remove'])
        self.assertEqual(report['firstFailure']['reasonCode'],'OFFLINE_CACHE_PREFLIGHT_FAILED')
        self.assertEqual(report['originalExit'],1);self.assertEqual(report['mavenExit'],1);self.assertEqual(report['result'],'NOT_ACCEPTED')
        self.assertEqual(report['preparationTests']['state'],'UNKNOWN');self.assertIsInstance(failure,ValueError)
        self.assertNotIn('PRIVATE_LOG_VALUE',json.dumps(report));self.assertTrue(private)


    def test_fixed_c1_policy_reaches_both_real_probe_forks_and_the_original_business_consumer(self):
        # Command/source control only. This does not execute a JVM or establish the OOM cause.
        shell=B.fixed_cache_command()[1];actual=B.fixed_maven_command();child=B.child_environment(self.env())
        self.assertEqual(B.RUNTIME_MAVEN_OPTIONS.count('-XX:TieredStopAtLevel=1'),1)
        self.assertEqual(B.SUREFIRE_MEMORY_ARGUMENTS.count('-XX:TieredStopAtLevel=1'),1)
        self.assertEqual(shell.count("MAVEN_OPTS='"+B.RUNTIME_MAVEN_OPTIONS+"'"),3)
        self.assertEqual(shell.count(B.SUREFIRE_FORK_ARGUMENTS),3)
        self.assertEqual(child['MAVEN_OPTS'],B.RUNTIME_MAVEN_OPTIONS)
        self.assertIn('-DargLine='+B.SUREFIRE_FORK_ARGUMENTS,actual)
        self.assertIn('test',actual);self.assertIn('-am',actual);self.assertIn('-Dtest='+B.CLASS,actual)
        self.assertNotIn('-DskipTests',actual);self.assertNotIn('-Dmaven.test.skip=true',actual)
        self.assertEqual(shell.count('-Dtest='+B.PROVIDER_PROBE),2)
        self.assertIn("'-Dtest="+B.PROVIDER_PROBE+"'",shell)
        self.assertIn("'-Dtest="+B.CONFIGURATION_UNIT+','+B.PHOTO_UNIT+"'",shell)
        self.assertEqual(len(B.CONFIGURATION_METHODS),11);self.assertEqual(len(B.PHOTO_METHODS),23)
        self.assertEqual(len(B.TEST_SOURCES),21);self.assertEqual(len(B.REQUIRED_TEST_CLASSES)+3,40)
        self.assertEqual(B.MEMORY,1073741824);self.assertEqual(B.LIMIT_SECONDS,1200)
        for role,options,heap,direct,code in (('MAVEN',B.RUNTIME_MAVEN_OPTIONS,128,16,32),
                ('SUREFIRE',B.SUREFIRE_MEMORY_ARGUMENTS,384,64,64)):
            proof=B.jvm_memory_binding(options.split(),role)
            self.assertEqual(proof['tieredStopAtLevel'],1);self.assertEqual(proof['compilationMode'],'C1_SIMPLE')
            self.assertEqual(proof['maxHeapBytes'],heap*1024**2)
            self.assertEqual(proof['maxDirectMemoryBytes'],direct*1024**2)
            self.assertEqual(proof['reservedCodeCacheBytes'],code*1024**2);self.assertEqual(proof['collector'],'SerialGC')

    def test_c1_policy_rejects_missing_duplicate_disabled_or_overridden_compilation_flags(self):
        overrides=('-XX:TieredStopAtLevel=4','-XX:TieredStopAtLevel=1','-XX:-TieredCompilation',
                   '-XX:+TieredCompilation','-XX:CompilationMode=high-only','-Xint','-Xcomp',
                   '-XX:-UseCompiler','-XX:+UseCompiler','-XX:+ProfileInterpreter','-XX:-ProfileInterpreter',
                   '-XX:+UseJVMCICompiler','-XX:-UseJVMCICompiler')
        for role,options in (('MAVEN',B.RUNTIME_MAVEN_OPTIONS),('SUREFIRE',B.SUREFIRE_MEMORY_ARGUMENTS)):
            missing=[value for value in options.split() if value!='-XX:TieredStopAtLevel=1']
            with self.subTest(role=role,kind='missing'),self.assertRaisesRegex(ValueError,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED'):
                B.jvm_memory_binding(missing,role)
            for override in overrides:
                with self.subTest(role=role,override=override),self.assertRaisesRegex(ValueError,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED'):
                    B.jvm_memory_binding(options.split()+[override],role)
        scheduler=B.SCHEDULER_MEMORY_ARGUMENTS.split()+['-cp','unchanged',B.SCHEDULER_PROBE]
        proof=B.jvm_memory_binding(scheduler,'DESCENDANT_JVM')
        self.assertEqual(B.SCHEDULER_MEMORY_ARGUMENTS,'-Xmx64m')
        self.assertIsNone(proof['tieredStopAtLevel']);self.assertEqual(proof['compilationMode'],'DEFAULT_UNATTESTED')
        with self.assertRaisesRegex(ValueError,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED'):
            B.jvm_memory_binding(scheduler+['-XX:TieredStopAtLevel=1'],'DESCENDANT_JVM')

    def test_subject_requires_current_observed_c1_policy_and_oom_still_rejects_all_other_good_evidence(self):
        report=self.subject_report();expected=B.identity(self.env())
        B.check_subject(report,expected,41,'f'*64)
        for role in ('MAVEN','SUREFIRE'):
            for field,value in (('tieredStopAtLevel',None),('tieredStopAtLevel',4),('compilationMode','DEFAULT_UNATTESTED')):
                changed=copy.deepcopy(report);process=next(row for row in changed['javaProcesses'] if row['role']==role)
                process['jvmMemory'][field]=value
                with self.subTest(role=role,field=field,value=value),self.assertRaisesRegex(ValueError,'FIXED_JVM_MEMORY_ARGUMENTS_REQUIRED'):
                    B.check_subject(changed,expected,41,'f'*64)
        state,cleaned,port=self.outcome();state['cgroupMemoryEvents']['oom_kill']='1'
        self.assertFalse(B.accepted_outcome(state,0,[],cleaned,port))


    def content_fixture(self,root):
        # Synthetic class bytes/property protocol only; never compilation or business proof.
        source=root/B.CONTENT_SECURITY_SOURCE;source.parent.mkdir(parents=True,exist_ok=True)
        source.write_bytes((Path(__file__).resolve().parents[1]/B.CONTENT_SECURITY_SOURCE).read_bytes())
        directory=root/'chengyinhub-admin/target/controlled-content-security'
        compiled=directory/'classes'/B.CONTENT_SECURITY_CLASS;compiled.parent.mkdir(parents=True);compiled.write_bytes(b'\xca\xfe\xba\xbeSYNTHETIC_CLASS')
        proof=directory/'proof.properties'
        proof.write_text('sourceSha='+self.env()['SOURCE_SHA']+'\nsourceSha256='+B.digest(source)+'\nclassSha256='+B.digest(compiled)+'\n');proof.chmod(0o600)
        return directory,source,compiled,proof

    def test_exact_provider_classpath_is_complete_phase_bound_and_never_reconstructed_or_published(self):
        with tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
            directory,cache=self.provider_scope(Path(name));proof=B.provider_runtime_proof(cache)
            for phase in ('online','offline'):
                raw=(directory/(phase+'.classpath')).read_bytes();row=proof[phase]['testClasspath']
                self.assertEqual(set(row),{'fileName','sha256','byteLength','entryCount'})
                self.assertEqual(row['sha256'],B.hashlib.sha256(raw).hexdigest())
                self.assertEqual(row['byteLength'],len(raw));self.assertEqual(row['entryCount'],len(raw.decode()[:-1].split(':')))
                record=json.loads((directory/(phase+'.json')).read_text())
                actual,verified_raw,execution=B.provider_classpath_proof(cache,phase.upper(),record)
                self.assertEqual(actual,row);self.assertEqual(verified_raw,raw)
                self.assertTrue(raw.endswith(b':'));self.assertFalse(raw.endswith(b'::'))
                self.assertEqual(execution.encode(),raw[:-1]);self.assertTrue(all(execution.split(':')))
                self.assertNotIn(raw.decode(),json.dumps(proof));self.assertNotIn(name,json.dumps(proof))
            self.assertEqual({key:value for key,value in proof['online']['testClasspath'].items() if key!='fileName'},
                             {key:value for key,value in proof['offline']['testClasspath'].items() if key!='fileName'})
            # Preserve original order and repeated real entries; only the formatter's final ':' is removed.
            original=(directory/'online.classpath').read_bytes();entries=original[:-1].decode().split(':')
            repeated=(':'.join([entries[-1],*entries,entries[0]])+':').encode()
            for phase in ('online','offline'):
                (directory/(phase+'.classpath')).write_bytes(repeated)
                record_path=directory/(phase+'.json');record=json.loads(record_path.read_text())
                record['testClasspath'].update(sha256=B.hashlib.sha256(repeated).hexdigest(),byteLength=len(repeated),entryCount=len(entries)+2)
                record_path.write_text(json.dumps(record))
                _,verified_raw,execution=B.provider_classpath_proof(cache,phase.upper(),record)
                self.assertEqual(verified_raw,repeated);self.assertEqual(execution.split(':'),[entries[-1],*entries,entries[0]])
            repeated_proof=B.provider_runtime_proof(cache)
            self.assertEqual(repeated_proof['online']['testClasspath']['entryCount'],len(entries)+2)
            self.assertNotIn(name,json.dumps(repeated_proof))

    def test_provider_classpath_missing_drift_empty_remote_foreign_alias_and_symlink_all_reject(self):
        for kind in ('missing','symlink','mode','hash','empty','missing-terminator','only-terminator','leading-empty','internal-empty',
                     'null','linefeed','carriage-return','invalid-utf8','null-proof','entry-count-includes-terminator',
                     'remote','foreign','alias','missing-main','offline-drift'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
                root=Path(name);directory,cache=self.provider_scope(root);path=directory/'online.classpath'
                if kind=='missing':path.unlink()
                elif kind=='symlink':
                    raw=path.read_bytes();path.unlink();other=root/'private';other.write_bytes(raw);path.symlink_to(other)
                elif kind=='mode':path.chmod(0o644)
                else:
                    raw=path.read_text()
                    if kind=='hash':path.write_text(raw+':PRIVATE_VALUE')
                    else:
                        if kind=='empty':raw+=':'
                        if kind=='missing-terminator':raw=raw[:-1]
                        if kind=='only-terminator':raw=':'
                        if kind=='leading-empty':raw=':'+raw
                        if kind=='internal-empty':raw=raw.replace(':','::',1)
                        if kind=='null':raw=raw[:-1]+'\0:'
                        if kind=='linefeed':raw+='\n'
                        if kind=='carriage-return':raw+='\r'
                        if kind=='remote':raw='https://PRIVATE_VALUE:'+raw
                        if kind=='foreign':raw='/PRIVATE_VALUE:'+raw
                        if kind=='alias':raw=raw.replace('/target/classes','/target/../target/classes')
                        if kind=='missing-main':raw=':'.join(value for value in raw[:-1].split(':') if value!=str(root/'chengyinhub-admin/target/classes'))+':'
                        if kind=='offline-drift':path=directory/'offline.classpath';raw=raw[:-1]+':'+raw.split(':')[0]+':'
                        path.write_bytes(raw.encode()+(b'\xff' if kind=='invalid-utf8' else b''));record_path=path.with_suffix('.json');record=json.loads(record_path.read_text())
                        record['testClasspath']={'fileName':path.name,'sha256':B.digest(path),'byteLength':path.stat().st_size,'entryCount':sum(bool(value) for value in raw.split(':'))}
                        if kind=='null-proof':record['testClasspath']=None
                        if kind=='entry-count-includes-terminator':record['testClasspath']['entryCount']+=1
                        record_path.write_text(json.dumps(record))
                with self.assertRaises(ValueError):B.provider_runtime_proof(cache)

    def test_controlled_content_proof_rejects_stale_or_foreign_source_class_extra_output_and_symlink(self):
        for kind in ('valid','missing-proof','old-source','source','class','extra','generated','symlink','duplicate-key'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
                directory,source,compiled,proof=self.content_fixture(Path(name))
                if kind=='valid':
                    result=B.controlled_content_proof();self.assertEqual(result['sourceSha'],self.env()['SOURCE_SHA'])
                    self.assertEqual(result['classSha256'],B.digest(compiled));self.assertNotIn(name,json.dumps(result));continue
                if kind=='missing-proof':proof.unlink()
                if kind=='old-source':proof.write_text(proof.read_text().replace(self.env()['SOURCE_SHA'],'0'*40))
                if kind=='source':source.write_text('changed original')
                if kind=='class':compiled.write_bytes(b'\xca\xfe\xba\xbeCHANGED')
                if kind=='extra':(directory/'classes/Extra.class').write_bytes(b'EXTRA')
                if kind=='generated':(directory/'Generated.java').write_text('unexpected generated source')
                if kind=='symlink':
                    raw=compiled.read_bytes();compiled.unlink();other=Path(name)/'private';other.write_bytes(raw);compiled.symlink_to(other)
                if kind=='duplicate-key':proof.write_text(proof.read_text()+'sourceSha=PRIVATE_VALUE\n')
                with self.assertRaisesRegex(ValueError,'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED'):B.controlled_content_proof()

    def test_preparation_compiler_uses_exact_online_classpath_and_separate_observed_exit(self):
        for compiler_exit in (0,1):
            with self.subTest(exit=compiler_exit),tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
                root=Path(name);directory,cache=self.provider_scope(root);source=root/B.CONTENT_SECURITY_SOURCE
                source.parent.mkdir(parents=True)
                source.write_bytes((Path(__file__).resolve().parents[1]/B.CONTENT_SECURITY_SOURCE).read_bytes());calls=[]
                def transport(argv,**kwargs):
                    calls.append(argv)
                    if '-c' in argv and 'javac -J-Xmx128m -proc:none' in argv[argv.index('-c')+1]:
                        raw=(directory/'online.classpath').read_bytes()
                        self.assertEqual(argv[-3:],[str(source),raw[:-1].decode(),str(root/'chengyinhub-admin/target/controlled-content-security/classes')])
                        self.assertIn('-classpath "$2"',argv[argv.index('-c')+1])
                        self.assertNotIn('$(cat',argv[argv.index('-c')+1]);self.assertTrue(all(argv[-2].split(':')))
                        if compiler_exit==0:
                            compiled=Path(argv[-1])/B.CONTENT_SECURITY_CLASS;compiled.parent.mkdir(parents=True,exist_ok=True);compiled.write_bytes(b'\xca\xfe\xba\xbeSYNTHETIC')
                    if argv[-1]=='/tmp/cy-cache.javac.exit':return str(compiler_exit).encode()
                    return b''
                with patch.object(B,'command',side_effect=transport),patch.object(B.time,'monotonic',return_value=0):
                    result=B.compile_controlled_content('1'*64,cache,1200)
                self.assertEqual(result['exit'],compiler_exit);self.assertIs(result['exitObserved'],True)
                self.assertEqual(result['testClasspath']['sha256'],B.digest(directory/'online.classpath'))
                self.assertEqual(result['testClasspath']['byteLength'],len((directory/'online.classpath').read_bytes()))
                self.assertEqual(calls[-1][-1],str(compiler_exit));self.assertEqual(result['purpose'],'PREPARATION_COMPILER_ONLY')
                if compiler_exit==0:self.assertEqual(result['proof'],B.controlled_content_proof())
                else:
                    self.assertNotIn('proof',result)
                    self.assertFalse((root/'chengyinhub-admin/target/controlled-content-security/proof.properties').exists())

    def test_javac_failure_preserves_online_maven_zero_and_cannot_sign_preflight_or_skip_cleanup(self):
        reports,mutations,failure,private=self.warm_transport_control(compiler_exit=1)
        report=reports[-1];self.assertEqual(report['originalExit'],1)
        self.assertIs(report['mavenExitObserved'],True);self.assertEqual(report['mavenExit'],0)
        self.assertEqual(report['controlledContentSecurityCompiler']['exit'],1)
        self.assertEqual(report['firstFailure']['reasonCode'],'CONTROLLED_CONTENT_SECURITY_COMPILE_FAILED')
        self.assertEqual(report['result'],'NOT_ACCEPTED');self.assertEqual(mutations,['stop','remove'])
        self.assertTrue(private);self.assertIsInstance(failure,ValueError)

    def test_precompiled_binding_is_required_by_cache_and_final_subject_without_weakening_other_proofs(self):
        for kind in ('missing','class','source','compiler','classpath'):
            report=self.cache_receipt()
            if kind=='missing':report.pop('controlledContentSecurity')
            if kind=='class':report['controlledContentSecurity']['classSha256']='0'*64
            if kind=='source':report['controlledContentSecurity']['sourceSha']='0'*40
            if kind=='compiler':report['controlledContentSecurityCompiler']['exit']=1
            if kind=='classpath':report['controlledContentSecurityCompiler']['testClasspath']['sha256']='0'*64
            with self.subTest(kind=kind),self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PREFLIGHT_REQUIRED'):self.check_cache_receipt(report)
        report=self.subject_report();report['source']['controlledContentSecurity']['classSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED'):
            B.check_subject(report,B.identity(self.env()),41,'f'*64)

    def test_content_consumer_mount_is_readonly_and_compile_boundary_keeps_original_stage_roster(self):
        shell=B.fixed_cache_command()[1]
        self.assertLess(shell.index('ONLINE_PROVIDER_RUNTIME'),shell.index('CONTENT_SECURITY_COMPILE'))
        self.assertLess(shell.index('CONTENT_SECURITY_COMPILE'),shell.index('OFFLINE_PROVIDER_RUNTIME'))
        self.assertEqual(shell.count('-Dtest='+B.PROVIDER_PROBE),2);self.assertEqual(shell.count(B.SUREFIRE_FORK_ARGUMENTS),3)
        self.assertNotIn('javac',B.fixed_maven_command()[1]);self.assertIn('test',B.fixed_maven_command())
        path=Path('/tmp/cy-http-budget.abcdefgh');state={'cache':'/existing/repository','jdkEnvironment':{}}
        content=str(B.ROOT/'chengyinhub-admin/target/controlled-content-security')
        rows=[(str(B.ROOT),str(B.ROOT),False),('/existing/repository','/cache/repository',False),(str(path/'launch'),'/budget',False),
              (content,content,False)]+[(str(B.ROOT/module/'target'),str(B.ROOT/module/'target'),True) for module in B.MODULES]
        obj={'HostConfig':{'ReadonlyRootfs':True},'Config':{'User':str(os.getuid())+':'+str(os.getgid()),
             'Entrypoint':['/bin/bash'],'Cmd':B.fixed_maven_command(),'Env':[key+'='+value for key,value in B.child_environment(self.env()).items()]},
             'Mounts':[{'Source':source,'Destination':target,'RW':rw,'Type':'bind'} for source,target,rw in rows]}
        with patch.dict(os.environ,self.env(),clear=True):
            B.validate_app_inputs(obj,path,state)
            for missing in (False,True):
                changed=copy.deepcopy(obj)
                if missing:changed['Mounts']=[row for row in changed['Mounts'] if row['Destination']!=content]
                else:next(row for row in changed['Mounts'] if row['Destination']==content)['RW']=True
                with self.assertRaisesRegex(ValueError,'PRIVATE_MOUNT_BOUNDARY_CHANGED'):B.validate_app_inputs(changed,path,state)


    def test_original_source_manifest_keeps_full_reactor_trees_and_also_detects_controlled_class_drift(self):
        # Synthetic source/JAR/class protocol inputs only, not javac or application evidence.
        with tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
            root=Path(name);self.preparation_scope(root);directory,source,compiled,proof=self.content_fixture(root)
            for module in B.MODULES:
                target=root/module/'target';classes=target/'classes';classes.mkdir(parents=True,exist_ok=True)
                (classes/'Original.class').write_bytes(b'SYNTHETIC_ORIGINAL_MAIN_CLASS')
                jar=target/('chengyinhub-admin.jar' if module=='chengyinhub-admin' else module+'.jar')
                with B.zipfile.ZipFile(jar,'w') as archive:
                    archive.writestr('BOOT-INF/classes/git.properties','git.commit.id='+self.env()['SOURCE_SHA']+'\n')
            props=root/'chengyinhub-admin/target/classes/git.properties';props.write_text('git.commit.id='+self.env()['SOURCE_SHA']+'\n')
            out=root/'chengyinhub-admin/target/http-e2e';out.mkdir();(out/'fresh-schema.json').write_text('synthetic scope')
            with patch.object(B,'OUT',out),patch.object(B,'command',return_value=self.env()['SOURCE_SHA'].encode()),\
                 patch.object(B.subprocess,'run',return_value=SimpleNamespace(returncode=0)):
                before=B.source_manifest();self.assertEqual(before,B.source_manifest())
                compiled.write_bytes(b'\xca\xfe\xba\xbeCHANGED')
                with self.assertRaisesRegex(ValueError,'CONTROLLED_CONTENT_SECURITY_PROOF_REQUIRED'):B.source_manifest()
                proof.write_text('sourceSha='+self.env()['SOURCE_SHA']+'\nsourceSha256='+B.digest(source)+'\nclassSha256='+B.digest(compiled)+'\n')
                changed=B.source_manifest()
                self.assertEqual(before['classTrees'],changed['classTrees']);self.assertEqual(before['reactorJars'],changed['reactorJars'])
                self.assertNotEqual(before['controlledContentSecurity'],changed['controlledContentSecurity']);self.assertNotEqual(before,changed)


    def compiler_diagnostic_scope(self,root):
        # Source/log/owner protocol only. Deliberately no class files or compiled postimage.
        self.compilation_sources(root)
        for relative in B.TEST_SOURCES:
            (root/'chengyinhub-admin/src/test/java'/relative).write_text(('\t// PRIVATE_SOURCE_SQL_URL '+('x'*80)+'\n')*20)
        inputs=B.fixed_test_compilation_inputs();obj=self.obj();obj['HostConfig']['NetworkMode']='bridge'
        obj['State'].update(Running=True,Pid=17)
        obj['Config'].update(Cmd=B.fixed_cache_command(),Entrypoint=['/bin/bash'],User=str(os.getuid())+':'+str(os.getgid()),
            Env=[key+'='+value for key,value in {'SOURCE_SHA':self.env()['SOURCE_SHA'],'GITHUB_SHA':self.env()['SOURCE_SHA'],
                'GITHUB_RUN_ID':self.env()['GITHUB_RUN_ID'],'GITHUB_RUN_ATTEMPT':self.env()['GITHUB_RUN_ATTEMPT']}.items()])
        state={'nonce':'nonce','slots':{'app':{'cid':obj['Id'],'name':'own-app'}}}
        report={**B.identity(self.env()),'phase':'ONLINE_PACKAGE','mavenExitObserved':True,'mavenExit':1,'originalExit':1,
            'image':B.JDK,'imageId':obj['Image'],'mavenVersion':B.CORE_VERSION,'mavenCoreSha256':'c'*64,
            'settingsSha256':B.hashlib.sha256(B.EMPTY_SETTINGS.encode()).hexdigest(),'pomSha256':{'pom.xml':'f'*64},
            'budget':B.validate_container(obj,'own-app',B.JDK,B.labels(self.env(),'nonce'),B.MEMORY,'bridge',obj['Id'])}
        return report,inputs,state,obj

    def compiler_diagnostic_log(self,root,diagnostics=None):
        if diagnostics is None:diagnostics=['cannot find symbol']
        source=root/'chengyinhub-admin/src/test/java'/B.TEST_SOURCES[0]
        rows=['[ERROR] '+str(source)+':['+str(index+1)+',9] '+message+'\n  symbol: PRIVATE_SYMBOL\n  location: PRIVATE_LOCATION'
              for index,message in enumerate(diagnostics)]
        return ('[INFO] --- compiler:3.15.0:testCompile (default-testCompile) @ chengyinhub-admin ---\n'
                '[ERROR] COMPILATION ERROR : \n[INFO] -------------------------------------------------------------\n'+
                '\n'.join(rows)+'\n[INFO] '+str(len(rows))+' errors \n[ERROR] PRIVATE_FINAL_MESSAGE URL SQL token=PRIVATE_TOKEN\n')

    def compiler_diagnostic_scan(self,scope,text):
        report,inputs,state,obj=scope
        with patch.object(B,'cache_inputs',return_value={'pom.xml':'f'*64}):
            return B.compile_failure_evidence(report,inputs,state,obj,report['phase'],text)

    def test_compiler_projection_binds_current_source_without_binary_proof_and_only_exports_fixed_locations_and_categories(self):
        with tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
            root=Path(name);scope=self.compiler_diagnostic_scope(root);before=copy.deepcopy(scope[0])
            messages=['cannot find symbol','incompatible types: PRIVATE_A cannot be converted to PRIVATE_B',
                'variable PRIVATE_A is already defined in method PRIVATE_B()',
                'unreported exception PRIVATE_A; must be caught or declared to be thrown',"';' expected"]
            text=self.compiler_diagnostic_log(root,messages);result=self.compiler_diagnostic_scan(scope,text)
            self.assertEqual(result['state'],'OBSERVED');self.assertIs(result['businessAccepted'],False)
            self.assertEqual(result['purpose'],'PREPARATION_COMPILER_DIAGNOSTIC_ONLY')
            self.assertEqual([row['category'] for row in result['errors']],['CANNOT_FIND_SYMBOL','INCOMPATIBLE_TYPES',
                'DUPLICATE_DEFINITION','UNDECLARED_CHECKED_EXCEPTION','SYNTAX_ERROR'])
            self.assertEqual([row['line'] for row in result['errors']],[1,2,3,4,5])
            for row in result['errors']:
                self.assertEqual(set(row),{'source','sourceSha256','line','column','category'})
                self.assertEqual(row['source'],B.TEST_SOURCES[0]);self.assertEqual(row['column'],9)
                self.assertEqual(row['sourceSha256'],scope[1]['sources'][B.TEST_SOURCES[0]])
            self.assertEqual(scope[0],before);self.assertEqual(result['mavenExit'],1)
            self.assertFalse((root/'chengyinhub-admin/target/test-classes').exists())
            for private in ('PRIVATE_',name,'cannot find symbol','symbol:','location:','URL','SQL'):
                self.assertNotIn(private,json.dumps(result))
            # A legitimate source edit/import moves both hash and valid positions. No old line is pinned.
            source=root/'chengyinhub-admin/src/test/java'/B.TEST_SOURCES[0];old_hash=result['errors'][0]['sourceSha256']
            source.write_text('// current import row\n'+source.read_text());scope[1].update(B.fixed_test_compilation_inputs())
            moved=self.compiler_diagnostic_scan(scope,self.compiler_diagnostic_log(root).replace(':[1,9]',':[2,9]'))
            self.assertEqual(moved['state'],'OBSERVED');self.assertEqual(moved['errors'][0]['line'],2)
            self.assertNotEqual(moved['errors'][0]['sourceSha256'],old_hash)

    def test_compiler_projection_rejects_wrong_owner_stage_exit_source_and_malformed_mixed_or_expanding_log_sections(self):
        kinds=('sourceSha','runId','attempt','phase','zero','unobserved','original-exit','core','owner','budget','command','env',
               'source-drift','symlink','profile','oversize','missing-marker','missing-count','wrong-goal','later-goal','foreign','traversal',
               'mixed','line','column','unknown-category','duplicate','inflation','arbitrary-hint','bad-location')
        for kind in kinds:
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as name,patch.object(B,'ROOT',Path(name)),patch.dict(os.environ,self.env(),clear=True):
                root=Path(name);report,inputs,state,obj=scope=self.compiler_diagnostic_scope(root)
                text=self.compiler_diagnostic_log(root);source=root/'chengyinhub-admin/src/test/java'/B.TEST_SOURCES[0]
                if kind in ('sourceSha','runId','attempt'):report[kind]='PRIVATE_ID'
                if kind=='phase':report['phase']='CONTENT_SECURITY_COMPILE'
                if kind=='zero':report['mavenExit']=report['originalExit']=0
                if kind=='unobserved':report['mavenExitObserved']=False
                if kind=='original-exit':report['originalExit']=2
                if kind=='core':report['mavenCoreSha256']='PRIVATE_CORE'
                if kind=='owner':obj['Config']['Labels']['chengyin.http.source']='0'*40
                if kind=='budget':obj['HostConfig']['Memory']=B.MEMORY*2
                if kind=='command':obj['Config']['Cmd'].append('PRIVATE_COMMAND')
                if kind=='env':obj['Config']['Env'].append('SOURCE_SHA=PRIVATE_ENV')
                if kind=='source-drift':source.write_text('changed')
                if kind=='symlink':
                    source.unlink();private=root/'PRIVATE_FILE';private.write_text('private');source.symlink_to(private)
                if kind=='profile':inputs['profile']='PRIVATE_PROFILE'
                if kind=='oversize':text='x'*(1024*1024+1)+text
                if kind=='missing-marker':text=text.replace('[ERROR] COMPILATION ERROR : ','PRIVATE_HEADER')
                if kind=='missing-count':text=text.replace('[INFO] 1 errors ','PRIVATE_COUNT')
                if kind=='wrong-goal':text=text.replace('compiler:3.15.0:testCompile','compiler:3.14.0:testCompile')
                if kind=='later-goal':text+='[INFO] --- PRIVATE_PLUGIN:1.0.0:run (PRIVATE_EXECUTION) @ chengyinhub-admin ---\n'
                if kind=='foreign':text=text.replace(str(source),'/PRIVATE_ROOT/Foreign.java')
                if kind=='traversal':text=text.replace(str(source),str(source.parent)+'/../'+source.parent.name+'/'+source.name)
                if kind=='mixed':text=text.replace('[INFO] 1 errors ',
                    '[ERROR] /PRIVATE_ROOT/Foreign.java:[1,9] cannot find symbol\n[INFO] 2 errors ')
                if kind=='line':text=text.replace(':[1,9]',':[999999,9]')
                if kind=='column':text=text.replace(':[1,9]',':[1,999999]')
                if kind=='unknown-category':text=text.replace('cannot find symbol','PRIVATE_UNKNOWN_REASON')
                if kind=='duplicate':text=text.replace('[INFO] 1 errors ',
                    '[ERROR] '+str(source)+':[1,9] cannot find symbol\n[INFO] 2 errors ')
                if kind=='inflation':text=self.compiler_diagnostic_log(root,['cannot find symbol']*9)
                if kind=='arbitrary-hint':text=text.replace('[ERROR] '+str(source),'[INFO] quoted hint [ERROR] '+str(source))
                if kind=='bad-location':text=text.replace(':[1,9]',':[PRIVATE_LINE,9]')
                result=self.compiler_diagnostic_scan(scope,text)
                self.assertEqual(result['state'],'UNKNOWN');self.assertIs(result['businessAccepted'],False)
                self.assertNotIn('errors',result);self.assertNotIn('PRIVATE_',json.dumps(result));self.assertNotIn(name,json.dumps(result))

    def test_failed_compilation_finally_projects_before_cleanup_without_replacing_first_failure_or_junit_unknown(self):
        original=B.compile_failure_evidence;cleanup=B.cleanup_slot;events=[]
        text='[INFO] --- compiler:3.15.0:testCompile (default-testCompile) @ chengyinhub-admin ---\nPRIVATE_LOG SQL URL'
        def collect(*args):
            events.append('COMPILER_DIAGNOSTIC');self.assertEqual(args[-1],text)
            self.assertIs(args[0]['mavenExitObserved'],True);self.assertEqual(args[0]['mavenExit'],1)
            return original(*args) # Transport fixture has no complete source table; never manufacture proof.
        def remove(*args):events.append('CLEANUP');return cleanup(*args)
        with patch.object(B,'compile_failure_evidence',side_effect=collect),patch.object(B,'cleanup_slot',side_effect=remove):
            reports,mutations,failure,private=self.warm_transport_control(exit_code=1,preparation_phase='ONLINE_PACKAGE',compiler_log=text)
        report=reports[-1];self.assertEqual(events,['COMPILER_DIAGNOSTIC','CLEANUP']);self.assertEqual(mutations,['stop','remove'])
        self.assertEqual(report['firstFailure']['reasonCode'],'OFFLINE_CACHE_PREFLIGHT_FAILED')
        self.assertEqual(report['originalExit'],1);self.assertEqual(report['mavenExit'],1);self.assertEqual(report['result'],'NOT_ACCEPTED')
        self.assertEqual(report['compileFailure']['state'],'UNKNOWN');self.assertEqual(report['preparationTests']['state'],'UNKNOWN')
        self.assertEqual(report['failures'],[]);self.assertTrue(private);self.assertIsInstance(failure,ValueError)
        self.assertNotIn('PRIVATE_LOG',json.dumps(report));self.assertNotIn('PRIVATE_LOG',json.dumps(report['compileFailure']))


    def test_verified_execution_reuses_compiler_outputs_without_skipping_surefire_or_preparation(self):
        actual=B.fixed_maven_command();warm=B.fixed_cache_command()[1]
        flag='-Dcombined.execution.precompiled=true'
        self.assertEqual(actual.count(flag),1)
        self.assertNotIn('combined.execution.precompiled',warm)
        self.assertIn('-P'+B.TEST_PROFILE,actual);self.assertIn('-am',actual);self.assertIn('test',actual)
        self.assertIn('-Dtest='+B.CLASS,actual);self.assertIn('-DforkCount=1',actual)
        for forbidden in ('-DskipTests','-DskipTests=true','-Dmaven.test.skip=true','-Dmaven.main.skip=true',
                          'surefire:test','org.apache.maven.plugins:maven-surefire-plugin:3.5.6:test'):
            self.assertNotIn(forbidden,actual)
        self.assertIn("'package' '-DskipTests'",warm)
        self.assertIn('ONLINE_PROVIDER_RUNTIME',warm);self.assertIn('OFFLINE_PROVIDER_RUNTIME',warm)
        self.assertIn('ONLINE_CONFIGURATION_UNITS',warm);self.assertIn('OFFLINE_TEST_PREFLIGHT',warm)

    def test_reuse_property_is_compiler_execution_local_with_original_lifecycle_and_admin_inputs(self):
        import xml.etree.ElementTree as ET
        ns={'m':'http://maven.apache.org/POM/4.0.0'}
        root=ET.parse(B.ROOT/'pom.xml').getroot()
        profiles=[p for p in root.findall('m:profiles/m:profile',ns) if p.findtext('m:id',namespaces=ns)==B.TEST_PROFILE]
        self.assertEqual(len(profiles),1);profile=profiles[0]
        self.assertEqual(profile.findtext('m:properties/m:combined.execution.precompiled',namespaces=ns),'false')
        plugins=profile.findall('m:build/m:plugins/m:plugin',ns);self.assertEqual(len(plugins),1)
        self.assertEqual(plugins[0].findtext('m:artifactId',namespaces=ns),'maven-compiler-plugin')
        executions=plugins[0].findall('m:executions/m:execution',ns)
        self.assertEqual([e.findtext('m:id',namespaces=ns) for e in executions],['default-compile','default-testCompile'])
        for execution,tag in zip(executions,('skipMain','skip')):
            self.assertIsNone(execution.find('m:phase',ns));self.assertIsNone(execution.find('m:goals',ns))
            configuration=execution.find('m:configuration',ns)
            self.assertEqual([c.tag.rsplit('}',1)[-1] for c in configuration],[tag])
            self.assertEqual(configuration.findtext('m:'+tag,namespaces=ns),'${combined.execution.precompiled}')
        self.assertEqual([c.tag.rsplit('}',1)[-1] for c in profile.find('m:properties',ns)],['combined.execution.precompiled'])
        admin=ET.parse(B.ROOT/'chengyinhub-admin/pom.xml').getroot()
        admin_profiles=[p for p in admin.findall('m:profiles/m:profile',ns) if p.findtext('m:id',namespaces=ns)==B.TEST_PROFILE]
        self.assertEqual(len(admin_profiles),1)
        configuration=admin_profiles[0].find('m:build/m:plugins/m:plugin/m:executions/m:execution/m:configuration',ns)
        self.assertEqual(configuration.findtext('m:implicit',namespaces=ns),'none')
        self.assertEqual([e.text for e in configuration.findall('m:testIncludes/m:testInclude',ns)],list(B.TEST_SOURCES))

    def test_reused_execution_requires_verified_cache_and_full_source_before_any_app_creation(self):
        path=Path('/tmp/cy-http-budget.abcdefgh')
        for stage in ('cache','source'):
            with self.subTest(stage=stage):
                state={'cache':'/existing/repository','cacheProof':{'fixed':'proof'}}
                with patch.object(B,'backing_mysql'),patch.object(B,'verified_cache',
                        side_effect=ValueError('OFFLINE_CACHE_PROOF_CHANGED') if stage=='cache' else None,
                        return_value=state['cacheProof']),patch.object(B,'source_manifest',
                        side_effect=ValueError('SOURCE_MANIFEST_REQUIRED')),patch.object(B,'create_slot') as create:
                    with self.assertRaisesRegex(ValueError,'OFFLINE_CACHE_PROOF_CHANGED' if stage=='cache' else 'SOURCE_MANIFEST_REQUIRED'):
                        B.run_owned_app(path,state)
                    create.assert_not_called()
        state={'cache':'/existing/repository','jdkEnvironment':{'PATH':'/trusted-image'}}
        obj={'HostConfig':{'ReadonlyRootfs':True},'Config':{'User':str(os.getuid())+':'+str(os.getgid()),
             'Entrypoint':['/bin/bash'],'Cmd':B.fixed_maven_command()},'Mounts':[]}
        rows=[(str(B.ROOT),str(B.ROOT),False),('/existing/repository','/cache/repository',False),
              (str(path/'launch'),'/budget',False),
              (str(B.ROOT/'chengyinhub-admin/target/controlled-content-security'),str(B.ROOT/'chengyinhub-admin/target/controlled-content-security'),False)]
        rows += [(str(B.ROOT/m/'target'),str(B.ROOT/m/'target'),True) for m in B.MODULES]
        obj['Mounts']=[{'Source':source,'Destination':target,'RW':rw,'Type':'bind'} for source,target,rw in rows]
        obj['Config']['Env']=[k+'='+v for k,v in {**state['jdkEnvironment'],**B.child_environment(self.env())}.items()]
        with patch.dict(os.environ,self.env(),clear=True):
            B.validate_app_inputs(obj,path,state)
            for replacement in ([],['-Dcombined.execution.precompiled=false'],['-Dcombined.execution.precompiled=true']*2):
                wrong=copy.deepcopy(obj);args=wrong['Config']['Cmd'];at=args.index('-Dcombined.execution.precompiled=true')
                args[at:at+1]=replacement
                with self.assertRaisesRegex(ValueError,'FIXED_OFFLINE_MAVEN_COMMAND_CHANGED'):
                    B.validate_app_inputs(wrong,path,state)


    def test_private_mxbean_rows_survive_raw_log_append_and_bind_only_exact_accepted_jvms(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'OUT',Path(directory)):
            state={'identity':B.identity(self.env()),'nonce':'nonce','slots':{'app':{'cid':'1'*64,'name':'own-app'}},
                   'javaProcesses':[{'role':'SUREFIRE','namespacePid':22,'hostPid':100,'startTicks':800,'jvmMemory':{'profile':'SUREFIRE'}},
                                    {'role':'DESCENDANT_JVM','namespacePid':23,'hostPid':101,'startTicks':900,'jvmMemory':{'profile':'SCHEDULER_PROBE'}}]}
            log=B.private_run_log(create=True);self.assertEqual(log.stat().st_mode & 0o777,0o600)
            prefix='CONTROLLED_COMBINATION_MEMORY '+self.env()['SOURCE_SHA']+' 41 2 '
            scalar=' 0 12 32 64 0 9 16 -1 1 10 0 8 16 -1 1 1 2 7 9\n'
            original=(prefix+'SUREFIRE HELD 22 100 200'+scalar+prefix+'SCHEDULER_PROBE ENTER 23 80 201'+scalar).encode()
            log.write_bytes(original)
            def transport(argv,**kwargs):
                self.assertEqual(argv,B.DOCKER+['logs','1'*64]);kwargs['stdout'].write(b'PRIVATE_RAW_DOCKER_TEXT\n')
                return SimpleNamespace(returncode=0)
            with patch.object(B,'inspect',return_value=self.obj()),patch.object(B,'validate_owner') as owner,\
                 patch.object(B.subprocess,'run',side_effect=transport),patch.object(B,'save'):
                B.capture_app_log(Path(directory),state)
                owner.assert_called_once();self.assertIs(state['runLogCaptured'],True)
            self.assertEqual(log.read_bytes(),original+b'PRIVATE_RAW_DOCKER_TEXT\n')
            observed=B.mxbean_readback(state);self.assertEqual(observed['state'],'OBSERVED')
            self.assertIs(observed['businessAccepted'],False);self.assertEqual(len(observed['records']),2)
            self.assertEqual([row['hostPid'] for row in observed['records']],[100,101])
            self.assertEqual(observed['records'][0]['heap'],{'initBytes':0,'usedBytes':12,'committedBytes':32,'maxBytes':64})
            self.assertEqual(observed['records'][1]['nonheap']['maxBytes'],-1)
            self.assertEqual(observed['records'][1]['buffers'],[{'kindCode':1,'count':2,'memoryUsedBytes':7,'totalCapacityBytes':9}])
            self.assertNotIn('PRIVATE_',json.dumps(observed))
            log.write_bytes(original+(prefix+'SUREFIRE HELD 22').encode())
            partial=B.mxbean_readback(state);self.assertEqual(partial['state'],'PARTIAL_OR_UNKNOWN')
            self.assertEqual(partial['records'],observed['records'],'A truncated last write is not another sample and cannot erase complete rows')
            with self.assertRaises(FileExistsError):B.private_run_log(create=True)

    def test_mxbean_projection_rejects_foreign_malformed_unbounded_and_unowned_log_inputs(self):
        for kind in ('source','run','attempt','role','phase','value','overflow','truncated','extra','pools','buffers',
                     'oversize','duplicate','permission','symlink','missing','state-identity','no-pid-proof','missing-reading'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True),patch.object(B,'OUT',Path(directory)):
                state={'identity':B.identity(self.env()),'runLogCaptured':True,'slots':{'app':{'cid':'1'*64}},
                       'javaProcesses':[{'role':'SUREFIRE','namespacePid':22,'hostPid':100,'startTicks':800,'jvmMemory':{'profile':'SUREFIRE'}}]}
                log=B.private_run_log(create=True)
                line='CONTROLLED_COMBINATION_MEMORY '+self.env()['SOURCE_SHA']+' 41 2 SUREFIRE HELD 22 100 200 0 12 32 64 0 9 16 -1 0 0\n'
                if kind=='source':line=line.replace(self.env()['SOURCE_SHA'],'b'*40)
                if kind=='run':line=line.replace(' 41 2 ',' 42 2 ')
                if kind=='attempt':line=line.replace(' 41 2 ',' 41 3 ')
                if kind=='role':line=line.replace('SUREFIRE','PRIVATE_ROLE')
                if kind=='phase':line=line.replace('HELD','PRIVATE_PHASE')
                if kind=='value':line=line.replace(' 12 ',' PRIVATE_VALUE ')
                if kind=='overflow':line=line.replace(' 12 ',' 9223372036854775808 ')
                if kind=='truncated':line=line[:-4]+'\n'
                if kind=='extra':line=line[:-1]+' 8\n'
                if kind=='pools':line=line[:-4]+' 17 0\n'
                if kind=='buffers':line=line[:-2]+'5\n'
                if kind=='oversize':line=line[:-1]+' 8'*4097+'\n'
                if kind=='duplicate':line+=line
                if kind=='state-identity':state['identity']['sourceSha']='b'*40
                if kind=='no-pid-proof':state['javaProcesses']=[]
                if kind=='missing-reading':line=line.replace('0 12 32 64','-1 -1 -1 -1')
                log.write_text(line)
                if kind=='permission':log.chmod(0o644)
                if kind=='symlink':log.unlink();target=Path(directory)/'PRIVATE_TARGET';target.write_text(line);log.symlink_to(target)
                if kind=='missing':log.unlink()
                observed=B.mxbean_readback(state)
                if kind in ('no-pid-proof','missing-reading'):
                    self.assertEqual(observed['state'],'PARTIAL_OR_UNKNOWN');self.assertEqual(len(observed['records']),1)
                else:self.assertEqual(observed['state'],'UNKNOWN');self.assertEqual(observed['records'],[])
                self.assertNotIn('PRIVATE_',json.dumps(observed));self.assertNotIn(directory,json.dumps(observed))

    def test_oom_readback_precedes_cleanup_and_cannot_replace_failure_or_sign_business_success(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True):
            path,state,obj,mysql,namespace=self.namespace_fixture(directory);events=[];reports=[]
            out=Path(directory)/'out';out.mkdir()
            state['firstFailure']={'phase':'APP_EXECUTION','reasonCode':'ACTUAL_CGROUP_CHILD_OOM_KILL'}
            original=copy.deepcopy(state['firstFailure']);state['cgroupMemoryEvents']={'oom_kill':'1'}
            state['javaProcesses'].append({'role':'SUREFIRE','namespacePid':22,'hostPid':100,'startTicks':800,'jvmMemory':{'profile':'SUREFIRE'}})
            def failed_capture(*args):events.append('RAW_LOG_ATTEMPT');raise OSError('PRIVATE_LOG_VALUE')
            def clean(path,current,role):
                events.append('CLEANUP_'+role)
                return {'role':role,'cid':current['slots'][role]['cid'],'result':'EXACT_CID_REMOVED_AND_ABSENT',
                        'terminal':{'ExitCode':137 if role=='app' else 0,'OOMKilled':role=='app','Running':False,'Pid':0}}
            with patch.object(B,'OUT',out):
                log=B.private_run_log(create=True)
                log.write_text('CONTROLLED_COMBINATION_MEMORY '+self.env()['SOURCE_SHA']+' 41 2 SUREFIRE HELD 22 100 200 0 12 32 64 0 9 16 -1 0 0\n')
                with patch.object(B,'load_private',return_value=(path,state)),patch.object(B,'capture_app_log',side_effect=failed_capture),\
                     patch.object(B,'namespace_port_proof',return_value={'result':'UNKNOWN'}),patch.object(B,'cleanup_slot',side_effect=clean),\
                     patch.object(B,'write',side_effect=lambda path,value:reports.append(copy.deepcopy(value))):
                    with self.assertRaisesRegex(ValueError,'ORIGINAL_EXIT_OR_BUDGET_OR_CLEANUP_NOT_ACCEPTED'):B.finalize(path,2)
            report=reports[-1];self.assertEqual(events,['RAW_LOG_ATTEMPT','CLEANUP_redis','CLEANUP_app'])
            self.assertEqual(report['firstFailure'],original);self.assertEqual(report['result'],'NOT_ACCEPTED')
            self.assertEqual(report['originalExit'],2);self.assertIsNone(report['mavenExit']);self.assertIsNone(report['appExit'])
            self.assertEqual(report['jvmMxBeanReadback']['state'],'PARTIAL_OR_UNKNOWN')
            self.assertEqual(len(report['jvmMxBeanReadback']['records']),1)
            self.assertEqual(report['cgroupMemoryEvents'],{'oom_kill':'1'});self.assertNotIn('PRIVATE_',json.dumps(report))


if __name__=='__main__':unittest.main()
