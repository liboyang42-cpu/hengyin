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
if __name__=='__main__':unittest.main()
