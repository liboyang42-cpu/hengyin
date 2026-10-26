"""Pure budget-owner control SOURCE; NOT_RUN. No Java, Docker, database or network calls."""
import copy
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
                                 (str(path/'launch'),'/budget',False)]+[(str(B.ROOT/m/'target'),str(B.ROOT/m/'target'),True) for m in B.MODULES]:
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
        report.update(result='FIXED_COMBINED_BUDGET_PROCESS_PROVEN',source={'artifactJarSha256':'f'*64},
                      originalExit=0,mavenExit=0,appExit={'ExitCode':0,'OOMKilled':False,'Running':False,'Pid':0},
                      database=self.env()['CHENGYIN_HTTP_E2E_DATABASE'])
        limits={'memory.max':str(B.MEMORY),'memory.swap.max':'0','cpu.max':'100000 100000','pids.max':'256'}
        report['javaProcesses']=[{'role':'MAVEN','namespacePid':2,'cgroup':{'limits':limits}},
                                 {'role':'SUREFIRE','namespacePid':41,'cgroup':{'limits':limits}}]
        for role,image,memory in (('app',B.JDK,B.MEMORY),('redis',B.REDIS,67108864)):
            report['slots'][role]['budget']={'cid':report['slots'][role]['cid'],'image':image,
                    'memory':memory,'memorySwap':memory,'nanoCpus':1000000000,'pids':256}
        report['mysql'].update(image=B.MYSQL,memory=B.MEMORY,memorySwap=B.MEMORY,nanoCpus=1000000000,pids=256)
        report['mysqlBootstrap']={**B.identity(self.env()),'result':'READY','originalExit':0,'reportSha256':'a'*64,
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

    def cleanup_control(self,obj=None,net=None,write_fails=False,diagnostic_fails=False,lost_ids=False):
        # Native owner transport doubles only; no application service or business response is mocked.
        obj=obj or self.mysql_object();net=net or self.network_object();reports=[];mutations=[]
        env=self.resource_env();env.pop('B_PAYMENT_FACT_DDL_APPROVED');env.pop('CHENGYIN_AI_REDIS_IT_DATABASE')
        def object_read(kind,target):return obj if kind=='container' else net
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
            self.assertIn('--defaults-file=/dev/stdin',argv);self.assertIn('--no-login-paths',argv)

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


if __name__=='__main__':unittest.main()
