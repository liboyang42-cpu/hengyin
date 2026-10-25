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
ALLOWED_MUTABLE={'sys_oper_log','sys_job_log','sys_logininfor'}
LATE_TABLES={'wx_verified_payment_fact','wx_payment_callback_inbox','wx_payment_callback_fact_link'}
REQUIRED_TABLES={'ums_member','member_cash_withdraw','member_cash_withdraw_fund_fact','member_cash_withdraw_transfer_intent',
                 'cms_registration','cms_registration_payment_fact','game_command_receipt','club_lead_state',
                 'committed_official_notice_wx','wx_verified_payment_fact','wx_payment_callback_inbox'}


def load(name,relative):
    spec=importlib.util.spec_from_file_location(name,ROOT/relative)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


def require(value,reason):
    if not value:raise ValueError(reason)


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


def snapshot(auth,port,schema):
    tables=query(auth,port,None,'SELECT TABLE_NAME,AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s AND TABLE_TYPE=\'BASE TABLE\' ORDER BY TABLE_NAME',(schema,))
    names={row['TABLE_NAME'] for row in tables}
    require(REQUIRED_TABLES<=names,'full canonical schema/financial evidence tables required')
    result={}
    for table in tables:
        name=table['TABLE_NAME'];require(re.fullmatch('[A-Za-z0-9_]+',name),'fixed canonical table identifier')
        if name in ALLOWED_MUTABLE:continue
        rows=query(auth,port,schema,'SELECT * FROM `'+name+'`')
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


def assert_late_callback(before,after,callback,registration):
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
    current=[row for row in events if row['bootId']==boot]
    for symbol in ('CashWithdrawServiceImpl.withdraw','RefundServiceImpl.saveBackOfficeRefund',
                   'CommittedBusinessNotice.dispatchInNewTransaction'):
        rows=[r for r in current if r['symbol']==symbol]
        require(any(r['stage']=='ENTER' and r['held'] is True for r in rows),'actual owner not entered: '+symbol)
        require(any(r['stage']=='THROW' and r.get('blocked') is True
                    and r.get('errorType')=='com.chengyinhub.common.config.RestoreDispatchGuard$Blocked'
                    for r in rows),'actual owner did not throw real Blocked: '+symbol)
    for symbol in ('BeforeStartNotifyTask.run','CommittedOfficialNoticeTask.deliverPending','CommittedOfficialNoticeService.reconcilePending'):
        rows=[r for r in current if r['symbol']==symbol]
        require(any(r['stage']=='ENTER' and r['held'] is True for r in rows)
                and any(r['stage']=='RETURN' and r['held'] is True for r in rows),'real Quartz/notice wake not observed: '+symbol)
    require(not any(r['symbol']=='TeamGroupServiceImpl.notifyBeforeStartBatch' for r in current),'before-start guard did not pause the real sweep')
    require(not any(r['stage']=='SQL' and r['symbol'].endswith(('CommittedOfficialNoticeWxMapper.maxPendingId','CommittedOfficialNoticeWxMapper.selectPendingPage')) for r in current),'notice recovery guard did not pause the real pending selector')
    return [{'symbol':symbol,'entered':True} for symbol in sorted({r['symbol'] for r in current})]


def good(response):
    status,value=response;require(status==200 and value.get('code')==200,'real HTTP operation not successful');return value


def refused(response):
    status,value=response;require(status in (200,400,401,403,409,500,503) and value.get('code')!=200,'unexpected accepted write or HTTP shape')


def defaults(path,port):
    password=os.environ['CHENGYIN_AUTH_FIXTURE_DB_PASSWORD']
    require(re.fullmatch('[A-Za-z0-9_-]{24,64}',password),'original random fixture secret format')
    with path.open('x') as stream:
        path.chmod(0o600);stream.write('[client]\nhost=127.0.0.1\nprotocol=TCP\nuser=root\nport='+str(port)+'\npassword='+password+'\n')


def run(name,schema,out,credentials):
    runtime=load('restore_http_application_slot','ci/restore_application_http_runtime.py');runtime.gate(os.environ)
    out=Path(out).resolve();slot=runtime.ApplicationSlot(name,schema,out,credentials);auth=slot.auth
    tool=load('restore_http_original_canonical_backup','scripts/db-backup-restore.py')
    report={'sourceSha':os.environ['GITHUB_SHA'],'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],
            'jarSha256':slot.jar_hash,'schema':schema,'result':'NOT_COMPLETED','productionDispatchRelease':False,
            'scope':'test-owned full Spring HTTP restore startup; controlled original withdrawal-complete and late payment gap only',
            'sourceOnlyUntilHostedRun':False,'boots':[],'checks':[],'resourceCleanup':'PENDING_OUTER_OWNER'}
    failure=None
    report['sourceMysqlCid']=slot.source['id'];report['targetMysqlCid']=slot.target['id'];report['redisCid']=slot.redis['Id']
    try:
        require(subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip()==os.environ['GITHUB_SHA'],'exact source checkout')
        with zipfile.ZipFile(slot.jar) as package:
            props=dict(line.split('=',1) for line in package.read('BOOT-INF/classes/git.properties').decode().splitlines() if '=' in line and not line.startswith('#'))
            require(props.get('git.commit.id.full',props.get('git.commit.id'))==os.environ['GITHUB_SHA'],'actual full JAR source')
            require(not any('RestoreHttpApplicationChild' in p for p in package.namelist()),'test child must not ship in normal JAR')
        source_ready=json.loads((out/'schema-summary.json').read_text())
        target_ready=json.loads((out/'restore-mysql-readiness.json').read_text())['mysqlReadiness']
        require(source_ready['nativeJarSha256']==slot.jar_hash,'original owner JAR changed')
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
            # Only this fresh, test-owned source; preserve all definitions, disable incidental cron scheduling
            # before any app is launched. The existing targets are manually invoked through original HTTP.
            with auth.database(sp,schema) as db,db.cursor() as cur:
                cur.execute('SELECT COUNT(*) FROM cms_registration');require(cur.fetchone()==(0,),'fresh source business fixture')
                cur.execute("UPDATE sys_job SET status='1' WHERE status<>'1'")
            slot.start('source','false',identities['source']['uuid'],seed=True)
            ids=json.loads((slot.root/'identities.json').read_text());actor=ids['a']['memberId'];a=ids['a']['token'];b=ids['b']['token']
            admin=slot.login_admin();slot.redactions(a,b)
            good(slot.http('/getInfo',admin))
            good(slot.http('/api/club/lead/unlock-chapter',a,ids['chapter'],form=True))
            first=good(slot.http('/api/wallet/withdraw',a,{'amount':'30.00'}))
            wid=first['data']['id']
            good(slot.http('/business/cashwithdraw/requery/'+str(wid),admin,{}))
            original_provider=json.loads((slot.root/'withdrawal-provider.json').read_text())
            before=snapshot(auth,sp,schema);assert_complete(before,actor,original_provider)
            original_provider_bytes=(slot.root/'withdrawal-provider.json').read_bytes()
            receipt_path='/api/club/lead/unlock-chapter-receipt?'+urllib.parse.urlencode({k:ids['chapter'][k] for k in ('activityId','commandId')})
            original_receipt=good(slot.http(receipt_path,a))
            slot.verify_network();slot.stop()
            before=snapshot(auth,sp,schema) # Quiescent snapshot after real Quartz graceful shutdown.
            report['backupRows']=public_snapshot(before)
            report['providerJournalSha256']=sha(original_provider_bytes)
            report['checks'].append({'amountFen':3000,'openingBalance':'100.00','finalBalance':'70.00',
                                     'case':'original_complete_cohort','withdrawalId':wid,'actorId':actor,
                                     'commandIdentityHash':sha(canonical(ids['chapter'])),'providerSubmits':1})
            paths={side:slot.root/(side+'.cnf') for side in ('source','target')}
            defaults(paths['source'],sp);defaults(paths['target'],tp)
            args={side:argparse.Namespace(defaults_file=paths[side],database=schema,server_uuid=identities[side]['uuid'],confirm_test_target=True)
                  for side in ('source','target')}
            args['source'].output=slot.root/'backup.sql';report['backupStarted']=now()
            with contextlib.redirect_stdout(io.StringIO()):tool.backup(args['source'])
            report['backupCompleted']=now()
            with auth.database(tp) as db,db.cursor() as cur:
                cur.execute('SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=%s',(schema,));require(cur.fetchone()==(0,),'never adopt a target schema')
                with auth.database(sp) as source,source.cursor() as src:
                    src.execute('SHOW CREATE DATABASE `'+schema+'`');ddl=src.fetchone()[1]
                require(ddl.startswith('CREATE DATABASE `'+schema+'`'),'original database definition')
                cur.execute(ddl)
            args['target'].backup=args['source'].output;report['restoreStarted']=now();tick=time.monotonic()
            with contextlib.redirect_stdout(io.StringIO()):tool.restore(args['target'])
            report['restoreCompleted']=now();report['restoreSeconds']=time.monotonic()-tick
            require(snapshot(auth,tp,schema)==before,'canonical restore and independent complete rows/high waters')
            # mysql.user is outside the dump. Only the existing resource owner can install this runtime
            # account, with this exact schema DML grant. No root application datasource is accepted.
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
            slot.start('target','false',identities['target']['uuid'])
            require(good(slot.http(receipt_path,a))==original_receipt,'normal test-owned original receipt readback')
            good(slot.http('/api/wallet/withdraws',a,{}));good(slot.http('/business/cashwithdraw/requery/'+str(wid),admin,{}))
            require(snapshot(auth,tp,schema)==before,'normal original-ID readback created an effect/high-water change')
            require((slot.root/'withdrawal-provider.json').read_bytes()==original_provider_bytes,'normal boot repeated provider submit/query')
            report['normalReadbackRows']=public_snapshot(snapshot(auth,tp,schema))
            slot.verify_network();slot.stop();report['normalReadbackCompleted']=now()
            # No normal boot occurs below: introducing the independent late SUCCESS intentionally creates
            # a difference that remains held. It is never auto-repaired/reissued or marked READY.
            for mode in HELD_MODES:
                boot=slot.start('target',mode,identities['target']['uuid'])
                mark=snapshot(auth,tp,schema)
                require(good(slot.http(receipt_path,a))==original_receipt,'held original receipt unreadable')
                denied=slot.http(receipt_path,b);refused(denied);require(not denied[1].get('data'),'other actor received original receipt')
                refused(slot.http('/__restore_http/evidence',b))
                assert_equal(mark,snapshot(auth,tp,schema))
                changed=dict(ids['chapter'],targetChapterId=ids['chapter']['targetChapterId']+1)
                refused(slot.http('/api/club/lead/unlock-chapter',a,changed,form=True));assert_equal(mark,snapshot(auth,tp,schema))
                refused(slot.http('/api/wallet/withdraw',a,{'amount':'30.00'}))
                refused(slot.http('/business/refund',admin,{'registrationId':ids['registrationId'],'refundAmount':'1.00'}))
                assert_equal(mark,snapshot(auth,tp,schema))
                # Actual durable notice intent is committed before attempting the original blocked wake.
                refused(slot.http('/__restore_http/notice',admin,{}))
                pending=snapshot(auth,tp,schema)
                notice=pending['committed_official_notice_wx']['rows']
                require(len(notice)==1 and notice[0]['wx_state']=='PENDING','real eligible original pending notice required')
                with auth.database(tp,schema) as db,db.cursor() as cur:
                    cur.execute('SELECT COUNT(*) FROM sys_job_log');logs=cur.fetchone()[0]
                for job in ids['jobs'].values():good(slot.http('/monitor/job/run',admin,{'jobId':job},method='PUT'))
                until=time.monotonic()+45
                while time.monotonic()<until:
                    evidence=good(slot.http('/__restore_http/evidence',admin))['data']
                    entered={r['symbol'] for r in evidence['events'] if r['bootId']==boot['bootId'] and r['stage']=='RETURN'}
                    observed_logs=query(auth,tp,schema,'SELECT invoke_target,status FROM sys_job_log ORDER BY job_log_id')
                    if ({'BeforeStartNotifyTask.run','CommittedOfficialNoticeTask.deliverPending','CommittedOfficialNoticeService.reconcilePending'}<=entered
                            and len(observed_logs)>=logs+2 and all(any(r['invoke_target']==target and r['status']=='0' for r in observed_logs[logs:]) for target in ids['jobs'])):break
                    time.sleep(.2)
                actual=assert_owner_events(evidence['events'],boot['bootId'])
                assert_equal(pending,snapshot(auth,tp,schema))
                observed_logs=query(auth,tp,schema,'SELECT invoke_target,status FROM sys_job_log ORDER BY job_log_id')
                require(len(observed_logs)>=logs+2 and all(any(r['invoke_target']==target and r['status']=='0' for r in observed_logs[logs:])
                        for target in ids['jobs']),'real persisted Quartz task completion proof')
                prior=snapshot(auth,tp,schema)
                callback=good(slot.http('/__restore_http/verified-callback',admin,{}))
                assert_late_callback(prior,snapshot(auth,tp,schema),callback,ids['registrationId'])
                readback=good(slot.http('/__restore_http/evidence',admin))['data']
                require(readback['verifiedFacts']==[{'id':callback['data']['factId'],'fact_hash':callback['data']['factHash']}]
                        and len(readback['inbox'])==1 and readback['inbox'][0]['biz_id']==ids['registrationId']
                        and readback['inbox'][0]['state']==0,'HTTP authenticated original fact and WAIT inbox readback')
                stable=snapshot(auth,tp,schema)
                callback_replay=good(slot.http('/__restore_http/verified-callback',admin,{}))
                require(callback_replay==callback,'same original verified callback identity')
                assert_callback_replay(stable,snapshot(auth,tp,schema))
                require((slot.root/'withdrawal-provider.json').read_bytes()==original_provider_bytes,'provider original record changed')
                require(not (slot.root/'provider-attempts.jsonl').exists(),'controlled transport fence was entered; not a production guard proof')
                slot.verify_network();slot.stop(remove=False)
                report['boots'].append({k:boot[k] for k in ('bootId','javaPid','sourceSha','jarSha256','serverUuid','schema','restoreMode','guardHeld','schedulerStarted','appCid')})
                report['checks'].append({'protectedRowsBefore':public_snapshot(mark),'protectedRowsAfter':public_snapshot(snapshot(auth,tp,schema)),
                                         'providerJournalSha256':sha(original_provider_bytes),
                                         'callbackReplayHighWaters':{table:{'before':stable[table]['nextId'],'after':snapshot(auth,tp,schema)[table]['nextId']} for table in LATE_TABLES},
                                         'case':'held-full-http-and-actual-wake','mode':mode,'owners':actual,
                                         'lateCallback':'FACT_AND_WAIT_INBOX_PRESERVED_NO_ECONOMIC_CONSUME','newDispatches':0})
            require(len({b['bootId'] for b in slot.boots})==5,'five distinct real application processes')
            report['completeBoots']=[{k:b[k] for k in ('bootId','side','restoreMode','guardHeld','jarSha256','appCid')} for b in slot.boots[:2]]
            report['result']='SCOPED_RESTORE_HTTP_STARTUP_AND_REAL_OWNER_HOLD_VERIFIED'
            report['candidateState']='TEST_OWNED_LATE_FACT_GAP_BLOCKED'
            report['RPO']='NOT_A_PRODUCTION_RPO_MEASUREMENT'
            report['RTO']='FIXTURE_ONLY_RESTORE_TO_ORIGINAL_READBACK_TIMESTAMPS'
            report['fullHttpApplicationStartup']='ACTUAL_SPRING_SECURITY_MVC_QUARTZ_ON_RESTORED_FULL_SCHEMA'
    except BaseException as exc:
        failure=exc;report['firstFailureType']=type(exc).__name__
        raise
    finally:
        try:
            slot.stop(remove=False)
            report['applicationStop']='STOPPED_EXACT_APP_CID' if slot.cid else 'NO_ACTIVE_APP_SLOT'
            if slot.cid:report['stoppedApp']=slot.stopped
        except BaseException as cleanup:
            report['applicationStop']='UNKNOWN'
            if failure is None:failure=cleanup
        report['ownedAppCids']=slot.owned;report['removedAppCids']=slot.removed
        report['productionDispatchRelease']=False
        report['allDispatchDomainsFenced']='NOT_CLAIMED_SPECIFIC_REAL_OWNERS_ONLY'
        if report.get('applicationStop')!='UNKNOWN':
            try:
                # Raw dumps, tokens, original provider store and traces never enter artifacts.
                # Stop precedes erasure; keep the final stopped container for original log cleanup.
                for path in sorted(slot.root.rglob('*'),key=lambda p:len(p.parts),reverse=True):
                    require(not path.is_symlink(),'unexpected private symlink; retain failure')
                    if path.is_file():path.unlink()
                    elif path.is_dir():path.rmdir()
                    else:raise ValueError('unknown private resource; retain failure')
                slot.root.rmdir();report['privateCleanup']='EXACT_OWN_SUBTREE_REMOVED'
            except BaseException as cleanup:
                report['privateCleanup']='UNKNOWN'
                if failure is None:failure=cleanup
        if failure is not None:report.setdefault('firstFailureType',type(failure).__name__)
        save(out/'restore-http-application.json',report)
        save(out/'restore-http-provenance.json',{'sourceSha':os.environ['GITHUB_SHA'],'jarSha256':slot.jar_hash,
                'runId':os.environ['GITHUB_RUN_ID'],'runAttempt':os.environ['GITHUB_RUN_ATTEMPT'],
                'classPostimages':slot.class_hashes,'canonicalToolSha256':sha((ROOT/'scripts/db-backup-restore.py').read_bytes()),
                'sourceMysqlCid':slot.source['id'],'targetMysqlCid':slot.target['id'],
                'fullHttpBoundary':'TEST_CLASSPATH_CHILD_PLUS_UNCHANGED_PRODUCTION_JAR',
                'providerBoundary':'CONTROLLED_WITHDRAWAL_WIRE_AND_HISTORICAL_PAYMENT_FACT_NOT_WECHAT_SDK_OR_SIGNATURE_ACCEPTANCE'})
        if failure is not None:raise failure


def finalize(name,schema,out,original_exit):
    runtime=load('restore_http_finalize_slot','ci/restore_application_http_runtime.py');runtime.gate(os.environ)
    require(re.fullmatch('cy-auth-[0-9a-f]{32}',name) and schema=='cy_e2e_auth_'+name[8:],'exact final scope')
    auth=load('restore_http_finalize_owner','ci/authenticated_redis_recovery_probe.py')
    report=json.loads((Path(out)/'restore-http-application.json').read_text())
    require(report['sourceSha']==os.environ['GITHUB_SHA'] and report['schema']==schema
            and report['runId']==os.environ['GITHUB_RUN_ID'] and report['runAttempt']==os.environ['GITHUB_RUN_ATTEMPT'],'source/run/attempt-bound result')
    checks=[]
    for kind,target in [('container',c) for c in report['ownedAppCids']]+[(kind,target) for kind,target in
            [('container',name+'-'+suffix) for suffix in ('app','restore-mysql','mysql','redis')]+[('network',name)]]:
        command=auth.DOCKER+(['network','inspect',target] if kind=='network' else ['container','inspect',target])
        value=subprocess.run(command,capture_output=True,timeout=15)
        auth.require_absent(kind,target,value.returncode,value.stderr.decode());checks.append({'kind':kind,'id':target,'absent':True})
    original=json.loads((Path(out)/'authenticated-redis-recovery.json').read_text())
    cleanup=original.get('resourceCleanup',{})
    require(cleanup.get('cleanup')=='CONFIRMED_EXACT_OWNED_REMOVAL','original owner cleanup must be confirmed')
    expected={name+'-app':report['ownedAppCids'][-1],name+'-mysql':report['sourceMysqlCid'],
              name+'-restore-mysql':report['targetMysqlCid'],name+'-redis':report['redisCid']}
    removed=cleanup.get('ownedRemoved',[])
    require(len(removed)==4 and {row['name']:row['id'] for row in removed}==expected
            and all(row.get('independentNoSuch') is True for row in removed),'original owner exact four known CIDs')
    proof={'sourceSha':report['sourceSha'],'schema':schema,'originalExit':original_exit,'resourceCleanup':'CLEAN',
           'absence':checks,'productionDispatchRelease':False,'result':'NOT_ACCEPTED'}
    if original_exit==0 and report['result']=='SCOPED_RESTORE_HTTP_STARTUP_AND_REAL_OWNER_HOLD_VERIFIED' and report.get('privateCleanup')=='EXACT_OWN_SUBTREE_REMOVED':
        proof['result']='SCOPED_HTTP_RESTORE_EVIDENCE_COMPLETE_NO_PRODUCTION_RELEASE'
    save(Path(out)/'restore-http-cleanup.json',proof)
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
