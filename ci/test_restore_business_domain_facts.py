"""New T20 SOURCE controls. Synthetic reader inputs never substitute for hosted restore receipts."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('restore_domain_facts',HERE/'restore_business_domain_facts.py')
d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)


def table(values):
    columns=list(values[0]) if values else []
    return {'columns':[[c,'CONTROL_ONLY'] for c in columns],
            'rows':[[None if r[c] is None else str(r[c]) for c in columns] for r in values]}


def put(snapshot,name,key,value):
    data=snapshot['tables'][name];data['rows'][0][[c[0] for c in data['columns']].index(key)]=str(value)


def cash():
    commands={};withdrawals=[];snapshots=[]
    for id,case,member in ((1,'ledger_success',61),(2,'master_success',62)):
        commands[case]={'withdrawalId':id,'memberId':member,'source':'FUND_FACT' if id==1 else 'TRANSFER_INTENT',
            'submissionIntentId':2 if id==1 else None,'requestHash':'a'*64 if id==1 else 'b'*64,
            'masterRequestHash':'b'*64,'wireHash':'c'*64,'mchid':'mch','appid':'app','openid':'original',
            'outBatchNo':'CW'+str(id),'outDetailNo':'CD'+str(id),'amountFen':3000,
            'origin':'REAL_ADMISSION_AND_SUBMIT' if id==1 else 'HISTORICAL_MASTER_ONLY:original-TransferIntent9/B-history-fixture'}
        withdrawals.append(dict(id=id,member_id=member,out_batch_no='CW'+str(id),out_detail_no='CD'+str(id),amount='30.00',status=1,balance_deduct_id=1 if id==1 else None))
        snapshots.append(dict(withdraw_id=id,request_hash='b'*64,mchid='mch',appid='app',openid='original',
                              out_batch_no='CW'+str(id),out_detail_no='CD'+str(id),amount_fen=3000))
    data={'member_cash_withdraw':withdrawals,'ums_member':[dict(id=61,balance='70.00'),dict(id=62,balance='70.00'),dict(id=63,balance='100.00'),dict(id=64,balance='100.00')],
          'member_cash_withdraw_transfer_intent':snapshots,'member_cash_withdraw_fund_fact':[
              dict(id=1,withdrawal_id=1,kind='DEBIT',amount='30.00',balance_before=100,balance_after=70,request_hash=None,request_mchid=None,request_appid=None,request_openid=None,request_amount_fen=None),
              dict(id=2,withdrawal_id=1,kind='SUBMIT_INTENT',amount='30.00',balance_before=None,balance_after=None,request_hash='a'*64,request_mchid='mch',request_appid='app',request_openid='original',request_amount_fen=3000)],
          'merchant_referral_bounty':[],'player_withdrawal_cooldown':[]}
    snapshot={'tables':{t:table(v) for t,v in data.items()},'highWater':{t:{'max':2,'next':3} for t in d.SPECS['withdrawal-complete']['ids']}}
    provider={'records':[{'frozen':'prefix'}],'originals':commands,'states':{k:'SUCCESS' for k in commands},'submits':{'ledger_success':1}}
    return snapshot,provider


def refund():
    identity={'refundId':1,'refundNo':'RF99-1','refundFen':2000,'paymentFactId':1,'paymentFactHash':'p'*64,
              'paymentTransactionId':'TX99','providerRefundId':'WX-RF99-1','totalFen':6800,
              'businessNo':'REG99','mchid':'merchant','appid':'wx-original','currency':'CNY'}
    data={'cms_registration':[dict(id=99,registration_no='REG99',transaction_id='TX99')],
          'cms_registration_payment_fact':[dict(registration_id=99,registration_no='REG99',payment_fact_id=1,payment_fact_hash='p'*64,transaction_id='TX99')],
          'wx_verified_payment_fact':[dict(id=1,fact_kind='PAYMENT',fact_hash='p'*64,transaction_id='TX99',provider_id='TX99',amount_fen=6800,total_fen=6800,
                                          merchant_event_no='REG99',business_out_trade_no='REG99',wx_out_trade_no='REG99',mchid='merchant',appid='wx-original',currency='CNY'),
                                      dict(id=2,fact_kind='REFUND',fact_hash='r'*64,transaction_id='TX99',provider_id='WX-RF99-1',amount_fen=2000,total_fen=6800,
                                          merchant_event_no='RF99-1',business_out_trade_no='REG99',wx_out_trade_no='REG99',mchid='merchant',appid='wx-original',currency='CNY')],
          'refund_application':[dict(id=1,source_id=99,refund_no='RF99-1',refund_amount='20.00',payout_status=1)],
          'wx_payment_callback_inbox':[dict(id=1,biz_type=2,biz_id=1,event_type='SUCCESS',notify_hash=d.hashlib.sha256(b'REFUND:RF99-1:SUCCESS').hexdigest().upper(),state=2)]}
    for t in d.SPECS['refund-complete']['tables']:data.setdefault(t,[])
    snapshot={'tables':{t:table(v) for t,v in data.items()},'highWater':{t:{'max':2,'next':3} for t in d.SPECS['refund-complete']['ids']}}
    provider={'records':[{'frozen':'prefix'}],'originals':{'original_refund':identity},'states':{'original_refund':'SUCCESS'},'submits':{'original_refund':1}}
    return snapshot,provider


def voice(unknown=False):
    sample_hash=hashlib.sha256(b'media-asset:73').hexdigest()
    values=[dict(id=1,request_id='sample:'+sample_hash,npc_id=555,actor_member_id=88,scope_type=2,scope_id=31,profile_version=3,
                 sample_sha256=sample_hash,sample_reference='media-asset:73',state='READY',voice_id='fixture-original-voice-555')]
    identity={k:values[0][k] for k in ('id','request_id','npc_id','actor_member_id','scope_type','scope_id','profile_version','sample_sha256','sample_reference')}
    identity['voiceId']=values[0]['voice_id'];originals={'known_voice':identity};states={'known_voice':'READY'}
    identity['profile_version']=1
    if unknown:
        value=dict(values[0],id=2,npc_id=556,state='UNKNOWN',voice_id=None,profile_version=1);values.append(value)
        original=dict(identity,id=2,npc_id=556,voiceId=None);originals['unknown_voice']=original;states['unknown_voice']='UNKNOWN'
    profiles=[dict(id=v['npc_id'],voice_version=v['profile_version'],voice_id=v['voice_id']) for v in values]
    snapshot={'tables':{'npc_profile':table(profiles),'npc_voice_enrollment_intent':table(values)},
              'highWater':{'npc_voice_enrollment_intent':{'max':len(values),'next':len(values)+1}}}
    # Parsed-event controls use RestoreVoiceReconciliationFixture's real vocabulary;
    # they do not substitute for the separately verified raw journal/hash chain.
    records=[]
    for case,original in originals.items():
        events=[('SUBMIT','IN_FLIGHT'),('RESULT',states[case])]
        if case=='known_voice':events.append(('QUERY','READY'))
        for operation,state in events:
            records.append(dict(domain='VOICE',case=case,operation=operation,state=state,
                                identity=copy.deepcopy(original)))
    provider={'records':records,'originals':originals,'states':states,'submits':{k:1 for k in originals}}
    return snapshot,provider


def evaluate(cohort,fixture):
    snapshot,provider=fixture
    return d.evaluate(cohort,snapshot,copy.deepcopy(snapshot),copy.deepcopy(snapshot),provider,copy.deepcopy(provider))


class CompleteFactsControls(unittest.TestCase):
    def test_complete_two_independent_v1_sources_can_enter_only_test_normal_readback(self):
        result=evaluate('withdrawal-complete',cash());self.assertTrue(result['normalBootEligible']);self.assertFalse(result['dispatchRelease'])
        self.assertFalse(result['allDispatchDomainsFenced']);self.assertEqual('TEST_OWNED_RECONCILED',result['candidateState'])

    def test_valid_original_refund_done_can_enter_test_normal_readback(self):
        self.assertTrue(evaluate('refund-complete',refund())['normalBootEligible'])

    def test_ready_known_original_voice_can_enter_test_normal_readback(self):
        self.assertTrue(evaluate('voice-complete',voice())['normalBootEligible'])

    def test_unknown_voice_remains_manual_and_cannot_enter_normal_boot(self):
        s,p=voice(True)
        unknown=[row for row in p['records'] if row['case']=='unknown_voice']
        self.assertEqual([('SUBMIT','IN_FLIGHT'),('RESULT','UNKNOWN')],
                         [(row['operation'],row['state']) for row in unknown])
        self.assertTrue(all(row['domain']=='VOICE' and row['identity']==p['originals']['unknown_voice'] for row in unknown))
        result=evaluate('voice-gap',(s,p));self.assertFalse(result['normalBootEligible']);self.assertEqual('BLOCKED',result['candidateState'])
        self.assertEqual('PROVIDER_ORIGINAL_UNQUERYABLE_MANUAL',
                         next(row['reason'] for row in result['differences'] if row['case']=='unknown_voice'))

    def test_unknown_voice_cannot_use_prefix_query_or_invented_id(self):
        with self.subTest(fence='unknown_original_cannot_be_queried'):
            s,p=voice(True)
            p['records'].append(dict(domain='VOICE',case='unknown_voice',operation='QUERY',state='UNKNOWN',
                                     identity=copy.deepcopy(p['originals']['unknown_voice'])))
            with self.assertRaisesRegex(ValueError,'never query'):evaluate('voice-gap',(s,p))
        with self.subTest(fence='prefix_match_cannot_supply_an_original_voice_id'):
            s,p=voice(True);p['originals']['unknown_voice']['voiceId']='prefix-match-not-the-original'
            with self.assertRaisesRegex(ValueError,'^unqueryable voice must remain UNKNOWN$'):evaluate('voice-gap',(s,p))

    def test_source_post_backup_rows_cannot_be_ignored_in_complete_case(self):
        s,p=cash();fault=copy.deepcopy(s);put(fault,'ums_member','balance','69.00')
        with self.assertRaisesRegex(ValueError,'post-backup'):d.evaluate('withdrawal-complete',s,fault,s,p,p)

    def test_original_transfer_amount_and_recipient_cannot_drift(self):
        for key,value in (('amountFen',3001),('openid','another-recipient'),('memberId',99)):
            with self.subTest(key=key):
                s,p=cash();p['originals']['ledger_success'][key]=value
                with self.assertRaises(ValueError):evaluate('withdrawal-complete',(s,p))

    def test_two_v1_hashes_cannot_be_collapsed_into_one_version(self):
        s,p=cash();p['originals']['ledger_success']['requestHash']='b'*64
        with self.assertRaisesRegex(ValueError,'independent V1'):evaluate('withdrawal-complete',(s,p))

    def test_master_history_cannot_be_converted_to_a_fabricated_ledger_debit(self):
        s,p=cash();p['originals']['master_success']['source']='FUND_FACT'
        with self.assertRaisesRegex(ValueError,'must not invent'):evaluate('withdrawal-complete',(s,p))

    def test_success_status_without_original_real_debit_reference_is_rejected(self):
        s,p=cash();put(s,'member_cash_withdraw','balance_deduct_id',999)
        with self.assertRaisesRegex(ValueError,'actual debit'):evaluate('withdrawal-complete',(s,p))

    def test_complete_candidate_cannot_have_an_id_reuse_risk(self):
        s,p=cash();s['highWater']['member_cash_withdraw']['next']=2
        with self.assertRaisesRegex(ValueError,'unresolved'):evaluate('withdrawal-complete',(s,p))

    def test_provider_duplicate_submit_count_is_not_a_success(self):
        s,p=cash();p['submits']['ledger_success']=2
        with self.assertRaisesRegex(ValueError,'one original provider'):evaluate('withdrawal-complete',(s,p))

    def test_refund_needs_original_payment_fact_and_exact_amount(self):
        for mutate in ('receipt','money'):
            s,p=refund()
            if mutate=='receipt':put(s,'cms_registration_payment_fact','payment_fact_id',999)
            else:p['originals']['original_refund']['refundFen']=2001
            with self.assertRaises(ValueError):evaluate('refund-complete',(s,p))

    def test_refund_success_needs_original_done_not_http_or_provider_status_only(self):
        s,p=refund();s['tables']['wx_payment_callback_inbox']=table([])
        with self.assertRaisesRegex(ValueError,'DONE'):evaluate('refund-complete',(s,p))

    def test_voice_identity_is_not_a_payment_fingerprint_or_default_provider_match(self):
        s,p=voice();p['originals']['known_voice']['request_id']='different-original'
        with self.assertRaisesRegex(ValueError,'request ID'):evaluate('voice-complete',(s,p))

    def test_restore_full_typed_difference_is_never_normalized_away(self):
        s,p=voice();changed=copy.deepcopy(s);put(changed,'npc_voice_enrollment_intent','profile_version',1)
        with self.assertRaisesRegex(ValueError,'typed restore'):d.evaluate('voice-complete',s,s,changed,p,p)


class ActualOwnerProofControls(unittest.TestCase):
    def evidence(self,cohort,phase='RESTORED_OWNER'):
        restoring=phase in ('RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER')
        return dict(owner=d.SPECS[cohort]['owner'],phase=phase,newDispatchBlocked=restoring,originalReadback=True,sameTerminalRows=True,
                    blockedMethods=list(d.SPECS[cohort]['blocked']) if restoring else [])
    def test_helper_boolean_alone_cannot_prove_real_owner(self):
        s,p=cash();e=self.evidence('withdrawal-complete');e['owner']='RestoreDispatchGuard'
        with self.assertRaisesRegex(ValueError,'actual owner'):d.verify_owner('withdrawal-complete',e,s,s,p,p,'RESTORED_OWNER')
    def test_real_owner_evidence_also_requires_independent_rows_and_provider(self):
        s,p=cash();changed=copy.deepcopy(s);put(changed,'ums_member','balance','40.00')
        with self.assertRaisesRegex(ValueError,'full economic'):d.verify_owner('withdrawal-complete',self.evidence('withdrawal-complete'),s,changed,p,p,'RESTORED_OWNER')
    def test_owner_unknown_failure_cannot_be_not_sent_or_guard_success(self):
        s,p=cash();e=self.evidence('withdrawal-complete');e['newDispatchBlocked']=False
        with self.assertRaisesRegex(ValueError,'did not reject'):d.verify_owner('withdrawal-complete',e,s,s,p,p,'RESTORED_OWNER')
    def test_gap_normal_boot_is_explicitly_refused(self):
        s,p=voice(True);e=self.evidence('voice-gap','NORMAL_READBACK')
        with self.assertRaisesRegex(ValueError,'no normal boot'):d.verify_owner('voice-gap',e,s,s,p,p,'NORMAL_READBACK')
    def test_provider_resend_after_restore_cannot_be_hidden_by_same_terminal_rows(self):
        s,p=cash();after=copy.deepcopy(p);after['submits']['ledger_success']=2
        with self.assertRaisesRegex(ValueError,'resent'):d.verify_owner('withdrawal-complete',self.evidence('withdrawal-complete'),s,s,p,after,'RESTORED_OWNER')
    def test_exact_technical_inbox_reservation_is_retained_and_only_moves_forward(self):
        s,p=refund();after=copy.deepcopy(s);after['highWater']['wx_payment_callback_inbox']['next']+=1
        result=d.verify_owner('refund-complete',self.evidence('refund-complete'),s,after,p,p,'RESTORED_OWNER')
        self.assertEqual([dict(table='wx_payment_callback_inbox',before=3,after=4)],result['technicalInboxReservations'])
        after['highWater']['wx_payment_callback_inbox']['next']=2
        with self.assertRaisesRegex(ValueError,'unregistered allocator'):d.verify_owner('refund-complete',self.evidence('refund-complete'),s,after,p,p,'RESTORED_OWNER')
    def test_economic_id_reservation_cannot_use_inbox_exception(self):
        s,p=cash();after=copy.deepcopy(s);after['highWater']['member_cash_withdraw']['next']+=1
        with self.assertRaisesRegex(ValueError,'unregistered allocator'):d.verify_owner('withdrawal-complete',self.evidence('withdrawal-complete'),s,after,p,p,'RESTORED_OWNER')
    def test_caller_sources_keep_real_owner_and_do_not_call_guard_to_fabricate_outcome(self):
        root=HERE.parent/'chengyinhub-admin/src/test/java/com/chengyinhub/business/service'
        source=(root/'impl/RestoreWithdrawalReconciliationFixture.java').read_text()
        self.assertIn('new FundsRecoveryProcessProbe.Store(source)',source);self.assertIn('store.service(member,observer,false,false)',source)
        self.assertIn('owner(64).withdraw(64L',source);self.assertNotIn('requireDispatchAllowed()',source)
        for path in ('impl/RestoreRefundReconciliationFixture.java','ai/RestoreVoiceReconciliationFixture.java'):
            text=(root/path).read_text();self.assertIn('RestoreDispatchGuard.Blocked',text);self.assertNotIn('requireDispatchAllowed()',text)
    def test_host_sets_restore_startup_mode_in_distinct_jvms_and_never_releases_gap(self):
        source=(HERE/'restore_business_reconciliation_probe.py').read_text()
        self.assertIn("['RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER'] + (['NORMAL_READBACK'] if proof['normalBootEligible'] else [])",source)
        self.assertIn("'CHENGYIN_RESTORE_MODE':mode",source);self.assertIn('subprocess.Popen',source)
        self.assertIn("domains.verify_owner",source);self.assertNotIn('os.killpg',source)


class CombinedReceiptControls(unittest.TestCase):
    def receipts(self):
        result=[]
        for cohort in ('withdrawal-complete','refund-complete','voice-complete'):
            observations=[]
            for number,phase in enumerate(('RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER','NORMAL_READBACK'),1):
                observations.append(dict(phase=phase,pid=number,boot=str(number),readbackProven=True,independentRowsSame=True,
                    providerCreatesSame=True,realOwnerBlocked=phase!='NORMAL_READBACK',
                    blockedMethods=list(d.SPECS[cohort]['blocked']) if phase!='NORMAL_READBACK' else []))
            business=dict(cohort=cohort,sourceSha='a'*40,jarSha256='b'*64,canonicalToolSha256='c'*64,runId='1',runAttempt='1',
                scope=cohort,factsComplete=True,normalBootEligible=True,differences=[],highWaterRisks=[],
                schemaCleanup='CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT',processExit=0,actualOwnerObservations=observations,
                times={'normalOriginalReadbackAvailableAt':'2026-10-25T00:00:00Z'},providerJournalSha256='e'*64)
            final=dict(sourceSha='a'*40,scope=cohort,cohort=cohort,originalExit=0,resourceCleanup='EXACT_ABSENCE',
                result='TEST_COHORT_NORMAL_READBACK_COMPLETE_NO_PRODUCTION_RELEASE')
            result.append(dict(business=business,final=final))
        return result
    def test_complete_owner_coverage_is_derived_and_scoped_not_production_release(self):
        observed=d.summarize_completed_cohorts(self.receipts())
        self.assertTrue(observed['allDeclaredDispatchMethodsFenced'])
        self.assertFalse(observed['globalProductionFenceVerified']);self.assertFalse(observed['productionDispatchRelease'])
        self.assertEqual(6,len(observed['declaredDispatchMethods']))
    def test_missing_consumer_cannot_be_replaced_by_helper_boolean(self):
        values=self.receipts();values[0]['business']['actualOwnerObservations'][0]['blockedMethods']=['RestoreDispatchGuard']
        with self.assertRaisesRegex(ValueError,'missing actual'):d.summarize_completed_cohorts(values)
    def test_mixed_source_or_incomplete_cleanup_is_refused(self):
        values=self.receipts();values[0]['business']['sourceSha']='f'*40
        with self.assertRaisesRegex(ValueError,'cannot mix'):d.summarize_completed_cohorts(values)
        values=self.receipts();values[0]['final']['resourceCleanup']='UNKNOWN'
        with self.assertRaisesRegex(ValueError,'cleanup'):d.summarize_completed_cohorts(values)
    def test_unknown_gap_cannot_replace_the_required_positive_cohort(self):
        values=self.receipts();values[0]['business']['cohort']='withdrawal-gap'
        with self.assertRaisesRegex(ValueError,'three exact complete'):d.summarize_completed_cohorts(values)


class SavedObservationControls(unittest.TestCase):
    def observed(self):
        after,provider=refund();put(after,'refund_application','payout_status',6);put(after,'wx_payment_callback_inbox','state',0)
        after['tables']['wx_payment_callback_fact_link']=table([dict(inbox_id=1,fact_id=2,fact_hash='r'*64,
            notify_hash=d.hashlib.sha256(b'REFUND:RF99-1:SUCCESS').hexdigest())])
        after['highWater']['wx_verified_payment_fact']={'max':2,'next':3}
        after['highWater']['wx_payment_callback_inbox']={'max':1,'next':3}
        before=copy.deepcopy(after);before['tables']['wx_verified_payment_fact']['rows'].pop()
        before['tables']['wx_payment_callback_inbox']['rows']=[];before['tables']['wx_payment_callback_fact_link']['rows']=[]
        before['highWater']['wx_verified_payment_fact']={'max':1,'next':2};before['highWater']['wx_payment_callback_inbox']={'max':0,'next':1}
        return before,after,provider
    def test_restore_can_save_exact_original_late_success_without_consuming_money(self):
        before,after,provider=self.observed();result=d.verify_saved_refund_observation(before,after,provider)
        self.assertEqual('0',result['inboxState']);self.assertTrue(result['originalProviderSuccessSaved']);self.assertFalse(result['automaticConsumption'])
    def test_observation_permission_does_not_allow_new_money_or_false_done(self):
        for table,key,value in (('refund_application','refund_amount','21.00'),('wx_payment_callback_inbox','state',2)):
            before,after,provider=self.observed();put(after,table,key,value)
            with self.assertRaises(ValueError):d.verify_saved_refund_observation(before,after,provider)
    def test_saved_fact_cannot_use_another_provider_amount_or_original_reference(self):
        before,after,provider=self.observed();after['tables']['wx_verified_payment_fact']['rows'][-1][
            [c[0] for c in after['tables']['wx_verified_payment_fact']['columns']].index('amount_fen')]='2001'
        with self.assertRaisesRegex(ValueError,'provider money'):d.verify_saved_refund_observation(before,after,provider)
    def test_jar_provenance_uses_actual_admin_plugin_output_and_only_original_full_sha(self):
        ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=ET.parse(HERE.parent/'chengyinhub-admin/pom.xml')
        plugin=next(p for p in pom.findall('.//m:plugin',ns) if p.findtext('m:artifactId',namespaces=ns)=='git-commit-id-maven-plugin')
        config=plugin.find('m:configuration',ns);self.assertEqual('true',config.findtext('m:generateGitPropertiesFile',namespaces=ns))
        self.assertIsNone(config.find('m:generateGitPropertiesFilename',ns))
        self.assertEqual(r'^git\.commit\.id$',config.findtext('m:includeOnlyProperties/m:includeOnlyProperty',namespaces=ns))
        source=(HERE/'restore_business_reconciliation_probe.py').read_text()
        self.assertIn("package.read('BOOT-INF/classes/git.properties')",source)
        self.assertNotIn("package.read('BOOT-INF/classes/META-INF/git.properties')",source)
        self.assertIn("build.get('git.commit.id') == resources['sourceSha']",source)


class ExternalNamespaceControls(unittest.TestCase):
    def write(self,path,duplicate=False):
        identity=cash()[1]['originals']['ledger_success'];previous='0'*64
        events=[('SUBMIT','ACCEPTED'),('RESULT','SUCCESS')]+([('SUBMIT','ACCEPTED')] if duplicate else [])
        with path.open('w') as stream:
            for number,(operation,state) in enumerate(events,1):
                row=dict(sequence=number,previous=previous,scope='a'*32,cohort='withdrawal-complete',domain='WITHDRAWAL',
                         case='ledger_success',operation=operation,state=state,identity=identity)
                payload=json.dumps(row);previous=d.hashlib.sha256(payload.encode()).hexdigest()
                stream.write(json.dumps(dict(payload=payload,sha256=previous))+'\n')
        path.chmod(0o600)
    def test_same_pair_reuse_cannot_mix_provider_cohorts_or_original_namespace(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'store.jsonl';self.write(file)
            self.assertEqual({'ledger_success':1},d.journal(file,'a'*32,'withdrawal-complete')['submits'])
            with self.assertRaisesRegex(ValueError,'cohort namespace'):d.journal(file,'a'*32,'withdrawal-gap')
            with self.assertRaisesRegex(ValueError,'continuity/owner'):d.journal(file,'b'*32,'withdrawal-complete')
    def test_independent_external_store_rejects_duplicate_and_truncated_submission(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/'store.jsonl';self.write(file,True)
            with self.assertRaisesRegex(ValueError,'duplicate external'):d.journal(file,'a'*32,'withdrawal-complete')
            file.write_text('truncated')
            with self.assertRaisesRegex(ValueError,'incomplete'):d.journal(file,'a'*32,'withdrawal-complete')


class VoiceCompositeIdentityControls(unittest.TestCase):
    """Real SOURCE shape; synthetic snapshots do not replace the hosted restore."""
    def test_two_npcs_share_original_sample_request_but_unknown_never_becomes_normal(self):
        snapshot,provider=voice(True)
        known=provider['originals']['known_voice'];unknown=provider['originals']['unknown_voice']
        self.assertEqual(known['request_id'],unknown['request_id'])
        self.assertNotEqual(known['npc_id'],unknown['npc_id'])
        candidates=[r for r in d.rows(snapshot,'npc_voice_enrollment_intent') if r['request_id']==unknown['request_id']]
        self.assertEqual(2,len(candidates))
        # This is the exact former predicate: both legitimate source rows made
        # a globally keyed reader reject before any owner boot.
        with self.assertRaisesRegex(ValueError,'original voice request ID missing'):
            d.require(len(candidates)==1,'original voice request ID missing')
        frozen=copy.deepcopy((snapshot,provider))
        result=evaluate('voice-gap',(snapshot,provider))
        self.assertEqual([dict(case='unknown_voice',reason='PROVIDER_ORIGINAL_UNQUERYABLE_MANUAL',originalId='2')],result['differences'])
        self.assertFalse(result['normalBootEligible']);self.assertFalse(result['dispatchRelease'])
        self.assertEqual('BLOCKED',result['candidateState']);self.assertEqual(frozen,(snapshot,provider))

    def test_missing_unknown_cannot_borrow_known_row_with_same_request(self):
        snapshot,provider=voice(True)
        snapshot['tables']['npc_voice_enrollment_intent']['rows'].pop()
        with self.assertRaisesRegex(ValueError,'original voice request ID missing'):
            evaluate('voice-gap',(snapshot,provider))

    def test_wrong_npc_or_request_is_not_an_original_match(self):
        for column,value in (('npc_id','557'),('request_id','foreign-original-request')):
            with self.subTest(column=column):
                snapshot,provider=voice(True)
                names=[c[0] for c in snapshot['tables']['npc_voice_enrollment_intent']['columns']]
                snapshot['tables']['npc_voice_enrollment_intent']['rows'][1][names.index(column)]=value
                with self.assertRaisesRegex(ValueError,'original voice request ID missing'):
                    evaluate('voice-gap',(snapshot,provider))

    def test_duplicate_same_composite_identity_still_fails_exactly_one(self):
        snapshot,provider=voice(True)
        snapshot['tables']['npc_voice_enrollment_intent']['rows'].append(
            copy.deepcopy(snapshot['tables']['npc_voice_enrollment_intent']['rows'][1]))
        with self.assertRaisesRegex(ValueError,'original voice request ID missing'):
            evaluate('voice-gap',(snapshot,provider))

    def test_match_does_not_erase_original_id_actor_scope_sample_or_state(self):
        for column,value in (('id','99'),('actor_member_id','99'),('scope_type','4'),('scope_id','32'),
                             ('sample_sha256','f'*64),('sample_reference','media-asset:74'),('state','READY')):
            with self.subTest(column=column):
                snapshot,provider=voice(True)
                names=[c[0] for c in snapshot['tables']['npc_voice_enrollment_intent']['columns']]
                snapshot['tables']['npc_voice_enrollment_intent']['rows'][1][names.index(column)]=value
                with self.assertRaises(ValueError):evaluate('voice-gap',(snapshot,provider))

    def test_actual_journal_roundtrip_retains_both_npc_scoped_originals(self):
        snapshot,provider=voice(True)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'provider.jsonl';previous='0'*64
            with path.open('w') as stream:
                for number,event in enumerate(provider['records'],1):
                    row=dict(event,sequence=number,previous=previous,scope='a'*32,cohort='voice-gap')
                    payload=json.dumps(row);previous=hashlib.sha256(payload.encode()).hexdigest()
                    stream.write(json.dumps(dict(payload=payload,sha256=previous))+'\n')
            path.chmod(0o600)
            parsed=d.journal(path,'a'*32,'voice-gap')
            self.assertEqual(provider['originals'],parsed['originals'])
            self.assertEqual(1,parsed['originals']['known_voice']['profile_version'])
            self.assertEqual(1,parsed['originals']['unknown_voice']['profile_version'])
            result=evaluate('voice-gap',(snapshot,parsed))
            self.assertFalse(result['factsComplete']);self.assertFalse(result['normalBootEligible'])

    def test_source_contract_is_npc_scoped_before_backup_not_a_lost_new_action(self):
        root=HERE.parent
        source=(root/'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/NpcVoiceEnrollmentService.java').read_text()
        self.assertIn('String identity = requestId == null ? "sample:" + hash : requestId;',source)
        self.assertIn('intents.find(npcId, identity.substring(7)) : intents.findRequest(npcId, identity)',source)
        xml=ET.parse(root/'chengyinhub-system/src/main/resources/mapper/business/NpcVoiceEnrollmentMapper.xml')
        for name in ('find','findRequest'):
            self.assertIn('npc_id=#{npcId}',xml.find("select[@id='"+name+"']").text)
        migration=(root/'chengyinhub-admin/sql/migration_npc_voice_confirmed_attempt_20261024.sql').read_text()
        self.assertIn('ADD UNIQUE KEY uk_nvei_request(npc_id,request_id)',migration)
        fixture=(root/'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/RestoreVoiceReconciliationFixture.java').read_text()
        self.assertIn('owner.enroll(88L,2,31L,npc,"media-asset:73",',fixture)
        self.assertIn('value.put(key,row.get(key))',fixture)
        self.assertLess(fixture.index('external.append("VOICE",name,"SUBMIT","IN_FLIGHT",original)'),
                        fixture.index('external.append("VOICE",name,"RESULT",npc==555?"READY":"UNKNOWN",original)'))
        run=fixture.split('private void run()throws Exception {',1)[1]
        self.assertLess(run.index('requireOriginalProjection(556,IntentState.UNKNOWN,unknown);'),run.index('signal("BACKUP_READY")'))


class ProductionVersionContractControls(unittest.TestCase):
    """SOURCE contracts plus synthetic negatives, not a hosted cohort receipt."""
    def test_unknown_projection_is_admission_not_a_guessed_plus_one_or_two(self):
        for version in (2,3):
            with self.subTest(version=version):
                s,p=voice(True)
                s['tables']['npc_voice_enrollment_intent']['rows'][1][
                    [c[0] for c in s['tables']['npc_voice_enrollment_intent']['columns']].index('profile_version')]=str(version)
                with self.assertRaisesRegex(ValueError,'UNKNOWN must retain original admission version'):
                    evaluate('voice-gap',(s,p))

    def test_ready_projection_needs_both_pending_and_ready_cas(self):
        for version in (1,2,4):
            with self.subTest(version=version):
                s,p=voice();put(s,'npc_voice_enrollment_intent','profile_version',version)
                with self.assertRaisesRegex(ValueError,'PENDING/READY version progression'):
                    evaluate('voice-complete',(s,p))

    def test_original_journal_version_cannot_be_replaced_by_current_projection(self):
        s,p=voice(True);original=copy.deepcopy(p)
        evaluate('voice-gap',(s,p))
        self.assertEqual(original,p)
        p['originals']['known_voice']['profile_version']=3
        with self.assertRaisesRegex(ValueError,'PENDING/READY version progression'):
            evaluate('voice-gap',(s,p))

    def test_journal_admission_version_requires_original_json_integer(self):
        for value in (None,True,1.0,'1',0,-1,9223372036854775808):
            with self.subTest(value=value):
                s,p=voice();p['originals']['known_voice']['profile_version']=value
                with self.assertRaisesRegex(ValueError,'version representation'):evaluate('voice-complete',(s,p))
        s,p=voice();del p['originals']['known_voice']['profile_version']
        with self.assertRaisesRegex(ValueError,'version representation'):evaluate('voice-complete',(s,p))

    def test_projection_requires_exact_cast_text_not_numeric_coercion(self):
        for value in (None,True,3,3.0,'3.0','03','+3','3 ','0','9223372036854775808'):
            with self.subTest(value=value):
                s,p=voice();column=[c[0] for c in s['tables']['npc_voice_enrollment_intent']['columns']].index('profile_version')
                s['tables']['npc_voice_enrollment_intent']['rows'][0][column]=value
                with self.assertRaisesRegex(ValueError,'version representation'):evaluate('voice-complete',(s,p))

    def test_voice_source_distinguishes_admission_unknown_pending_and_ready(self):
        root=HERE.parent
        source=(root/'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ai/NpcVoiceEnrollmentService.java').read_text()
        finish=source.split('private NpcProfile finish(',1)[1].split('private NpcProfile profile(',1)[0]
        self.assertIn('row.setProfileVersion(current.getVoiceVersion() + 1);',source)
        self.assertIn('if (!"UNKNOWN".equals(state) && Objects.equals(current.getVoiceVersion(), row.getProfileVersion())',finish)
        self.assertIn('("PENDING".equals(state) && "IN_FLIGHT".equals(row.getState()))',finish)
        self.assertIn('("READY".equals(state) && "PENDING".equals(row.getState()))',finish)
        self.assertLess(finish.index('requireOne(update(current));'),finish.index('row.setProfileVersion(current.getVoiceVersion());'))
        self.assertLess(finish.index('row.setProfileVersion(current.getVoiceVersion());'),finish.index('requireOne(intents.complete(row));'))
        xml=ET.parse(root/'chengyinhub-system/src/main/resources/mapper/business/NpcVoiceEnrollmentMapper.xml')
        self.assertIn('profile_version=#{profileVersion}',xml.find("update[@id='complete']").text)
        fixture=(root/'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/ai/RestoreVoiceReconciliationFixture.java').read_text()
        self.assertIn('value.put(key,row.get(key))',fixture)
        self.assertIn('pending.get("profile_version")',fixture)
        self.assertIn('original.get("profile_version")).longValue()+1',fixture)
        host=(HERE/'restore_business_reconciliation_probe.py').read_text()
        self.assertIn("'CAST(' + tool.quoted(c[0]) + ' AS CHAR CHARACTER SET utf8mb4)'",host)

    def test_withdrawal_keeps_two_actual_v1_sources_and_original_master_snapshot(self):
        root=HERE.parent/'chengyinhub-system/src/main/java/com/chengyinhub/business'
        dto=(root/'domain/dto/CashWithdrawTransferIntent.java').read_text()
        self.assertIn('enum OriginalSource { FUND_FACT, TRANSFER_INTENT }',dto)
        self.assertIn('public static final int REQUEST_VERSION = 1;',dto)
        admission=(root/'service/support/WithdrawalBalanceAdmissionService.java').read_text()
        self.assertIn('cash.insertFundFact(fact)',admission)
        self.assertIn('transferIntents.insert(intent)',admission)
        s,p=cash();put(s,'member_cash_withdraw_transfer_intent','request_hash','a'*64)
        with self.assertRaisesRegex(ValueError,'original master V1'):evaluate('withdrawal-complete',(s,p))

    def test_refund_original_gross_money_and_done_are_separate_required_facts(self):
        s,p=refund();p['originals']['original_refund']['totalFen']=2000
        with self.assertRaisesRegex(ValueError,'payment money/provenance'):evaluate('refund-complete',(s,p))
        constants=(HERE.parent/'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/WxPaymentCallbackInbox.java').read_text()
        self.assertIn('STATE_DONE = 2;',constants);self.assertIn('BIZ_TYPE_REFUND = 2;',constants)
        s,p=refund();put(s,'wx_payment_callback_inbox','state',0)
        with self.assertRaisesRegex(ValueError,'DONE'):evaluate('refund-complete',(s,p))

    def test_fixed_failure_context_preserves_original_predicate_and_value_error(self):
        for reason,code in d._FAILURE_CODES.items():
            with self.subTest(code=code):
                try:d.require(False,reason)
                except ValueError as failure:
                    self.assertEqual((reason,),failure.args)
                    self.assertEqual({'contractCode':code},d.failure_context(failure))
                else:self.fail('failed contract was accepted')

    def test_unreviewed_failure_cannot_export_message_rows_or_promote_unknown(self):
        canary='CANARY_PRIVATE_RESPONSE_SQL_TOKEN'
        for failure in (ValueError(canary),RuntimeError(canary),d.DomainFactMismatch(canary),
                        d.DomainFactMismatch({'row':canary}),d.DomainFactMismatch('original master V1',canary)):
            observed=d.failure_context(failure)
            self.assertEqual({'contractCode':'UNKNOWN_DOMAIN_FACT_CONTRACT'},observed)
            self.assertNotIn(canary,json.dumps(observed))


if __name__=='__main__':unittest.main()
