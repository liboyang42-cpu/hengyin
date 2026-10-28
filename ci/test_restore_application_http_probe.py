"""Direct control SOURCE. Intentionally NOT_RUN in the source-construction task."""
import copy
from contextlib import nullcontext, contextmanager
from decimal import Decimal
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
import uuid
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'ci'/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

P=module('restore_http_probe_controls','restore_application_http_probe.py')
R=module('restore_http_runtime_controls','restore_application_http_runtime.py')


def events():
    result=[]
    for symbol in ('CashWithdrawServiceImpl.withdraw','RefundServiceImpl.saveBackOfficeRefund','CommittedBusinessNotice.dispatchInNewTransaction'):
        result.extend([{'bootId':'boot','symbol':symbol,'stage':'ENTER','held':True},
                       {'bootId':'boot','symbol':symbol,'stage':'THROW','held':True,'blocked':True,
                        'errorType':'com.chengyinhub.common.config.RestoreDispatchGuard$Blocked'}])
    for symbol in ('BeforeStartNotifyTask.run','CommittedOfficialNoticeTask.deliverPending','CommittedOfficialNoticeService.reconcilePending'):
        result.extend([{'bootId':'boot','symbol':symbol,'stage':'ENTER','held':True},
                       {'bootId':'boot','symbol':symbol,'stage':'RETURN','held':True}])
    return result


def late():
    before={table:{'rows':[],'nextId':1 if table!= 'wx_payment_callback_fact_link' else None} for table in P.LATE_TABLES}
    before['ums_member']={'rows':[{'id':4,'balance':{'decimal':'70.00'}}],'nextId':5}
    after=copy.deepcopy(before)
    after['wx_verified_payment_fact']={'rows':[{'id':1,'fact_hash':'a'*64}],'nextId':2}
    after['wx_payment_callback_inbox']={'rows':[{'id':1,'biz_id':8,'state':0}],'nextId':2}
    after['wx_payment_callback_fact_link']['rows']=[{'inbox_id':1,'fact_id':1,'fact_hash':'a'*64}]
    callback={'code':200,'data':{'originalOutcome':'FAIL','factId':1,'factHash':'a'*64}}
    return before,after,callback


class RestoreHttpDirectControls(unittest.TestCase):
    def test_query_preserves_driver_no_parameter_and_original_tuple_contract(self):
        calls=[]
        class Cursor:
            description=(('n',),)
            def execute(self,sql,*args):
                calls.append((sql,args))
                # Pinned PyMySQL 1.1.1 Cursor.mogrify formats whenever args is not None.
                # This is a driver-call control, not database or business acceptance.
                if args and args[0] is not None:sql % args[0]
            def fetchall(self):return ((1,),)
        cursor=Cursor()
        class Database:
            def cursor(self):return nullcontext(cursor)
        class Auth:
            @staticmethod
            def database(port,schema):return nullcontext(Database())
        literal="SELECT COUNT(*) AS n FROM mysql.user WHERE User='startup_probe' AND Host='%'"
        self.assertEqual(P.query(Auth,3306,None,literal),[{'n':1}])
        self.assertEqual(calls[0],(literal,()))
        sql='SELECT COUNT(*) AS n FROM mysql.user WHERE User=%s AND Host=%s'
        parameters=('startup_probe','%')
        self.assertEqual(P.query(Auth,3306,None,sql,parameters),[{'n':1}])
        self.assertEqual(calls[1][0],sql)
        self.assertEqual(len(calls[1][1]),1)
        self.assertIs(calls[1][1][0],parameters)
        self.assertEqual(P.query(Auth,3306,None,literal,None),[{'n':1}])
        self.assertEqual(calls[2],(literal,()))

    def test_exact_real_owner_and_task_events_are_accepted(self):
        self.assertTrue(P.assert_owner_events(events(),'boot'))

    def test_http_503_alone_cannot_prove_guard(self):
        P.refused((503,{'code':503}))
        with self.assertRaises(ValueError):P.assert_owner_events([],'boot')

    def test_disabled_provider_error_is_not_blocked(self):
        value=events();value[1]['blocked']=False;value[1]['errorType']='java.lang.IllegalStateException'
        with self.assertRaises(ValueError):P.assert_owner_events(value,'boot')

    def test_other_boot_cannot_supply_guard_evidence(self):
        with self.assertRaises(ValueError):P.assert_owner_events(events(),'other')

    def test_normal_mode_events_cannot_supply_hold(self):
        value=events()
        for row in value:row['held']=False
        with self.assertRaises(ValueError):P.assert_owner_events(value,'boot')

    def test_missing_quartz_entry_does_not_pass(self):
        with self.assertRaises(ValueError):P.assert_owner_events([r for r in events() if not r['symbol'].startswith('BeforeStartNotifyTask')],'boot')

    def test_removed_before_start_guard_reaches_real_sweep_and_fails(self):
        value=events()+[{'bootId':'boot','symbol':'TeamGroupServiceImpl.notifyBeforeStartBatch','stage':'ENTER','held':True}]
        with self.assertRaises(ValueError):P.assert_owner_events(value,'boot')

    def test_removed_notice_guard_reaches_real_pending_selector_and_fails(self):
        value=events()+[{'bootId':'boot','symbol':'com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper.maxPendingId','stage':'SQL','held':True}]
        with self.assertRaises(ValueError):P.assert_owner_events(value,'boot')

    def test_changed_balance_is_not_normalized(self):
        a={'ums_member':{'rows':[{'balance':P.typed(Decimal('70.00'))}],'nextId':4}}
        b=copy.deepcopy(a);b['ums_member']['rows'][0]['balance']=P.typed(Decimal('100.00'))
        with self.assertRaises(ValueError):P.assert_equal(a,b)

    def test_rejected_write_must_not_allocate_id(self):
        with self.assertRaises(ValueError):P.assert_equal({'x':{'rows':[],'nextId':8}},{'x':{'rows':[],'nextId':9}})

    def test_late_verified_fact_wait_inbox_is_preserved(self):
        P.assert_late_callback(*late(),registration=8)

    def test_callback_done_is_not_paused_success(self):
        before,after,callback=late();after['wx_payment_callback_inbox']['rows'][0]['state']=2
        with self.assertRaises(ValueError):P.assert_late_callback(before,after,callback,8)

    def test_callback_cannot_change_economy(self):
        before,after,callback=late();after['ums_member']['rows'][0]['balance']={'decimal':'71.00'}
        with self.assertRaises(ValueError):P.assert_late_callback(before,after,callback,8)

    def test_callback_cannot_fake_original_ack(self):
        before,after,callback=late();callback['data']['originalOutcome']='SUCCESS'
        with self.assertRaises(ValueError):P.assert_late_callback(before,after,callback,8)

    def test_callback_cannot_bind_other_registration(self):
        with self.assertRaises(ValueError):P.assert_late_callback(*late(),registration=9)

    def test_callback_cannot_drop_original_link(self):
        before,after,callback=late();after['wx_payment_callback_fact_link']['rows']=[]
        with self.assertRaises(ValueError):P.assert_late_callback(before,after,callback,8)

    def test_callback_cannot_attach_another_fact_identity(self):
        before,after,callback=late();after['wx_payment_callback_fact_link']['rows'][0]['fact_id']=91
        with self.assertRaises(ValueError):P.assert_late_callback(before,after,callback,8)

    def test_duplicate_callback_may_reserve_unused_id_but_no_new_fact(self):
        _,before,_=late();after=copy.deepcopy(before);after['wx_payment_callback_inbox']['nextId']+=1
        P.assert_callback_replay(before,after)
        after['wx_payment_callback_inbox']['rows'].append({'id':3,'biz_id':8,'state':0})
        with self.assertRaises(ValueError):P.assert_callback_replay(before,after)

    def test_duplicate_callback_cannot_regress_allocator(self):
        _,before,_=late();after=copy.deepcopy(before);after['wx_verified_payment_fact']['nextId']=1
        with self.assertRaises(ValueError):P.assert_callback_replay(before,after)

    def test_unknown_sql_type_cannot_be_stringified_away(self):
        with self.assertRaises(ValueError):P.typed(object())
        self.assertEqual(P.typed(b'\x00\xff'),{'binary':'00ff'})
        self.assertEqual(P.typed(0.1),{'floatHex':float(0.1).hex()})
        with self.assertRaises(ValueError):P.typed(float('nan'))

    def test_only_explicit_manual_admission_is_allowed(self):
        valid={'GITHUB_EVENT_NAME':'workflow_dispatch','CHENGYIN_CI_RESTORE_HTTP_STARTUP':'true','GITHUB_SHA':'a'*40,
               'GITHUB_RUN_ID':'17','GITHUB_RUN_ATTEMPT':'1'}
        class OriginalGate:
            @staticmethod
            def validate_execution_env(env):pass # This control isolates only the added gate, not original owner validation.
        with patch.object(R,'load',return_value=OriginalGate):
            R.gate(valid)
            for key,value in [('GITHUB_EVENT_NAME','pull_request'),('CHENGYIN_CI_RESTORE_HTTP_STARTUP',''),
                              ('CHENGYIN_CI_CAPACITY_MIXED','true'),('GITHUB_SHA','short')]:
                env=dict(valid);env[key]=value
                with self.assertRaises(ValueError):R.gate(env)

    def test_real_process_env_and_original_java_entry_are_required(self):
        source=(ROOT/'ci/restore_application_http_runtime.py').read_text()
        self.assertIn("'-e','CHENGYIN_RESTORE_MODE='+mode",source)
        self.assertNotIn('-DCHENGYIN_RESTORE_MODE',source)
        self.assertIn('org.springframework.boot.loader.launch.PropertiesLauncher',source)
        self.assertIn("'--network',self.name",source)
        self.assertNotIn("['network','create'",source)
        self.assertNotIn("'--publish'",source)

    def test_child_uses_actual_services_and_preserves_real_quartz(self):
        source=(ROOT/'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RestoreHttpApplicationChild.java').read_text()
        for literal in ('RuoYiApplication.class','loginByMember','registrations.handleWechatPayCallback(result)',
                        'facts.recordVerified(original)','notices.enqueueOfficial','wake.dispatchInNewTransaction',
                        '--chengyin.jobs.enabled=true','--spring.quartz.auto-startup=true',
                        '--spring.quartz.wait-for-jobs-to-complete-on-shutdown=true',
                        'factory.getConfiguration().addInterceptor(new MapperObservations())','file.force(true)'):
            self.assertIn(literal,source)
        self.assertIn('originalCallbackReference(PRIVATE,config,ids.path("registrationId").asLong(),fact,facts,attempt)',source)
        reference_owner=source[source.index('    static WechatVerifiedPaymentFact.Reference originalCallbackReference('):
                               source.index('    static boolean sameCallbackBinding(')]
        self.assertIn('facts.recordVerified(original)',reference_owner)
        self.assertIn('var stored=facts.requireVerified(reference)',reference_owner)
        self.assertIn('String original=callOriginalRegistration(registrations,fact,reference)',source)
        self.assertNotIn('Mockito',source)
        self.assertNotIn('setAuthentication',source)
        self.assertIn('call.proceed()',source)

    def test_runtime_user_stays_with_original_owner_fixed_phase(self):
        source=(ROOT/'ci/restore_application_http_probe.py').read_text()
        self.assertIn("'restore-runtime-user'",source)
        self.assertIn("'OWNED_RESTORED_RUNTIME_USER_PREPARED'",source)
        for forbidden in ('CREATE USER','GRANT SELECT','GRANT ALL','ALTER USER'):
            self.assertNotIn(forbidden,source)
        child=(ROOT/'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RestoreHttpApplicationChild.java').read_text()
        self.assertIn('--spring.datasource.druid.master.username=startup_probe',child)
        self.assertNotIn('--spring.datasource.druid.master.username=root',child)

    def test_no_production_release_or_health_only_success(self):
        source=(ROOT/'ci/restore_application_http_probe.py').read_text()
        self.assertNotIn("productionDispatchRelease':True",source)
        self.assertIn("'productionDispatchRelease':False",source)
        self.assertIn('assert_complete(before,actor,original_provider)',source)
        self.assertIn('assert_owner_events(evidence',source)
        self.assertIn('tool.backup(',source);self.assertIn('tool.restore(',source)
        self.assertIn('original_exit==0',source)


class RestoreHttpFailureLifecycleControls(unittest.TestCase):
    """C lifecycle controls only; owner transport/host admission are explicit doubles, not acceptance."""
    name='cy-auth-'+'a'*32
    schema='cy_e2e_auth_'+'a'*32
    env={'GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'31','GITHUB_RUN_ATTEMPT':'1'}

    def failed_report(self):
        return dict(sourceSha='a'*40,runId='31',runAttempt='1',schema=self.schema,jarSha256='b'*64,
                    result='NOT_COMPLETED',productionDispatchRelease=False,applicationStartAttempted=False,
                    phase='SOURCE_DATABASE_PREPARATION',firstFailurePhase='SOURCE_DATABASE_PREPARATION',
                    firstFailureType='ModuleNotFoundError',boots=[],completeBoots=[],checks=[],
                    ownedAppCids=[],removedAppCids=[],applicationStop='NO_ACTIVE_APP_SLOT',
                    privateCleanup='EXACT_OWN_SUBTREE_REMOVED',sourceMysqlCid='1'*64,targetMysqlCid='2'*64,redisCid='3'*64)

    def owner(self,app=None,log='NOT_STARTED'):
        ids={'mysql':'1'*64,'restore-mysql':'2'*64,'redis':'3'*64}
        if app:ids['app']=app
        return {'resourceCleanup':{'cleanup':'CONFIRMED_EXACT_OWNED_REMOVAL','ownedRemoved':[
                    {'name':self.name+'-'+suffix,'id':cid,'independentNoSuch':True} for suffix,cid in ids.items()]},
                'applicationLogStatus':log,'applicationLogPreserved':log=='PRESERVED'}

    def finalization(self,report,owner=None,original_exit=1,deny_absence=False):
        # Execute the real finalize/JSON/absence-classification logic against fixed transport results.
        # No Docker, application, MySQL, Redis or driver is invoked by these controls.
        auth=module('restore_http_failure_original_absence','authenticated_redis_recovery_probe.py')
        fake_auth=SimpleNamespace(DOCKER=auth.DOCKER,require_absent=auth.require_absent)
        fake_runtime=SimpleNamespace(gate=lambda env:None)
        commands=[]
        def absent(argv,**kwargs):
            commands.append(argv)
            kind='network' if argv[-3:-1]==['network','inspect'] else 'container'
            error=('permission denied; do-not-export-inner-detail' if deny_absence else 'Error: No such '+kind+': '+argv[-1])
            return SimpleNamespace(returncode=1,stderr=error.encode())
        with tempfile.TemporaryDirectory() as directory:
            out=Path(directory)
            if report is not None:
                (out/'restore-http-application.json').write_text(report if isinstance(report,str) else json.dumps(report))
            value=self.owner() if owner is None else owner
            (out/'authenticated-redis-recovery.json').write_text(value if isinstance(value,str) else json.dumps(value))
            def load(name,path):return fake_runtime if path.endswith('runtime.py') else fake_auth
            failure=None
            with patch.dict(os.environ,self.env),patch.object(P,'load',side_effect=load),patch.object(P.subprocess,'run',side_effect=absent):
                try:P.finalize(self.name,self.schema,out,original_exit)
                except ValueError as caught:failure=caught
            proof=json.loads((out/'restore-http-cleanup.json').read_text())
        return proof,failure,commands

    def success_report(self):
        report=self.failed_report();report.pop('firstFailureType');report.pop('firstFailurePhase')
        report.update(applicationStartAttempted=True,phase='RESTORED_HELD_WORKLOAD',
                      result='SCOPED_RESTORE_HTTP_STARTUP_AND_REAL_OWNER_HOLD_VERIFIED',
                      applicationStop='STOPPED_EXACT_APP_CID',networkId='d'*64,
                      sourceServerUuid='11111111-1111-1111-1111-111111111111',
                      targetServerUuid='22222222-2222-2222-2222-222222222222')
        owned=[str(i)*64 for i in (4,5,6,7,8)];report['ownedAppCids']=owned;report['removedAppCids']=owned[:-1]
        boots=[]
        for i,mode in enumerate(('false','false','true','invalid','')):
            boots.append(dict(bootId='boot-'+str(i),appCid=owned[i],javaPid=1,side='source' if i==0 else 'target',
                              serverUuid=report['sourceServerUuid'] if i==0 else report['targetServerUuid'],
                              restoreMode=mode,guardHeld=mode!='false',schedulerStarted=True,
                              sourceSha=report['sourceSha'],schema=report['schema'],jarSha256=report['jarSha256']))
        report['completeBoots']=boots[:2];report['boots']=boots[2:]
        report['checks']=[{'case':'original_complete_cohort','providerSubmits':1}]+[
            {'case':'held-full-http-and-actual-wake','mode':mode,'newDispatches':0} for mode in P.HELD_MODES]
        report['applicationStopHistory']=[]
        for boot in boots:
            cid=boot['appCid']
            identity=dict(cid=cid,bootId=boot['bootId'],sourceSha=report['sourceSha'],
                    jarSha256=report['jarSha256'],hostPid=100,startTicks='123',
                    cmdlineSha256='a'*64,configSha256='b'*64,readySha256='c'*64)
            report['applicationStopHistory'].append(dict(cid=cid,bootId=boot['bootId'],
                    sourceSha=report['sourceSha'],jarSha256=report['jarSha256'],identity=identity,
                    proofKind='READY_JVM_EXIT_CONFIRMED',
                    stopped=dict(cid=cid,running=False,hostPid=0,oomKilled=False,exitCode=143,restarting=False),
                    log=dict(result='COMPLETE_PRIVATE_CAPTURE_VERIFIED',bytes=12,sha256='d'*64)))
        report['applicationLogPrivateErasure']='VERIFIED_PRIVATE_CAPTURES_ERASED_AFTER_REPORT'
        return report

    def test_zero_boot_failure_preserves_original_cause_and_resource_proof_without_acceptance(self):
        proof,failure,commands=self.finalization(self.failed_report())
        self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertEqual(proof['firstFailureType'],'ModuleNotFoundError')
        self.assertEqual(proof['firstFailurePhase'],'SOURCE_DATABASE_PREPARATION')
        self.assertEqual(proof['originalExit'],1);self.assertTrue(proof['confirmedPreStartFailure'])
        self.assertEqual(proof['resourceCleanup'],'CONFIRMED_EXACT_OWNED_REMOVAL')
        self.assertEqual(proof['applicationLogStatus'],'NOT_STARTED');self.assertFalse(proof['productionDispatchRelease'])
        self.assertEqual(len(commands),8) # Five exact names plus the three known created backing CIDs.

    def test_zero_boot_failure_cannot_turn_green_if_outer_exit_is_wrongly_zero(self):
        proof,failure,_=self.finalization(self.failed_report(),original_exit=0)
        self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertNotEqual(proof['resourceCleanup'],'CLEAN')

    def test_attempt_is_persisted_before_a_failed_create_and_never_inferred_from_empty_cids(self):
        snapshots=[];report=self.failed_report()
        class Slot:
            def runtime_report_write(self,path,value):snapshots.append(copy.deepcopy(value))
            def start(self,*args,**kwargs):
                if snapshots[-1]['applicationStartAttempted'] is not True:raise AssertionError('missing durable attempt checkpoint')
                raise RuntimeError('do-not-export-inner-detail')
        with self.assertRaisesRegex(RuntimeError,'do-not-export-inner-detail'):
            P.start_application(Slot(),report,Path('unused'),'SOURCE_APPLICATION_START','source','false','original-uuid')
        self.assertTrue(report['applicationStartAttempted']);self.assertEqual(report['ownedAppCids'],[])
        self.assertEqual(snapshots[0]['phase'],'SOURCE_APPLICATION_START');self.assertFalse(P.confirmed_pre_start_failure(report))

    def test_attempted_create_with_no_cid_cannot_claim_no_app_logs_needed(self):
        report=self.failed_report();report.update(applicationStartAttempted=True,phase='SOURCE_APPLICATION_START',firstFailurePhase='SOURCE_APPLICATION_START')
        proof,failure,_=self.finalization(report)
        self.assertIsInstance(failure,ValueError);self.assertFalse(proof['confirmedPreStartFailure'])
        self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertTrue(any(row['phase']=='READ_OR_VERIFY_OWNER_CLEANUP' for row in proof['finalizationFailures']))

    def test_attempted_start_with_unavailable_logs_remains_failure_even_after_resource_removal(self):
        report=self.failed_report();report.update(applicationStartAttempted=True,phase='SOURCE_APPLICATION_START',firstFailurePhase='SOURCE_APPLICATION_START')
        proof,failure,_=self.finalization(report,self.owner(log='UNAVAILABLE'))
        self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertEqual(proof['applicationLogStatus'],'UNAVAILABLE');self.assertNotEqual(proof['resourceCleanup'],'CLEAN')
        self.assertEqual(proof['firstFailureType'],'ModuleNotFoundError')

    def test_wrong_source_run_attempt_or_schema_cannot_supply_failure_identity(self):
        for key,value in (('sourceSha','f'*40),('runId','999'),('runAttempt','2'),('schema','foreign')):
            with self.subTest(key=key):
                report=self.failed_report();report[key]=value;report['ownedAppCids']=['9'*64]
                proof,failure,commands=self.finalization(report)
                self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')
                self.assertNotIn('firstFailureType',proof)
                self.assertTrue(any(row['phase']=='READ_APPLICATION_REPORT' for row in proof['finalizationFailures']))
                self.assertNotIn('9'*64,[argv[-1] for argv in commands])

    def test_corrupt_or_missing_report_retains_safe_error_and_independent_absence(self):
        for damaged in ('{"token":"do-not-export-inner-detail",',None):
            with self.subTest(damaged=damaged is None):
                proof,failure,commands=self.finalization(damaged)
                self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')
                self.assertEqual(proof['originalExit'],1);self.assertEqual(len(commands),8)
                self.assertNotIn('do-not-export-inner-detail',json.dumps(proof))

    def test_claimed_success_with_zero_boots_is_rejected_without_index_error(self):
        report=self.success_report();report.update(boots=[],completeBoots=[],ownedAppCids=[],removedAppCids=[])
        proof,failure,_=self.finalization(report,self.owner(app='8'*64,log='PRESERVED'),original_exit=0)
        self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertTrue(any(row['phase']=='VERIFY_SUCCESSFUL_APPLICATION_LIFECYCLE' for row in proof['finalizationFailures']))

    def test_unknown_absence_cannot_hide_original_failure_or_publish_inner_detail(self):
        proof,failure,_=self.finalization(self.failed_report(),deny_absence=True)
        self.assertIsInstance(failure,ValueError);self.assertEqual(proof['resourceCleanup'],'UNKNOWN')
        self.assertEqual(proof['firstFailureType'],'ModuleNotFoundError');self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertTrue(all(not row['absent'] for row in proof['absence']))
        self.assertNotIn('do-not-export-inner-detail',json.dumps(proof))

    def test_exact_five_boot_success_still_requires_four_cids_logs_and_original_zero_exit(self):
        report=self.success_report();owner=self.owner(app=report['ownedAppCids'][-1],log='PRESERVED')
        proof,failure,_=self.finalization(report,owner,original_exit=0)
        self.assertIsNone(failure);self.assertEqual(proof['result'],'SCOPED_HTTP_RESTORE_EVIDENCE_COMPLETE_NO_PRODUCTION_RELEASE')
        self.assertEqual(proof['resourceCleanup'],'CLEAN');self.assertFalse(proof['productionDispatchRelease'])
        proof,failure,_=self.finalization(report,owner,original_exit=1)
        self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')

    def test_success_cannot_drop_a_boot_change_original_jar_or_backing_cid(self):
        for change in ('boot','jar','cid','pid'):
            with self.subTest(change=change):
                report=self.success_report();owner=self.owner(app=report['ownedAppCids'][-1],log='PRESERVED')
                if change=='boot':report['boots'].pop()
                elif change=='jar':report['boots'][0]['jarSha256']='f'*64
                elif change=='pid':report['boots'][0]['javaPid']=9
                else:owner['resourceCleanup']['ownedRemoved'][0]['id']='9'*64
                proof,failure,_=self.finalization(report,owner,original_exit=0)
                self.assertIsInstance(failure,ValueError);self.assertEqual(proof['result'],'NOT_ACCEPTED')


class RestoreHttpDiagnosticControls(unittest.TestCase):
    """Pure diagnostic controls; no application or transport acceptance is manufactured."""

    def test_failure_diagnostic_exports_only_fixed_phase_reason_and_type(self):
        failure=ValueError('password=fixture-secret SQL body token must remain private')
        failure.restore_reason_code='password=fixture-secret'
        result=P.failure_diagnostic(failure,'SOURCE_WORKLOAD','ORIGINAL_WITHDRAW_COMMAND')
        self.assertEqual(result,{'phase':'SOURCE_WORKLOAD','substage':'ORIGINAL_WITHDRAW_COMMAND',
                                 'type':'ValueError','reasonCode':'UNCLASSIFIED_FAILURE'})
        self.assertNotIn('fixture-secret',json.dumps(result))
        with self.assertRaises(ValueError) as rejected:P.good((200,{'code':503,'msg':'private-body'}))
        self.assertEqual(P.failure_diagnostic(rejected.exception,'SOURCE_WORKLOAD','ADMIN_IDENTITY_READ')['reasonCode'],
                         'HTTP_OPERATION_REJECTED')

    def test_checkpoint_is_durable_but_never_claims_ready_or_completed(self):
        snapshots=[];report={'result':'NOT_COMPLETED','applicationStartAttempted':True,'boots':[],'checks':[]}
        slot=SimpleNamespace(runtime_report_write=lambda path,value:snapshots.append(copy.deepcopy(value)))
        P.checkpoint(slot,report,Path('unused'),'SOURCE_WORKLOAD','ORIGINAL_CHAPTER_COMMAND')
        self.assertEqual(snapshots[-1]['substage'],'ORIGINAL_CHAPTER_COMMAND')
        self.assertEqual(report['boots'],[]);self.assertEqual(report['checks'],[])
        self.assertEqual(report['result'],'NOT_COMPLETED')
        with self.assertRaisesRegex(ValueError,'fixed diagnostic checkpoint'):
            P.checkpoint(slot,report,Path('unused'),'SOURCE_WORKLOAD','arbitrary secret stage')
        self.assertEqual(len(snapshots),1)

    def test_ready_return_is_separate_from_workload_and_original_boot_acceptance(self):
        snapshots=[]
        boot=dict(bootId='actual-ready',side='source',restoreMode='false',guardHeld=False,jarSha256='b'*64,
                  appCid='4'*64,javaPid=1,sourceSha='a'*40,serverUuid='original-uuid',schema='original-schema',schedulerStarted=True)
        report={'result':'NOT_COMPLETED','boots':[],'completeBoots':[],'checks':[],'ownedAppCids':['4'*64]}
        slot=SimpleNamespace(runtime_report_write=lambda path,value:snapshots.append(copy.deepcopy(value)),
                             start=lambda *args,**kwargs:boot)
        self.assertIs(P.start_application(slot,report,Path('unused'),'SOURCE_APPLICATION_START','source','false','original-uuid'),boot)
        self.assertEqual(report['readyBootObservations'],[boot])
        self.assertEqual(report['result'],'NOT_COMPLETED');self.assertEqual(report['completeBoots'],[])
        self.assertEqual(report['boots'],[]);self.assertEqual(report['checks'],[])
        with self.assertRaisesRegex(ValueError,'five independent original app boots'):
            P.assert_successful_lifecycle(report)

    @staticmethod
    def http_slot(raw):
        slot=object.__new__(R.ApplicationSlot)
        slot.budget=lambda:None;slot.cid='4'*64
        slot.auth=SimpleNamespace(DOCKER=['docker'],command=lambda *args,**kwargs:raw)
        return slot

    def test_http_diagnostic_keeps_original_codes_but_not_body_token_or_original_query(self):
        slot=self.http_slot(b'{"code":409,"msg":"private-token-or-SQL","data":{"secret":"raw"}}\n200')
        status,value=slot.http('/api/club/lead/unlock-chapter-receipt?activityId=42&commandId=original-private-id','private-token')
        self.assertEqual((status,value['code']),(200,409))
        self.assertEqual(slot.last_http,{'route':'/api/club/lead/unlock-chapter-receipt','method':'GET',
                                       'status':200,'businessCode':409,'result':'JSON_OBJECT_RECEIVED'})
        self.assertNotIn('private',json.dumps(slot.last_http));self.assertNotIn('42',json.dumps(slot.last_http))

    def test_malformed_http_retains_received_status_and_exact_parse_reason(self):
        slot=self.http_slot(b'private-not-json\n502')
        with self.assertRaises(ValueError) as rejected:slot.http('/getInfo','private-token')
        result=P.failure_diagnostic(rejected.exception,'SOURCE_WORKLOAD','ADMIN_IDENTITY_READ')
        self.assertEqual(result['reasonCode'],'HTTP_RESPONSE_NOT_JSON')
        self.assertEqual(slot.last_http['status'],502)
        self.assertNotIn('private',json.dumps(result)+json.dumps(slot.last_http))
        slot=self.http_slot(b'[]\n200')
        with self.assertRaises(ValueError) as rejected:slot.http('/getInfo')
        self.assertEqual(rejected.exception.restore_reason_code,'HTTP_RESPONSE_NOT_OBJECT')

    def test_safe_log_summary_retains_only_finite_markers_and_types(self):
        raw=b'password=private-token SQL SELECT body\nApplication run failed\njava.sql.SQLSyntaxErrorException: private-SQL'
        result=R.summarize_application_log(raw)
        self.assertTrue(result['markers']['Application run failed'])
        self.assertEqual(result['exceptionTypes'],['java.sql.SQLSyntaxErrorException'])
        self.assertNotIn('private',json.dumps(result));self.assertNotIn('SELECT',json.dumps(result))
        self.assertEqual(result['sampleSha256'],__import__('hashlib').sha256(raw).hexdigest())
        limited=R.summarize_application_log(b'x'*(1024*1024+1))
        self.assertEqual(limited['sampleBytes'],1024*1024);self.assertTrue(limited['sampleTruncated'])

    @staticmethod
    def diagnostic_slot():
        slot=object.__new__(R.ApplicationSlot);slot.name='cy-auth-'+'a'*32;slot.cid='4'*64;slot.stop_history=[]
        slot.source={'networkId':'5'*64};slot.auth=SimpleNamespace(DOCKER=['docker'])
        obj={'Id':slot.cid,'Name':'/'+slot.name+'-app','Config':{'Labels':{'chengyin.auth-recovery':slot.name}},
             'NetworkSettings':{'Networks':{slot.name:{'NetworkID':'5'*64}}},
             'State':{'Running':False,'OOMKilled':False,'Restarting':False,'Pid':0,'ExitCode':137}}
        slot.inspect=lambda target:obj
        return slot,obj

    def test_unknown_stop_preserves_actual_exit_and_does_not_remove_or_accept_it(self):
        slot,_=self.diagnostic_slot();calls=[]
        slot.auth.command=lambda *args,**kwargs:calls.append(args)
        with self.assertRaisesRegex(ValueError,'app stop/process exit unknown') as rejected:slot.stop(remove=False)
        self.assertEqual(rejected.exception.restore_reason_code,'APP_STOP_EXIT_NOT_CONFIRMED')
        self.assertEqual(slot.stopped['exitCode'],137);self.assertFalse(slot.stopped['running'])
        self.assertEqual(calls,[])

    def test_safe_log_read_checks_exact_original_owner_before_observing(self):
        slot,obj=self.diagnostic_slot();obj['NetworkSettings']['Networks'][slot.name]['NetworkID']='6'*64
        with patch.object(R.subprocess,'run') as command:
            with self.assertRaisesRegex(ValueError,'exact diagnostic app owner'):slot.diagnostic_summary()
        command.assert_not_called()

    def test_log_summary_is_inside_original_cid_and_no_cid_is_not_absence_proof(self):
        slot,_=self.diagnostic_slot()
        with patch.object(R.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'Application run failed private-token')) as command:
            result=slot.diagnostic_summary()
        self.assertEqual(command.call_args.args[0],['docker','logs','--tail','160','4'*64])
        self.assertEqual(result['state']['ExitCode'],137);self.assertEqual(result['result'],'EXACT_APP_OBSERVED')
        self.assertNotIn('private-token',json.dumps(result))
        slot.cid=None
        self.assertEqual(slot.diagnostic_summary(),{'result':'NO_CURRENT_CID_OBSERVATION','startAbsenceProven':False})



class RestoreHttpGracefulStopControls(unittest.TestCase):
    """Controlled proc/Docker results only. No Java, Docker, signals, SQL or backup is executed."""

    @staticmethod
    def proc_bytes(ticks=b'123'):
        argv=[b'java', b'-Xmx512m', ('-Dloader.main='+R.MAIN).encode(),
              b'-Dloader.path=/restore-http-testclasses', b'-cp', b'/application.jar',
              b'org.springframework.boot.loader.launch.PropertiesLauncher']
        # stat field 3 starts at index0 after ') '; kernel starttime (22) is index19.
        fields=[b'S', b'0']+[b'0']*17+[ticks]
        return b'\x00'.join(argv)+b'\x00'+b'1 (java) '+b' '.join(fields)+b'\n'

    def running_slot(self, directory, exit_code=143):
        slot=object.__new__(R.ApplicationSlot);slot.name='cy-auth-'+'a'*32;slot.cid='4'*64;slot.stop_history=[]
        slot.source={'networkId':'5'*64};slot.root=Path(directory);slot.schema='cy_e2e_auth_'+'a'*32
        slot.jar=slot.root/'application.jar';slot.jar.write_bytes(b'controlled original jar bytes')
        slot.jar_hash=R.hashlib.sha256(slot.jar.read_bytes()).hexdigest();slot.manifest_hash='b'*64
        boot=dict(appCid=slot.cid,javaPid=1,bootId='original-boot',sourceSha='a'*40,
                  jarSha256=slot.jar_hash,schema=slot.schema,restoreMode='false',serverUuid='11111111-1111-1111-1111-111111111111')
        config={key:boot[key] for key in ('sourceSha','jarSha256','schema','restoreMode','serverUuid')}
        config.update(classManifestSha256=slot.manifest_hash,scope=slot.name[8:])
        (slot.root/'ready.json').write_text(json.dumps(boot));(slot.root/'config.json').write_text(json.dumps(config))
        obj={'Id':slot.cid,'Name':'/'+slot.name+'-app',
             'Config':{'Image':R.JDK,'Entrypoint':['/trace/strace'],'Cmd':['-D','-f'],
                       'Labels':{'chengyin.auth-recovery':slot.name},
                       'Env':['GITHUB_SHA='+'a'*40,'CHENGYIN_RESTORE_MODE=false']},
             'NetworkSettings':{'Networks':{slot.name:{'NetworkID':'5'*64}}},
             'State':{'Running':True,'OOMKilled':False,'Restarting':False,'Pid':1234,'ExitCode':0}}
        calls=[];data={'proc':self.proc_bytes(),'exit':exit_code,'error':None}
        def command(argv, *args, **kwargs):
            calls.append((argv,kwargs))
            if argv==['docker','exec',slot.cid,'cat','/proc/1/cmdline','/proc/1/stat']:return data['proc']
            if argv==['docker','stop','--time','20',slot.cid]:
                if data['error'] is not None:raise data['error']
                obj['State'].update(Running=False,Pid=0,ExitCode=data['exit']);return b'controlled-stop'
            if argv==['docker','rm',slot.cid]:return b'controlled-remove'
            raise AssertionError('unexpected command')
        slot.auth=SimpleNamespace(DOCKER=['docker'],command=command);slot.inspect=lambda target:obj
        slot.absence=lambda:calls.append(('absence',{}));slot.boots=[boot];slot.removed=[];slot.owned=[slot.cid]
        slot.launch={**{key:boot[key] for key in ('sourceSha','jarSha256','schema','restoreMode','serverUuid')},
                     'cid':slot.cid,'configSha256':R.hashlib.sha256((slot.root/'config.json').read_bytes()).hexdigest()}
        slot.pending_process=None;slot.ready_for_current_launch=True;slot.startup_observation=None
        slot.read_complete_application_log=lambda:b'Application run failed private-log-canary\n'
        with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
            slot.pending_process=slot.running_launch_identity(obj)
            slot.process=slot.running_child_identity(boot,obj)
        calls.clear()
        return slot,obj,calls,data

    def test_daemonized_tracer_keeps_original_budget_and_real_shutdown_contract(self):
        source=(ROOT/'ci/restore_application_http_runtime.py').read_text()
        self.assertIn("'--entrypoint','/trace/strace',JDK,'-D','-f'",source)
        self.assertIn("['stop','--time','20',self.cid],timeout=30",source)
        self.assertIn("stopped['ExitCode'] in (0,143)",source)
        self.assertNotIn("stopped['ExitCode'] in (0,137,143)",source)
        child=(ROOT/'chengyinhub-admin/src/test/java/com/chengyinhub/business/it/RestoreHttpApplicationChild.java').read_text()
        self.assertIn('--spring.quartz.wait-for-jobs-to-complete-on-shutdown=true',child)
        self.assertIn('application.registerShutdownHook()',child)
        self.assertIn('org.springframework.beans.DirectFieldAccessor(factory)',child)
        self.assertIn('.getPropertyValue("waitForJobsToCompleteOnShutdown")',child)

    def test_proc_proof_is_fixed_to_original_java_pid1_not_a_pid_selector(self):
        proof=R.pid1_jvm_proof(self.proc_bytes())
        self.assertEqual(proof['startTicks'],'123')
        for value in (b'', self.proc_bytes().replace(b'1 (java)',b'9 (java)'),
                      self.proc_bytes().replace(b'PropertiesLauncher',b'WrongLauncher'),
                      self.proc_bytes().replace(b'loader.main=',b'loader.wrong='),
                      self.proc_bytes(ticks=b'not-a-tick')):
            with self.subTest(raw_length=len(value)):
                with self.assertRaises(ValueError):R.pid1_jvm_proof(value)

    def test_exact_original_stop_accepts_only_graceful_exit_and_preserves_log_slot(self):
        for exit_code in (0,143):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as directory:
                slot,obj,calls,_=self.running_slot(directory,exit_code)
                with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):slot.stop(remove=False)
                self.assertEqual([argv for argv,_ in calls],
                        [['docker','exec','4'*64,'cat','/proc/1/cmdline','/proc/1/stat'],
                         ['docker','stop','--time','20','4'*64]])
                self.assertEqual(slot.stopped['exitCode'],exit_code);self.assertEqual(slot.stopped['hostPid'],0)
                self.assertEqual(slot.cid,'4'*64);self.assertEqual(slot.removed,[])
                self.assertFalse(obj['State']['Running'])

    def test_exact_stop_removes_only_its_original_cid_after_graceful_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,calls,_=self.running_slot(directory,0)
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):slot.stop()
            self.assertEqual([argv for argv,_ in calls],
                    [['docker','exec','4'*64,'cat','/proc/1/cmdline','/proc/1/stat'],
                     ['docker','stop','--time','20','4'*64],['docker','rm','4'*64],'absence'])
            self.assertEqual(slot.removed,['4'*64]);self.assertIsNone(slot.cid);self.assertIsNone(slot.process)

    def test_changed_cid_owner_boot_source_jar_or_proc_never_receives_stop(self):
        mutations=[
            lambda s,o,d:o.update(Id='6'*64),
            lambda s,o,d:o['Config']['Labels'].update({'chengyin.auth-recovery':'another-owner'}),
            lambda s,o,d:o['NetworkSettings']['Networks'][s.name].update(NetworkID='6'*64),
            lambda s,o,d:s.boots[-1].update(bootId='different-boot'),
            lambda s,o,d:s.boots[-1].update(sourceSha='b'*40),
            lambda s,o,d:s.boots[-1].update(javaPid=9),
            lambda s,o,d:s.jar.write_bytes(b'changed jar'),
            lambda s,o,d:d.update(proc=self.proc_bytes(ticks=b'124')),
            lambda s,o,d:o['State'].update(Pid=1235),
            lambda s,o,d:o['State'].update(Restarting=True),
            lambda s,o,d:o['State'].update(OOMKilled=True),
            lambda s,o,d:s.boots[-1].update(javaPid=True),
            lambda s,o,d:o['Config'].update(Cmd=['-f']),
            lambda s,o,d:(s.root/'ready.json').write_text((s.root/'ready.json').read_text()+' '),
            lambda s,o,d:(s.root/'config.json').write_text((s.root/'config.json').read_text()+' '),
        ]
        for change in mutations:
            with self.subTest(change=mutations.index(change)), tempfile.TemporaryDirectory() as directory:
                slot,obj,calls,data=self.running_slot(directory);change(slot,obj,data)
                with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
                    with self.assertRaises(ValueError):slot.stop(remove=False)
                self.assertFalse(any(isinstance(argv,list) and argv[1]=='stop' for argv,_ in calls))
                self.assertEqual(slot.removed,[]);self.assertEqual(slot.cid,'4'*64)

    def test_forced_exit_or_timeout_remains_unknown_without_removal(self):
        for result in (137,'timeout'):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as directory:
                slot,_,calls,data=self.running_slot(directory,137)
                error=R.subprocess.TimeoutExpired('controlled-stop',30)
                if result=='timeout':data['error']=error
                with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
                    if result=='timeout':
                        with self.assertRaises(R.subprocess.TimeoutExpired) as caught:slot.stop()
                        self.assertIs(caught.exception,error);self.assertTrue(slot.stopped['running'])
                    else:
                        with self.assertRaisesRegex(ValueError,'app stop/process exit unknown') as caught:slot.stop()
                        self.assertEqual(caught.exception.restore_reason_code,'APP_STOP_EXIT_NOT_CONFIRMED')
                        self.assertEqual(slot.stopped['exitCode'],137)
                self.assertFalse(any(isinstance(argv,list) and argv[1]=='rm' for argv,_ in calls))
                self.assertEqual(slot.removed,[])

    def test_source_stop_failure_prevents_quiescent_sql_snapshot_and_propagates_original_error(self):
        report={'result':'NOT_COMPLETED'};saved=[];failure=ValueError('controlled stop failure')
        def stop(**kwargs):
            self.assertEqual(kwargs,{'remove':False});raise failure
        slot=SimpleNamespace(stop=stop,ready_for_current_launch=True,boots=[{'serverUuid':'11111111-1111-1111-1111-111111111111'}],runtime_report_write=lambda path,value:saved.append(copy.deepcopy(value)))
        with patch.object(P,'snapshot') as read:
            with self.assertRaises(ValueError) as caught:P.source_quiescent_snapshot(slot,object(),3306,'owned',report,Path('unused'))
        self.assertIs(caught.exception,failure);read.assert_not_called()
        self.assertEqual(report['substage'],'SOURCE_QUIESCENT_STOP');self.assertEqual(report['result'],'NOT_COMPLETED')
        self.assertEqual(len(saved),1)

    def test_source_snapshot_uses_original_database_arguments_only_after_stop(self):
        events=[];report={'result':'NOT_COMPLETED'};auth=object();rows=object()
        slot=SimpleNamespace(stop=lambda **kwargs:events.append('stop') if kwargs=={'remove':False} else self.fail('original source must retain CID'),ready_for_current_launch=True,boots=[{'serverUuid':'11111111-1111-1111-1111-111111111111'}],
                runtime_report_write=lambda path,value:events.append(value['substage']))
        def read(*args):
            self.assertEqual(events,['SOURCE_QUIESCENT_STOP','stop','QUIESCENT_SQL_SNAPSHOT'])
            self.assertEqual(args[:3],(auth,3306,'original_schema'));self.assertEqual(args[3],'11111111-1111-1111-1111-111111111111');events.append('snapshot');return rows
        with patch.object(P,'snapshot',side_effect=read):
            self.assertIs(P.source_quiescent_snapshot(slot,auth,3306,'original_schema',report,Path('unused')),rows)
        self.assertEqual(report['result'],'NOT_COMPLETED')



class RestoreHttpStartupFailureControls(unittest.TestCase):
    """SOURCE only: exact private typed observations and proc/stop simulations, no real app or DB."""

    @staticmethod
    def startup(slot, stage='QUARTZ_FACTORY', status='FAILED', reason='ASSERTION_REJECTED'):
        value={key:slot.launch[key] for key in ('sourceSha','jarSha256','schema','restoreMode')}
        value.update(classManifestSha256=slot.manifest_hash,scope=slot.name[8:],
                     bootId='11111111-2222-3333-4444-555555555555',javaPid=1,stage=stage,status=status,
                     reasonCode=reason,failureType='ILLEGAL_STATE' if status=='FAILED' else 'NONE')
        (slot.root/'startup.json').write_text(json.dumps(value))
        return value

    def test_failed_startup_returns_only_fixed_stage_reason_and_original_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory)
            expected=self.startup(slot)
            self.assertEqual(slot.read_startup_observation(),expected)
            self.assertEqual(slot.startup_observation['stage'],'QUARTZ_FACTORY')
            self.assertEqual(slot.startup_observation['sourceSha'],slot.launch['sourceSha'])
            for secret in ('tokenSecret','jdbcUrl','message','cause','stack'):
                self.assertNotIn(secret,slot.startup_observation)

    def test_foreign_unbounded_or_unclassified_observation_is_not_public_evidence(self):
        for mutation in ('source','jar','manifest','scope','pid','stage','reason','type','status','raw','size','misreported-size','symlink'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory);value=self.startup(slot)
                if mutation=='source':value['sourceSha']='b'*40
                elif mutation=='jar':value['jarSha256']='b'*64
                elif mutation=='manifest':value['classManifestSha256']='c'*64
                elif mutation=='scope':value['scope']='c'*32
                elif mutation=='pid':value['javaPid']=True
                elif mutation=='stage':value['stage']='private-config-or-SQL'
                elif mutation=='reason':value['reasonCode']='private-message'
                elif mutation=='type':value['failureType']='private-cause'
                elif mutation=='status':value['status']='READY'
                elif mutation=='raw':value['message']='private-token-stack-canary'
                path=slot.root/'startup.json';path.write_text(json.dumps(value))
                if mutation=='size':path.write_text('x'*4097)
                elif mutation=='symlink':
                    path.unlink();path.symlink_to(slot.root/'config.json')
                if mutation=='misreported-size':
                    path.write_text(json.dumps(value)+' '*5000)
                    with patch.object(Path,'stat',return_value=SimpleNamespace(st_size=1,st_mode=0o100600)):
                        with self.assertRaises(ValueError):slot.read_startup_observation()
                else:
                    with self.assertRaises(ValueError):slot.read_startup_observation()
                self.assertIsNone(slot.startup_observation)

    def test_child_failure_is_seen_before_timeout_without_inventing_a_ready_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,calls,_=RestoreHttpGracefulStopControls().running_slot(directory)
            self.startup(slot);(slot.root/'ready.json').unlink()
            slot.boots=[];slot.process=None;slot.ready_for_current_launch=False;slot.pending_process=None
            slot.budget=lambda:None
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}), patch.object(R.time,'sleep') as sleep:
                with self.assertRaisesRegex(ValueError,'child startup rejected') as caught:slot.wait_for_ready()
            self.assertEqual(caught.exception.restore_reason_code,'APP_CHILD_STARTUP_REJECTED')
            sleep.assert_not_called();self.assertEqual(slot.boots,[])
            self.assertIsNotNone(slot.pending_process);self.assertFalse(slot.ready_for_current_launch)
            self.assertFalse(any(argv[1]=='stop' for argv,_ in calls))

    def test_unready_owned_jvm_can_stop_with_original_pre_ready_proof_but_is_not_a_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory,143)
            slot.boots=[];slot.process=None;slot.ready_for_current_launch=False
            (slot.root/'ready.json').unlink();self.startup(slot)
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):slot.stop(remove=False)
            self.assertEqual(slot.stop_proof_kind,'PRE_READY_JVM_EXIT_CONFIRMED')
            self.assertEqual(slot.stopped['exitCode'],143);self.assertEqual(slot.boots,[])
            self.assertFalse(slot.ready_for_current_launch);self.assertEqual(slot.removed,[])

    def test_pre_ready_changed_process_or_missing_capture_never_receives_stop(self):
        for mutation in ('startticks','hostpid','config','jar','source','missing'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                slot,obj,calls,data=RestoreHttpGracefulStopControls().running_slot(directory)
                slot.boots=[];slot.process=None;slot.ready_for_current_launch=False
                if mutation=='startticks':data['proc']=RestoreHttpGracefulStopControls.proc_bytes(ticks=b'124')
                elif mutation=='hostpid':obj['State']['Pid']=999
                elif mutation=='config':(slot.root/'config.json').write_text('{}')
                elif mutation=='jar':slot.jar.write_bytes(b'wrong original jar')
                elif mutation=='source':slot.launch['sourceSha']='b'*40
                else:slot.pending_process=None
                with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
                    with self.assertRaises(ValueError):slot.stop(remove=False)
                self.assertFalse(any(argv[1]=='stop' for argv,_ in calls));self.assertEqual(slot.removed,[])

    def test_pre_ready_137_and_no_captured_jvm_remain_unknown(self):
        for captured,exit_code in ((True,137),(False,0)):
            with self.subTest(captured=captured,exit_code=exit_code), tempfile.TemporaryDirectory() as directory:
                slot,obj,calls,_=RestoreHttpGracefulStopControls().running_slot(directory,exit_code)
                slot.boots=[];slot.process=None;slot.ready_for_current_launch=False
                if not captured:
                    slot.pending_process=None;obj['State'].update(Running=False,Pid=0,ExitCode=exit_code)
                with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
                    with self.assertRaises(ValueError):slot.stop()
                self.assertEqual(slot.stopped['exitCode'],exit_code);self.assertEqual(slot.removed,[])
                self.assertFalse(any(isinstance(argv,list) and argv[1]=='rm' for argv,_ in calls))

    def test_startup_progress_or_missing_ready_stays_bounded_and_never_becomes_a_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory)
            self.startup(slot,'SCHEDULER_LEADERSHIP','PROGRESS','NONE')
            (slot.root/'ready.json').unlink();slot.boots=[];slot.ready_for_current_launch=False
            slot.budget=lambda:None
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}), patch.object(R.time,'monotonic',side_effect=[0,0,181]), patch.object(R.time,'sleep') as sleep:
                with self.assertRaises(ValueError) as caught:slot.wait_for_ready()
            self.assertEqual(caught.exception.restore_reason_code,'APP_READINESS_TIMEOUT')
            sleep.assert_called_once_with(1);self.assertEqual(slot.boots,[])
            self.assertEqual(slot.startup_observation['stage'],'SCHEDULER_LEADERSHIP')

    def test_ready_file_alone_is_not_an_admission_and_cannot_trigger_source_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory)
            self.startup(slot,'QUARTZ_FACTORY','PROGRESS','NONE')
            slot.ready_for_current_launch=False;slot.budget=lambda:None
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}), patch.object(R.time,'monotonic',side_effect=[0,0,181]), patch.object(R.time,'sleep'):
                with self.assertRaises(ValueError):slot.wait_for_ready()
            report={'result':'NOT_COMPLETED'}
            slot.runtime_report_write=lambda *args:None
            with patch.object(P,'snapshot') as read, patch.object(slot,'stop') as stop:
                with self.assertRaisesRegex(ValueError,'must be ready'):P.source_quiescent_snapshot(slot,object(),3306,'owned',report,Path('unused'))
            stop.assert_not_called();read.assert_not_called()
            self.assertEqual(report['result'],'NOT_COMPLETED')



class RestoreHttpCanonicalComparisonControls(unittest.TestCase):
    """Controlled complete typed rows/allocators and fixed UUID readbacks, never MySQL acceptance."""
    schema='cy_e2e_auth_'+'a'*32
    source_uuid='11111111-1111-1111-1111-111111111111'
    target_uuid='22222222-2222-2222-2222-222222222222'

    def fixture(self):
        before={'member_cash_withdraw':{'rows':[{'id':1,'amount':{'decimal':'30.00'},
                        'wire':'private-original-wire-canary','status':1}],'nextId':2}}
        report=dict(sourceSha='a'*40,runId='31',runAttempt='1',jarSha256='b'*64,
                schema=self.schema,sourceServerUuid=self.source_uuid,targetServerUuid=self.target_uuid,
                phase='CANONICAL_BACKUP_RESTORE',substage='RESTORED_SQL_COMPARE',
                result='NOT_COMPLETED',productionDispatchRelease=False,backupToolReturned=True,restoreToolReturned=True,
                canonicalBackupEvidence=dict(canonicalToolSha256=P.CANONICAL_TOOL_SHA256,
                        dumpSha256='c'*64,receiptSha256='d'*64))
        saved=[]
        slot=SimpleNamespace(stop_history=[dict(cid='4'*64,proofKind='READY_JVM_EXIT_CONFIRMED',
                bootId='original-source-boot',sourceSha='a'*40,jarSha256='b'*64,
                stopped=dict(cid='4'*64,running=False,hostPid=0,oomKilled=False,exitCode=143,restarting=False))],
                runtime_report_write=lambda path,value:saved.append(copy.deepcopy(value)))
        return before,report,slot,saved

    def readback(self,auth,port,schema,sql,args=None):
        self.assertEqual(schema,self.schema);self.assertEqual(sql,'SELECT @@server_uuid AS uuid,DATABASE() AS db')
        self.assertIsNone(args)
        return [dict(uuid=self.source_uuid if port==3306 else self.target_uuid,db=self.schema)]

    def rejected(self,before,after,report,slot,readback=None):
        with patch.object(P,'query',side_effect=readback or self.readback):
            with self.assertRaisesRegex(ValueError,'canonical restore and independent complete rows/high waters') as caught:
                P.verify_restored_snapshot(before,after,slot,object(),3306,3307,self.schema,report,Path('unused'))
        self.assertEqual(caught.exception.restore_reason_code,'INVARIANT_REJECTED')
        self.assertEqual(report['result'],'NOT_COMPLETED');self.assertFalse(report['productionDispatchRelease'])
        return caught.exception

    def test_full_equal_rows_types_and_high_waters_do_not_trigger_diagnostic_reads_or_writes(self):
        before,report,slot,saved=self.fixture();after=copy.deepcopy(before)
        with patch.object(P,'query') as read:
            P.verify_restored_snapshot(before,after,slot,object(),3306,3307,self.schema,report,Path('unused'))
        read.assert_not_called();self.assertEqual(saved,[])
        self.assertEqual(before,after);self.assertNotIn('canonicalRestoreComparison',report)

    def test_actual_row_drift_is_rejected_without_repair_and_only_safe_hashes_are_published(self):
        before,report,slot,saved=self.fixture();after=copy.deepcopy(before)
        after['member_cash_withdraw']['rows'][0]['amount']={'decimal':'31.00'}
        original_before=copy.deepcopy(before);original_after=copy.deepcopy(after)
        self.rejected(before,after,report,slot)
        result=report['canonicalRestoreComparison'];row=result['tables'][0]
        self.assertEqual(row['table'],'member_cash_withdraw');self.assertEqual(row['categories'],['TYPED_ROWS_DIFFER'])
        self.assertEqual(row['source']['rowCount'],1);self.assertEqual(row['target']['rowCount'],1)
        self.assertNotEqual(row['source']['rowSha256'],row['target']['rowSha256'])
        self.assertEqual(result['binding']['identityResult'],'MATCH')
        self.assertEqual(result['binding']['sourceStop']['stopped']['exitCode'],143)
        self.assertEqual(result['binding']['canonicalToolSha256'],P.CANONICAL_TOOL_SHA256)
        self.assertTrue(result['binding']['backupReturned']);self.assertTrue(result['binding']['restoreReturned'])
        self.assertEqual(result['differenceCause'],'UNKNOWN')
        self.assertNotIn('private-original-wire-canary',json.dumps(saved))
        self.assertNotIn('SELECT ',json.dumps(saved));self.assertNotIn('"rows"',json.dumps(saved))
        self.assertEqual(before,original_before);self.assertEqual(after,original_after)

    def test_allocator_regression_advance_null_and_type_coercion_are_all_rejected(self):
        for value in (1,3,None,False):
            with self.subTest(nextId=value):
                before,report,slot,_=self.fixture();after=copy.deepcopy(before)
                after['member_cash_withdraw']['nextId']=value
                self.rejected(before,after,report,slot)
                row=report['canonicalRestoreComparison']['tables'][0]
                self.assertEqual(row['categories'],['NEXT_ID_DIFFER'])
                self.assertEqual(row['source']['rowSha256'],row['target']['rowSha256'])
        before,report,slot,_=self.fixture();before['member_cash_withdraw']['nextId']=0
        after=copy.deepcopy(before);after['member_cash_withdraw']['nextId']=False
        self.assertEqual(before,after) # Python == alone is weaker than the original typed encoding contract.
        self.rejected(before,after,report,slot)
        self.assertEqual(report['canonicalRestoreComparison']['tables'][0]['target']['nextId']['kind'],'BOOLEAN')

    def test_exact_row_types_and_binary_decimal_float_temporal_encodings_are_not_normalized_away(self):
        changes=[(0,False),({'decimal':'30.00'},{'decimal':'30.0'}),
                 ({'binary':'0001'},{'binary':'0002'}),
                 ({'floatHex':'-0x0.0p+0'},{'floatHex':'0x0.0p+0'}),
                 ({'temporal':'2026-10-25T00:00:00'},{'temporal':'2026-10-25T00:00:01'})]
        for old,new in changes:
            with self.subTest(old=old,new=new):
                before,report,slot,_=self.fixture();before['member_cash_withdraw']['rows'][0]['typed']=old
                after=copy.deepcopy(before);after['member_cash_withdraw']['rows'][0]['typed']=new
                self.rejected(before,after,report,slot)
                self.assertIn('TYPED_ROWS_DIFFER',report['canonicalRestoreComparison']['tables'][0]['categories'])

    def test_missing_or_extra_table_is_rejected_and_unknown_target_name_is_hash_only(self):
        before,report,slot,_=self.fixture();after={}
        self.rejected(before,after,report,slot)
        self.assertEqual(report['canonicalRestoreComparison']['tables'][0]['categories'],['MISSING_TARGET_TABLE'])
        before,report,slot,_=self.fixture();after=copy.deepcopy(before)
        after['private_unapproved_target_canary']={'rows':[],'nextId':None}
        self.rejected(before,after,report,slot)
        row=report['canonicalRestoreComparison']['tables'][0]
        self.assertEqual(row['categories'],['UNEXPECTED_TARGET_TABLE']);self.assertNotIn('table',row)
        self.assertNotIn('private_unapproved_target_canary',json.dumps(report))

    def test_detail_cap_never_truncates_comparison_or_accepts_late_table_drift(self):
        before,report,slot,_=self.fixture()
        before={('fixed_'+str(i).zfill(3)):{'rows':[],'nextId':1} for i in range(40)}
        after=copy.deepcopy(before)
        for row in after.values():row['nextId']=2
        self.rejected(before,after,report,slot)
        result=report['canonicalRestoreComparison']
        self.assertEqual(result['changedTableCount'],40);self.assertEqual(len(result['tables']),32)
        self.assertTrue(result['detailsTruncated'])
        after=copy.deepcopy(before);after['fixed_039']['nextId']=2
        before_hash=P.sha(P.canonical(before));self.rejected(before,after,report,slot)
        self.assertEqual(report['canonicalRestoreComparison']['sourceSnapshotSha256'],before_hash)
        self.assertEqual(report['canonicalRestoreComparison']['tables'][0]['table'],'fixed_039')

    def test_uuid_or_readback_failure_does_not_replace_the_original_compare_failure(self):
        for kind in ('wrong-uuid','database-error'):
            with self.subTest(kind=kind):
                before,report,slot,_=self.fixture();after=copy.deepcopy(before);after['member_cash_withdraw']['nextId']=3
                def observe(auth,port,schema,sql,args=None):
                    if kind=='database-error':raise OSError('private-SQL-password-canary')
                    return [dict(uuid='33333333-3333-3333-3333-333333333333',db=schema)]
                self.rejected(before,after,report,slot,observe)
                binding=report['canonicalRestoreComparison']['binding']
                self.assertEqual(binding['identityResult'],'MISMATCH' if kind=='wrong-uuid' else 'UNKNOWN')
                self.assertNotIn('private-SQL-password-canary',json.dumps(report))

    def test_diagnostic_write_failure_keeps_primary_valueerror_and_does_not_claim_pass(self):
        before,report,slot,_=self.fixture();after=copy.deepcopy(before);after['member_cash_withdraw']['nextId']=3
        def fail_write(*args):raise OSError('private-report-path-token-canary')
        slot.runtime_report_write=fail_write
        self.rejected(before,after,report,slot)
        self.assertEqual(report['canonicalComparisonDiagnosticFailure']['type'],'OSError')
        self.assertNotIn('private-report-path-token-canary',json.dumps(report))


class RestoreHttpStoppedLogControls(unittest.TestCase):
    """Real slot methods with owned transport bytes/files only; no Docker/Java/DB is executed."""

    def test_source_snapshot_retains_stopped_original_cid_and_exact_complete_log_until_next_boot(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,calls,_=RestoreHttpGracefulStopControls().running_slot(directory)
            report={'result':'NOT_COMPLETED'};slot.runtime_report_write=lambda *args:None
            rows=object()
            def read(*args):
                self.assertEqual(slot.cid,'4'*64);self.assertEqual(len(slot.stop_history),1)
                self.assertTrue(slot.private_log_path(slot.cid).is_file())
                self.assertFalse(any(isinstance(argv,list) and argv[1]=='rm' for argv,_ in calls))
                return rows
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}),patch.object(P,'snapshot',side_effect=read):
                self.assertIs(P.source_quiescent_snapshot(slot,object(),3306,'owned',report,Path('unused')),rows)
            self.assertEqual(slot.stop_history[0]['proofKind'],'READY_JVM_EXIT_CONFIRMED')
            self.assertEqual(slot.stop_history[0]['stopped']['exitCode'],143)
            self.assertNotIn('private-log-canary',json.dumps(slot.stop_history))

    def test_repeated_stop_captures_once_then_verifies_before_exact_removal_and_keeps_history(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,calls,_=RestoreHttpGracefulStopControls().running_slot(directory)
            captures=[];slot.read_complete_application_log=lambda:captures.append('read') or b'private-log-canary'
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
                slot.stop(remove=False);slot.stop(remove=False)
                original_identity=dict(slot.process);slot.process={**slot.process,'startTicks':'124'}
                with self.assertRaises(ValueError):slot.stop(remove=False)
                slot.process=original_identity;slot.stop()
            self.assertEqual(captures,['read']);self.assertEqual(len(slot.stop_history),1)
            self.assertEqual(sum(isinstance(argv,list) and argv[1]=='stop' for argv,_ in calls),1)
            self.assertEqual(sum(isinstance(argv,list) and argv[1]=='rm' for argv,_ in calls),1)
            self.assertEqual(slot.removed,['4'*64]);self.assertIsNone(slot.cid)
            diagnostic=slot.diagnostic_summary()
            self.assertEqual(diagnostic['lastStoppedEvidence']['cid'],'4'*64)
            self.assertFalse(diagnostic['startAbsenceProven'])
            self.assertNotIn('private-log-canary',json.dumps(diagnostic))
            slot.verify_all_stopped_logs()

    def test_capture_failure_or_changed_private_archive_prevents_removal_and_erasure_proof(self):
        for kind in ('capture-failure','archive-drift','missing-archive'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                slot,_,calls,_=RestoreHttpGracefulStopControls().running_slot(directory)
                with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}):
                    if kind=='capture-failure':
                        def unavailable():raise ValueError('controlled original log unavailable')
                        slot.read_complete_application_log=unavailable
                    else:
                        slot.stop(remove=False)
                        path=slot.private_log_path(slot.cid)
                        if kind=='archive-drift':path.write_bytes(b'wrong retained bytes')
                        else:path.unlink()
                    with self.assertRaises(ValueError):slot.stop()
                self.assertFalse(any(isinstance(argv,list) and argv[1]=='rm' for argv,_ in calls))
                self.assertEqual(slot.removed,[]);self.assertEqual(slot.stopped['exitCode'],143)
                with self.assertRaises(ValueError):slot.verify_all_stopped_logs()

    def test_complete_log_capture_keeps_stdout_and_stderr_private_and_rejects_exit_or_size_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory)
            with patch.object(R.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout=b'out',stderr=b'error')) as read:
                self.assertEqual(R.ApplicationSlot.read_complete_application_log(slot),b'outerror')
            self.assertEqual(read.call_args.args[0],['docker','logs','4'*64]);self.assertEqual(read.call_args.kwargs['timeout'],20)
            for value in (SimpleNamespace(returncode=1,stdout=b'',stderr=b'private-error'),
                          SimpleNamespace(returncode=0,stdout=b'x'*(8*1024*1024+1),stderr=b'')):
                with patch.object(R.subprocess,'run',return_value=value):
                    with self.assertRaises(ValueError):R.ApplicationSlot.read_complete_application_log(slot)

    def test_lifecycle_rejects_missing_foreign_unavailable_or_preready_log_proof(self):
        fixture=RestoreHttpFailureLifecycleControls()
        for kind in ('missing','foreign','unavailable','preready','137','private-erasure'):
            with self.subTest(kind=kind):
                report=fixture.success_report()
                if kind=='missing':report['applicationStopHistory'].pop()
                elif kind=='foreign':report['applicationStopHistory'][0]['cid']='9'*64
                elif kind=='unavailable':report['applicationStopHistory'][0]['log']['result']='UNAVAILABLE'
                elif kind=='preready':report['applicationStopHistory'][0]['proofKind']='PRE_READY_JVM_EXIT_CONFIRMED'
                elif kind=='137':report['applicationStopHistory'][0]['stopped']['exitCode']=137
                else:report['applicationLogPrivateErasure']='UNKNOWN'
                with self.assertRaises(ValueError):P.assert_successful_lifecycle(report)

    def test_original_failure_with_complete_resource_absence_and_unavailable_owner_log_stays_rejected(self):
        fixture=RestoreHttpFailureLifecycleControls()
        report=fixture.success_report();report['result']='NOT_COMPLETED'
        report['firstFailureType']='ValueError';report['firstFailurePhase']='CANONICAL_BACKUP_RESTORE'
        report['phase']='CANONICAL_BACKUP_RESTORE'
        proof,failure,_=fixture.finalization(report,fixture.owner('8'*64,'UNAVAILABLE'),original_exit=1)
        self.assertIsNotNone(failure);self.assertEqual(proof['result'],'NOT_ACCEPTED')
        self.assertEqual(proof['resourceCleanup'],'CONFIRMED_EXACT_OWNED_REMOVAL')
        self.assertIn({'phase':'APPLICATION_LOG_UNAVAILABLE','type':'ValueError'},proof['finalizationFailures'])



class RestoreHttpCurrentAllocatorControls(unittest.TestCase):
    """Per-connection driver double: cached metadata is not current-MySQL runtime evidence."""
    schema='cy_e2e_auth_'+'a'*32
    uuid='11111111-1111-1111-1111-111111111111'

    def driver(self):
        owner=self
        class Auth:
            sessions=[];ignore_set=False;actual_uuid=owner.uuid;actual_schema=owner.schema
            expiry_override=None;current=211;cached=2;actual_autocommit=1
            table_names=None;rows={};row_error=None;fail_table=None
            @classmethod
            @contextmanager
            def database(cls,port,schema,autocommit=True):
                session=SimpleNamespace(id=len(cls.sessions),expiry=86400,schema=schema,events=[],
                                        requested_autocommit=autocommit,closed=False)
                class Cursor:
                    description=(('id',),)
                    def execute(self,sql,args=None):
                        session.events.append((sql,args))
                        if sql=='SET SESSION information_schema_stats_expiry=0':
                            if not cls.ignore_set:session.expiry=0
                        elif sql=='SELECT @@SESSION.information_schema_stats_expiry,@@server_uuid,DATABASE()':
                            self.identity=(session.expiry if cls.expiry_override is None else cls.expiry_override,
                                           cls.actual_uuid,cls.actual_schema)
                        elif sql.startswith('SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES'):
                            owner.assertEqual(args,(owner.schema,))
                            self.description=(('TABLE_NAME',),('AUTO_INCREMENT',))
                            self.metadata=True
                        elif sql=='SELECT @@SESSION.autocommit':
                            self.identity=(cls.actual_autocommit,)
                        else:
                            owner.assertTrue(sql.startswith('SELECT * FROM '));owner.assertIsNone(args)
                            name=sql[len('SELECT * FROM `'):-1]
                            if name==cls.fail_table:raise cls.row_error
                            rows=cls.rows.get(name,[])
                            columns=tuple(rows[0]) if rows else ('id',)
                            self.description=tuple((column,) for column in columns)
                            self.values=tuple(tuple(row[column] for column in columns) for row in rows)
                            self.metadata=False
                    def fetchone(self):return self.identity
                    def fetchall(self):
                        if getattr(self,'metadata',False):
                            return tuple((name,(cls.current if session.expiry==0 else cls.cached)
                                    if name=='member_cash_withdraw' else None)
                                    for name in sorted(P.REQUIRED_TABLES if cls.table_names is None else cls.table_names))
                        return self.values
                session.cursor=lambda:nullcontext(Cursor())
                cls.sessions.append(session)
                try:yield session
                finally:session.closed=True
        return Auth

    def test_set_effective_zero_identity_and_metadata_use_one_exact_connection(self):
        auth=self.driver()
        value=P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertEqual(value['member_cash_withdraw']['nextId'],211)
        self.assertEqual(set(value),P.REQUIRED_TABLES)
        metadata_sessions=[session for session in auth.sessions if any(
                sql.startswith('SELECT TABLE_NAME,AUTO_INCREMENT') for sql,_ in session.events)]
        self.assertEqual(len(metadata_sessions),1)
        first=metadata_sessions[0]
        self.assertEqual([sql for sql,_ in first.events[:2]],
                ['SET SESSION information_schema_stats_expiry=0',
                 'SELECT @@SESSION.information_schema_stats_expiry,@@server_uuid,DATABASE()'])
        self.assertTrue(first.events[2][0].startswith('SELECT TABLE_NAME,AUTO_INCREMENT'))
        self.assertEqual(first.expiry,0)
        self.assertTrue(all(not sql.startswith(('SET GLOBAL','ANALYZE','UPDATE','INSERT','ALTER'))
                for session in auth.sessions for sql,_ in session.events))

    def test_set_on_another_session_is_not_accepted_as_effective_zero(self):
        auth=self.driver()
        with auth.database(3306,self.schema) as other,other.cursor() as cur:
            cur.execute('SET SESSION information_schema_stats_expiry=0')
        self.assertEqual(auth.sessions[0].expiry,0)
        auth.ignore_set=True
        with self.assertRaisesRegex(ValueError,'same-session current allocator metadata identity'):
            P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertEqual(auth.sessions[1].expiry,86400)
        self.assertFalse(any(sql.startswith('SELECT TABLE_NAME,AUTO_INCREMENT')
                for sql,_ in auth.sessions[1].events))
        # A later legitimate sampler must set and verify its own fresh connection too.
        auth.ignore_set=False
        self.assertEqual(P.snapshot(auth,3306,self.schema,self.uuid)['member_cash_withdraw']['nextId'],211)

    def test_cached_or_wrong_identity_or_noninteger_zero_cannot_read_allocator_metadata(self):
        for kind in ('wrong-uuid','wrong-schema','cached','boolean-zero','string-zero'):
            with self.subTest(kind=kind):
                auth=self.driver()
                if kind=='wrong-uuid':auth.actual_uuid='22222222-2222-2222-2222-222222222222'
                elif kind=='wrong-schema':auth.actual_schema='foreign-schema'
                elif kind=='cached':auth.ignore_set=True
                elif kind=='boolean-zero':auth.expiry_override=False
                else:auth.expiry_override='0'
                with self.assertRaisesRegex(ValueError,'same-session current allocator metadata identity'):
                    P.snapshot(auth,3306,self.schema,self.uuid)
                self.assertFalse(any(sql.startswith('SELECT TABLE_NAME,AUTO_INCREMENT')
                        for session in auth.sessions for sql,_ in session.events))

    def test_fresh_null_zero_or_changed_allocator_is_preserved_and_strict_compare_still_rejects_drift(self):
        for current in (None,0,212):
            with self.subTest(current=current):
                auth=self.driver();auth.current=current
                rows=P.snapshot(auth,3306,self.schema,self.uuid)
                if current is None:self.assertIsNone(rows['member_cash_withdraw']['nextId'])
                else:self.assertEqual(rows['member_cash_withdraw']['nextId'],current)
                before=copy.deepcopy(rows);before['member_cash_withdraw']['nextId']=211
                difference=P.restore_difference_summary(before,rows)
                self.assertEqual(difference['tables'][0]['categories'],['NEXT_ID_DIFFER'])
                controls=RestoreHttpCanonicalComparisonControls()
                _,report,slot,_=controls.fixture()
                controls.rejected(before,rows,report,slot)
                changed=copy.deepcopy(rows);changed['member_cash_withdraw']['rows']=[{'id':1,'status':1}]
                controls.rejected(rows,changed,report,slot)
                self.assertIn('TYPED_ROWS_DIFFER',report['canonicalRestoreComparison']['tables'][0]['categories'])



class RestoreHttpHeldProtocolControls(unittest.TestCase):
    """Protocol/direct-owner controls only. No server, scheduler, DB or economic fact is executed."""
    def envelope(self,mode='true'):
        boot={'bootId':'boot','guardHeld':True,'restoreMode':mode,
              'serverUuid':'11111111-1111-1111-1111-111111111111','schema':'cy_e2e_auth_'+'a'*32}
        data={'bootId':boot['bootId'],'held':True,'serverUuid':boot['serverUuid'],'schema':boot['schema'],
              'events':events(),'verifiedFacts':[],'inbox':[]}
        return boot,(200,{'code':200,'msg':'original operation','data':data})

    def jobs(self):
        return {'beforeStartNotifyTask.run()':101,'committedOfficialNoticeTask.deliverPending()':102}

    def test_good_keeps_full_original_envelope_and_never_implicitly_unwraps_or_requires_data(self):
        boot,response=self.envelope()
        self.assertIs(P.good(response),response[1])
        self.assertIs(P.evidence_payload(response,boot),response[1]['data'])
        self.assertIs(P.good((200,{'code':200})).get('data'),None)
        _,_,callback=late()
        self.assertIs(P.callback_envelope(P.good((200,callback))),callback)
        self.assertEqual(callback['data']['factId'],1)

    def test_each_original_held_mode_keeps_owner_quartz_wait_fact_replay_and_identity_guards(self):
        self.assertEqual(P.HELD_MODES,('true','invalid',''))
        for mode in P.HELD_MODES:
            with self.subTest(mode=mode):
                boot,response=self.envelope(mode)
                data=P.evidence_payload(response,boot);self.assertTrue(P.assert_owner_events(data['events'],boot['bootId']))
                jobs=P.held_jobs({'jobs':self.jobs()})
                logs=[{'invoke_target':target,'status':'0'} for target in jobs]
                P.assert_quartz_completion(logs,0,jobs)
                observation=P.held_observation(data,logs,boot,0,jobs)
                self.assertEqual(observation['mode'],mode);self.assertEqual(observation['completedTargetCount'],2)
                before,after,callback=late()
                value=P.callback_envelope(P.good((200,callback)))
                P.assert_late_callback(before,after,value,8)
                data['verifiedFacts']=[{'id':1,'fact_hash':'a'*64}]
                data['inbox']=[{'id':1,'biz_id':8,'state':0}]
                readback=P.evidence_payload(response,boot)
                self.assertEqual(readback['verifiedFacts'][0]['id'],value['data']['factId'])
                self.assertEqual(readback['verifiedFacts'][0]['fact_hash'],value['data']['factHash'])
                self.assertEqual(readback['inbox'][0]['state'],0)
                P.assert_callback_replay(after,copy.deepcopy(after))
                self.assertEqual(P.callback_envelope(P.good((200,copy.deepcopy(callback)))),callback)

    def test_missing_layer_or_tree_bean_shape_is_rejected_with_fixed_presence_types_not_keyerror_defaults(self):
        for kind in ('missing-data','double-data','missing-events','tree-event','events-object'):
            with self.subTest(kind=kind):
                boot,response=self.envelope();body=response[1];data=body['data']
                if kind=='missing-data':del body['data']
                elif kind=='double-data':body['data']={'data':data}
                elif kind=='missing-events':del data['events']
                elif kind=='tree-event':data['events']=[{'nodeType':'OBJECT','private-token-key':'private-canary-value'}]
                else:data['events']={}
                with self.assertRaises(P.FixtureContractRejected) as caught:P.evidence_payload(response,boot)
                diagnostic=P.failure_diagnostic(caught.exception,'RESTORED_HELD_WORKLOAD','HELD_QUARTZ_OBSERVE')
                self.assertEqual(diagnostic['reasonCode'],'HTTP_EVIDENCE_CONTRACT_REJECTED')
                self.assertNotIn('private-token-key',json.dumps(diagnostic))
                self.assertNotIn('private-canary-value',json.dumps(diagnostic))
                if kind=='tree-event':
                    self.assertEqual(diagnostic['contract']['recordIndex'],0)
                    fields={row['field']:row for row in diagnostic['contract']['fields']}
                    self.assertFalse(fields['bootId']['present']);self.assertEqual(fields['bootId']['type'],'MISSING')

    def test_foreign_boot_mode_schema_or_uuid_cannot_supply_another_instances_evidence(self):
        for key,value in (('bootId','foreign-boot'),('held',False),('schema','foreign-schema'),('serverUuid','foreign-uuid')):
            with self.subTest(key=key):
                boot,response=self.envelope();response[1]['data'][key]=value
                with patch.object(P,'event_records') as consume:
                    with self.assertRaises(P.FixtureContractRejected) as caught:P.evidence_payload(response,boot)
                consume.assert_not_called()
                self.assertFalse(caught.exception.observation['identityMatches'][key])
                if isinstance(value,str):self.assertNotIn(value,json.dumps(caught.exception.observation))

    def test_callback_requires_original_typed_fact_identity_and_retains_fail_outcome_rule(self):
        for key,value in (('factId',None),('factId',True),('factId','1'),('factHash','private-hash-canary')):
            with self.subTest(key=key,value=value):
                _,_,callback=late();callback['data'][key]=value
                with self.assertRaises(P.FixtureContractRejected):P.callback_envelope(P.good((200,callback)))
        before,after,callback=late();callback['data']['originalOutcome']='SUCCESS'
        P.callback_envelope(callback) # Shape is distinct from the original paused business result.
        with self.assertRaisesRegex(ValueError,'original callback must remain paused'):
            P.assert_late_callback(before,after,callback,8)

    def test_event_contract_does_not_replace_original_blocked_owner_and_pending_selector_guards(self):
        value=events();value[1]['blocked']=False;value[1]['errorType']='java.lang.IllegalStateException'
        self.assertIs(P.event_records(value),value)
        with self.assertRaisesRegex(ValueError,'actual owner did not throw real Blocked'):P.assert_owner_events(value,'boot')
        value=events()+[{'bootId':'boot','symbol':'com.chengyinhub.business.mapper.CommittedOfficialNoticeWxMapper.selectPendingPage',
                         'stage':'SQL','held':True}]
        with self.assertRaisesRegex(ValueError,'notice recovery guard did not pause'):P.assert_owner_events(value,'boot')
        value=events();value[1]['blocked']='true'
        with self.assertRaises(P.FixtureContractRejected):P.assert_owner_events(value,'boot')
        value=events()+[{'bootId':'boot','symbol':'com.example.Mapper.insert!selectKey','stage':'SQL','held':True}]
        self.assertIs(P.event_records(value),value)
        proven=P.assert_owner_events(value,'boot')
        self.assertEqual(len(proven),6);self.assertNotIn('com.example.Mapper.insert!selectKey',[row['symbol'] for row in proven])

    def test_quartz_ack_or_failure_duplicate_target_or_stale_rows_do_not_prove_two_real_completions(self):
        jobs=self.jobs();success=[{'invoke_target':target,'status':'0'} for target in jobs]
        P.assert_quartz_completion(success,0,jobs)
        for rows,baseline in (([],0),(success,2),([success[0],success[0]],0),
                              ([{**row,'status':'1'} for row in success],0)):
            with self.subTest(rows=rows,baseline=baseline):
                with self.assertRaisesRegex(ValueError,'real persisted Quartz task completion proof'):
                    P.assert_quartz_completion(rows,baseline,jobs)
        for rows in ([{'status':'0'}],[{'invoke_target':'beforeStartNotifyTask.run()','status':0}]):
            with self.assertRaises(P.FixtureContractRejected):P.assert_quartz_completion(rows,0,jobs)

    def test_private_seed_job_ids_must_match_the_two_actual_original_targets_without_defaults(self):
        value={'jobs':self.jobs()};self.assertIs(P.held_jobs(value),value['jobs'])
        for kind in ('missing','duplicate-id','wrong-target','boolean-id'):
            invalid={'jobs':self.jobs()}
            if kind=='missing':del invalid['jobs']
            elif kind=='duplicate-id':invalid['jobs']['beforeStartNotifyTask.run()']=102
            elif kind=='wrong-target':invalid['jobs']['private-target-canary']=invalid['jobs'].pop('beforeStartNotifyTask.run()')
            else:invalid['jobs']['beforeStartNotifyTask.run()']=True
            with self.assertRaises(P.FixtureContractRejected) as caught:P.held_jobs(invalid)
            self.assertNotIn('private-target-canary',json.dumps(caught.exception.observation))

    def test_unclassified_keyerror_keeps_only_real_reviewed_source_frame_not_key_message_or_private_path(self):
        class Cursor:
            description=(('n',),)
            def execute(self,*args):pass
            def fetchall(self):raise KeyError('private-SQL-token-key-canary')
            def fetchone(self):raise KeyError('private-SQL-token-key-canary')
        class Database:
            def cursor(self):return nullcontext(Cursor())
        auth=SimpleNamespace(database=lambda *args,**kwargs:nullcontext(Database()))
        try:P.snapshot(auth,3306,'owned','11111111-1111-1111-1111-111111111111')
        except KeyError as failure:
            diagnostic=P.failure_diagnostic(failure,'RESTORED_HELD_WORKLOAD','HELD_QUARTZ_OBSERVE')
        else:self.fail('original KeyError was swallowed')
        self.assertEqual(diagnostic['type'],'KeyError');self.assertEqual(diagnostic['reasonCode'],'UNCLASSIFIED_FAILURE')
        frame=diagnostic['sourceFrame']
        self.assertEqual(frame['path'],'ci/restore_application_http_probe.py');self.assertGreater(frame['line'],0)
        self.assertEqual(frame['sourceSha256'],P.sha((ROOT/frame['path']).read_bytes()))
        self.assertNotIn('private-SQL-token-key-canary',json.dumps(diagnostic))
        self.assertNotIn('SELECT controlled',json.dumps(diagnostic));self.assertNotIn(str(ROOT),json.dumps(diagnostic))
        self.assertNotIn('SET SESSION ',json.dumps(diagnostic));self.assertNotIn('SELECT @@SESSION',json.dumps(diagnostic))

    def test_three_real_ready_observations_never_replace_five_completed_original_workloads(self):
        fixture=RestoreHttpFailureLifecycleControls();report=fixture.success_report()
        report['readyBootObservations']=(report['completeBoots']+report['boots'])[:3]
        report['completeBoots']=[];report['boots']=[]
        with self.assertRaisesRegex(ValueError,'five independent original app boots'):
            P.assert_successful_lifecycle(report)

    def test_verified_fact_and_inbox_column_names_and_scalar_types_remain_strict(self):
        for kind in ('fact-column','fact-id-bool','fact-hash-bytes','inbox-column','inbox-state-bool'):
            boot,response=self.envelope();data=response[1]['data']
            data['verifiedFacts']=[{'id':1,'fact_hash':'a'*64}];data['inbox']=[{'id':1,'biz_id':8,'state':0}]
            if kind=='fact-column':data['verifiedFacts'][0]['hash']=data['verifiedFacts'][0].pop('fact_hash')
            elif kind=='fact-id-bool':data['verifiedFacts'][0]['id']=True
            elif kind=='fact-hash-bytes':data['verifiedFacts'][0]['fact_hash']=b'private-hash'
            elif kind=='inbox-column':del data['inbox'][0]['biz_id']
            else:data['inbox'][0]['state']=False
            with self.subTest(kind=kind):
                with self.assertRaises(P.FixtureContractRejected):P.evidence_payload(response,boot)

    def test_received_http200_or_missing_data_is_not_successful_evidence_and_does_not_echo_error_text(self):
        boot,_=self.envelope()
        for response in ((200,{'code':500,'msg':'private-server-body'}),
                         (403,{'code':403,'msg':'private-authorization-body'}),
                         (200,{'code':True,'data':{}}),(200,['private-unexpected-array'])):
            with self.assertRaises(ValueError) as caught:P.evidence_payload(response,boot)
            self.assertEqual(P.failure_diagnostic(caught.exception,'RESTORED_HELD_WORKLOAD',
                    'HELD_QUARTZ_OBSERVE')['reasonCode'],'HTTP_OPERATION_REJECTED')
            self.assertNotIn('private',json.dumps(P.failure_diagnostic(caught.exception,
                    'RESTORED_HELD_WORKLOAD','HELD_QUARTZ_OBSERVE')))



class RestoreHttpMultiBootHandoffControls(unittest.TestCase):
    """Original slot methods on controlled Docker/proc/child bytes only; no JVM/DB/network runs."""
    class Driver:
        def __init__(self,directory):
            self.slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory)
            slot=self.slot
            slot.cid=None;slot.boots=[];slot.owned=[];slot.removed=[];slot.stop_history=[]
            slot.process=None;slot.pending_process=None;slot.launch=None
            slot.ready_for_current_launch=False;slot.startup_observation=None;slot.start_transition=None
            slot.source={'id':'1'*64,'ip':'172.20.0.2','networkId':'5'*64}
            slot.target={'id':'2'*64,'ip':'172.20.0.3','networkId':'5'*64}
            slot.redis_ip='172.20.0.4';slot.token_secret='controlled'
            slot.classes=slot.root/'classes';slot.classes.mkdir()
            slot.out=slot.root/'out';(slot.out/'trace-libs').mkdir(parents=True)
            slot.deadline=1500
            self.now=0;self.objects={};self.current=None;self.events=[];self.configs=[];self.endpoint_fault=False
            self.ready_fault=False;self.fail_log=False;self.fail_remove=False
            original_auth=module('restore_http_multiboot_original_absence','authenticated_redis_recovery_probe.py')
            slot.auth=SimpleNamespace(DOCKER=['docker'],command=self.command,
                    mysql_endpoint=self.endpoint,require_absent=original_auth.require_absent)
            # Restore real slot methods replaced only by the old single-stop fixture.
            del slot.inspect;del slot.absence;del slot.read_complete_application_log

        def endpoint(self,name,suffix):
            if name!=self.slot.name or suffix not in ('mysql','restore-mysql'):raise AssertionError('foreign controlled DB endpoint')
            result=dict(self.slot.source if suffix=='mysql' else self.slot.target)
            if self.endpoint_fault:result['id']='9'*64
            return result

        def command(self,argv,**kwargs):
            slot=self.slot
            if argv[:2]==['docker','inspect']:
                target=argv[2]
                cid=self.current if target==slot.name+'-app' else target
                if cid not in self.objects:raise AssertionError('unknown controlled CID')
                return json.dumps([self.objects[cid]]).encode()
            if argv[:2]==['docker','run']:
                if self.current is not None:raise AssertionError('previous original slot not removed')
                self.assert_old_files_absent()
                config=json.loads((slot.root/'config.json').read_text());self.configs.append(copy.deepcopy(config))
                index=len(self.configs);cid=format(100+index,'064x')
                mode=config['restoreMode'];env=[argv[i+1] for i,part in enumerate(argv[:-1]) if part=='-e']
                if 'CHENGYIN_RESTORE_MODE='+mode not in env:raise AssertionError('real mode environment missing')
                command=argv[argv.index(R.JDK)+1:]
                obj={'Id':cid,'Name':'/'+slot.name+'-app',
                     'Config':{'Image':R.JDK,'Entrypoint':['/trace/strace'],'Cmd':command,
                               'Labels':{'chengyin.auth-recovery':slot.name},'Env':env},
                     'HostConfig':{'Memory':1073741824,'NanoCpus':1000000000,'PortBindings':{}},
                     'NetworkSettings':{'Networks':{slot.name:{'NetworkID':'5'*64}}},
                     'State':{'Running':True,'OOMKilled':False,'Restarting':False,'Pid':1000+index,'ExitCode':0}}
                self.objects[cid]=obj;self.current=cid
                boot_id=str(uuid.UUID(int=index))
                ready={key:config[key] for key in ('sourceSha','jarSha256','schema','restoreMode','serverUuid')}
                ready.update(bootId=boot_id,javaPid=1,guardHeld=mode!='false',schedulerStarted=True,port=8080)
                startup={key:config[key] for key in ('sourceSha','jarSha256','classManifestSha256','scope','schema','restoreMode')}
                startup.update(bootId=boot_id,javaPid=1,stage='READY',status='READY',reasonCode='NONE',failureType='NONE')
                if self.ready_fault:ready['bootId']=str(uuid.UUID(int=999))
                R.write_private(slot.root/'ready.json',ready);R.write_private(slot.root/'startup.json',startup)
                self.events.append(('create',cid,mode));return cid.encode()
            if argv[:2]==['docker','exec']:
                if argv[2]!=self.current or argv[3:]!=['cat','/proc/1/cmdline','/proc/1/stat']:
                    raise AssertionError('unexpected proc target')
                return RestoreHttpGracefulStopControls.proc_bytes(str(2000+len(self.configs)).encode())
            if argv[:2]==['docker','stop']:
                if argv!=['docker','stop','--time','20',self.current] or kwargs.get('timeout')!=30:
                    raise AssertionError('original stop budget changed')
                self.objects[self.current]['State'].update(Running=False,Pid=0,ExitCode=143)
                self.events.append(('stop',self.current));return b'controlled-stop'
            if argv[:2]==['docker','rm']:
                cid=argv[2]
                if self.fail_remove:raise RuntimeError('controlled exact removal unavailable')
                if cid!=self.current or not slot.stop_history or slot.stop_history[-1]['cid']!=cid:
                    raise AssertionError('capture must precede original removal')
                slot.verify_private_log(slot.stop_history[-1])
                self.events.append(('remove',cid));del self.objects[cid];self.current=None
                return b'controlled-remove'
            raise AssertionError('unexpected Docker command')

        def assert_old_files_absent(self):
            if any((self.slot.root/name).exists() for name in ('ready.json','startup.json','callback-diagnostic.json','callback-diagnostic.next')):
                raise AssertionError('old ready/startup was reused before new create')

        def subprocess(self,argv,**kwargs):
            if argv[:3]==['docker','container','inspect']:
                target=argv[-1];self.events.append(('absence',target))
                if self.current is None:
                    return SimpleNamespace(returncode=1,stderr=('Error: No such container: '+target).encode())
                return SimpleNamespace(returncode=0,stderr=b'')
            if argv[:2]==['docker','logs']:
                cid=argv[-1]
                if cid not in self.objects:raise AssertionError('foreign log CID')
                self.events.append(('log',cid))
                if self.fail_log:return SimpleNamespace(returncode=1,stdout=b'',stderr=b'controlled failure')
                return SimpleNamespace(returncode=0,stdout=b'Application run failed private-controlled-log',stderr=b'controlled-stderr')
            raise AssertionError('unexpected subprocess')

        @contextmanager
        def active(self):
            with patch.dict(R.os.environ,{'GITHUB_SHA':'a'*40}),patch.object(R.time,'monotonic',side_effect=lambda:self.now),patch.object(
                    R.time,'sleep',side_effect=AssertionError('unexpected readiness wait')),patch.object(
                    R.shutil,'which',return_value='/controlled/strace'),patch.object(R.subprocess,'run',side_effect=self.subprocess):
                yield self

        def begin(self,side,mode,seed=False):
            value='11111111-1111-1111-1111-111111111111' if side=='source' else '22222222-2222-2222-2222-222222222222'
            return self.slot.start(side,mode,value,seed=seed)

        def first_three(self):
            self.begin('source','false',True);self.slot.stop(remove=False)
            self.begin('target','false');self.slot.stop(remove=False)
            self.begin('target','true');self.slot.stop(remove=False)

    def test_all_five_original_modes_reuse_exact_slot_and_keep_each_log_before_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            driver=self.Driver(directory);slot=driver.slot
            with driver.active():
                for index,(side,mode) in enumerate((('source','false'),('target','false'),('target','true'),
                                                     ('target','invalid'),('target',''))):
                    boot=driver.begin(side,mode,index==0)
                    self.assertEqual(boot['restoreMode'],mode);self.assertIs(boot['guardHeld'],mode!='false')
                    self.assertEqual(slot.start_transition['stage'],'READY')
                    slot.stop(remove=False)
                slot.verify_all_stopped_logs()
            self.assertEqual([row['restoreMode'] for row in slot.boots],['false','false','true','invalid',''])
            self.assertEqual(len({row['appCid'] for row in slot.boots}),5)
            self.assertEqual(slot.removed,slot.owned[:-1]);self.assertEqual(slot.cid,slot.owned[-1])
            self.assertEqual(len(slot.stop_history),5)
            self.assertEqual([row['restoreMode'] for row in driver.configs],['false','false','true','invalid',''])
            self.assertEqual([row['seed'] for row in driver.configs],[True,False,False,False,False])
            for cid in slot.removed:
                first_log=next(i for i,event in enumerate(driver.events) if event==('log',cid))
                removed=next(i for i,event in enumerate(driver.events) if event==('remove',cid))
                self.assertLess(first_log,removed)
            self.assertEqual(slot.stopped['exitCode'],143)
            self.assertEqual(slot.start_transition['mode'],'')
            self.assertNotIn('private-controlled-log',json.dumps(slot.stop_history))

    def test_expired_original_budget_before_invalid_mode_preserves_previous_true_and_has_outer_guard_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            driver=self.Driver(directory);slot=driver.slot
            with driver.active():
                driver.first_three();previous=slot.cid;prior_events=list(driver.events)
                driver.now=1500
                try:driver.begin('target','invalid')
                except ValueError as failure:
                    diagnostic=P.failure_diagnostic(failure,'RESTORED_HELD_APPLICATION_START','APPLICATION_SLOT_START')
                else:self.fail('expired original lifecycle budget was accepted')
                summary=slot.diagnostic_summary()
            self.assertEqual(driver.events[:len(prior_events)],prior_events)
            self.assertEqual(len([event for event in driver.events if event[0]=='create']),3)
            self.assertEqual(slot.cid,previous);self.assertEqual(slot.start_transition['stage'],'BUDGET')
            self.assertEqual(slot.start_transition['mode'],'invalid')
            self.assertEqual(diagnostic['reasonCode'],'APP_LIFECYCLE_BUDGET_EXHAUSTED')
            frame=diagnostic['sourceFrame'];self.assertEqual(frame['path'],'ci/restore_application_http_runtime.py')
            lines=(ROOT/frame['path']).read_text().splitlines()
            self.assertEqual(lines[frame['line']-1].strip(),'self.budget()')
            self.assertEqual(summary['startTransition']['stage'],'BUDGET')
            self.assertEqual(summary['lastStoppedEvidence']['cid'],previous)
            self.assertEqual(len(slot.boots),3);self.assertEqual(slot.stopped['exitCode'],143)

    def test_prior_log_or_identity_drift_stops_handoff_without_foreign_removal_or_new_boot(self):
        for kind in ('log','config','ready','identity'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                driver=self.Driver(directory);slot=driver.slot
                with driver.active():
                    driver.first_three();previous=slot.cid
                    if kind=='log':slot.private_log_path(previous).write_bytes(b'changed private log')
                    elif kind=='config':(slot.root/'config.json').write_text('{}')
                    elif kind=='ready':(slot.root/'ready.json').write_text('{}')
                    else:slot.process={**slot.process,'startTicks':'999'}
                    with self.assertRaises(ValueError):driver.begin('target','invalid')
                self.assertEqual(slot.cid,previous);self.assertEqual(len(slot.boots),3)
                self.assertEqual(slot.removed,slot.owned[:2]);self.assertEqual(len(driver.configs),3)
                self.assertEqual(slot.start_transition['stage'],'PREVIOUS_STOP_AND_LOG')

    def test_endpoint_change_or_stale_config_next_keeps_stopped_history_and_cannot_create_invalid_boot(self):
        for kind in ('endpoint','config-next'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                driver=self.Driver(directory);slot=driver.slot
                with driver.active():
                    driver.first_three();previous=slot.cid
                    if kind=='endpoint':driver.endpoint_fault=True
                    else:(slot.root/'config.json.next').write_text('private-failed-write-canary')
                    with self.assertRaises(ValueError if kind=='endpoint' else FileExistsError) as caught:
                        driver.begin('target','invalid')
                    diagnostic=P.failure_diagnostic(caught.exception,'RESTORED_HELD_APPLICATION_START','APPLICATION_SLOT_START')
                    summary=slot.diagnostic_summary();slot.verify_all_stopped_logs()
                self.assertIsNone(slot.cid);self.assertIn(previous,slot.removed);self.assertEqual(len(slot.boots),3)
                self.assertEqual(len(driver.configs),3);self.assertTrue(slot.start_transition['previousRemovalConfirmed'])
                self.assertEqual(slot.start_transition['stage'],'DATABASE_IDENTITY' if kind=='endpoint' else 'CONFIGURATION')
                self.assertEqual(summary['lastStoppedEvidence']['cid'],previous)
                self.assertNotIn('private-failed-write-canary',json.dumps(diagnostic))

    def test_failed_invalid_or_empty_ready_never_becomes_a_completed_boot_or_quiescent_snapshot(self):
        for mode in ('invalid',''):
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as directory:
                driver=self.Driver(directory);slot=driver.slot
                with driver.active():
                    driver.first_three();driver.ready_fault=True
                    with self.assertRaises(ValueError):driver.begin('target',mode)
                    new_cid=slot.cid;self.assertEqual(slot.start_transition['mode'],mode)
                    self.assertEqual(slot.start_transition['stage'],'READINESS')
                    self.assertEqual(len(slot.boots),3);self.assertFalse(slot.ready_for_current_launch)
                    slot.stop(remove=False)
                    self.assertEqual(slot.stop_history[-1]['cid'],new_cid)
                    self.assertEqual(slot.stop_history[-1]['proofKind'],'PRE_READY_JVM_EXIT_CONFIRMED')
                    slot.runtime_report_write=lambda *args:None
                    with patch.object(P,'snapshot') as read:
                        with self.assertRaisesRegex(ValueError,'must be ready'):
                            P.source_quiescent_snapshot(slot,object(),3306,'owned',{'result':'NOT_COMPLETED'},Path('unused'))
                    read.assert_not_called()
                self.assertEqual(len(slot.stop_history),4);self.assertEqual(slot.stopped['exitCode'],143)

    def test_remove_failure_retains_original_cid_capture_and_cannot_advance_to_invalid_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            driver=self.Driver(directory);slot=driver.slot
            with driver.active():
                driver.first_three();previous=slot.cid;driver.fail_remove=True
                with self.assertRaises(RuntimeError):driver.begin('target','invalid')
                slot.verify_all_stopped_logs()
            self.assertEqual(slot.cid,previous);self.assertEqual(slot.start_transition['stage'],'PREVIOUS_STOP_AND_LOG')
            self.assertEqual(len(slot.boots),3);self.assertEqual(slot.removed,slot.owned[:2])
            self.assertEqual(slot.stop_history[-1]['cid'],previous)
            self.assertEqual(len([event for event in driver.events if event[0]=='create']),3)

    def test_one_post_stop_full_snapshot_supplies_same_protected_rows_and_all_original_allocators(self):
        _,stable,_=late();completed=copy.deepcopy(stable)
        completed['wx_verified_payment_fact']['nextId']=7
        completed['wx_payment_callback_inbox']['nextId']=8
        original=copy.deepcopy(completed);auth=object()
        expected_uuid='22222222-2222-2222-2222-222222222222'
        with patch.object(P,'snapshot',return_value=completed) as read:
            result=P.completed_held_rows(auth,3307,'owned',expected_uuid,stable)
        read.assert_called_once_with(auth,3307,'owned',expected_uuid)
        self.assertEqual(result['protectedRowsAfter'],P.public_snapshot(completed))
        self.assertEqual(set(result['callbackReplayHighWaters']),P.LATE_TABLES)
        for table in P.LATE_TABLES:
            self.assertEqual(result['callbackReplayHighWaters'][table],
                    {'before':stable[table]['nextId'],'after':completed[table]['nextId']})
        self.assertEqual(completed,original)
        with patch.object(P,'snapshot',return_value={}) as read:
            with self.assertRaises(KeyError):P.completed_held_rows(auth,3307,'owned',expected_uuid,stable)
        read.assert_called_once()

    def test_http_value_helper_frame_is_skipped_for_the_actual_reviewed_evidence_caller(self):
        boot,_=RestoreHttpHeldProtocolControls().envelope()
        try:P.evidence_payload((200,{'code':503,'msg':'private-body-canary'}),boot)
        except ValueError as failure:
            diagnostic=P.failure_diagnostic(failure,'RESTORED_HELD_WORKLOAD','HELD_QUARTZ_OBSERVE')
        else:self.fail('original rejected HTTP operation was accepted')
        self.assertEqual(diagnostic['reasonCode'],'HTTP_OPERATION_REJECTED')
        frame=diagnostic['sourceFrame'];self.assertEqual(frame['path'],'ci/restore_application_http_probe.py')
        self.assertEqual((ROOT/frame['path']).read_text().splitlines()[frame['line']-1].strip(),'value=good(response)')
        self.assertNotIn('private-body-canary',json.dumps(diagnostic))



class RestoreHttpCallbackReplayObservationControls(unittest.TestCase):
    """Actual runtime observer/HTTP parser with controlled child document and curl bytes only."""
    def slot(self,directory):
        slot,_,_,_=RestoreHttpGracefulStopControls().running_slot(directory)
        slot.budget=lambda:None
        return slot

    def document(self,slot,sequence=1,status='THREW',stage='REGISTRATION_CALLBACK'):
        value={key:slot.launch[key] for key in ('sourceSha','jarSha256','schema','serverUuid')}
        value.update(classManifestSha256=slot.manifest_hash,scope=slot.name[8:],
                bootId=slot.boots[-1]['bootId'],javaPid=1,operation='VERIFIED_CALLBACK',sequence=sequence,
                status=status,stage=stage,reasonCode='UNCLASSIFIED_FAILURE' if status=='THREW' else 'NONE',
                outcome='FAIL' if status=='RETURNED' else 'NONE',
                causeTypes=['DATA_ACCESS','SQL'] if status=='THREW' else [],
                origin={'class':'com.chengyinhub.business.service.impl.WxPaymentCallbackInboxServiceImpl','line':123}
                       if status=='THREW' else None,
                sqlState='23000' if status=='THREW' else None,sqlErrorCode=1062 if status=='THREW' else None)
        (slot.root/'callback-diagnostic.json').write_text(json.dumps(value))
        return value

    def test_real_bounded_document_binds_original_boot_scope_and_fixed_stage_type_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            slot=self.slot(directory);value=self.document(slot)
            self.assertEqual(slot.read_callback_observation(),value)
            self.assertEqual(value['stage'],'REGISTRATION_CALLBACK')
            self.assertEqual(value['reasonCode'],'UNCLASSIFIED_FAILURE')
            self.assertEqual(value['sqlState'],'23000');self.assertEqual(value['sqlErrorCode'],1062)
            self.assertNotIn('message',value);self.assertNotIn('stack',value);self.assertNotIn('cause',value)

    def test_foreign_unbounded_unclassified_raw_or_wrong_scalar_document_is_unknown_not_a_business_result(self):
        for kind in ('source','jar','schema','uuid','boot','sequence','pid','stage','type','reason','origin','sql','raw','size','symlink'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                slot=self.slot(directory);value=self.document(slot)
                if kind=='source':value['sourceSha']='b'*40
                elif kind=='jar':value['jarSha256']='b'*64
                elif kind=='schema':value['schema']='foreign'
                elif kind=='uuid':value['serverUuid']='foreign'
                elif kind=='boot':value['bootId']='foreign'
                elif kind=='sequence':value['sequence']=True
                elif kind=='pid':value['javaPid']=True
                elif kind=='stage':value['stage']='private-stage-canary'
                elif kind=='type':value['causeTypes']=['private-type-canary']
                elif kind=='reason':value['reasonCode']='private-reason-canary'
                elif kind=='origin':value['origin']={'class':'private-class-canary','line':1}
                elif kind=='sql':value['sqlState']='private-SQL-canary'
                elif kind=='raw':value['message']='private-payload-canary'
                path=slot.root/'callback-diagnostic.json';path.write_text(json.dumps(value))
                if kind=='size':path.write_text('x'*4097)
                elif kind=='symlink':path.unlink();path.symlink_to(slot.root/'config.json')
                result=slot.callback_observation()
                self.assertEqual(result,{'result':'UNKNOWN','reason':'PRIVATE_OBSERVATION_REJECTED'})
                self.assertNotIn('private-',json.dumps(result))

    def test_received_business500_stays_failure_and_fresh_controller_cause_is_observed_without_rawbody(self):
        with tempfile.TemporaryDirectory() as directory:
            slot=self.slot(directory)
            def transport(*args,**kwargs):
                self.document(slot,2)
                return b'{"code":500,"msg":"private-response-canary"}\n200'
            slot.auth.command=transport
            response=slot.http('/__restore_http/verified-callback','controlled',{})
            self.assertEqual((response[0],response[1]['code']),(200,500))
            with self.assertRaises(ValueError) as caught:P.good(response)
            self.assertEqual(caught.exception.restore_reason_code,'HTTP_OPERATION_REJECTED')
            observed=slot.last_callback_observation
            self.assertIs(observed['newControllerExecution'],True)
            self.assertEqual(observed['after']['value']['stage'],'REGISTRATION_CALLBACK')
            self.assertEqual(observed['after']['value']['causeTypes'],['DATA_ACCESS','SQL'])
            self.assertNotIn('private-response-canary',json.dumps(slot.last_http)+json.dumps(observed))

    def test_unchanged_prior_returned_diagnostic_cannot_explain_new_business500(self):
        with tempfile.TemporaryDirectory() as directory:
            slot=self.slot(directory);self.document(slot,1,'RETURNED','RESPONSE')
            slot.auth.command=lambda *args,**kwargs:b'{"code":500}\n200'
            response=slot.http('/__restore_http/verified-callback','controlled',{})
            self.assertIs(slot.last_callback_observation['newControllerExecution'],False)
            self.assertEqual(slot.last_callback_observation['after']['value']['status'],'RETURNED')
            with self.assertRaises(ValueError):P.good(response)

    def test_transport_exception_is_preserved_while_entered_controller_is_never_an_ack(self):
        with tempfile.TemporaryDirectory() as directory:
            slot=self.slot(directory);original=RuntimeError('private-transport-canary')
            def transport(*args,**kwargs):
                self.document(slot,1,'ENTERED','REGISTRATION_CALLBACK');raise original
            slot.auth.command=transport
            with self.assertRaises(RuntimeError) as caught:slot.http('/__restore_http/verified-callback','controlled',{})
            self.assertIs(caught.exception,original)
            self.assertIs(slot.last_callback_observation['newControllerExecution'],True)
            self.assertEqual(slot.last_callback_observation['after']['value']['status'],'ENTERED')
            self.assertEqual(slot.last_http['result'],'ATTEMPTED')
            self.assertNotIn('private-transport-canary',json.dumps(slot.last_callback_observation))

    def test_unreadable_prior_observation_keeps_correlation_unknown_even_if_current_document_is_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            slot=self.slot(directory);(slot.root/'callback-diagnostic.json').write_text('private-truncated-canary')
            def transport(*args,**kwargs):
                self.document(slot,2)
                return b'{"code":500}\n200'
            slot.auth.command=transport
            response=slot.http('/__restore_http/verified-callback','controlled',{})
            self.assertEqual(slot.last_callback_observation['newControllerExecution'],'UNKNOWN')
            with self.assertRaises(ValueError):P.good(response)
            self.assertNotIn('private-truncated-canary',json.dumps(slot.last_callback_observation))

    def test_new_boot_resets_only_old_diagnostic_files_and_preserves_the_original_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            driver=RestoreHttpMultiBootHandoffControls.Driver(directory);slot=driver.slot
            with driver.active():
                driver.first_three()
                receipt=slot.root/'callback-reference.json';receipt.write_bytes(b'controlled-original-reference')
                (slot.root/'callback-diagnostic.json').write_bytes(b'old-observation')
                (slot.root/'callback-diagnostic.next').write_bytes(b'old-partial-observation')
                driver.begin('target','invalid')
                self.assertFalse((slot.root/'callback-diagnostic.json').exists())
                self.assertFalse((slot.root/'callback-diagnostic.next').exists())
                self.assertEqual(receipt.read_bytes(),b'controlled-original-reference')
                self.assertEqual(slot.start_transition['stage'],'READY')
                slot.stop(remove=False);slot.verify_all_stopped_logs()


class RestoreHttpLifecycleBudgetCostControls(unittest.TestCase):
    """SOURCE controls on original sampler/checkpoint/budget methods; no MySQL or timing claim."""
    schema=RestoreHttpCurrentAllocatorControls.schema
    uuid=RestoreHttpCurrentAllocatorControls.uuid

    def test_complete_418_table_observation_uses_one_new_autocommit_connection_and_all_original_rows(self):
        auth=RestoreHttpCurrentAllocatorControls().driver()
        protected=set(P.REQUIRED_TABLES)
        protected.update('restore_fixture_table_'+str(index).zfill(4) for index in range(418-len(protected)))
        auth.table_names=protected|P.ALLOWED_MUTABLE
        auth.rows={'ums_member':[{'id':2,'balance':Decimal('2.00'),'binary':b'\x00\xff'},
                                  {'id':1,'balance':Decimal('1.00'),'binary':b'\x01'}]}
        observed=P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertEqual(len(auth.sessions),1);session=auth.sessions[0]
        self.assertIs(session.requested_autocommit,True);self.assertTrue(session.closed)
        self.assertEqual(set(observed),protected);self.assertEqual(len(observed),418)
        reads=[sql for sql,_ in session.events if sql.startswith('SELECT * FROM ')]
        self.assertEqual(len(reads),418)
        self.assertEqual(set(reads),{'SELECT * FROM `'+name+'`' for name in protected})
        self.assertEqual(observed['ums_member']['rows'],
                sorted([{key:P.typed(value) for key,value in row.items()} for row in auth.rows['ums_member']],key=P.canonical))
        self.assertEqual(observed['member_cash_withdraw']['nextId'],211)
        self.assertEqual(session.events[0][0],'SET SESSION information_schema_stats_expiry=0')
        self.assertIn(('SELECT @@SESSION.autocommit',None),session.events)
        self.assertFalse(any(sql.startswith(('START TRANSACTION','BEGIN','SET GLOBAL','ANALYZE','UPDATE','INSERT','ALTER'))
                for sql,_ in session.events))

    def test_each_independent_phase_reopens_and_observes_changed_rows_high_water_and_table_set(self):
        auth=RestoreHttpCurrentAllocatorControls().driver()
        auth.rows={'ums_member':[{'id':1,'balance':Decimal('70.00')}]}
        first=P.snapshot(auth,3306,self.schema,self.uuid)
        auth.rows={'ums_member':[{'id':1,'balance':Decimal('69.00')}]};auth.current=212
        second=P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertEqual(len(auth.sessions),2);self.assertTrue(all(session.closed for session in auth.sessions))
        self.assertEqual(first['member_cash_withdraw']['nextId'],211)
        self.assertEqual(second['member_cash_withdraw']['nextId'],212)
        self.assertNotEqual(first['ums_member']['rows'],second['ums_member']['rows'])
        with self.assertRaisesRegex(ValueError,'protected rows or ID high water changed'):P.assert_equal(first,second)
        auth.table_names=P.REQUIRED_TABLES|{'new_actual_table'}
        third=P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertEqual(len(auth.sessions),3)
        with self.assertRaisesRegex(ValueError,'table set drift'):P.assert_equal(second,third)

    def test_session_autocommit_must_be_effective_integer_one_before_any_row_read(self):
        for value in (0,False,True,'1',None):
            with self.subTest(value=value):
                auth=RestoreHttpCurrentAllocatorControls().driver();auth.actual_autocommit=value
                with self.assertRaisesRegex(ValueError,'require session autocommit'):
                    P.snapshot(auth,3306,self.schema,self.uuid)
                self.assertEqual(len(auth.sessions),1);self.assertTrue(auth.sessions[0].closed)
                self.assertFalse(any(sql.startswith('SELECT * FROM ') for sql,_ in auth.sessions[0].events))

    def test_mid_observation_failure_closes_connection_and_never_returns_or_reuses_partial_snapshot(self):
        auth=RestoreHttpCurrentAllocatorControls().driver();failure=RuntimeError('private-row-read-canary')
        auth.fail_table='ums_member';auth.row_error=failure
        with self.assertRaises(RuntimeError) as caught:P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertIs(caught.exception,failure);self.assertTrue(auth.sessions[0].closed)
        auth.fail_table=None;auth.current=213
        recovered=P.snapshot(auth,3306,self.schema,self.uuid)
        self.assertEqual(len(auth.sessions),2);self.assertTrue(auth.sessions[1].closed)
        self.assertEqual(recovered['member_cash_withdraw']['nextId'],213)

    def test_normal_report_uses_exact_checked_complete_readback_once_without_replacing_later_observations(self):
        _,original,_=late()
        with tempfile.TemporaryDirectory() as directory:
            slot=SimpleNamespace(root=Path(directory));provider=b'original-provider-record'
            (slot.root/'withdrawal-provider.json').write_bytes(provider)
            with patch.object(P,'snapshot',return_value=copy.deepcopy(original)) as read:
                result=P.normal_readback_rows(slot,object(),3307,self.schema,self.uuid,original,provider)
            self.assertEqual(read.call_count,1);self.assertEqual(result,P.public_snapshot(original))
            changed=copy.deepcopy(original);changed['ums_member']['nextId']+=1
            with patch.object(P,'snapshot',return_value=changed) as read:
                with self.assertRaisesRegex(ValueError,'effect/high-water change'):
                    P.normal_readback_rows(slot,object(),3307,self.schema,self.uuid,original,provider)
            self.assertEqual(read.call_count,1)
            (slot.root/'withdrawal-provider.json').write_bytes(b'changed-provider')
            with patch.object(P,'snapshot',return_value=copy.deepcopy(original)):
                with self.assertRaisesRegex(ValueError,'repeated provider submit/query'):
                    P.normal_readback_rows(slot,object(),3307,self.schema,self.uuid,original,provider)

    def test_relative_phase_measurements_keep_original_deadline_and_fixed_source_stage_names(self):
        report={};slot=SimpleNamespace(deadline=1500,owned=['source'],runtime_report_write=lambda *args:None)
        with patch.object(P.time,'monotonic',side_effect=[10,310,760]):
            P.checkpoint(slot,report,Path('unused'),'CANONICAL_BACKUP_RESTORE','CANONICAL_BACKUP')
            slot.owned.append('normal')
            P.checkpoint(slot,report,Path('unused'),'CANONICAL_BACKUP_RESTORE','CANONICAL_RESTORE')
            P.observe_phase_timing(slot,report,'RESTORED_HELD_APPLICATION_START','APPLICATION_SLOT_START')
        self.assertEqual(slot.deadline,1500)
        self.assertEqual(report['phaseElapsed'],[
            {'phase':'CANONICAL_BACKUP_RESTORE','substage':'CANONICAL_BACKUP','createdBootCount':1,'elapsedMillis':300000},
            {'phase':'CANONICAL_BACKUP_RESTORE','substage':'CANONICAL_RESTORE','createdBootCount':2,'elapsedMillis':450000}])
        self.assertEqual(report['lifecycleTiming'],{'budgetSeconds':1500,'elapsedMillis':760000,'remainingMillis':740000})
        self.assertNotIn('private',json.dumps(report))

    def test_phase_measurements_are_bounded_and_do_not_claim_an_unobserved_boot_ready_or_complete(self):
        report={'boots':[],'completeBoots':[]};slot=SimpleNamespace(deadline=1500,owned=[])
        with patch.object(P.time,'monotonic',return_value=100):
            for _ in range(131):
                P.observe_phase_timing(slot,report,'RESTORED_HELD_APPLICATION_START','APPLICATION_SLOT_START')
        self.assertEqual(len(report['phaseElapsed']),128);self.assertIs(report['phaseElapsedTruncated'],True)
        self.assertEqual(report['boots'],[]);self.assertEqual(report['completeBoots'],[])
        self.assertEqual(slot.deadline,1500)

    def test_original_global_budget_still_rejects_at_deadline_and_retains_finite_remaining_evidence(self):
        slot=object.__new__(R.ApplicationSlot);slot.deadline=1600
        with patch.object(R.time,'monotonic',return_value=1599):slot.budget()
        self.assertEqual(slot.lifecycle_budget_observation,
                {'budgetSeconds':1500,'elapsedMillis':1499000,'remainingMillis':1000})
        with patch.object(R.time,'monotonic',return_value=1600):
            with self.assertRaises(ValueError) as caught:slot.budget()
        self.assertEqual(caught.exception.restore_reason_code,'APP_LIFECYCLE_BUDGET_EXHAUSTED')
        self.assertEqual(slot.lifecycle_budget_observation,
                {'budgetSeconds':1500,'elapsedMillis':1500000,'remainingMillis':0})
        self.assertEqual(slot.deadline,1600)


if __name__=='__main__':unittest.main()
