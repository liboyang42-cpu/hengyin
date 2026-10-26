#!/usr/bin/env python3
"""Full Spring HTTP startup on the original owner's restored database, fixed manual scope only.
SOURCE ONLY until the coordinator supplies the original four slots and performs the unified run.
No provider/production release, provisioning, arbitrary URL/SQL/profile or canonical-tool bypass.
"""
from __future__ import annotations
import argparse
import contextlib
import datetime as dt
from decimal import Decimal
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse
import zipfile

ROOT=Path(__file__).resolve().parents[1]
HELD_MODES=('true','invalid','')
PRE_START_PHASES={'PRE_APPLICATION_PREPARATION','SOURCE_DATABASE_PREPARATION'}
RUN_PHASES=PRE_START_PHASES|{'SOURCE_APPLICATION_START','SOURCE_WORKLOAD','CANONICAL_BACKUP_RESTORE',
        'RESTORED_NORMAL_APPLICATION_START','RESTORED_NORMAL_READBACK',
        'RESTORED_HELD_APPLICATION_START','RESTORED_HELD_WORKLOAD','APPLICATION_STOP','PRIVATE_CLEANUP'}
SUBSTAGES={'APPLICATION_SLOT_START','APPLICATION_READY','READ_ORIGINAL_IDENTITIES','ADMIN_LOGIN',
        'ADMIN_IDENTITY_READ','ORIGINAL_CHAPTER_COMMAND','ORIGINAL_WITHDRAW_COMMAND','ORIGINAL_WITHDRAW_REQUERY',
        'READ_ORIGINAL_PROVIDER','COMPLETE_FACTS_SQL','ORIGINAL_CHAPTER_RECEIPT','NETWORK_PROOF',
        'SOURCE_QUIESCENT_STOP','QUIESCENT_SQL_SNAPSHOT','CANONICAL_BACKUP','EMPTY_TARGET_CREATE',
        'CANONICAL_RESTORE','RESTORED_SQL_COMPARE','TARGET_RUNTIME_USER','NORMAL_ORIGINAL_READBACK',
        'HELD_OWNER_WORKLOAD','HELD_BASELINE_SQL','HELD_RECEIPT_READBACK','HELD_FOREIGN_REFUSAL',
        'HELD_BLOCKED_CHAPTER','HELD_BLOCKED_MONEY','HELD_NOTICE_PENDING','HELD_QUARTZ_REQUEST',
        'HELD_QUARTZ_OBSERVE','HELD_LATE_CALLBACK','HELD_LATE_READBACK','HELD_REPLAY',
        'HELD_PROVIDER_FENCE','HELD_NETWORK_STOP','APPLICATION_STOP','PRIVATE_CLEANUP'}
SAFE_REASON_CODES={'INVARIANT_REJECTED','UNCLASSIFIED_FAILURE','HTTP_OPERATION_REJECTED',
        'CAPTCHA_CONTRACT_REJECTED','CAPTCHA_ORACLE_REJECTED','ADMIN_LOGIN_REJECTED',
        'HTTP_EVIDENCE_CONTRACT_REJECTED','HTTP_CALLBACK_CONTRACT_REJECTED','FIXTURE_CONTRACT_REJECTED',
        'APP_STOP_EXIT_NOT_CONFIRMED','APP_EXITED_BEFORE_READY','APP_READINESS_TIMEOUT',
        'APP_BOOT_IDENTITY_REJECTED','APP_CHILD_STARTUP_REJECTED','HTTP_STATUS_MALFORMED','HTTP_RESPONSE_NOT_JSON','HTTP_RESPONSE_NOT_OBJECT'}
FACT_REASON_CODES={
    'original controlled withdrawal must be terminal SUCCESS':'WITHDRAW_TERMINAL_FACT_REJECTED',
    'original wire IDs':'ORIGINAL_WIRE_ID_REJECTED',
    'independent original provider identity':'ORIGINAL_PROVIDER_FACT_REJECTED',
    'complete original cash facts':'CASH_FACT_SET_REJECTED',
    'one real debit; no fabricated opening balance reconciliation':'ORIGINAL_BALANCE_REJECTED',
    'original chapter receipt complete':'ORIGINAL_CHAPTER_RECEIPT_REJECTED',
    'original debit ledger linkage and amounts':'DEBIT_LEDGER_LINK_REJECTED',
    'master immutable V1 original':'ORIGINAL_MASTER_V1_REJECTED',
    'wire V1 and terminal original submission linkage':'ORIGINAL_SUBMIT_LINK_REJECTED',
}
SAFE_REASON_CODES.update(FACT_REASON_CODES.values())
SAFE_REASON_CODES.update(["APP_LIFECYCLE_BUDGET_EXHAUSTED","APP_BOOT_MATRIX_REJECTED","APP_SEED_ADMISSION_REJECTED","APP_OWNER_REJECTED","APP_STOP_HISTORY_REJECTED","APP_PRIVATE_LOG_REJECTED","APP_DB_IDENTITY_REJECTED","APP_TRACE_PREPARATION_REJECTED","APP_ISOLATION_REJECTED","APP_MODE_ENV_REJECTED","APP_LOG_PATH_REJECTED","APP_PRIVATE_PATH_REJECTED","APP_LOG_CAPTURE_REJECTED","APP_LOG_SIZE_REJECTED","APP_LOG_ROSTER_REJECTED","APP_LOG_OWNER_REJECTED"])
CANONICAL_TOOL_SHA256='fead7bfce99af5aadeb15b696190fade40109f37d25233aa401116d10ea1279c'
COMPARE_TABLE_LIMIT=32
ALLOWED_MUTABLE={'sys_oper_log','sys_job_log','sys_logininfor'}
LATE_TABLES={'wx_verified_payment_fact','wx_payment_callback_inbox','wx_payment_callback_fact_link'}
REQUIRED_TABLES={'ums_member','member_cash_withdraw','member_cash_withdraw_fund_fact','member_cash_withdraw_transfer_intent',
                 'cms_registration','cms_registration_payment_fact','game_command_receipt','club_lead_state',
                 'committed_official_notice_wx','wx_verified_payment_fact','wx_payment_callback_inbox'}


def load(name,relative):
    spec=importlib.util.spec_from_file_location(name,ROOT/relative)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


def require(value,reason):
    if not value:
        failure=ValueError(reason)
        failure.restore_reason_code=FACT_REASON_CODES.get(reason,'INVARIANT_REJECTED');raise failure


def owned_source_frame(failure):
    # Fixed reviewed files only. No traceback formatting, function/locals/KeyError args or private path.
    allowed={str((ROOT/relative).resolve()):relative for relative in
             ('ci/restore_application_http_probe.py','ci/restore_application_http_runtime.py')}
    selected=None;frame=failure.__traceback__;count=0
    while frame is not None and count<32:
        try:
            resolved=str(Path(frame.tb_frame.f_code.co_filename).resolve())
            if resolved in allowed:
                path=ROOT/allowed[resolved];raw=path.read_bytes()
                skipped=({'require','budget','write_private'} if allowed[resolved].endswith('_runtime.py') else
                         {'require','good','refused','typed','query','canonical','sha','load','checkpoint',
                          'failure_diagnostic','owned_source_frame','__init__','<listcomp>','<genexpr>'})
                if frame.tb_frame.f_code.co_name not in skipped and 0<frame.tb_lineno<=len(raw.splitlines()):
                    selected={'path':allowed[resolved],'line':frame.tb_lineno,
                              'sourceSha256':sha(raw),'scope':'OWNED_SOURCE_ONLY'}
        except (OSError,ValueError):pass
        frame=frame.tb_next;count+=1
    if selected is not None:selected['searchTruncated']=frame is not None
    return selected


def failure_diagnostic(failure,phase,substage):
    # Neither str(failure) nor its cause/traceback is public evidence.
    kind=type(failure).__name__
    code=getattr(failure,'restore_reason_code','UNCLASSIFIED_FAILURE')
    value={'phase':phase if phase in RUN_PHASES else 'PRE_APPLICATION_PREPARATION',
            'substage':substage if substage in SUBSTAGES else 'UNCLASSIFIED_SUBSTAGE',
            'type':kind if re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,95}',kind) else 'UnclassifiedError',
            'reasonCode':code if code in SAFE_REASON_CODES else 'UNCLASSIFIED_FAILURE'}
    frame=owned_source_frame(failure)
    if frame is not None:value['sourceFrame']=frame
    if isinstance(failure,FixtureContractRejected):value['contract']=failure.observation
    return value


class FixtureContractRejected(ValueError):
    def __init__(self,contract,container,fields,index=None,bindings=None):
        allowed_fields={'code','data','bootId','held','serverUuid','schema','events','verifiedFacts','inbox',
                        'symbol','stage','errorType','blocked','id','fact_hash','biz_id','state',
                        'originalOutcome','factId','factHash','invoke_target','status',
                        'beforeStartNotifyTask.run()','committedOfficialNoticeTask.deliverPending()'}
        require(contract in {'CALLBACK','JOBS','EVENTS','EVENT_RECORD','EVIDENCE_ENVELOPE','EVIDENCE_DATA',
                             'EVIDENCE_IDENTITY','VERIFIED_FACT_ROW','INBOX_ROW','QUARTZ_LOG_ROW'}
                and set(fields)<=allowed_fields,'fixed fixture contract diagnostic only')
        super().__init__('original fixture response contract rejected')
        self.restore_reason_code=('HTTP_CALLBACK_CONTRACT_REJECTED' if contract=='CALLBACK'
                else 'FIXTURE_CONTRACT_REJECTED' if contract=='JOBS' else 'HTTP_EVIDENCE_CONTRACT_REJECTED')
        kinds={type(None):'NULL',bool:'BOOLEAN',int:'INTEGER',float:'FLOAT',str:'STRING',dict:'OBJECT',list:'ARRAY'}
        mapping=container if isinstance(container,dict) else {}
        self.observation={'kind':contract,'containerType':kinds.get(type(container),'OTHER'),
                'fields':[{'field':field,'present':field in mapping,
                           'type':kinds.get(type(mapping[field]),'OTHER') if field in mapping else 'MISSING'}
                          for field in fields],
                'otherKeyCount':len(set(mapping)-set(fields))}
        if index is not None:self.observation['recordIndex']=index
        if bindings is not None:self.observation['identityMatches']=bindings


def event_records(rows):
    base=('bootId','symbol','stage','held')
    if not isinstance(rows,list):raise FixtureContractRejected('EVENTS',rows,base)
    for index,row in enumerate(rows):
        stage=row.get('stage') if isinstance(row,dict) else None
        fields=base+('errorType','blocked') if stage=='THROW' else base
        valid=(isinstance(row,dict) and set(row)==set(fields)
                and isinstance(row['bootId'],str) and re.fullmatch('[A-Za-z0-9_-]{1,80}',row['bootId'])
                and isinstance(row['symbol'],str) and re.fullmatch('[A-Za-z_$][A-Za-z0-9_.$!#:-]{0,1023}',row['symbol'])
                and stage in ('ENTER','RETURN','THROW','SQL') and type(row['held']) is bool)
        if valid and stage=='THROW':
            valid=(isinstance(row['errorType'],str) and re.fullmatch('[A-Za-z_$][A-Za-z0-9_.$]{0,255}',row['errorType'])
                   and type(row['blocked']) is bool)
        if not valid:raise FixtureContractRejected('EVENT_RECORD',row,fields,index)
    return rows


def evidence_payload(response,boot):
    value=good(response)
    if not isinstance(value.get('data'),dict):raise FixtureContractRejected('EVIDENCE_ENVELOPE',value,('code','data'))
    data=value['data'];fields=('bootId','held','serverUuid','schema','events','verifiedFacts','inbox')
    valid=(set(data)==set(fields) and isinstance(data['bootId'],str) and type(data['held']) is bool
            and isinstance(data['serverUuid'],str) and isinstance(data['schema'],str)
            and isinstance(data['verifiedFacts'],list) and isinstance(data['inbox'],list))
    if not valid:raise FixtureContractRejected('EVIDENCE_DATA',data,fields)
    matches={'bootId':data['bootId']==boot['bootId'],'held':data['held'] is boot['guardHeld'],
             'serverUuid':data['serverUuid']==boot['serverUuid'],'schema':data['schema']==boot['schema']}
    if not all(matches.values()):raise FixtureContractRejected('EVIDENCE_IDENTITY',data,fields,bindings=matches)
    event_records(data['events'])
    for index,row in enumerate(data['verifiedFacts']):
        names=('id','fact_hash')
        if not (isinstance(row,dict) and set(row)==set(names) and type(row['id']) is int and row['id']>0
                and isinstance(row['fact_hash'],str) and re.fullmatch('[0-9a-f]{64}',row['fact_hash'])):
            raise FixtureContractRejected('VERIFIED_FACT_ROW',row,names,index)
    for index,row in enumerate(data['inbox']):
        names=('id','biz_id','state')
        if not (isinstance(row,dict) and set(row)==set(names) and type(row['id']) is int and row['id']>0
                and type(row['biz_id']) is int and row['biz_id']>0 and type(row['state']) is int):
            raise FixtureContractRejected('INBOX_ROW',row,names,index)
    return data


def callback_envelope(value):
    names=('originalOutcome','factId','factHash')
    if not isinstance(value,dict) or not isinstance(value.get('data'),dict):
        raise FixtureContractRejected('CALLBACK',value,('code','data'))
    data=value['data']
    if not (set(data)==set(names) and isinstance(data['originalOutcome'],str)
            and type(data['factId']) is int and data['factId']>0
            and isinstance(data['factHash'],str) and re.fullmatch('[0-9a-f]{64}',data['factHash'])):
        raise FixtureContractRejected('CALLBACK',data,names)
    return value


def quartz_log_rows(rows):
    fields=('invoke_target','status')
    if not isinstance(rows,list):raise FixtureContractRejected('QUARTZ_LOG_ROW',rows,fields)
    for index,row in enumerate(rows):
        if not (isinstance(row,dict) and set(row)==set(fields)
                and isinstance(row['invoke_target'],str) and row['status'] in ('0','1')):
            raise FixtureContractRejected('QUARTZ_LOG_ROW',row,fields,index)
    return rows


def assert_quartz_completion(rows,baseline,jobs):
    quartz_log_rows(rows)
    require(len(rows)>=baseline+2 and all(any(row['invoke_target']==target and row['status']=='0'
            for row in rows[baseline:]) for target in jobs),'real persisted Quartz task completion proof')


def held_observation(data,rows,boot,baseline,jobs):
    # Real validated journal/log readbacks only; these counts never replace original owner assertions.
    current=[row for row in data['events'] if row['bootId']==boot['bootId']]
    return {'bootId':boot['bootId'],'mode':boot['restoreMode'],'eventCount':len(data['events']),
            'currentBootStageCounts':{stage:sum(row['stage']==stage for row in current)
                    for stage in ('ENTER','RETURN','THROW','SQL')},
            'verifiedFactCount':len(data['verifiedFacts']),'inboxCount':len(data['inbox']),
            'quartzBaselineCount':baseline,'quartzObservedCount':len(rows),
            'requestedTargets':len(jobs),'completedTargetCount':sum(any(
                    row['invoke_target']==target and row['status']=='0' for row in rows[baseline:]) for target in jobs)}


def held_jobs(ids):
    targets=('beforeStartNotifyTask.run()','committedOfficialNoticeTask.deliverPending()')
    jobs=ids.get('jobs') if isinstance(ids,dict) else None
    if not (isinstance(jobs,dict) and set(jobs)==set(targets)
            and all(type(jobs[target]) is int and jobs[target]>0 for target in targets)
            and len(set(jobs.values()))==2):
        raise FixtureContractRejected('JOBS',jobs,targets)
    return jobs


def observe_phase_timing(slot,report,phase,substage):
    # Bounded relative timing only; never a cached identity/SQL/readiness observation. The
    # original constructor deadline is read, not moved. Every existing checkpoint remains.
    deadline=getattr(slot,'deadline',None)
    if type(deadline) not in (int,float):return # Source-only unit slots without a lifecycle clock.
    tick=time.monotonic()
    previous=getattr(slot,'_phase_timing_cursor',None)
    samples=report.setdefault('phaseElapsed',[])
    if previous is not None:
        elapsed=max(0,int((tick-previous['tick'])*1000))
        if len(samples)<128:
            samples.append({'phase':previous['phase'],'substage':previous['substage'],
                    'createdBootCount':previous['createdBootCount'],'elapsedMillis':elapsed})
        else:report['phaseElapsedTruncated']=True
    report['lifecycleTiming']={'budgetSeconds':1500,
            'elapsedMillis':max(0,int((tick-(deadline-1500))*1000)),
            'remainingMillis':max(0,int((deadline-tick)*1000))}
    slot._phase_timing_cursor={'tick':tick,'phase':phase,'substage':substage,
            'createdBootCount':len(getattr(slot,'owned',()))}


def checkpoint(slot,report,path,phase,substage):
    require(phase in RUN_PHASES and substage in SUBSTAGES,'fixed diagnostic checkpoint only')
    observe_phase_timing(slot,report,phase,substage)
    report['phase']=phase;report['substage']=substage
    slot.runtime_report_write(path,report)


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()


def sha(value):return hashlib.sha256(value).hexdigest()
def now():return dt.datetime.now(dt.timezone.utc).isoformat()


def typed(value):
    if value is None or isinstance(value,(str,int,bool)):return value
    if isinstance(value,bytes):return {'binary':value.hex()}
    if isinstance(value,Decimal):
        require(value.is_finite(),'non-finite SQL decimal');return {'decimal':str(value)}
    if isinstance(value,float):
        require(__import__('math').isfinite(value),'non-finite SQL double');return {'floatHex':value.hex()}
    if isinstance(value,dt.timedelta):
        return {'sqlTimeMicroseconds':(value.days*86400+value.seconds)*1000000+value.microseconds}
    if isinstance(value,(dt.datetime,dt.date,dt.time)):return {'temporal':value.isoformat()}
    raise ValueError('unrecognized SQL value; never normalize economics/time away')


def save(path,value):
    with Path(path).open('x') as stream:
        os.chmod(path,0o600);json.dump(value,stream,sort_keys=True,indent=2);stream.flush();os.fsync(stream.fileno())


def query(auth,port,schema,sql,args=None):
    with auth.database(port,schema) as db,db.cursor() as cur:
        if args is None:cur.execute(sql)
        else:cur.execute(sql,args)
        columns=[d[0] for d in cur.description]
        return [dict(zip(columns,map(typed,row))) for row in cur.fetchall()]


def snapshot(auth,port,schema,expected_uuid):
    require(isinstance(expected_uuid,str) and re.fullmatch('[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}',expected_uuid),
            'original snapshot UUID required')
    # One NEW connection per complete observation, never shared with a later stage/boot. The
    # original relay verifies its physical endpoint on each connection. Opening one connection
    # per table repeats that transport/identity handshake without making the row read fresher.
    # Autocommit is explicit and read back: this does NOT establish an RR transaction/snapshot.
    with auth.database(port,schema,autocommit=True) as db,db.cursor() as cur:
        cur.execute('SET SESSION information_schema_stats_expiry=0')
        cur.execute('SELECT @@SESSION.information_schema_stats_expiry,@@server_uuid,DATABASE()')
        identity=cur.fetchone()
        require(isinstance(identity,tuple) and len(identity)==3 and type(identity[0]) is int and identity[0]==0
                and identity[1]==expected_uuid and identity[2]==schema,
                'same-session current allocator metadata identity')
        cur.execute("SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME",(schema,))
        columns=[column[0] for column in cur.description]
        tables=[dict(zip(columns,map(typed,row))) for row in cur.fetchall()]
        names={row['TABLE_NAME'] for row in tables}
        require(REQUIRED_TABLES<=names,'full canonical schema/financial evidence tables required')
        cur.execute('SELECT @@SESSION.autocommit')
        actual_autocommit=cur.fetchone()
        require(isinstance(actual_autocommit,tuple) and len(actual_autocommit)==1
                and type(actual_autocommit[0]) is int and actual_autocommit[0]==1,
                'fresh per-statement snapshot reads require session autocommit')
        result={}
        for table in tables:
            name=table['TABLE_NAME'];require(re.fullmatch('[A-Za-z0-9_]+',name),'fixed canonical table identifier')
            if name in ALLOWED_MUTABLE:continue
            cur.execute('SELECT * FROM `'+name+'`')
            columns=[column[0] for column in cur.description]
            rows=[dict(zip(columns,map(typed,row))) for row in cur.fetchall()]
            result[name]={'rows':sorted(rows,key=lambda row:canonical(row)), 'nextId':table['AUTO_INCREMENT']}
        return result


def assert_equal(before,after,except_tables=()):
    require(set(before)==set(after),'table set drift')
    for table in before:
        if table not in except_tables:
            require(before[table]==after[table],'protected rows or ID high water changed: '+table)


def assert_complete(rows,actor,provider):
    withdrawals=rows['member_cash_withdraw']['rows']
    require(len(withdrawals)==1 and withdrawals[0]['member_id']==actor
            and withdrawals[0]['status']==1,'original controlled withdrawal must be terminal SUCCESS')
    wid=withdrawals[0]['id']
    require(withdrawals[0]['out_batch_no']=='CW'+str(wid) and withdrawals[0]['out_detail_no']=='CD'+str(wid),'original wire IDs')
    require(provider['withdrawalId']==wid and provider['amountFen']==3000 and provider['submits']==1
            and provider['state']=='SUCCESS' and provider['requestVersion']==1,'independent original provider identity')
    facts=rows['member_cash_withdraw_fund_fact']['rows']
    kinds=[r['kind'] for r in facts if r['withdrawal_id']==wid]
    require(kinds.count('DEBIT')==kinds.count('SUBMIT_INTENT')==kinds.count('SUCCESS')==1
            and kinds.count('ACCEPTED')==1 and set(kinds)=={'DEBIT','SUBMIT_INTENT','ACCEPTED','SUCCESS'},'complete original cash facts')
    balances=[r['balance'] for r in rows['ums_member']['rows'] if r['id']==actor]
    require(balances==[{'decimal':'70.00'}],'one real debit; no fabricated opening balance reconciliation')
    require(len(rows['game_command_receipt']['rows'])==1 and rows['game_command_receipt']['rows'][0]['outcome']=='APPLIED','original chapter receipt complete')
    debit=next(r for r in facts if r['withdrawal_id']==wid and r['kind']=='DEBIT')
    submit=next(r for r in facts if r['withdrawal_id']==wid and r['kind']=='SUBMIT_INTENT')
    success=next(r for r in facts if r['withdrawal_id']==wid and r['kind']=='SUCCESS')
    require(debit['balance_before']=={'decimal':'100.00'} and debit['balance_after']=={'decimal':'70.00'}
            and debit['amount']=={'decimal':'30.00'} and withdrawals[0]['balance_deduct_id']==debit['id'],'original debit ledger linkage and amounts')
    master=rows['member_cash_withdraw_transfer_intent']['rows']
    require(len(master)==1 and master[0]['withdraw_id']==wid and master[0]['member_id']==actor
            and master[0]['amount_fen']==3000 and master[0]['request_version']==1
            and master[0]['submit_time'] is not None and re.fullmatch('[0-9a-f]{64}',master[0]['request_hash']), 'master immutable V1 original')
    require(submit['request_hash']==provider['wireHash'] and submit['request_version']==1
            and success['submission_intent_id']==submit['id']
            and success['channel_batch_id']=='fixture-batch-'+str(wid), 'wire V1 and terminal original submission linkage')
    return wid


def public_snapshot(value):
    return {table:{'rowCount':len(data['rows']),'rowSha256':sha(canonical(data['rows'])),'nextId':data['nextId']}
            for table,data in value.items()}


def next_id_observation(value):
    # No unexpected string/object becomes a public allocator value.
    if value is None:return {'kind':'NULL','value':None}
    if type(value) is int and value>=0:return {'kind':'INTEGER','value':value}
    kinds={bool:'BOOLEAN',float:'FLOAT',str:'STRING',dict:'ENCODED',list:'ARRAY'}
    return {'kind':kinds.get(type(value),'OTHER'),'sha256':sha(canonical(value))}


def restore_difference_summary(before,after):
    """Fixed source roster only; neither an SQL selector nor a public row/body projection."""
    changes=[]
    for name in sorted(set(before)|set(after)):
        source=before.get(name);target=after.get(name);categories=[]
        if name not in before:categories.append('UNEXPECTED_TARGET_TABLE')
        elif name not in after:categories.append('MISSING_TARGET_TABLE')
        else:
            if len(source['rows'])!=len(target['rows']):categories.append('ROW_COUNT_DIFFER')
            if canonical(source['rows'])!=canonical(target['rows']):categories.append('TYPED_ROWS_DIFFER')
            if canonical(source['nextId'])!=canonical(target['nextId']):categories.append('NEXT_ID_DIFFER')
        if not categories:continue
        row={'categories':categories}
        if name in before and re.fullmatch('[A-Za-z0-9_]{1,64}',name):row['table']=name
        else:row['tableNameSha256']=sha(name.encode())
        for label,data in (('source',source),('target',target)):
            row[label]=None if data is None else {
                    'rowCount':len(data['rows']),'rowSha256':sha(canonical(data['rows'])),
                    'nextId':next_id_observation(data['nextId'])}
        changes.append(row)
    # Hashes cover the complete comparison, even when the fixed public detail budget is exhausted.
    return {'result':'MISMATCH','differenceCause':'UNKNOWN',
            'sourceTableCount':len(before),'targetTableCount':len(after),
            'sourceSnapshotSha256':sha(canonical(before)),'targetSnapshotSha256':sha(canonical(after)),
            'changedTableCount':len(changes),'detailLimit':COMPARE_TABLE_LIMIT,
            'detailsTruncated':len(changes)>COMPARE_TABLE_LIMIT,'tables':changes[:COMPARE_TABLE_LIMIT]}


def verify_restored_snapshot(before,after,slot,auth,source_port,target_port,schema,report,path):
    # Retain whole dictionaries and every original typed row/allocator; canonical bytes also forbid
    # Python's bool==int coercion from silently accepting a type-only change.
    if after==before and canonical(after)==canonical(before):return
    original=ValueError('canonical restore and independent complete rows/high waters')
    original.restore_reason_code='INVARIANT_REJECTED'
    try:
        diagnostic=restore_difference_summary(before,after)
        last=slot.stop_history[-1] if slot.stop_history else None
        binding={'sourceSha':report['sourceSha'],'runId':report['runId'],'runAttempt':report['runAttempt'],
                 'jarSha256':report['jarSha256'],'schema':schema,
                 'sourceServerUuid':report['sourceServerUuid'],'targetServerUuid':report['targetServerUuid'],
                 'canonicalToolSha256':sha((ROOT/'scripts/db-backup-restore.py').read_bytes()),
                 'canonicalBackup':report.get('canonicalBackupEvidence'),
                 'backupReturned':report.get('backupToolReturned') is True,
                 'restoreReturned':report.get('restoreToolReturned') is True,
                 'sourceStop':last}
        diagnostic['binding']=binding
        report['canonicalRestoreComparison']=diagnostic
        # Independent current UUID/database readback is evidence only; errors never replace mismatch.
        try:
            observed={}
            for side,port in (('source',source_port),('target',target_port)):
                rows=query(auth,port,schema,'SELECT @@server_uuid AS uuid,DATABASE() AS db')
                require(len(rows)==1 and isinstance(rows[0]['uuid'],str)
                        and re.fullmatch('[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}',rows[0]['uuid'])
                        and rows[0]['db']==schema,'original independent comparison identity')
                observed[side]={'serverUuid':rows[0]['uuid'],'schema':schema}
            binding['observed']=observed
            binding['identityResult']='MATCH' if (observed['source']['serverUuid']==report['sourceServerUuid']
                    and observed['target']['serverUuid']==report['targetServerUuid']
                    and binding['canonicalToolSha256']==CANONICAL_TOOL_SHA256) else 'MISMATCH'
        except BaseException as observation:
            binding['identityResult']='UNKNOWN'
            binding['identityFailure']=failure_diagnostic(observation,report['phase'],report.get('substage'))
        require(len(canonical(diagnostic))<=64*1024,'bounded canonical difference diagnostic')
        slot.runtime_report_write(path,report)
    except BaseException as diagnostic_failure:
        # Same original ValueError/reason/type even if hashing, identity readback or persistence fails.
        report['canonicalComparisonDiagnosticFailure']=failure_diagnostic(
                diagnostic_failure,report['phase'],report.get('substage'))
    raise original


def assert_late_callback(before,after,callback,registration):
    callback_envelope(callback)
    require(callback.get('code')==200 and callback.get('data',{}).get('originalOutcome')=='FAIL','original callback must remain paused, never fake ACK')
    # Only independent verified fact + its original WAIT inbox + immutable link can be appended.
    assert_equal(before,after,LATE_TABLES)
    for table in LATE_TABLES:
        require(table in before and table in after,'exact fact/link table required')
        previous=before[table]['rows'];current=after[table]['rows']
        require(all(row in current for row in previous),'old verified evidence changed or vanished')
        require(after[table]['nextId'] is None or (before[table]['nextId'] is not None
                and after[table]['nextId']>=before[table]['nextId']),'fact allocator regressed')
    facts=after['wx_verified_payment_fact']['rows'];inbox=after['wx_payment_callback_inbox']['rows']
    reference=callback['data']
    require(len(facts)==1 and facts[0]['id']==reference['factId'] and facts[0]['fact_hash']==reference['factHash'],'actual immutable fact readback')
    require(len(inbox)==1 and inbox[0]['biz_id']==registration and inbox[0]['state']==0,'WAIT original inbox, no economic consume or DONE')
    links=after['wx_payment_callback_fact_link']['rows']
    require(len(links)==1 and links[0]['inbox_id']==inbox[0]['id'] and links[0]['fact_id']==reference['factId']
            and links[0]['fact_hash']==reference['factHash'],'original verified fact/inbox binding')


def assert_callback_replay(before,after):
    assert_equal(before,after,LATE_TABLES)
    for table in LATE_TABLES:
        require(before[table]['rows']==after[table]['rows'],'callback replay added/changed facts or binding')
        a,b=before[table]['nextId'],after[table]['nextId']
        require(a==b if a is None or b is None else b>=a,'callback replay allocator regressed')
    # The exact raw before/after allocators are retained by the caller. INSERT IGNORE in the
    # original fact/inbox producer can reserve an unused ID; it is never an economic effect.


def assert_owner_events(events,boot):
    event_records(events)
    proven=set()
    current=[row for row in events if row['bootId']==boot]
    for symbol in ('CashWithdrawServiceImpl.withdraw','RefundServiceImpl.saveBackOfficeRefund',
                   'CommittedBusinessNotice.dispatchInNewTransaction'):
        rows=[r for r in current if r['symbol']==symbol]
        require(any(r['stage']=='ENTER' and r['held'] is True for r in rows),'actual owner not entered: '+symbol)
        require(any(r['stage']=='THROW' and r.get('blocked') is True
                    and r.get('errorType')=='com.chengyinhub.common.config.RestoreDispatchGuard$Blocked'
                    for r in rows),'actual owner did not throw real Blocked: '+symbol)
        proven.add(symbol)
    for symbol in ('BeforeStartNotifyTask.run','CommittedOfficialNoticeTask.deliverPending','CommittedOfficialNoticeService.reconcilePending'):
        rows=[r for r in current if r['symbol']==symbol]
        require(any(r['stage']=='ENTER' and r['held'] is True for r in rows)
                and any(r['stage']=='RETURN' and r['held'] is True for r in rows),'real Quartz/notice wake not observed: '+symbol)
        proven.add(symbol)
    require(not any(r['symbol']=='TeamGroupServiceImpl.notifyBeforeStartBatch' for r in current),'before-start guard did not pause the real sweep')
    require(not any(r['stage']=='SQL' and r['symbol'].endswith(('CommittedOfficialNoticeWxMapper.maxPendingId','CommittedOfficialNoticeWxMapper.selectPendingPage')) for r in current),'notice recovery guard did not pause the real pending selector')
    return [{'symbol':symbol,'entered':True} for symbol in sorted(proven)]


def good(response):
    status,value=response
    if not (type(status) is int and status==200 and isinstance(value,dict)
            and type(value.get('code')) is int and value['code']==200):
        failure=ValueError('real HTTP operation not successful')
        failure.restore_reason_code='HTTP_OPERATION_REJECTED';raise failure
    return value


def refused(response):
    status,value=response;require(status in (200,400,401,403,409,500,503) and value.get('code')!=200,'unexpected accepted write or HTTP shape')


def defaults(path,port):
    password=os.environ['CHENGYIN_AUTH_FIXTURE_DB_PASSWORD']
    require(re.fullmatch('[A-Za-z0-9_-]{24,64}',password),'original random fixture secret format')
    with path.open('x') as stream:
        path.chmod(0o600);stream.write('[client]\nhost=127.0.0.1\nprotocol=TCP\nuser=root\nport='+str(port)+'\npassword='+password+'\n')


def start_application(slot,report,path,phase,side,mode,uuid,seed=False):
    # Persist intent before entering start, including validation/create/lost-reply failures.
    report['applicationStartAttempted']=True
    checkpoint(slot,report,path,phase,'APPLICATION_SLOT_START')
    boot=slot.start(side,mode,uuid,seed=seed)
    # Readiness is separate from a completed workload; original five-boot acceptance stays intact.
    report.setdefault('readyBootObservations',[]).append({k:boot[k] for k in
            ('bootId','side','restoreMode','guardHeld','jarSha256','appCid','javaPid','sourceSha','serverUuid','schema','schedulerStarted')})
    checkpoint(slot,report,path,phase,'APPLICATION_READY')
    return boot


def source_quiescent_snapshot(slot, auth, port, schema, report, path):
    """The original source backup cannot read its final SQL state until exact JVM stop succeeds."""
    checkpoint(slot,report,path,'SOURCE_WORKLOAD','SOURCE_QUIESCENT_STOP')
    require(getattr(slot,'ready_for_current_launch',False), 'source application must be ready before quiescent SQL snapshot')
    slot.stop(remove=False)
    checkpoint(slot,report,path,'SOURCE_WORKLOAD','QUIESCENT_SQL_SNAPSHOT')
    return snapshot(auth,port,schema,slot.boots[-1]['serverUuid'])


def normal_readback_rows(slot,auth,port,schema,expected_uuid,before,original_provider_bytes):
    # The report is the exact complete observation just checked, not a second SQL sample and
    # not a cache for any later baseline, action, callback, stop or boot.
    observed=snapshot(auth,port,schema,expected_uuid)
    require(observed==before,'normal original-ID readback created an effect/high-water change')
    require((slot.root/'withdrawal-provider.json').read_bytes()==original_provider_bytes,'normal boot repeated provider submit/query')
    return public_snapshot(observed)


def completed_held_rows(auth,port,schema,expected_uuid,stable):
    # The app has already passed its ready-stop fence. One complete post-stop read supplies the
    # original full-table report AND all three allocator fields at the same observation point.
    completed=snapshot(auth,port,schema,expected_uuid)
    return {'protectedRowsAfter':public_snapshot(completed),
            'callbackReplayHighWaters':{table:{'before':stable[table]['nextId'],
                                             'after':completed[table]['nextId']} for table in LATE_TABLES}}


def run(name,schema,out,credentials):
    runtime=load('restore_http_application_slot','ci/restore_application_http_runtime.py');runtime.gate(os.environ)
    out=Path(out).resolve();slot=runtime.ApplicationSlot(name,schema,out,credentials);auth=slot.auth
    tool=load('restore_http_original_canonical_backup','scripts/db-backup-restore.py')
    report={'sourceSha':os.environ['GITHUB_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],
            'jarSha256':slot.jar_hash,'schema':schema,'result':'NOT_COMPLETED','productionDispatchRelease':False,
            'scope':'test-owned full Spring HTTP restore startup; controlled original withdrawal-complete and late payment gap only',
            'sourceOnlyUntilHostedRun':False,'boots':[],'completeBoots':[],'checks':[],
            'ownedAppCids':[],'removedAppCids':[],'readyBootObservations':[],'applicationStartAttempted':False,
            'phase':'PRE_APPLICATION_PREPARATION','resourceCleanup':'PENDING_OUTER_OWNER'}
    failure=None
    report['sourceMysqlCid']=slot.source['id'];report['targetMysqlCid']=slot.target['id'];report['redisCid']=slot.redis['Id']
    report['networkId']=slot.source['networkId']
    report_path=out/'restore-http-application.json'
    require(not report_path.exists() and not report_path.is_symlink(),'never adopt a previous application report')
    slot.runtime_report_write(report_path,report)
    try:
        require(subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()==os.environ['GITHUB_SHA'],'exact source checkout')
        with zipfile.ZipFile(slot.jar) as package:
            props=dict(line.split('=',1) for line in package.read('BOOT-INF/classes/git.properties').decode().splitlines() if '=' in line and not line.startswith('#'))
            require(props.get('git.commit.id.full',props.get('git.commit.id'))==os.environ['GITHUB_SHA'],'actual full JAR source')
            require(not any('RestoreHttpApplicationChild' in p for p in package.namelist()),'test child must not ship in normal JAR')
        source_ready=json.loads((out/'schema-summary.json').read_text())
        target_ready=json.loads((out/'restore-mysql-readiness.json').read_text())['mysqlReadiness']
        require(source_ready['nativeJarSha256']==slot.jar_hash,'original owner JAR changed')
        report['phase']='SOURCE_DATABASE_PREPARATION'
        with slot.relay('source') as sp,slot.relay('target') as tp:
            identities={}
            for side,port in (('source',sp),('target',tp)):
                value=query(auth,port,None,'SELECT @@server_uuid AS uuid,VERSION() AS version,@@global.event_scheduler AS scheduler')[0]
                readiness=source_ready['mysqlReadiness'] if side=='source' else target_ready
                endpoint=slot.source if side=='source' else slot.target
                require(value['uuid']==readiness['serverUuid'] and value['version']==readiness['version']
                        and value['scheduler']=='OFF' and readiness['endpoint']==endpoint,'actual readiness/UUID/CID/network')
                identities[side]=value
            require(identities['source']['uuid']!=identities['target']['uuid']
                    and identities['source']['version']==identities['target']['version'],'different UUID same MySQL version')
            report['sourceServerUuid']=identities['source']['uuid'];report['targetServerUuid']=identities['target']['uuid']
            # Only this fresh, test-owned source; preserve all definitions, disable incidental cron scheduling
            # before any app is launched. The existing targets are manually invoked through original HTTP.
            with auth.database(sp,schema) as db,db.cursor() as cur:
                cur.execute('SELECT COUNT(*) FROM cms_registration');require(cur.fetchone()==(0,),'fresh source business fixture')
                cur.execute("UPDATE sys_job SET status='1' WHERE status<>'1'")
            start_application(slot,report,report_path,'SOURCE_APPLICATION_START','source','false',identities['source']['uuid'],seed=True)
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','READ_ORIGINAL_IDENTITIES')
            ids=json.loads((slot.root/'identities.json').read_text());actor=ids['a']['memberId'];a=ids['a']['token'];b=ids['b']['token']
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','ADMIN_LOGIN')
            admin=slot.login_admin();slot.redactions(a,b)
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','ADMIN_IDENTITY_READ')
            good(slot.http('/getInfo',admin))
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','ORIGINAL_CHAPTER_COMMAND')
            good(slot.http('/api/club/lead/unlock-chapter',a,ids['chapter'],form=True))
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','ORIGINAL_WITHDRAW_COMMAND')
            first=good(slot.http('/api/wallet/withdraw',a,{'amount':'30.00'}))
            wid=first['data']['id']
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','ORIGINAL_WITHDRAW_REQUERY')
            good(slot.http('/business/cashwithdraw/requery/'+str(wid),admin,{}))
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','READ_ORIGINAL_PROVIDER')
            original_provider=json.loads((slot.root/'withdrawal-provider.json').read_text())
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','COMPLETE_FACTS_SQL')
            before=snapshot(auth,sp,schema,identities['source']['uuid']);assert_complete(before,actor,original_provider)
            original_provider_bytes=(slot.root/'withdrawal-provider.json').read_bytes()
            receipt_path='/api/club/lead/unlock-chapter-receipt?'+urllib.parse.urlencode({k:ids['chapter'][k] for k in ('activityId','commandId')})
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','ORIGINAL_CHAPTER_RECEIPT')
            original_receipt=good(slot.http(receipt_path,a))
            checkpoint(slot,report,report_path,'SOURCE_WORKLOAD','NETWORK_PROOF')
            slot.verify_network()
            before=source_quiescent_snapshot(slot,auth,sp,schema,report,report_path)
            report['backupRows']=public_snapshot(before)
            report['providerJournalSha256']=sha(original_provider_bytes)
            report['checks'].append({'amountFen':3000,'openingBalance':'100.00','finalBalance':'70.00',
                                     'case':'original_complete_cohort','withdrawalId':wid,'actorId':actor,
                                     'commandIdentityHash':sha(canonical(ids['chapter'])),'providerSubmits':1})
            paths={side:slot.root/(side+'.cnf') for side in ('source','target')}
            checkpoint(slot,report,report_path,'CANONICAL_BACKUP_RESTORE','CANONICAL_BACKUP')
            defaults(paths['source'],sp);defaults(paths['target'],tp)
            args={side:argparse.Namespace(defaults_file=paths[side],database=schema,server_uuid=identities[side]['uuid'],confirm_test_target=True)
                  for side in ('source','target')}
            args['source'].output=slot.root/'backup.sql';report['backupStarted']=now()
            with contextlib.redirect_stdout(io.StringIO()):tool.backup(args['source'])
            report['backupCompleted']=now();report['backupToolReturned']=True
            report['canonicalBackupEvidence']={'canonicalToolSha256':sha((ROOT/'scripts/db-backup-restore.py').read_bytes()),
                    'dumpSha256':tool.digest(args['source'].output),
                    'receiptSha256':tool.digest(Path(str(args['source'].output)+'.json'))}
            checkpoint(slot,report,report_path,'CANONICAL_BACKUP_RESTORE','EMPTY_TARGET_CREATE')
            with auth.database(tp) as db,db.cursor() as cur:
                cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s',(schema,));require(cur.fetchone()==(0,),'never adopt a target schema')
                with auth.database(sp) as source,source.cursor() as src:
                    src.execute('SHOW CREATE DATABASE `'+schema+'`');ddl=src.fetchone()[1]
                require(ddl.startswith('CREATE DATABASE `'+schema+'`'),'original database definition')
                cur.execute(ddl)
            args['target'].backup=args['source'].output;report['restoreStarted']=now();tick=time.monotonic()
            checkpoint(slot,report,report_path,'CANONICAL_BACKUP_RESTORE','CANONICAL_RESTORE')
            with contextlib.redirect_stdout(io.StringIO()):tool.restore(args['target'])
            report['restoreCompleted']=now();report['restoreSeconds']=time.monotonic()-tick;report['restoreToolReturned']=True
            checkpoint(slot,report,report_path,'CANONICAL_BACKUP_RESTORE','RESTORED_SQL_COMPARE')
            restored=snapshot(auth,tp,schema,identities['target']['uuid'])
            verify_restored_snapshot(before,restored,slot,auth,sp,tp,schema,report,report_path)
            # mysql.user is outside the dump. Only the existing resource owner can install this runtime
            # account, with this exact schema DML grant. No root application datasource is accepted.
            checkpoint(slot,report,report_path,'CANONICAL_BACKUP_RESTORE','TARGET_RUNTIME_USER')
            auth.command(['python3','-B',str(ROOT/'ci/authenticated_redis_recovery_probe.py'),'restore-runtime-user',
                          name,schema,str(out),str(Path(credentials).resolve()),'0'],timeout=60)
            runtime_proof=json.loads((out/'restore-runtime-user.json').read_text())
            require(runtime_proof['sourceSha']==os.environ['GITHUB_SHA'] and runtime_proof['schema']==schema
                    and runtime_proof['serverUuid']==identities['target']['uuid'] and runtime_proof['mysqlCid']==slot.target['id']
                    and runtime_proof['runtimeUser']=='startup_probe'
                    and runtime_proof['result']=='OWNED_RESTORED_RUNTIME_USER_PREPARED','original owner runtime-user proof')
            report['runtimeUser']=runtime_proof
            runtime_user=query(auth,tp,None,"SELECT COUNT(*) AS n FROM mysql.user WHERE User='startup_probe' AND Host='%'")[0]['n']
            require(runtime_user==1,'root owner must install original restricted target runtime user after restore')
            grants=query(auth,tp,None,'SELECT TABLE_SCHEMA,PRIVILEGE_TYPE,IS_GRANTABLE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE=%s', ("'startup_probe'@'%'",))
            require(len(grants)==4 and {(r['TABLE_SCHEMA'],r['PRIVILEGE_TYPE'],r['IS_GRANTABLE']) for r in grants}
                    =={(schema,p,'NO') for p in ('SELECT','INSERT','UPDATE','DELETE')},'independent original runtime-user CRUD only')
            global_grants=query(auth,tp,None,'SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE=%s',("'startup_probe'@'%'",))
            require(all(r['PRIVILEGE_TYPE']=='USAGE' for r in global_grants),'no global runtime grant')
            # Explicit separate false boot only for the independently proven complete original cohort.
            start_application(slot,report,report_path,'RESTORED_NORMAL_APPLICATION_START','target','false',identities['target']['uuid'])
            checkpoint(slot,report,report_path,'RESTORED_NORMAL_READBACK','NORMAL_ORIGINAL_READBACK')
            require(good(slot.http(receipt_path,a))==original_receipt,'normal test-owned original receipt readback')
            good(slot.http('/api/wallet/withdraws',a,{}));good(slot.http('/business/cashwithdraw/requery/'+str(wid),admin,{}))
            report['normalReadbackRows']=normal_readback_rows(slot,auth,tp,schema,identities['target']['uuid'],
                    before,original_provider_bytes)
            slot.verify_network();slot.stop();report['normalReadbackCompleted']=now()
            # No normal boot occurs below: introducing the independent late SUCCESS intentionally creates
            # a difference that remains held. It is never auto-repaired/reissued or marked READY.
            for mode in HELD_MODES:
                boot=start_application(slot,report,report_path,'RESTORED_HELD_APPLICATION_START','target',mode,identities['target']['uuid'])
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_OWNER_WORKLOAD')
                jobs=held_jobs(ids)
                progress={'bootId':boot['bootId'],'mode':mode,'completedStages':[]}
                report.setdefault('heldWorkloads',[]).append(progress)
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_BASELINE_SQL')
                mark=snapshot(auth,tp,schema,identities['target']['uuid'])
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_RECEIPT_READBACK')
                require(good(slot.http(receipt_path,a))==original_receipt,'held original receipt unreadable')
                progress['completedStages'].append('ORIGINAL_RECEIPT_READBACK')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_FOREIGN_REFUSAL')
                denied=slot.http(receipt_path,b);refused(denied);require(not denied[1].get('data'),'other actor received original receipt')
                refused(slot.http('/__restore_http/evidence',b))
                assert_equal(mark,snapshot(auth,tp,schema,identities['target']['uuid']))
                progress['completedStages'].append('FOREIGN_READ_REFUSED_ZERO_EFFECT')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_BLOCKED_CHAPTER')
                changed=dict(ids['chapter'],targetChapterId=ids['chapter']['targetChapterId']+1)
                refused(slot.http('/api/club/lead/unlock-chapter',a,changed,form=True));assert_equal(mark,snapshot(auth,tp,schema,identities['target']['uuid']))
                progress['completedStages'].append('CHANGED_CHAPTER_REFUSED_ZERO_EFFECT')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_BLOCKED_MONEY')
                refused(slot.http('/api/wallet/withdraw',a,{'amount':'30.00'}))
                refused(slot.http('/business/refund',admin,{'registrationId':ids['registrationId'],'refundAmount':'1.00'}))
                assert_equal(mark,snapshot(auth,tp,schema,identities['target']['uuid']))
                progress['completedStages'].append('WITHDRAW_REFUND_REFUSED_ZERO_EFFECT')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_NOTICE_PENDING')
                # Actual durable notice intent is committed before attempting the original blocked wake.
                refused(slot.http('/__restore_http/notice',admin,{}))
                pending=snapshot(auth,tp,schema,identities['target']['uuid'])
                notice=pending['committed_official_notice_wx']['rows']
                require(len(notice)==1 and notice[0]['wx_state']=='PENDING','real eligible original pending notice required')
                with auth.database(tp,schema) as db,db.cursor() as cur:
                    cur.execute('SELECT COUNT(*) FROM sys_job_log');logs=cur.fetchone()[0]
                progress['completedStages'].append('ORIGINAL_NOTICE_PENDING')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_QUARTZ_REQUEST')
                for job in jobs.values():good(slot.http('/monitor/job/run',admin,{'jobId':job},method='PUT'))
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_QUARTZ_OBSERVE')
                evidence=None
                until=time.monotonic()+45
                while time.monotonic()<until:
                    evidence=evidence_payload(slot.http('/__restore_http/evidence',admin),boot)
                    entered={r['symbol'] for r in evidence['events'] if r['bootId']==boot['bootId'] and r['stage']=='RETURN'}
                    observed_logs=quartz_log_rows(query(auth,tp,schema,'SELECT invoke_target,status FROM sys_job_log ORDER BY job_log_id'))
                    report['heldObservation']=held_observation(evidence,observed_logs,boot,logs,jobs)
                    if ({'BeforeStartNotifyTask.run','CommittedOfficialNoticeTask.deliverPending','CommittedOfficialNoticeService.reconcilePending'}<=entered
                            and len(observed_logs)>=logs+2 and all(any(r['invoke_target']==target and r['status']=='0' for r in observed_logs[logs:]) for target in jobs)):break
                    time.sleep(.2)
                require(evidence is not None,'real Quartz window produced no validated evidence response')
                actual=assert_owner_events(evidence['events'],boot['bootId'])
                assert_equal(pending,snapshot(auth,tp,schema,identities['target']['uuid']))
                observed_logs=quartz_log_rows(query(auth,tp,schema,'SELECT invoke_target,status FROM sys_job_log ORDER BY job_log_id'))
                report['heldObservation']=held_observation(evidence,observed_logs,boot,logs,jobs)
                assert_quartz_completion(observed_logs,logs,jobs)
                progress['completedStages'].append('REAL_QUARTZ_OWNERS_AND_LOGS')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_LATE_CALLBACK')
                prior=snapshot(auth,tp,schema,identities['target']['uuid'])
                callback=callback_envelope(good(slot.http('/__restore_http/verified-callback',admin,{})))
                assert_late_callback(prior,snapshot(auth,tp,schema,identities['target']['uuid']),callback,ids['registrationId'])
                progress['completedStages'].append('ORIGINAL_FACT_AND_WAIT_INBOX')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_LATE_READBACK')
                readback=evidence_payload(slot.http('/__restore_http/evidence',admin),boot)
                require(readback['verifiedFacts']==[{'id':callback['data']['factId'],'fact_hash':callback['data']['factHash']}]
                        and len(readback['inbox'])==1 and readback['inbox'][0]['biz_id']==ids['registrationId']
                        and readback['inbox'][0]['state']==0,'HTTP authenticated original fact and WAIT inbox readback')
                progress['completedStages'].append('AUTHENTICATED_ORIGINAL_FACT_READBACK')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_REPLAY')
                stable=snapshot(auth,tp,schema,identities['target']['uuid'])
                callback_replay=callback_envelope(good(slot.http('/__restore_http/verified-callback',admin,{})))
                require(callback_replay==callback,'same original verified callback identity')
                assert_callback_replay(stable,snapshot(auth,tp,schema,identities['target']['uuid']))
                progress['completedStages'].append('EXACT_ORIGINAL_CALLBACK_REPLAY')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_PROVIDER_FENCE')
                require((slot.root/'withdrawal-provider.json').read_bytes()==original_provider_bytes,'provider original record changed')
                require(not (slot.root/'provider-attempts.jsonl').exists(),'controlled transport fence was entered; not a production guard proof')
                progress['completedStages'].append('ORIGINAL_PROVIDER_UNCHANGED_ZERO_SEND')
                checkpoint(slot,report,report_path,'RESTORED_HELD_WORKLOAD','HELD_NETWORK_STOP')
                slot.verify_network();slot.stop(remove=False)
                progress['completedStages'].append('EXACT_NETWORK_AND_READY_STOP')
                completed_rows=completed_held_rows(auth,tp,schema,identities['target']['uuid'],stable)
                report['boots'].append({k:boot[k] for k in ('bootId','javaPid','sourceSha','jarSha256','serverUuid','schema','restoreMode','guardHeld','schedulerStarted','appCid','side')})
                report['checks'].append({'protectedRowsBefore':public_snapshot(mark),**completed_rows,
                                         'providerJournalSha256':sha(original_provider_bytes),
                                         'case':'held-full-http-and-actual-wake','mode':mode,'owners':actual,
                                         'lateCallback':'FACT_AND_WAIT_INBOX_PRESERVED_NO_ECONOMIC_CONSUME','newDispatches':0})
            require(len({b['bootId'] for b in slot.boots})==5,'five distinct real application processes')
            observe_phase_timing(slot,report,report['phase'],report.get('substage'))
            report['completeBoots']=[{k:b[k] for k in ('bootId','side','restoreMode','guardHeld','jarSha256','appCid',
                                    'javaPid','sourceSha','serverUuid','schema','schedulerStarted')} for b in slot.boots[:2]]
            report['result']='SCOPED_RESTORE_HTTP_STARTUP_AND_REAL_OWNER_HOLD_VERIFIED'
            report['candidateState']='TEST_OWNED_LATE_FACT_GAP_BLOCKED'
            report['RPO']='NOT_A_PRODUCTION_RPO_MEASUREMENT'
            report['RTO']='FIXTURE_ONLY_RESTORE_TO_ORIGINAL_READBACK_TIMESTAMPS'
            report['fullHttpApplicationStartup']='ACTUAL_SPRING_SECURITY_MVC_QUARTZ_ON_RESTORED_FULL_SCHEMA'
    except BaseException as exc:
        failure=exc;report['firstFailureType']=type(exc).__name__;report['firstFailurePhase']=report['phase']
        observe_phase_timing(slot,report,report['phase'],report.get('substage'))
        report['firstFailureDiagnostic']=failure_diagnostic(exc,report['phase'],report.get('substage'))
        print('Restore HTTP failure diagnostic: '+json.dumps(report['firstFailureDiagnostic'],sort_keys=True),
              file=__import__('sys').stderr)
        raise
    finally:
        try:
            slot.stop(remove=False)
            report['applicationStop']='STOPPED_EXACT_APP_CID' if slot.cid else 'NO_ACTIVE_APP_SLOT'
            if slot.cid:report['stoppedApp']=slot.stopped
            if hasattr(slot,'stop_proof_kind'):report['applicationStopProofKind']=slot.stop_proof_kind
        except BaseException as cleanup:
            report['applicationStop']='UNKNOWN'
            if hasattr(slot,'stopped'):report['stoppedApp']=slot.stopped
            report['stopFailureDiagnostic']=failure_diagnostic(cleanup,'APPLICATION_STOP','APPLICATION_STOP')
            if failure is None:
                failure=cleanup;report['firstFailurePhase']='APPLICATION_STOP'
                report['firstFailureDiagnostic']=report['stopFailureDiagnostic']
        # Safe fixed markers inside the existing JSON, not raw logs or another artifact channel.
        try:report['applicationDiagnostic']=slot.diagnostic_summary()
        except BaseException as diagnostic:
            report['applicationDiagnostic']={'result':'UNKNOWN',
                    'failure':failure_diagnostic(diagnostic,report['phase'],report.get('substage'))}
        if hasattr(slot,'lifecycle_budget_observation'):report['lastBudgetObservation']=slot.lifecycle_budget_observation
        if hasattr(slot,'last_http'):report['lastHttpObservation']=slot.last_http
        if hasattr(slot,'last_callback_observation'):report['lastCallbackObservation']=slot.last_callback_observation
        report['ownedAppCids']=slot.owned;report['removedAppCids']=slot.removed
        report['applicationStopHistory']=slot.stop_history
        if getattr(slot,'start_transition',None) is not None:report['applicationStartTransition']=dict(slot.start_transition)
        report['productionDispatchRelease']=False
        report['allDispatchDomainsFenced']='NOT_CLAIMED_SPECIFIC_REAL_OWNERS_ONLY'
        if report.get('applicationStop')!='UNKNOWN':
            try:
                # Persist complete safe stop/log evidence before erasing any private raw archive.
                # The last original stopped container remains for the unchanged outer owner's logs.
                slot.verify_all_stopped_logs()
                slot.runtime_report_write(report_path,report)
                # Raw dumps, tokens, original provider store, per-boot logs and traces stay private.
                for path in sorted(slot.root.rglob('*'),key=lambda p:len(p.parts),reverse=True):
                    require(not path.is_symlink(),'unexpected private symlink; retain failure')
                    if path.is_file():path.unlink()
                    elif path.is_dir():path.rmdir()
                    else:raise ValueError('unknown private resource; retain failure')
                slot.root.rmdir();report['privateCleanup']='EXACT_OWN_SUBTREE_REMOVED'
                report['applicationLogPrivateErasure']='VERIFIED_PRIVATE_CAPTURES_ERASED_AFTER_REPORT'
            except BaseException as cleanup:
                report['privateCleanup']='UNKNOWN'
                if failure is None:
                    failure=cleanup;report['firstFailurePhase']='PRIVATE_CLEANUP'
                    report['firstFailureDiagnostic']=failure_diagnostic(cleanup,'PRIVATE_CLEANUP','PRIVATE_CLEANUP')
        if failure is not None:report.setdefault('firstFailureType',type(failure).__name__)
        slot.runtime_report_write(report_path,report)
        save(out/'restore-http-provenance.json',{'sourceSha':os.environ['GITHUB_SHA'],'jarSha256':slot.jar_hash,
                'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],
                'classPostimages':slot.class_hashes,'canonicalToolSha256':sha((ROOT/'scripts/db-backup-restore.py').read_bytes()),
                'sourceMysqlCid':slot.source['id'],'targetMysqlCid':slot.target['id'],
                'fullHttpBoundary':'TEST_CLASSPATH_CHILD_PLUS_UNCHANGED_PRODUCTION_JAR',
                'providerBoundary':'CONTROLLED_WITHDRAWAL_WIRE_AND_HISTORICAL_PAYMENT_FACT_NOT_WECHAT_SDK_OR_SIGNATURE_ACCEPTANCE'})
        if failure is not None:raise failure


def read_final_report(path,name,schema):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size<=8*1024*1024,'bounded regular report required')
    report=json.loads(path.read_text())
    require(isinstance(report,dict),'report object required')
    require(report.get('sourceSha')==os.environ['GITHUB_SHA'] and report.get('schema')==schema
            and report.get('runId')==os.environ['GITHUB_RUN_ID'] and report.get('runAttempt')==os.environ['GITHUB_RUN_ATTEMPT'],
            'source/run/attempt-bound result required')
    require(re.fullmatch('[0-9a-f]{64}',report.get('jarSha256','')),'original JAR hash required')
    require(type(report.get('applicationStartAttempted')) is bool,'explicit start-attempt evidence required')
    for key in ('boots','completeBoots','checks','ownedAppCids','removedAppCids'):
        require(isinstance(report.get(key),list),'explicit lifecycle lists required')
    for key in ('ownedAppCids','removedAppCids'):
        require(all(isinstance(cid,str) and re.fullmatch('[0-9a-f]{64}',cid) for cid in report[key]),'exact app CIDs required')
        require(len(report[key])==len(set(report[key])),'duplicate app CID')
    resources=[report.get(key) for key in ('sourceMysqlCid','targetMysqlCid','redisCid')]
    require(all(isinstance(cid,str) and re.fullmatch('[0-9a-f]{64}',cid) for cid in resources)
            and len(set(resources))==3,'three distinct owned backing resources required')
    require(report.get('phase') in RUN_PHASES,'fixed run phase required')
    if 'firstFailureType' in report:
        require(isinstance(report['firstFailureType'],str) and re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,95}',report['firstFailureType'])
                and report.get('firstFailurePhase') in RUN_PHASES,'stable failure phase/type required')
    return report


def confirmed_pre_start_failure(report):
    # F's original owner must ALSO independently confirm the exact app slot is absent.
    # Neither exception name nor an empty CID list alone establishes that create was never attempted.
    return (report.get('applicationStartAttempted') is False and report.get('result')=='NOT_COMPLETED'
            and report.get('firstFailurePhase') in PRE_START_PHASES
            and isinstance(report.get('firstFailureType'),str)
            and re.fullmatch('[A-Za-z_][A-Za-z0-9_]{0,95}',report['firstFailureType']) is not None
            and report.get('boots')==[] and report.get('completeBoots')==[] and report.get('checks')==[]
            and report.get('ownedAppCids')==[] and report.get('removedAppCids')==[]
            and report.get('applicationStop')=='NO_ACTIVE_APP_SLOT'
            and report.get('privateCleanup')=='EXACT_OWN_SUBTREE_REMOVED'
            and report.get('productionDispatchRelease') is False)


def assert_successful_lifecycle(report):
    require(report['applicationStartAttempted'] is True and not report.get('firstFailureType'),'successful start evidence required')
    boots=report['completeBoots']+report['boots'];owned=report['ownedAppCids']
    require(len(report['completeBoots'])==2 and len(report['boots'])==3 and len(boots)==len(owned)==5
            and len(set(owned))==5 and len({b['bootId'] for b in boots})==5,'five independent original app boots required')
    require(report['removedAppCids']==owned[:-1] and report['applicationStop']=='STOPPED_EXACT_APP_CID','original final app retained for owner log cleanup')
    require(report['sourceServerUuid']!=report['targetServerUuid'] and re.fullmatch('[0-9a-f]{64}',report['networkId']),
            'distinct original UUIDs and owned network required')
    for number,(boot,mode) in enumerate(zip(boots,('false','false',*HELD_MODES))):
        side='source' if number==0 else 'target'
        uuid=report['sourceServerUuid'] if side=='source' else report['targetServerUuid']
        require(boot['appCid']==owned[number] and boot['side']==side and boot['restoreMode']==mode
                and boot['guardHeld'] is (mode!='false') and boot['schedulerStarted'] is True
                and boot['sourceSha']==report['sourceSha'] and boot['schema']==report['schema']
                and boot['serverUuid']==uuid and boot['jarSha256']==report['jarSha256']
                and type(boot['javaPid']) is int and boot['javaPid']==1,'actual original boot identity differs')
    history=report.get('applicationStopHistory')
    require(isinstance(history,list) and [row['cid'] for row in history]==owned
            and report.get('applicationLogPrivateErasure')=='VERIFIED_PRIVATE_CAPTURES_ERASED_AFTER_REPORT',
            'all original stopped log captures required')
    for boot,row in zip(boots,history):
        identity=row['identity'];state=row['stopped'];log=row['log']
        require(row['proofKind']=='READY_JVM_EXIT_CONFIRMED' and row['bootId']==boot['bootId']
                and row['sourceSha']==report['sourceSha'] and row['jarSha256']==report['jarSha256']
                and identity['cid']==row['cid'] and identity['bootId']==boot['bootId']
                and identity['sourceSha']==report['sourceSha'] and identity['jarSha256']==report['jarSha256']
                and type(identity['hostPid']) is int and identity['hostPid']>0
                and re.fullmatch('[1-9][0-9]*',identity['startTicks'])
                and all(re.fullmatch('[0-9a-f]{64}',identity[key]) for key in
                        ('cmdlineSha256','configSha256','readySha256'))
                and state['cid']==row['cid'] and state['running'] is False and state['oomKilled'] is False
                and state['restarting'] is False and type(state['hostPid']) is int and state['hostPid']==0
                and type(state['exitCode']) is int and state['exitCode'] in (0,143)
                and log['result']=='COMPLETE_PRIVATE_CAPTURE_VERIFIED' and type(log['bytes']) is int
                and 0<=log['bytes']<=8*1024*1024 and re.fullmatch('[0-9a-f]{64}',log['sha256']),
                'original app stop/log proof differs')
    require(len(report['checks'])==4 and report['checks'][0]['case']=='original_complete_cohort'
            and report['checks'][0]['providerSubmits']==1
            and [row['mode'] for row in report['checks'][1:]]==list(HELD_MODES)
            and all(row['case']=='held-full-http-and-actual-wake' and row['newDispatches']==0 for row in report['checks'][1:]),
            'original complete facts and three hold workloads required')


def finalize(name,schema,out,original_exit):
    runtime=load('restore_http_finalize_slot','ci/restore_application_http_runtime.py');runtime.gate(os.environ)
    require(re.fullmatch('cy-auth-[0-9a-f]{32}',name) and schema=='cy_e2e_auth_'+name[8:],'exact final scope')
    require(type(original_exit) is int and original_exit>=0,'original exit required')
    auth=load('restore_http_finalize_owner','ci/authenticated_redis_recovery_probe.py')
    out=Path(out);report=None;original=None;removed=[];owner_resources_verified=False
    proof={'sourceSha':os.environ['GITHUB_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],
           'schema':schema,'originalExit':original_exit,'resourceCleanup':'UNKNOWN','absence':[],
           'productionDispatchRelease':False,'result':'NOT_ACCEPTED','finalizationFailures':[]}
    def reject(phase,failure):
        # Never serialize str(failure), an untrusted report field, SQL or an inner exception.
        proof['finalizationFailures'].append({'phase':phase,'type':type(failure).__name__})
    try:
        report=read_final_report(out/'restore-http-application.json',name,schema)
        proof['applicationStartAttempted']=report['applicationStartAttempted']
        proof['confirmedPreStartFailure']=confirmed_pre_start_failure(report)
        if report.get('firstFailureType'):
            proof['firstFailurePhase']=report['firstFailurePhase'];proof['firstFailureType']=report['firstFailureType']
    except (OSError,UnicodeError,ValueError,KeyError,TypeError) as failure:
        reject('READ_APPLICATION_REPORT',failure)
    try:
        path=out/'authenticated-redis-recovery.json'
        require(path.is_file() and not path.is_symlink() and path.stat().st_size<=8*1024*1024,'bounded owner report required')
        original=json.loads(path.read_text());require(isinstance(original,dict),'owner report object required')
        cleanup=original.get('resourceCleanup',{})
        require(isinstance(cleanup,dict) and cleanup.get('cleanup')=='CONFIRMED_EXACT_OWNED_REMOVAL','original owner cleanup required')
        removed=cleanup.get('ownedRemoved',[])
        require(isinstance(removed,list) and len(removed) in (3,4),'original created-resource roster required')
        names={name+'-'+suffix for suffix in ('app','mysql','restore-mysql','redis')}
        require(all(isinstance(row,dict) and row.get('name') in names and isinstance(row.get('id'),str)
                    and re.fullmatch('[0-9a-f]{64}',row['id']) and row.get('independentNoSuch') is True for row in removed),
                'exact original owner removal rows required')
        require(len({row['name'] for row in removed})==len(removed) and len({row['id'] for row in removed})==len(removed),
                'duplicate owner removal identity')
        actual={row['name']:row['id'] for row in removed}
        require({name+'-'+suffix for suffix in ('mysql','restore-mysql','redis')}<=set(actual),'three owned backing resources required')
        if report is not None:
            expected={name+'-mysql':report['sourceMysqlCid'],name+'-restore-mysql':report['targetMysqlCid'],name+'-redis':report['redisCid']}
            require(all(actual.get(key)==cid for key,cid in expected.items()),'original backing CIDs differ')
        removed=[{'name':row['name'],'id':row['id'],'independentNoSuch':True} for row in removed]
        owner_resources_verified=True
        proof['originalOwnerCleanup']={'cleanup':cleanup['cleanup'],'ownedRemoved':removed}
        log_status=original.get('applicationLogStatus')
        require(log_status in ('PRESERVED','NOT_STARTED','UNAVAILABLE'),'explicit original log status required')
        proof['applicationLogStatus']=log_status
        if log_status=='NOT_STARTED':
            require(report is not None and confirmed_pre_start_failure(report) and name+'-app' not in actual
                    and original.get('applicationLogPreserved') is False,'strict no-start proof required')
        elif log_status=='PRESERVED':require(original.get('applicationLogPreserved') is True,'actual app log proof required')
        else:reject('APPLICATION_LOG_UNAVAILABLE',ValueError())
    except (OSError,UnicodeError,ValueError,KeyError,TypeError) as failure:
        original=None;reject('READ_OR_VERIFY_OWNER_CLEANUP',failure)
    targets=[('container',name+'-'+suffix) for suffix in ('app','restore-mysql','mysql','redis')]+[('network',name)]
    known=list(report['ownedAppCids']) if report is not None else []
    # Only validated owner rows may add exact CIDs to the independent absence checks.
    if owner_resources_verified:known.extend(row['id'] for row in removed)
    targets.extend(('container',cid) for cid in dict.fromkeys(known))
    absence_ok=True
    for kind,target in targets:
        command=auth.DOCKER+(['network','inspect',target] if kind=='network' else ['container','inspect',target])
        try:
            value=subprocess.run(command,capture_output=True,timeout=15)
            auth.require_absent(kind,target,value.returncode,value.stderr)
            proof['absence'].append({'kind':kind,'id':target,'absent':True})
        except (OSError,subprocess.TimeoutExpired,RuntimeError,ValueError) as failure:
            absence_ok=False;proof['absence'].append({'kind':kind,'id':target,'absent':False})
            reject('INDEPENDENT_RESOURCE_ABSENCE',failure)
    if owner_resources_verified and absence_ok:
        # Resource removal can be proven even when business never started or failed. It is not acceptance.
        proof['resourceCleanup']='CONFIRMED_EXACT_OWNED_REMOVAL'
    success=(report is not None and report.get('result')=='SCOPED_RESTORE_HTTP_STARTUP_AND_REAL_OWNER_HOLD_VERIFIED'
             and not report.get('firstFailureType') and report.get('privateCleanup')=='EXACT_OWN_SUBTREE_REMOVED')
    if success:
        try:
            assert_successful_lifecycle(report)
            require(original is not None and original.get('applicationLogStatus')=='PRESERVED','success requires original app logs')
            actual={row['name']:row['id'] for row in removed}
            expected={name+'-app':report['ownedAppCids'][-1],name+'-mysql':report['sourceMysqlCid'],
                      name+'-restore-mysql':report['targetMysqlCid'],name+'-redis':report['redisCid']}
            require(len(removed)==4 and actual==expected,'original owner exact four known CIDs')
        except (ValueError,KeyError,TypeError) as failure:
            success=False;reject('VERIFY_SUCCESSFUL_APPLICATION_LIFECYCLE',failure)
    if original_exit==0 and success and absence_ok and not proof['finalizationFailures']:
        proof['resourceCleanup']='CLEAN'
        proof['result']='SCOPED_HTTP_RESTORE_EVIDENCE_COMPLETE_NO_PRODUCTION_RELEASE'
    save(out/'restore-http-cleanup.json',proof)
    require(proof['result']!='NOT_ACCEPTED','original failure cannot become green during finalize')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase',choices=('run','finalize'))
    parser.add_argument('name');parser.add_argument('schema');parser.add_argument('out',type=Path);parser.add_argument('last')
    args=parser.parse_args()
    if args.phase=='run':run(args.name,args.schema,args.out,Path(args.last))
    else:finalize(args.name,args.schema,args.out,int(args.last))


if __name__=='__main__':
    try:main()
    except BaseException as failure:
        # Never echo credentials, raw HTTP/provider projections, SQL or inner exception strings.
        print('Restore HTTP fixture failed: '+type(failure).__name__,file=__import__('sys').stderr)
        raise SystemExit(1)
