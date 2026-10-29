"""Fixed additional T20 cohort readers. No runner, SQL, provider calls, repair, approval API or policy."""
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re

BASE = 'com.chengyinhub.business.service.'
SPECS = {
    'withdrawal-complete': {'child': BASE+'impl.RestoreWithdrawalReconciliationFixture',
        'tables': ('ums_member','merchant_referral_bounty','player_withdrawal_cooldown','member_cash_withdraw',
                   'member_cash_withdraw_fund_fact','member_cash_withdraw_transfer_intent'),
        'ids': {'member_cash_withdraw':'id','member_cash_withdraw_fund_fact':'id'}, 'owner':'CashWithdrawServiceImpl',
        'blocked':('CashWithdrawServiceImpl.withdraw','CashWithdrawServiceImpl.manualRefund','WechatPaymentSdkGateway.transferToBalance')},
    'refund-complete': {'child': BASE+'impl.RestoreRefundReconciliationFixture',
        'tables': ('cms_registration','cms_topic','cms_activity','refund_application','cms_registration_payment_fact',
                   'wx_verified_payment_fact','wx_verified_payment_fact_rejection','wx_pay_order_intent',
                   'wx_payment_callback_inbox','wx_payment_callback_fact_link','wx_payment_callback_outbox'),
        'ids': {'refund_application':'id','wx_verified_payment_fact':'id','wx_payment_callback_inbox':'id',
                'wx_payment_callback_outbox':'id','wx_verified_payment_fact_rejection':'id',
                'cms_registration':'id','cms_topic':'id','cms_activity':'id'},
        'owner':'RefundServiceImpl/RefundPayoutServiceImpl',
        'blocked':('RefundServiceImpl.saveBackOfficeRefund','WechatPaymentSdkGateway.submitRefund')},
    'voice-complete': {'child':BASE+'ai.RestoreVoiceReconciliationFixture',
        'tables':('npc_profile','npc_voice_enrollment_intent'), 'ids':{'npc_voice_enrollment_intent':'id'},
        'owner':'NpcVoiceEnrollmentService','blocked':('NpcVoiceEnrollmentService.enroll',)},
}
for _domain in ('withdrawal','refund','voice'):
    SPECS[_domain+'-gap'] = dict(SPECS[_domain+'-complete'])
ALL_TABLES = frozenset(t for s in SPECS.values() for t in s['tables'])


# Fixed SOURCE contract labels only. Never export the rejected row, value, provider
# identity, arbitrary exception message or SQL through the host's public receipt.
_FAILURE_CODES = {
    'typed original row shape': 'TYPED_ROW_SHAPE',
    'full typed restore rows/high-water differ': 'RESTORED_TYPED_SNAPSHOT',
    'external store rolled back': 'EXTERNAL_JOURNAL_PREFIX',
    'complete cohort cannot hide post-backup writes/allocated identities': 'COMPLETE_SOURCE_SNAPSHOT',
    'all original withdrawal IDs must be uniquely accounted': 'WITHDRAWAL_ORIGINAL_ROSTER',
    'orphan or extra original financial identity': 'WITHDRAWAL_EVIDENCE_ROSTER',
    'original master V1': 'WITHDRAWAL_MASTER_V1',
    'preserve both independent V1 identities and actual debit': 'WITHDRAWAL_LEDGER_V1',
    'original success required': 'WITHDRAWAL_ORIGINAL_SUCCESS',
    'original payment consumption reference': 'REFUND_PAYMENT_CONSUMPTION',
    'original refund ID/amount': 'REFUND_ORIGINAL_ID_AMOUNT',
    'refund original consumed payment': 'REFUND_ORIGINAL_PAYMENT_REFERENCE',
    'refund cannot substitute payment money/provenance': 'REFUND_ORIGINAL_GROSS_AMOUNT',
    'verified original refund success required': 'REFUND_ORIGINAL_VERIFIED_SUCCESS',
    'real original callback DONE receipt': 'REFUND_ORIGINAL_DONE',
    'original voice request ID missing': 'VOICE_ORIGINAL_REQUEST',
    'original voice owner/version changed': 'VOICE_ORIGINAL_OWNER',
    'unqueryable voice must remain UNKNOWN': 'VOICE_UNKNOWN_IDENTITY',
    'original voice version representation': 'VOICE_VERSION_REPRESENTATION',
    'UNKNOWN must retain original admission version': 'VOICE_UNKNOWN_ADMISSION_VERSION',
    'original PENDING/READY version progression': 'VOICE_READY_PROJECTION_VERSION',
    'voice profile is not the same original projection': 'VOICE_PROFILE_BINDING',
    'owner changed original full economic/task rows': 'OWNER_ORIGINAL_ROWS',
    'owner changed an unregistered allocator/high-water': 'OWNER_ORIGINAL_HIGH_WATER',
}


class DomainFactMismatch(ValueError):
    """Still a failed predicate, never an observation/approval or a replacement fact."""
    pass


def failure_context(failure):
    """Optional host projection; unknown failures/messages remain undisclosed UNKNOWN."""
    if type(failure) is not DomainFactMismatch or len(failure.args) != 1:
        return {'contractCode': 'UNKNOWN_DOMAIN_FACT_CONTRACT'}
    reason = failure.args[0]
    code = _FAILURE_CODES.get(reason) if type(reason) is str else None
    return {'contractCode': code or 'UNKNOWN_DOMAIN_FACT_CONTRACT'}


def require(ok, reason):
    if not ok: raise DomainFactMismatch(reason)


def voice_versions(row, identity):
    # capture.table_rows CASTs numeric columns to text; RestoreProviderJournal
    # records the JDBC admission BIGINT as a JSON integer. Do not coerce floats,
    # booleans, missing fields or the current projection into an original version.
    projected, admitted = row.get('profile_version'), identity.get('profile_version')
    require(type(projected) is str and re.fullmatch(r'[1-9][0-9]{0,18}', projected) is not None
            and int(projected) <= 9223372036854775807
            and type(admitted) is int and 0 < admitted <= 9223372036854775807,
            'original voice version representation')
    return int(projected), admitted


def rows(snapshot, name):
    data=snapshot['tables'][name]; names=[c[0] for c in data['columns']]
    require(len(names)==len(set(names)) and all(len(row)==len(names) for row in data['rows']), 'typed original row shape')
    return [dict(zip(names,row)) for row in data['rows']]


def journal(path, scope, cohort):
    file=Path(path);require(file.is_file() and not file.is_symlink() and not file.stat().st_mode & 0o077,'private original provider file required')
    raw=file.read_bytes(); require(raw.endswith(b'\n') and len(raw)<=1048576,'external journal incomplete')
    previous='0'*64; records=[]; originals={}; states={}; submits={}
    for number,line in enumerate(raw.splitlines(),1):
        outer=json.loads(line); require(hashlib.sha256(outer['payload'].encode()).hexdigest()==outer['sha256'],'external hash')
        row=json.loads(outer['payload']); require(row['sequence']==number and row['previous']==previous and row['scope']==scope,'external continuity/owner')
        require(cohort in SPECS and row.get('cohort')==cohort,'external cohort namespace changed')
        require(row['domain']=={'withdrawal':'WITHDRAWAL','refund':'REFUND','voice':'VOICE'}[cohort.split('-')[0]],'external domain changed')
        require(row['domain'] in ('WITHDRAWAL','REFUND','VOICE') and row['operation'] in ('SUBMIT','RESULT','QUERY','HISTORICAL_ACCEPTANCE'),'fixed external event')
        key=row['case']; identity=row['identity']; require(key not in originals or originals[key]==identity,'original identity/amount changed')
        if row['operation']=='SUBMIT': require(key not in submits,'duplicate external creation');submits[key]=1
        if row['operation']=='HISTORICAL_ACCEPTANCE': require(key not in originals and row['domain']=='WITHDRAWAL','explicit historical original only')
        if row['operation']=='QUERY': require(key in originals and states[key]==row['state'],'query cannot invent or change provider result')
        originals[key]=identity;states[key]=row['state'];previous=outer['sha256'];records.append(row)
    return {'records':records,'originals':originals,'states':states,'submits':submits,'head':previous,
            'sha256':hashlib.sha256(raw).hexdigest()}


def require_original_receipts(snapshot):
    for consumed in rows(snapshot,'cms_registration_payment_fact'):
        facts=[r for r in rows(snapshot,'wx_verified_payment_fact') if r['id']==consumed['payment_fact_id']]
        orders=[r for r in rows(snapshot,'cms_registration') if r['id']==consumed['registration_id']]
        require(len(facts)==len(orders)==1 and facts[0]['fact_hash']==consumed['payment_fact_hash']
                and facts[0]['transaction_id']==orders[0]['transaction_id']==consumed['transaction_id']
                and orders[0]['registration_no']==consumed['registration_no'],'original payment consumption reference')


def evaluate(cohort, backup, fault, restored, provider_before, provider_after):
    require(cohort in SPECS,'fixed cohort required')
    require(backup==restored,'full typed restore rows/high-water differ')
    require(provider_after['records'][:len(provider_before['records'])]==provider_before['records'],'external store rolled back')
    gap=cohort.endswith('-gap'); differences=[]
    if not gap: require(backup==fault,'complete cohort cannot hide post-backup writes/allocated identities')
    if cohort.startswith('withdrawal-'):
        expected={'ledger_success','master_success'}|({'ledger_unknown','lost_ledger_success'} if gap else set())
        require(set(provider_after['originals'])==expected,'complete two-V1 roster')
        original_ids={str(c['withdrawalId']) for c in provider_after['originals'].values()}
        require(len(original_ids)==len(expected) and {r['id'] for r in rows(fault,'member_cash_withdraw')}==original_ids,
                'all original withdrawal IDs must be uniquely accounted')
        require({r['withdraw_id'] for r in rows(fault,'member_cash_withdraw_transfer_intent')}==original_ids
                and all(r['withdrawal_id'] in original_ids for r in rows(fault,'member_cash_withdraw_fund_fact')),'orphan or extra original financial identity')
        for case in sorted(expected):
            identity=provider_after['originals'][case]; original_id=str(identity['withdrawalId'])
            source=[r for r in rows(fault,'member_cash_withdraw') if r['id']==original_id]
            local=[r for r in rows(restored,'member_cash_withdraw') if r['id']==original_id]
            require(len(source)==1 and source[0]['out_batch_no']==identity['outBatchNo']
                    and source[0]['out_detail_no']==identity['outDetailNo']
                    and Decimal(source[0]['amount'])*100==identity['amountFen'],'original transfer identity/money')
            facts=[r for r in rows(fault,'member_cash_withdraw_fund_fact') if r['withdrawal_id']==original_id]
            snapshots=[r for r in rows(fault,'member_cash_withdraw_transfer_intent') if r['withdraw_id']==original_id]
            require(len(snapshots)==1 and snapshots[0]['request_hash']==identity['masterRequestHash'],'original master V1')
            for key in ('mchid','appid','openid'):
                require(snapshots[0][key]==identity[key],'original master authority/recipient')
            require(snapshots[0]['out_batch_no']==identity['outBatchNo'] and snapshots[0]['out_detail_no']==identity['outDetailNo']
                    and int(snapshots[0]['amount_fen'])==identity['amountFen']
                    and source[0]['member_id']==str(identity['memberId']),'original master money/actor')
            member=source[0]['member_id']; balances=[r for r in rows(fault,'ums_member') if r['id']==member]
            require(len(balances)==1 and Decimal(balances[0]['balance'])==Decimal('70'),'actual balance debit once')
            if case=='master_success':
                require(not facts and source[0]['balance_deduct_id'] is None and identity['source']=='TRANSFER_INTENT'
                        and identity['origin']=='HISTORICAL_MASTER_ONLY:original-TransferIntent9/B-history-fixture'
                        and identity['requestHash']==identity['masterRequestHash'],'master-only must not invent ledger provenance')
                require(case not in provider_after['submits'],'historical acceptance is not this drill SUBMIT')
            else:
                debits=[r for r in facts if r['kind']=='DEBIT']; submissions=[r for r in facts if r['kind']=='SUBMIT_INTENT']
                require(len(debits)==len(submissions)==1 and source[0]['balance_deduct_id']==debits[0]['id']
                        and Decimal(debits[0]['balance_before'])==100 and Decimal(debits[0]['balance_after'])==70
                        and Decimal(debits[0]['amount'])*100==identity['amountFen']
                        and identity['source']=='FUND_FACT' and identity['requestHash']==submissions[0]['request_hash']
                        and identity['submissionIntentId']==int(submissions[0]['id'])
                        and identity['requestHash']!=identity['masterRequestHash'],'preserve both independent V1 identities and actual debit')
                for column,key in (('request_mchid','mchid'),('request_appid','appid'),('request_openid','openid')):
                    require(submissions[0][column]==identity[key],'original submission authority/recipient')
                require(int(submissions[0]['request_amount_fen'])==identity['amountFen'],'original submission money')
                require(provider_after['submits'].get(case)==1,'one original provider transfer')
            require(not any(r['kind']=='REFUND' for r in facts),'restore cannot credit/debit again')
            if case=='lost_ledger_success':
                require(not local,'whole lost withdrawal must really be absent');differences.append({'case':case,'reason':'UNKNOWN_MISSING_WHOLE_INTENT','originalId':original_id})
            elif case=='ledger_unknown':
                require(len(local)==1 and local[0]['status']!='1' and provider_after['states'][case]=='UNKNOWN','original UNKNOWN must stay unknown')
                differences.append({'case':case,'reason':'UNKNOWN_RETAIN_ORIGINAL','originalId':original_id})
            else: require(len(local)==1 and local[0]['status']=='1' and provider_after['states'][case]=='SUCCESS','original success required')
        require(rows(backup,'merchant_referral_bounty')==rows(fault,'merchant_referral_bounty')
                and rows(backup,'player_withdrawal_cooldown')==rows(fault,'player_withdrawal_cooldown'),'protected frozen rows changed')
        active={str(provider_after['originals'][c]['memberId']) for c in expected}
        require({r['id'] for r in rows(fault,'ums_member')}=={'61','62','63','64'},'complete fixed actor roster')
        require(all(Decimal(r['balance'])==100 for r in rows(fault,'ums_member') if r['id'] not in active),'unrelated actor balance changed')
    elif cohort.startswith('refund-'):
        require(set(provider_after['originals'])=={'original_refund'} and provider_after['submits']=={'original_refund':1},'one original refund')
        require_original_receipts(restored);require_original_receipts(fault)
        identity=provider_after['originals']['original_refund']; values=rows(restored,'refund_application')
        require(len(values)==1,'one original refund row'); r=values[0]
        require(r['id']==str(identity['refundId']) and r['source_id']=='99' and r['refund_no']==identity['refundNo']
                and Decimal(r['refund_amount'])*100==identity['refundFen']==2000,'original refund ID/amount')
        originals=rows(restored,'cms_registration_payment_fact')
        require(len(originals)==1 and int(originals[0]['payment_fact_id'])==identity['paymentFactId']
                and originals[0]['payment_fact_hash']==identity['paymentFactHash'],'refund original consumed payment')
        payments=[f for f in rows(restored,'wx_verified_payment_fact') if f['id']==str(identity['paymentFactId'])]
        require(len(payments)==1 and payments[0]['fact_kind']=='PAYMENT' and int(payments[0]['total_fen'])==identity['totalFen'],
                'refund cannot substitute payment money/provenance')
        for link in rows(restored,'wx_payment_callback_fact_link'):
            facts=[f for f in rows(restored,'wx_verified_payment_fact') if f['id']==link['fact_id']]
            inbox=[i for i in rows(restored,'wx_payment_callback_inbox') if i['id']==link['inbox_id']]
            require(len(facts)==len(inbox)==1 and facts[0]['fact_hash']==link['fact_hash'],'original callback immutable fact link')
        success=[f for f in rows(restored,'wx_verified_payment_fact') if f['fact_kind']=='REFUND']
        if gap:
            require(not success and r['payout_status']!='1','unacknowledged refund cannot be upgraded locally')
            require(provider_after['states']['original_refund']=='SUCCESS','controlled late refund success')
            differences.append({'case':'original_refund','reason':'EXTERNAL_SUCCESS_LOCAL_UNAPPLIED','originalId':r['id']})
        else:
            require(r['payout_status']=='1' and len(success)==1 and success[0]['provider_id']==identity['providerRefundId']
                    and int(success[0]['amount_fen'])==identity['refundFen'] and int(success[0]['total_fen'])==identity['totalFen']
                    and success[0]['transaction_id']==identity['paymentTransactionId'],'verified original refund success required')
            done=[i for i in rows(restored,'wx_payment_callback_inbox') if i['biz_type']=='2' and i['state']=='2']
            require(len(done)==1,'real original callback DONE receipt')
    else:
        expected={'known_voice'}|({'unknown_voice'} if gap else set())
        require(set(provider_after['originals'])==expected and provider_after['submits']=={c:1 for c in expected},'one create per original voice')
        for case in expected:
            identity=provider_after['originals'][case]
            # Original Mapper/uk_nvei_request identity is (npc_id, request_id).
            # Two NPCs using the same canonical sample legitimately share the
            # sample:<sha256> request label; it is not a global request key.
            local=[r for r in rows(restored,'npc_voice_enrollment_intent')
                   if r['npc_id']==str(identity['npc_id']) and r['request_id']==identity['request_id']]
            require(len(local)==1,'original voice request ID missing'); r=local[0]
            for key in ('id','npc_id','actor_member_id','scope_type','scope_id'):
                require(r[key]==str(identity[key]),'original voice owner/version changed')
            require(r['sample_sha256']==identity['sample_sha256'] and r['sample_reference']==identity['sample_reference']
                    and r['actor_member_id']=='88' and r['scope_id']=='31','original sample ownership/reference')
            projected_version, admission_version = voice_versions(r, identity)
            if case=='unknown_voice':
                require(r['state']=='UNKNOWN' and r['voice_id'] is None and identity['voiceId'] is None,'unqueryable voice must remain UNKNOWN')
                # finish(UNKNOWN) explicitly skips update(current); claim already
                # committed the admission projection before the provider call.
                require(projected_version==admission_version,'UNKNOWN must retain original admission version')
                require(not any(e['case']==case and e['operation']=='QUERY' for e in provider_after['records']),'never query prefix or invent original voice id')
                differences.append({'case':case,'reason':'PROVIDER_ORIGINAL_UNQUERYABLE_MANUAL','originalId':r['id']})
            else:
                require(r['state']=='READY' and r['voice_id']==identity['voiceId']=='fixture-original-voice-555','persisted original voice result')
                # Original finish(PENDING) and finish(READY) each CAS the profile and advance this local projection once.
                # The provider identity retains the ORIGINAL admission version; do not overwrite it with the final projection.
                require(projected_version==admission_version+2,'original PENDING/READY version progression')
            profiles=[p for p in rows(restored,'npc_profile') if p['id']==r['npc_id']]
            require(len(profiles)==1 and profiles[0]['voice_version']==r['profile_version']
                    and profiles[0]['voice_id']==r['voice_id'],'voice profile is not the same original projection')
    risks=[]
    for table,facts in fault['highWater'].items():
        next_id=restored['highWater'][table]['next']
        if next_id is None or next_id<=facts['max']:risks.append({'table':table,'restoreNext':next_id,'preFaultMax':facts['max']})
    if cohort=='withdrawal-gap': require(any(r['table']=='member_cash_withdraw' for r in risks),'lost CW/CD number-reuse risk not observed')
    if not gap: require(not differences and not risks,'complete cohort has unresolved facts or IDs')
    return {'cohort':cohort,'differences':differences,'highWaterRisks':risks,'factsComplete':not differences and not risks,
            'normalBootEligible':not gap and not differences and not risks,'candidateState':'TEST_OWNED_RECONCILED' if not gap else 'BLOCKED',
            'dispatchRelease':False,'allDispatchDomainsFenced':False,
            'scope':'this fixed cohort only; global owner coverage requires combined independent receipts'}


def verify_saved_refund_observation(before, after, provider):
    """A's actual restore query may append ONLY original verified fact/inbox/link, never money or DONE."""
    allowed={'wx_verified_payment_fact','wx_payment_callback_inbox','wx_payment_callback_fact_link'}
    for table in before['tables']:
        require(before['tables'][table]['columns']==after['tables'][table]['columns'],'restore observation changed typed columns')
        if table not in allowed:require(before['tables'][table]==after['tables'][table],'restore query changed economic/other original rows')
    identity=provider['originals']['original_refund']; facts=rows(after,'wx_verified_payment_fact')
    old=rows(before,'wx_verified_payment_fact')
    require(all(row in facts for row in old),'original verified fact was changed or removed')
    new=[row for row in facts if row not in old]
    require(len(new)<=1 and len(facts)==len(old)+len(new),'restore query appended unexpected facts')
    refund=[r for r in facts if r['fact_kind']=='REFUND']
    require(len(refund)==1,'original queried refund success must be durably saved')
    fact=refund[0]
    require(fact['provider_id']==identity['providerRefundId'] and fact['merchant_event_no']==identity['refundNo']
            and fact['business_out_trade_no']==identity['businessNo'] and fact['wx_out_trade_no']==identity['businessNo']
            and fact['mchid']==identity['mchid'] and fact['appid']==identity['appid'] and fact['currency']==identity['currency']
            and int(fact['amount_fen'])==identity['refundFen'] and int(fact['total_fen'])==identity['totalFen']
            and fact['transaction_id']==identity['paymentTransactionId'],'restore query substituted original provider money/identity')
    inbox=rows(after,'wx_payment_callback_inbox');old_inbox=rows(before,'wx_payment_callback_inbox')
    require(all(r in inbox for r in old_inbox) and len(inbox)==1 and inbox[0]['biz_type']=='2'
            and inbox[0]['biz_id']==str(identity['refundId']) and inbox[0]['state']=='0'
            and inbox[0]['event_type']=='SUCCESS','restore must retain pending original inbox, never false DONE')
    notify_hash=hashlib.sha256(('REFUND:'+identity['refundNo']+':SUCCESS').encode()).hexdigest()
    require(inbox[0]['notify_hash']==notify_hash.upper(),'original binary inbox identity')
    links=rows(after,'wx_payment_callback_fact_link');old_links=rows(before,'wx_payment_callback_fact_link')
    require(all(r in links for r in old_links) and len(links)==1 and links[0]['inbox_id']==inbox[0]['id']
            and links[0]['fact_id']==fact['id'] and links[0]['fact_hash']==fact['fact_hash']
            and links[0]['notify_hash']==notify_hash,'original pending inbox/fact binding')
    for table, additions in (('wx_verified_payment_fact',new),('wx_payment_callback_inbox',[r for r in inbox if r not in old_inbox])):
        first,last=before['highWater'][table],after['highWater'][table]
        require(len(additions)<=1 and first['next'] is not None and last['next'] is not None
                and last['next']>=first['next'],'original observation allocator moved backwards')
        if additions:
            require(int(additions[0]['id'])==first['next'] and last['max']==first['next']
                    and last['next']>last['max'],'observation did not use exactly its original next local evidence ID')
        else:require(last['max']==first['max'] and (last['next']==first['next'] or table=='wx_payment_callback_inbox'),
                     'unexpected evidence allocator reservation')
    return {'originalFactId':fact['id'],'inboxId':inbox[0]['id'],'inboxState':'0',
            'economicRowsSame':True,'originalProviderSuccessSaved':True,'automaticConsumption':False,
            'observationAllocators':{t:{'before':before['highWater'][t],'after':after['highWater'][t]}
                for t in ('wx_verified_payment_fact','wx_payment_callback_inbox')}}


def verify_owner(cohort, evidence, before, after, provider_before, provider_after, phase):
    require(evidence.get('owner')==SPECS[cohort]['owner'] and evidence.get('phase')==phase,'actual owner evidence missing')
    require(evidence.get('originalReadback') is True and evidence.get('sameTerminalRows') is True,'original read/replay missing')
    restoring=phase in ('RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER')
    if restoring:
        require(evidence.get('newDispatchBlocked') is True,'real owner did not reject restored new intent')
        require(sorted(evidence.get('blockedMethods',[]))==sorted(SPECS[cohort]['blocked']),'actual dispatch consumer proof incomplete')
    else: require(phase=='NORMAL_READBACK' and cohort.endswith('-complete'),'no normal boot for gap/UNKNOWN')
    saved_observation=None
    if cohort=='refund-gap' and restoring:
        saved_observation=verify_saved_refund_observation(before,after,provider_after)
    else:require(before['tables']==after['tables'],'owner changed original full economic/task rows')
    sequence_reservations=[]
    for table,first in before['highWater'].items():
        last=after['highWater'][table]
        if saved_observation and table in ('wx_verified_payment_fact','wx_payment_callback_inbox'):continue
        require(first['max']==last['max'],'owner allocated a new persisted row')
        if first['next']!=last['next']:
            # Exact existing registerVerified -> INSERT IGNORE may reserve an inbox id on DONE replay.
            # Preserve/read back both values; this is never an external order/refund/transfer identity.
            require(cohort.startswith('refund-') and table=='wx_payment_callback_inbox'
                    and first['next'] is not None and last['next']>first['next'], 'owner changed an unregistered allocator/high-water')
            sequence_reservations.append({'table':table,'before':first['next'],'after':last['next']})
    require(provider_before['submits']==provider_after['submits'] and provider_before['originals']==provider_after['originals']
            and provider_after['records'][:len(provider_before['records'])]==provider_before['records'],'owner resent or changed provider original')
    return {'owner':evidence['owner'],'phase':phase,'realOwnerBlocked':restoring,
            'blockedMethods':evidence.get('blockedMethods',[]),
            'readbackProven':True,'independentRowsSame':saved_observation is None,'independentEconomicRowsSame':True,
            'savedOriginalObservation':saved_observation,'providerCreatesSame':True,'technicalInboxReservations':sequence_reservations}


def summarize_completed_cohorts(pairs):
    """Root-only caller after exact resource cleanup. No CLI/auto-boot/global production authorization."""
    required={name for name in SPECS if name.endswith('-complete')}
    require(len(pairs)==3 and {p['business'].get('cohort') for p in pairs}==required,'three exact complete cohort receipts required')
    source={(p['business'].get('sourceSha'),p['business'].get('jarSha256'),p['business'].get('canonicalToolSha256'),
             p['business'].get('runId'),p['business'].get('runAttempt')) for p in pairs}
    require(len(source)==1 and all(all(value for value in identity) for identity in source),'cannot mix source/JAR/run/attempt')
    covered=set();observations=[]
    for pair in pairs:
        business,final=pair['business'],pair['final'];cohort=business['cohort']
        require(final.get('sourceSha')==business['sourceSha'] and final.get('scope')==business['scope']
                and final.get('cohort')==cohort and final.get('originalExit')==0
                and final.get('resourceCleanup')=='EXACT_ABSENCE'
                and final.get('result')=='TEST_COHORT_NORMAL_READBACK_COMPLETE_NO_PRODUCTION_RELEASE','same-source exact cleanup final receipt required')
        require(business.get('factsComplete') is True and business.get('normalBootEligible') is True
                and not business.get('differences') and not business.get('highWaterRisks')
                and business.get('schemaCleanup')=='CONFIRMED_EXACT_CREATED_SCHEMAS_ABSENT'
                and business.get('processExit')==0,'complete independent fact/cleanup proof required')
        phases=business.get('actualOwnerObservations',[])
        require({o['phase'] for o in phases}=={'RESTORED_OWNER','RESTORED_INVALID_OWNER','RESTORED_EMPTY_OWNER','NORMAL_READBACK'}
                and len(phases)==4 and len({o['pid'] for o in phases})==4 and len({o['boot'] for o in phases})==4,'four distinct actual owner boots required')
        for observation in phases:
            require(observation.get('readbackProven') is True and observation.get('independentRowsSame') is True
                    and observation.get('providerCreatesSame') is True,'owner-only boolean is not SQL/provider proof')
            if observation['phase']!='NORMAL_READBACK':
                require(observation.get('realOwnerBlocked') is True
                        and sorted(observation.get('blockedMethods',[]))==sorted(SPECS[cohort]['blocked']),'missing actual dispatch consumer')
                covered.update(observation['blockedMethods'])
        require(business['times'].get('normalOriginalReadbackAvailableAt'),'actual normal-read availability point missing')
        observations.append({'cohort':cohort,'scope':business['scope'],'providerJournalSha256':business['providerJournalSha256'],
                             'ownerBoots':phases,'times':business['times']})
    declared={m for n in required for m in SPECS[n]['blocked']}
    return {'sourceIdentity':list(next(iter(source))),'coverageScope':'C_FIXED_RESTORE_COHORTS_ONLY',
            'declaredDispatchMethods':sorted(declared),'observedBlockedMethods':sorted(covered),
            'allDeclaredDispatchMethodsFenced':covered==declared,'globalProductionFenceVerified':False,
            'productionDispatchRelease':False,'observations':observations,
            'notCovered':['F/A remaining consumer inventory and quota/auto-recovery owners outside these fixed adapters',
                          'live channels/production restore authorization and production RPO/RTO SLO']}
