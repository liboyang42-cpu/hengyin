"""Synthetic comparator-only models. No Java/MySQL receipts are generated or claimed."""
import copy,importlib.util,json,tempfile,unittest
from pathlib import Path
HERE=Path(__file__).parent
spec=importlib.util.spec_from_file_location('comparison',HERE/'compare_receipts.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
MANIFEST=json.loads((HERE/'inputs-manifest.json').read_text())
LEDGER='com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper.insertUmsMemberPointsDetail'
DEBIT='com.chengyinhub.business.mapper.UmsMemberMapper.deductPoints'
def n(v,kind='java.lang.Long'):return {'kind':'number','javaType':kind,'value':str(v)}
def t(v,kind='java.sql.Timestamp'):return {'kind':'time','javaType':kind,'epochMillis':v,'value':str(v)}
def model():
    case=next(x for x in MANIFEST['cases'] if x['case']=='legalSelfPlayUsesOneRealDebitAndOriginalEventIdentity')
    before={'schema':'chengyin_mybatis_c_hint_unlock_unit','tables':{
        'ums_member':{'columns':[],'rows':[{'id':n(7),'point':n(100)}]},
        'ums_member_points_detail':{'columns':[],'rows':[]},
        'cms_activity':{'columns':[],'rows':[{'id':n(500),'start_date':t(100),'end_date':t(200)}]},
        'cms_registration':{'columns':[],'rows':[{'id':n(1),'pay_expire_time':t(300),'expires_at':t(300)}]}}}
    after=copy.deepcopy(before);after['tables']['ums_member']['rows'][0]['point']=n(90)
    after['tables']['ums_member_points_detail']['rows']=[{'id':n(1),'member_id':n(7),'event_type':n(14),'event_id':n(401),'current_points':n(100),'after_points':n(90),'change_points':n(10),'change_type':n(2),'create_time':t(1000500)}]
    params=lambda values:[{'property':k,'value':v,'javaType':'testScalar','jdbcType':None,'mode':'IN'} for k,v in values.items()]
    events=[]
    def add(kind,**extra):
        events.append(dict(seq=len(events)+1,span=len(events)+1,call=1,thread='worker:1',jvmMillis=1000500,actualTransaction=True,readOnly=False,isolation=None,kind=kind,**extra))
    add('call.begin',input=case['inputs'][0])
    for stage in ['begin','end']:add('db.'+stage,transaction='tx1',statement='test.selectCmsRegistrationByIdForUpdate',operation='query',command='SELECT',sql='select registration for update',parameters=params({'id':n(92)}),**({'result':{'rows':1}} if stage=='end' else {}))
    lp=params(dict(memberId=n(7),currentPoints=n(100),afterPoints=n(90),changePoints=n(10),changeType=n(2),eventType=n(14),eventId=n(401),createTime=t(1000500,'java.util.Date')))
    for stage in ['begin','end']:add('db.'+stage,transaction='tx1',statement=LEDGER,operation='update',command='INSERT',sql='insert original ledger',parameters=lp,**({'result':n(1,'java.lang.Integer')} if stage=='end' else {}))
    dp=params(dict(id=n(7),point=n(10,'java.lang.Integer')))
    for stage in ['begin','end']:add('db.'+stage,transaction='tx1',statement=DEBIT,operation='update',command='UPDATE',sql='update member where point >= ?',parameters=dp,**({'result':n(1,'java.lang.Integer')} if stage=='end' else {}))
    add('tx.complete',transaction='tx1',logicalCall=1,completion=0)
    outcome={'kind':'return','value':{'code':n(200,'java.lang.Integer'),'msg':'操作成功','data':{'hint1':'one','hint2':'two','cost':n(10,'java.lang.Integer')}}}
    add('call.end',outcome=outcome)
    doc=dict(format='C-M1-EQUIVALENCE-1',complete=True,suite=case['suite'],case=case['case'],caseOrdinal=1,comparison_mode=case['mode'],jvmStartMillis=1000000,jvmEndMillis=1001000,
             environment={'test':'SYNTHETIC MODEL ONLY'},timestampCodec={'anchorEpochMillis':0,'roundtripEpochMillis':0,'databaseText':'fixed','jvmTimezone':'UTC'},initial=before,final=after,
             calls=[dict(call=1,**case['inputs'][0],argumentsAfter=copy.deepcopy(case['inputs'][0]['arguments']),outcome=outcome,before=before,after=after)],rawTrace=events,originalObservations={'testConnectionIds':[n(17)]})
    return doc,case
class ComparatorTests(unittest.TestCase):
    def canonical(self,d,case):return c.canonical(d,case,MANIFEST)
    def test_registered_operation_time_and_observation_labels_are_the_only_normalization(self):
        a,case=model();b=copy.deepcopy(a);b['jvmStartMillis']+=10000;b['jvmEndMillis']+=10000
        for frame in [b['final'],b['calls'][0]['after']]:frame['tables']['ums_member_points_detail']['rows'][0]['create_time']=t(1010500)
        for e in b['rawTrace']:
            e['thread']='different-thread:900';e['jvmMillis']+=10000
            for p in e.get('parameters',[]):
                if p['property']=='createTime':p['value']=t(1010500,'java.util.Date')
        b['originalObservations']['testConnectionIds']=[n(999)]
        self.assertEqual(self.canonical(a,case),self.canonical(b,case))
    def test_amount_drift_cannot_hide_behind_complete_row_comparison(self):
        a,case=model();b=copy.deepcopy(a);b['final']['tables']['ums_member']['rows'][0]['point']=n(89)
        with self.assertRaisesRegex(c.EvidenceError,'conserve'):self.canonical(b,case)
    def test_sql_ledger_member_lock_order_is_not_a_multiset(self):
        d,case=model();d['rawTrace'][3:7]=d['rawTrace'][5:7]+d['rawTrace'][3:5]
        for i,e in enumerate(d['rawTrace'],1):e['seq']=i
        with self.assertRaisesRegex(c.EvidenceError,'earlier successful'):self.canonical(d,case)
    def test_seed_date_must_remain_unchanged_inside_the_case(self):
        d,case=model();d['final']['tables']['cms_registration']['rows'][0]['expires_at']=t(301)
        with self.assertRaisesRegex(c.EvidenceError,'seeded date changed'):self.canonical(d,case)
    def test_wrong_actor_or_semantics_are_not_observation_labels(self):
        d,case=model();d['calls'][0]['actor']=n(8)
        with self.assertRaisesRegex(c.EvidenceError,'actor/scope'):self.canonical(d,case)
    def test_request_mutation_is_rejected(self):
        d,case=model();d['calls'][0]['argumentsAfter'][0]=n(999)
        with self.assertRaisesRegex(c.EvidenceError,'mutated'):self.canonical(d,case)
    def test_error_text_is_not_normalized(self):
        a,case=model();b=copy.deepcopy(a);b['calls'][0]['outcome']['value']['msg']='另一个错误'
        self.assertNotEqual(self.canonical(a,case),self.canonical(b,case))
    def test_registered_time_cannot_be_an_arbitrary_future_value(self):
        d,case=model();d['final']['tables']['ums_member_points_detail']['rows'][0]['create_time']=t(9999999)
        with self.assertRaisesRegex(c.EvidenceError,'outside exact'):self.canonical(d,case)
    def test_observed_jdbc_codec_is_exact_not_a_clock_skew_tolerance(self):
        d,case=model();d['timestampCodec']['roundtripEpochMillis']=28800000
        d['final']['tables']['ums_member_points_detail']['rows'][0]['create_time']=t(1000500+28800000)
        self.canonical(d,case)
        d['final']['tables']['ums_member_points_detail']['rows'][0]['create_time']=t(1000500+28800000+60000)
        with self.assertRaises(c.EvidenceError):self.canonical(d,case)
    def test_race_omits_only_cross_call_interleaving_preserving_each_full_trace(self):
        d,_=model();case=next(x for x in MANIFEST['cases'] if x['case']=='twoConnectionsSerializeOriginalRegistrationAndDebitOnce')
        d.update(case=case['case'],comparison_mode=case['mode']);other=copy.deepcopy(d['calls'][0]);other['call']=2
        other['outcome']['value']['data']['cost']=n(0,'java.lang.Integer');other.pop('before');other.pop('after')
        d['calls'].append(other);d['calls'][0].pop('before');d['calls'][0].pop('after')
        e2=[dict(copy.deepcopy(d['rawTrace'][0]),call=2)]
        e2 += [dict(copy.deepcopy(e),call=2,transaction='tx2') for e in d['rawTrace'][1:3]]
        duplicate_begin=dict(copy.deepcopy(d['rawTrace'][3]),call=2,transaction='tx2')
        duplicate_end=dict(copy.deepcopy(duplicate_begin),kind='db.end',exception={'class':'syntheticDuplicate','message':'original identity'})
        e2 += [duplicate_begin,duplicate_end,dict(copy.deepcopy(d['rawTrace'][-2]),call=2,logicalCall=2,transaction='tx2'),dict(copy.deepcopy(d['rawTrace'][-1]),call=2,outcome=other['outcome'])]
        d['originalObservations']['testConnectionIds']=[n(17),n(18)]
        original=d['rawTrace'];d['rawTrace']=original+e2
        for i,e in enumerate(d['rawTrace'],1):e['seq']=i
        b=copy.deepcopy(d);bycall={1:[e for e in b['rawTrace'] if e['call']==1],2:[e for e in b['rawTrace'] if e['call']==2]}
        b['rawTrace']=bycall[2][:1]+bycall[1]+bycall[2][1:]
        for i,e in enumerate(b['rawTrace'],1):e['seq']=i
        self.assertEqual(self.canonical(d,case),self.canonical(b,case))
        b['rawTrace'][4:8]=reversed(b['rawTrace'][4:8])
        for i,e in enumerate(b['rawTrace'],1):e['seq']=i
        with self.assertRaises(c.EvidenceError):self.canonical(b,case)
    def test_rolled_back_insert_cannot_prove_a_committed_ledger_row(self):
        d,_=model();next(e for e in d['rawTrace'] if e['kind']=='tx.complete')['completion']=1
        with self.assertRaisesRegex(c.EvidenceError,'committed transaction'):c.invariants(d)
    def test_claim_counter_without_actual_insert_and_rollback_is_rejected(self):
        d,_=model();d['case']='realLeadStorageFaultRollsBackTheOriginalBasicActionClaimWithoutTerminalReceipt'
        d['originalObservations']={'claims':n(1,'java.lang.Integer')};d['final']['tables']['game_command_receipt']={'columns':[],'rows':[]}
        with self.assertRaisesRegex(c.EvidenceError,'actual INSERT'):c.invariants(d)
    def test_preference_insert_with_rollback_cannot_prove_recovery(self):
        d,_=model();d['case']='preferenceLeadStorageFaultCannotCreateBranchSessionBeforeEitherHttpAdmission'
        template=copy.deepcopy(d['rawTrace'][4]);template.update(statement='com.chengyinhub.business.mapper.TopicRouteSessionMapper.insertSession',transaction='tx2')
        completion=copy.deepcopy(d['rawTrace'][-2]);completion.update(transaction='tx2',completion=1)
        d['rawTrace'][-1:-1]=[template,completion]
        for i,e in enumerate(d['rawTrace'],1):e['seq']=i
        with self.assertRaisesRegex(c.EvidenceError,'recovery lacks'):c.invariants(d)
    def test_not_run_refusal_must_preserve_every_raw_row(self):
        d,_=model();d['calls'][0]['outcome']={'kind':'throw','exception':{'getData':{'outcome':'NOT_RUN'}}}
        with self.assertRaisesRegex(c.EvidenceError,'raw fixture rows'):c.invariants(d)
    def test_missing_real_artifacts_never_looks_like_a_pass(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(c.EvidenceError,'missing trace/XML'):c.read_bundle(root,'a'*40,MANIFEST)
    def test_cleanup_unknown_is_rejected_before_comparison(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root,'receipt.json').write_text(json.dumps({'schema':'CY-REAL-STORAGE-IT-RECEIPT-20261019','class':MANIFEST['cases'][0]['suite'],'source_sha':'a'*40,'verdict':'PASS','maven':{'exit_code':0},'cleanup':{'status':'UNKNOWN'}}))
            with self.assertRaisesRegex(c.EvidenceError,'not CLEAN'):c.read_bundle(root,'a'*40,MANIFEST)
if __name__=='__main__':unittest.main()
