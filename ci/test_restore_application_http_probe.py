"""Direct control SOURCE. Intentionally NOT_RUN in the source-construction task."""
import copy
from contextlib import nullcontext
from decimal import Decimal
import importlib.util
from pathlib import Path
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


if __name__=='__main__':unittest.main()
