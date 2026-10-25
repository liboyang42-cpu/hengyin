"""Direct control SOURCE. Intentionally NOT_RUN in the source-construction task."""
import copy
from contextlib import nullcontext
from decimal import Decimal
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
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
                        'facts.recordVerified(fact)','notices.enqueueOfficial','wake.dispatchInNewTransaction',
                        '--chengyin.jobs.enabled=true','--spring.quartz.auto-startup=true',
                        'factory.getConfiguration().addInterceptor(new MapperObservations())','file.force(true)'):
            self.assertIn(literal,source)
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
            boots.append(dict(bootId='boot-'+str(i),appCid=owned[i],javaPid=17,side='source' if i==0 else 'target',
                              serverUuid=report['sourceServerUuid'] if i==0 else report['targetServerUuid'],
                              restoreMode=mode,guardHeld=mode!='false',schedulerStarted=True,
                              sourceSha=report['sourceSha'],schema=report['schema'],jarSha256=report['jarSha256']))
        report['completeBoots']=boots[:2];report['boots']=boots[2:]
        report['checks']=[{'case':'original_complete_cohort','providerSubmits':1}]+[
            {'case':'held-full-http-and-actual-wake','mode':mode,'newDispatches':0} for mode in P.HELD_MODES]
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
        for change in ('boot','jar','cid'):
            with self.subTest(change=change):
                report=self.success_report();owner=self.owner(app=report['ownedAppCids'][-1],log='PRESERVED')
                if change=='boot':report['boots'].pop()
                elif change=='jar':report['boots'][0]['jarSha256']='f'*64
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
                  appCid='4'*64,javaPid=17,sourceSha='a'*40,serverUuid='original-uuid',schema='original-schema',schedulerStarted=True)
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
        slot=object.__new__(R.ApplicationSlot);slot.name='cy-auth-'+'a'*32;slot.cid='4'*64
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


if __name__=='__main__':unittest.main()
