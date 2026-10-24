#!/usr/bin/env python3
"""Compare ONLY the 14 original M1 storage receipts. Does not run tests/Java/SQL/CI."""
import argparse,collections,copy,hashlib,json,re,sys,xml.etree.ElementTree as ET
from pathlib import Path
from decimal import Decimal

class EvidenceError(Exception):pass
def need(value,message):
    if not value:raise EvidenceError(message)
def stable(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def number(value):
    need(isinstance(value,dict) and value.get('kind')=='number','missing typed numeric fact')
    return Decimal(value['value'])
def key(row):
    need('id' in row,'seed-date fixture row has no original id')
    return stable(row['id'])
def proven_ledger_rounding_endpoint(value,document,source_row):
    # Only the already registered ledger Date column may use this exact DATETIME(0) boundary.
    if source_row is None or 'TIME_TRUNCATE_FRACTIONAL' in document['environment'].get('sql_mode','').split(','):return False
    columns=document['initial']['tables']['ums_member_points_detail']['columns']
    if not any(c.get('name')=='create_time' and c.get('typeName')=='DATETIME' and c.get('scale')==0 for c in columns):return False
    committed={e['transaction'] for e in document['rawTrace'] if e['kind']=='tx.complete' and e.get('completion')==0}
    delta=document['timestampCodec']['roundtripEpochMillis']-document['timestampCodec']['anchorEpochMillis']
    fields={'memberId':'member_id','eventType':'event_type','eventId':'event_id','currentPoints':'current_points','afterPoints':'after_points','changePoints':'change_points','changeType':'change_type'}
    for event in document['rawTrace']:
        if event['kind']!='db.end' or event.get('operation')!='update' or event.get('statement')!='com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.insertUmsMemberPointsDetail':continue
        if 'exception' in event or event.get('transaction') not in committed or number(event['result'])!=1:continue
        params={p['property']:p['value'] for p in event['parameters']}
        if not all(a in params and b in source_row and number(params[a])==number(source_row[b]) for a,b in fields.items()):continue
        bound=params.get('createTime')
        if not isinstance(bound,dict) or bound.get('kind')!='time':continue
        if not document['jvmStartMillis']<=bound['epochMillis']<=document['jvmEndMillis']:continue
        if ((bound['epochMillis']+delta+500)//1000)*1000==value['epochMillis']:return True
    return False

def operation_time(value,identity,document,persisted=False,source_row=None):
    if value is None:return None
    need(isinstance(value,dict) and value.get('kind')=='time','untyped operation date '+identity)
    low=(document['jvmStartMillis']//1000)*1000
    high=(document['jvmEndMillis']//1000+1)*1000-1
    codec=document['timestampCodec'];delta=codec['roundtripEpochMillis']-codec['anchorEpochMillis'] if persisted else 0
    in_window=low<=value['epochMillis']-delta<=high
    rounded_endpoint=persisted and identity=='ums_member_points_detail.create_time' and value['epochMillis']-delta==high+1 and proven_ledger_rounding_endpoint(value,document,source_row)
    need(in_window or rounded_endpoint,'operation date outside exact case/one-second precision + observed JDBC codec window: '+identity)
    return {'kind':'operationTime','javaType':value['javaType'],'field':identity}
def snapshot(value,document,manifest):
    need(value['schema']==document['initial']['schema'],'schema changed inside a case')
    original=document['initial']['tables'];result={'schema':value['schema'],'tables':{}}
    for table,content in sorted(value['tables'].items()):
        rows=[]
        for raw in content['rows']:
            row=copy.deepcopy(raw)
            for column in manifest['normalization']['seed_dates_unchanged'].get(table,[]):
                need(table in original,'missing original seeded table '+table)
                matches=[r for r in original[table]['rows'] if key(r)==key(raw)]
                need(len(matches)==1,'missing/duplicate original seeded identity')
                need(raw[column]==matches[0][column],'seeded date changed within case: '+table+'.'+column)
                if raw[column] is not None:row[column]={'kind':'seedTimeUnchanged','javaType':raw[column]['javaType'],'table':table,'column':column,'id':raw['id']}
            for column in manifest['normalization']['operation_dates'].get(table,[]):
                if column in row:row[column]=operation_time(raw[column],table+'.'+column,document,persisted=True,source_row=raw)
            rows.append(row)
        result['tables'][table]={'columns':content['columns'],'rows':sorted(rows,key=stable)}
    return result

def sql_parameters(event,document,manifest):
    allowed=manifest['normalization']['operation_parameters'].get(event.get('statement'),[])
    result=copy.deepcopy(event.get('parameters',[]))
    for param in result:
        if param['property'] in allowed:
            param['value']=operation_time(param['value'],event['statement']+'.'+param['property'],document)
    return result

def invariants(document):
    events=document['rawTrace'];need(any(e.get('kind')=='db.begin' for e in events),'no actual JDBC execution evidence');need([e['seq'] for e in events]==sorted({e['seq'] for e in events}),'duplicate/unordered raw event sequence')
    need(all(e['call']>0 for e in events),'unbound SQL/transaction/call event')
    txs={};claims=[]
    ledger_id='com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.insertUmsMemberPointsDetail'
    debit_id='com.chengyinhub.business.mapper.UmsMemberMapper.deductPoints'
    for event in events:
        tx=event.get('transaction')
        if event['kind']=='tx.complete':
            need(event['logicalCall']==event['call'],'transaction completion lost its logical call context')
            need(tx not in txs,'duplicate transaction completion')
            need(event['completion'] in (0,1),'UNKNOWN transaction completion')
            txs[tx]=event['completion']
        if event['kind']=='db.begin' and event.get('operation')=='update':
            need(event['actualTransaction'] and tx!='NONE','business write outside original transaction')
            if event['statement']==debit_id:
                params={p['property']:p['value'] for p in event['parameters']}
                before=[e for e in events if e['seq']<event['seq'] and e['kind']=='db.end' and e.get('operation')=='update' and e.get('statement')==ledger_id and e.get('transaction')==tx and 'exception' not in e and number(e['result'])==1]
                matching=[]
                for row in before:
                    p={v['property']:v['value'] for v in row['parameters']}
                    if p.get('memberId')==params.get('id') and number(p['changePoints'])==number(params['point']):matching.append(row)
                need(matching,'member debit has no earlier successful same-transaction ledger claim')
        if event['kind']=='db.end' and event.get('operation')=='update' and event.get('statement')==ledger_id and 'exception' not in event and number(event['result'])==1:
            p={v['property']:v['value'] for v in event['parameters']}
            need(number(p['currentPoints'])-number(p['changePoints'])==number(p['afterPoints']),'ledger before/after arithmetic drift')
            need(number(p['changeType'])==2,'unexpected non-debit fixture ledger')
    active={e['transaction'] for e in events if e.get('transaction') not in (None,'NONE')}
    need(active==set(txs),'missing transaction completion')
    first=document['initial']['tables'];last=document['final']['tables']
    initial_members={stable(r['id']):number(r['point']) for r in first['ums_member']['rows']}
    # SQL FK equality is numeric across BIGINT UNSIGNED (BigInteger) and BIGINT (Long).
    # Keep all raw typed identities and canonical snapshots unchanged.
    member_keys={number(r['id']):stable(r['id']) for r in first['ums_member']['rows']}
    need(len(member_keys)==len(initial_members),'duplicate numeric member identity')
    final_members={stable(r['id']):number(r['point']) for r in last['ums_member']['rows']}
    need(initial_members.keys()==final_members.keys(),'member identity set changed')
    need(not first['ums_member_points_detail']['rows'],'fixture starts with an unexpected prior ledger')
    successes=[e for e in events if e.get('kind')=='db.end' and e.get('operation')=='update' and e.get('statement')==ledger_id and 'exception' not in e and number(e['result'])==1]
    need(len(successes)>=len(last['ums_member_points_detail']['rows']),'committed ledger rows lack actual SQL evidence')
    for frame in document['calls']:
        if document['case']=='twoConnectionsSerializeOriginalRegistrationAndDebitOnce':
            own=[e for e in events if e['call']==frame['call']]
            need(any(e.get('kind')=='db.end' and e.get('statement','').endswith('.selectCmsRegistrationByIdForUpdate') and 'exception' not in e and re.search(r'for\s+update',e.get('sql',''),re.I) for e in own),'race call lacks real registration locking read')
            need(any(e.get('kind')=='db.begin' and e.get('statement')==ledger_id and e.get('operation')=='update' for e in own),'race call lacks its original ledger attempt')
    spending=collections.defaultdict(Decimal)
    for row in last['ums_member_points_detail']['rows']:
        related=[]
        for event in successes:
            p={v['property']:v['value'] for v in event['parameters']}
            fields={'memberId':'member_id','eventType':'event_type','eventId':'event_id','currentPoints':'current_points','afterPoints':'after_points','changePoints':'change_points','changeType':'change_type'}
            if all(number(p[a])==number(row[b]) for a,b in fields.items()) and txs[event['transaction']]==0:related.append(event)
        need(related,'committed ledger row has no matching actual INSERT in a committed transaction')
        need(number(row['change_type'])==2,'unexpected ledger change type')
        member=number(row['member_id'])
        need(member in member_keys,'ledger refers to a member outside the complete fixture member set')
        spending[member_keys[member]]+=number(row['change_points'])
    for member,before in initial_members.items():need(before-spending[member]==final_members[member],'committed balance/complete ledger rows do not conserve points')
    if document['case']=='twoConnectionsSerializeOriginalRegistrationAndDebitOnce':
        ids=document['originalObservations']['testConnectionIds'];need(len(ids)==2 and len({stable(v) for v in ids})==2,'original two-connection proof missing')
    if document['case']=='realLeadStorageFaultRollsBackTheOriginalBasicActionClaimWithoutTerminalReceipt':
        need(number(document['originalObservations']['claims'])==1,'original real claim INSERT proof missing')
        need(not last['game_command_receipt']['rows'],'rolled-back original claim survived')
        actual=[e for e in events if e.get('kind')=='db.end' and e.get('operation')=='update' and e.get('statement','').endswith('.insertReceiptClaim') and 'exception' not in e and number(e['result'])==1]
        need(len(actual)==1 and txs[actual[0]['transaction']]==1,'claim lacks actual INSERT + rollback trace')
    if document['case'].startswith('preferenceLeadStorageFaultCannotCreate'):
        inserts=[e for e in events if e.get('kind')=='db.end' and e.get('operation')=='update' and e.get('statement')=='com.chengyinhub.business.mapper.TopicRouteSessionMapper.insertSession' and 'exception' not in e and number(e['result'])==1]
        need(len(inserts)==1 and txs[inserts[0]['transaction']]==0,'recovery lacks actual route INSERT + commit trace')
    for frame in document['calls']:
        outcome=frame['outcome']
        if outcome.get('kind')=='throw' and outcome['exception'].get('getData')=={'outcome':'NOT_RUN'}:
            need(frame['before']==frame['after'],'explicit NOT_RUN refusal changed raw fixture rows')

def canonical(document,case,manifest):
    need(document.get('format')=='C-M1-EQUIVALENCE-1' and document.get('complete') is True,'incomplete/wrong trace format')
    need(document['suite']==case['suite'] and document['case']==case['case'],'wrong case identity')
    need(document['comparison_mode']==case['mode'],'wrong comparison mode')
    need(document['jvmStartMillis']<=document['jvmEndMillis'],'invalid operation window')
    frames=document['calls'];need(len(frames)==len(case['inputs']),'missing/extra logical calls')
    inputs=[{k:f[k] for k in ('method','actor','arguments')} for f in frames]
    if case['mode']=='full-order':need(inputs==case['inputs'],'original ordered actor/scope/command input changed')
    else:need(collections.Counter(map(stable,inputs))==collections.Counter(map(stable,case['inputs'])),'original race command multiset changed')
    for frame in frames:need(frame['arguments']==frame['argumentsAfter'],'original command mutated in place')
    invariants(document)
    by_call={f['call']:f for f in frames};need(len(by_call)==len(frames),'duplicate logical call')
    def event_value(event,labels):
        need(event['call'] in by_call,'event for unknown logical call')
        result={k:copy.deepcopy(v) for k,v in event.items() if k not in {'seq','span','call','thread','jvmMillis','connectionHandle','transaction','logicalCall'}}
        if 'transaction' in event:
            tag=event['transaction']
            if tag!='NONE':labels.setdefault(tag,'t'+str(len(labels)+1));tag=labels[tag]
            result['transaction']=tag
        if 'parameters' in event:result['parameters']=sql_parameters(event,document,manifest)
        return result
    if case['mode']=='full-order':
        labels={};trace=[dict(event_value(e,labels),logicalCall=frames.index(by_call[e['call']])) for e in document['rawTrace']]
        calls=[dict(input=inputs[i],outcome=f['outcome'],before=snapshot(f['before'],document,manifest),after=snapshot(f['after'],document,manifest)) for i,f in enumerate(frames)]
    else:
        # Preserve EACH logical call's FULL order, including the nested competing transaction.
        # Only unrelated cross-worker interleaving is omitted. This is never a SQL multiset.
        calls=[]
        for i,frame in enumerate(frames):
            labels={};events=[event_value(e,labels) for e in document['rawTrace'] if e['call']==frame['call']]
            calls.append(dict(input=inputs[i],outcome=frame['outcome'],orderedTrace=events))
        calls=sorted(calls,key=stable);trace=None
    observations=copy.deepcopy(document['originalObservations'])
    if 'testConnectionIds' in observations:
        observations['testConnectionIds']={'distinctTestConnections':len({stable(i) for i in observations['testConnectionIds']})}
    return dict(environment=document['environment'],timestampCodec=document['timestampCodec'],initial=snapshot(document['initial'],document,manifest),calls=calls,trace=trace,final=snapshot(document['final'],document,manifest),originalObservations=observations)

def read_bundle(directory,source,manifest):
    directory=Path(directory);need(re.fullmatch('[0-9a-f]{40}',source),'need exact tested source SHA')
    expected={(c['suite'],c['case']):c for c in manifest['cases']};docs={};hashes={};xml_cases={};receipts={}
    for path in directory.rglob('*.json'):
        if path.name=='inputs-manifest.json':continue
        try:value=json.loads(path.read_text())
        except (ValueError,UnicodeDecodeError):continue
        if isinstance(value,dict) and value.get('format')=='C-M1-EQUIVALENCE-1':
            key=(value.get('suite'),value.get('case'));need(key in expected,'unexpected canonical case')
            need(key not in docs,'duplicate canonical case');need(value.get('source_sha')==source,'trace source mismatch')
            docs[key]=value;hashes[str(path.relative_to(directory))]=hashlib.sha256(path.read_bytes()).hexdigest()
        if isinstance(value,dict) and value.get('schema')=='CY-REAL-STORAGE-IT-RECEIPT-20261019' and value.get('class') in {k[0] for k in expected}:
            cls=value['class'];need(cls not in receipts,'duplicate storage receipt');receipts[cls]=value
            need(value.get('source_sha')==source and value.get('verdict')=='PASS','storage source/verdict mismatch')
            need(value.get('maven',{}).get('exit_code')==0,'storage Maven did not succeed')
            need(value.get('cleanup',{}).get('status')=='CLEAN' and not value['cleanup'].get('mysql_leftover_schemas'),'storage not CLEAN')
            count=sum(k[0]==cls for k in expected);executed=value.get('executed',{})
            need(executed.get('tests')==count and executed.get('passed')==count,'storage execution count mismatch')
            need(all(executed.get(k)==0 for k in ('failures','errors','skipped')),'storage failures/errors/skips')
            names={(v['classname'],v['name']) for v in executed.get('testcases',[]) if v.get('status')=='passed'}
            need(len(executed.get('testcases',[]))==count and names=={k for k in expected if k[0]==cls},'storage case set/status mismatch')
            need(value.get('precheck',{}).get('candidate',{}).get('sha')==source,'storage precheck candidate mismatch')
            hashes[str(path.relative_to(directory))]=hashlib.sha256(path.read_bytes()).hexdigest()
    for path in directory.rglob('TEST-*.xml'):
        try:root=ET.parse(path).getroot()
        except ET.ParseError as failed:raise EvidenceError('bad XML '+str(path)) from failed
        selected=False
        for item in root.iter('testcase'):
            key=(item.get('classname'),item.get('name'))
            if key[0] not in {k[0] for k in expected}:continue
            need(key in expected and key not in xml_cases,'unexpected/duplicate XML case')
            need(not any(item.find(tag) is not None for tag in ('failure','error','skipped','flakyFailure','flakyError','rerunFailure','rerunError')),'non-passing XML case')
            xml_cases[key]=True;selected=True
        if selected:
            suites=[root] if root.tag=='testsuite' else list(root.findall('testsuite'))
            for suite in suites:
                if suite.get('name') in {k[0] for k in expected}:
                    need(all(int(suite.get(k,'0'))==0 for k in ('failures','errors','skipped')),'non-zero XML suite status')
                    count=sum(k[0]==suite.get('name') for k in expected)
                    need(int(suite.get('tests','-1'))==count and len(list(suite.iter('testcase')))==count,'XML suite count mismatch')
            hashes[str(path.relative_to(directory))]=hashlib.sha256(path.read_bytes()).hexdigest()
    need(set(docs)==set(expected)==set(xml_cases),'missing trace/XML cases')
    need(set(receipts)=={k[0] for k in expected},'missing two real-storage CLEAN receipts')
    for suite in receipts:
        ordered=sorted((v['caseOrdinal'],v['case']) for v in docs.values() if v['suite']==suite)
        need([n for n,_ in ordered]==list(range(1,len(ordered)+1)),'missing/duplicate per-suite execution ordinal')
    return docs,hashes

def compare(old_docs,new_docs,manifest):
    cases={(c['suite'],c['case']):c for c in manifest['cases']};need(set(old_docs)==set(new_docs)==set(cases),'14-case set mismatch')
    for suite in {k[0] for k in cases}:
        order=lambda docs:[n for _,n in sorted((v['caseOrdinal'],v['case']) for v in docs.values() if v['suite']==suite)]
        need(order(old_docs)==order(new_docs),'case execution order changed; generated IDs must not be normalized')
    results=[]
    for key,case in cases.items():
        old=canonical(old_docs[key],case,manifest);new=canonical(new_docs[key],case,manifest)
        need(old==new,'canonical drift: '+key[0]+'#'+key[1])
        results.append({'suite':key[0],'case':key[1],'canonical_sha256':hashlib.sha256(stable(old).encode()).hexdigest(),'mode':case['mode']})
    return results

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for side in ['old','new']:
        parser.add_argument('--'+side+'-dir',required=True);parser.add_argument('--'+side+'-source',required=True)
    parser.add_argument('--manifest',default=str(Path(__file__).with_name('inputs-manifest.json')))
    parser.add_argument('--out',required=True);args=parser.parse_args()
    try:
        manifest=json.loads(Path(args.manifest).read_text());need(manifest.get('format')=='C-M1-EQUIVALENCE-INPUTS-1','wrong manifest')
        need(args.old_source!=args.new_source,'old/new cannot be the same tested source')
        old,old_hashes=read_bundle(args.old_dir,args.old_source,manifest);new,new_hashes=read_bundle(args.new_dir,args.new_source,manifest)
        result={'status':'PASS','old_source':args.old_source,'new_source':args.new_source,'cases':compare(old,new,manifest),'old_artifact_hashes':old_hashes,'new_artifact_hashes':new_hashes,'manifest_sha256':hashlib.sha256(Path(args.manifest).read_bytes()).hexdigest()}
        Path(args.out).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('canonical 14/14; both sides XML PASS + two CLEAN receipts; source-bound hashes recorded');return 0
    except (EvidenceError,KeyError,ValueError,TypeError) as error:
        Path(args.out).write_text(json.dumps({'status':'FAIL','reason':str(error)},ensure_ascii=False,indent=2)+'\n');print('FAIL: '+str(error),file=sys.stderr);return 1
if __name__=='__main__':raise SystemExit(main())
