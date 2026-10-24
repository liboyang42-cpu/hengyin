"""13 synthetic controls against the ACTUAL comparator; no Java/MySQL evidence is generated."""
import copy,hashlib,importlib.util,json,subprocess,tempfile,unittest
from pathlib import Path
HERE=Path(__file__).resolve().parent;TOOLS=HERE.parent/'wp09-m1-equivalence'
def module(path,name):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
# The system under test is always the checked-out production file, never a patched temporary copy.
under_test=module(TOOLS/'compare_receipts.py','actual_comparison')
models=module(TOOLS/'test_compare_receipts.py','frozen_models')
def frozen_reference():
    # Reconstruct ONLY the old-rule witness for the two red counterexamples. Reverse the exact
    # reviewed deltas and require the immutable 1c138 preimage hash; no git history/network needed.
    with tempfile.TemporaryDirectory(prefix='c-frozen-witness-') as directory:
        root=Path(directory)
        target=root/'docs/delivery/architecture-day/C/evidence/wp09-m1-equivalence/compare_receipts.py'
        target.parent.mkdir(parents=True);target.write_bytes((TOOLS/'compare_receipts.py').read_bytes())
        for name in ['ledger-rounding-endpoint.review.patch','member-fk-join.review.patch']:
            subprocess.run(['git','apply','--reverse','--check',str(HERE/name)],cwd=root,check=True,capture_output=True)
            subprocess.run(['git','apply','--reverse',str(HERE/name)],cwd=root,check=True,capture_output=True)
        if hashlib.sha256(target.read_bytes()).hexdigest()!='8daee5ccef9d4966d3e45effdf7be2527cf109639eb4ce6e0893ac86b4375c23':
            raise AssertionError('frozen 1c138 comparator witness changed')
        return module(target,'frozen_comparison')
old=frozen_reference()
class ForeignKeyJoinReview(unittest.TestCase):
    new=under_test
    def model(self):
        d,case=models.model()
        # Both complete raw member snapshots have the JDBC native UNSIGNED id type.
        for snapshot in [d['initial'],d['final'],d['calls'][0]['before'],d['calls'][0]['after']]:
            snapshot['tables']['ums_member']['rows'][0]['id']['javaType']='java.math.BigInteger'
        return d,case
    def test_frozen_rule_rejects_a_mathematically_conserving_unsigned_pk_signed_fk(self):
        d,_=self.model()
        with self.assertRaisesRegex(old.EvidenceError,'do not conserve'):old.invariants(d)
        self.new.invariants(d)
    def test_all_raw_typed_rows_and_traces_are_preserved_by_the_proposal(self):
        d,case=self.model();before=copy.deepcopy(d);self.new.canonical(d,case,models.MANIFEST);self.assertEqual(before,d)
    def test_changed_amount_still_fails(self):
        d,_=self.model();d['final']['tables']['ums_member']['rows'][0]['point']=models.n(89)
        with self.assertRaisesRegex(self.new.EvidenceError,'conserve'):self.new.invariants(d)
    def test_final_member_identity_type_change_still_fails(self):
        d,_=self.model();d['final']['tables']['ums_member']['rows'][0]['id']['javaType']='java.lang.Long'
        with self.assertRaisesRegex(self.new.EvidenceError,'identity set changed'):self.new.invariants(d)
    def test_cross_source_member_id_type_drift_is_still_canonical_drift(self):
        a,case=self.model();b=copy.deepcopy(a)
        for snapshot in [b['initial'],b['final'],b['calls'][0]['before'],b['calls'][0]['after']]:snapshot['tables']['ums_member']['rows'][0]['id']['javaType']='java.lang.Long'
        self.assertNotEqual(self.new.canonical(a,case,models.MANIFEST),self.new.canonical(b,case,models.MANIFEST))
        b=copy.deepcopy(a)
        b['final']['tables']['ums_member_points_detail']['rows'][0]['member_id']['javaType']='java.math.BigInteger'
        self.assertNotEqual(self.new.canonical(a,case,models.MANIFEST),self.new.canonical(b,case,models.MANIFEST))
    def test_lost_committed_ledger_row_cannot_disappear_into_the_join(self):
        d,_=self.model();d['final']['tables']['ums_member_points_detail']['rows']=[]
        with self.assertRaisesRegex(self.new.EvidenceError,'conserve'):self.new.invariants(d)
    def test_orphan_member_ledger_is_rejected_even_when_traces_match(self):
        d,_=self.model();d['final']['tables']['ums_member_points_detail']['rows'][0]['member_id']=models.n(8)
        for event in d['rawTrace']:
            for p in event.get('parameters',[]):
                if p['property']=='memberId':p['value']=models.n(8)
                if p['property']=='id':p['value']=models.n(8)
        with self.assertRaisesRegex(self.new.EvidenceError,'outside the complete'):self.new.invariants(d)
    def test_rollback_and_reversed_ledger_member_order_still_fail(self):
        d,_=self.model();next(e for e in d['rawTrace'] if e['kind']=='tx.complete')['completion']=1
        with self.assertRaisesRegex(self.new.EvidenceError,'committed transaction'):self.new.invariants(d)
        d,_=self.model();d['rawTrace'][3:7]=d['rawTrace'][5:7]+d['rawTrace'][3:5]
        for i,event in enumerate(d['rawTrace'],1):event['seq']=i
        with self.assertRaisesRegex(self.new.EvidenceError,'earlier successful'):self.new.invariants(d)
class LedgerRoundingEndpointReview(unittest.TestCase):
    new=under_test
    def model(self):
        d,case=models.model();d.update(jvmStartMillis=1000737,jvmEndMillis=1000791)
        d['environment']['sql_mode']='STRICT_TRANS_TABLES'
        for snap in [d['initial'],d['final'],d['calls'][0]['before'],d['calls'][0]['after']]:
            snap['tables']['ums_member_points_detail']['columns']=[{'name':'create_time','typeName':'DATETIME','scale':0}]
        d['final']['tables']['ums_member_points_detail']['rows'][0]['create_time']=models.t(1001000)
        for event in d['rawTrace']:
            for param in event.get('parameters',[]):
                if param['property']=='createTime':param['value']=models.t(1000770,'java.util.Date')
        return d,case
    def check_date(self,d,identity='ums_member_points_detail.create_time'):
        row=d['final']['tables']['ums_member_points_detail']['rows'][0]
        return self.new.operation_time(row['create_time'],identity,d,persisted=True,source_row=row)
    def test_exact_datetime0_rounding_of_original_committed_bind_is_admitted(self):
        d,case=self.model()
        with self.assertRaisesRegex(old.EvidenceError,'outside exact'):old.canonical(d,case,models.MANIFEST)
        self.new.canonical(d,case,models.MANIFEST)
    def test_one_millisecond_beyond_boundary_and_wrong_or_future_bound_date_fail(self):
        for problem in ['beyond','not_rounded','future']:
            d,_=self.model()
            if problem=='beyond':d['final']['tables']['ums_member_points_detail']['rows'][0]['create_time']=models.t(1001001)
            if problem=='not_rounded':d['jvmStartMillis']=1000300
            for event in d['rawTrace']:
                for param in event.get('parameters',[]):
                    if param['property']=='createTime' and problem!='beyond':param['value']=models.t(1000400 if problem=='not_rounded' else 1000900,'java.util.Date')
            with self.subTest(problem=problem),self.assertRaises(self.new.EvidenceError):self.check_date(d)
    def test_missing_bind_rollback_or_wrong_business_identity_cannot_prove_boundary(self):
        for problem in ['missing_bind','rollback','other_member','other_event']:
            d,_=self.model();row=d['final']['tables']['ums_member_points_detail']['rows'][0]
            if problem=='rollback':next(e for e in d['rawTrace'] if e['kind']=='tx.complete')['completion']=1
            if problem=='other_member':row['member_id']=models.n(8)
            if problem=='other_event':row['event_id']=models.n(999)
            if problem=='missing_bind':
                for event in d['rawTrace']:
                    if 'parameters' in event:event['parameters']=[p for p in event['parameters'] if p['property']!='createTime']
            with self.subTest(problem=problem),self.assertRaises(self.new.EvidenceError):self.check_date(d)
    def test_other_precision_column_or_truncation_mode_cannot_gain_boundary(self):
        for problem in ['scale3','timestamp_type','truncate','other_field']:
            d,_=self.model();col=d['initial']['tables']['ums_member_points_detail']['columns'][0]
            if problem=='scale3':col['scale']=3
            if problem=='timestamp_type':col['typeName']='TIMESTAMP'
            if problem=='truncate':d['environment']['sql_mode']+=',TIME_TRUNCATE_FRACTIONAL'
            with self.subTest(problem=problem),self.assertRaises(self.new.EvidenceError):self.check_date(d,'topic_route_session.create_time' if problem=='other_field' else 'ums_member_points_detail.create_time')
    def test_boundary_proof_does_not_modify_any_raw_row_trace_or_timestamp(self):
        d,case=self.model();before=copy.deepcopy(d);self.new.canonical(d,case,models.MANIFEST);self.assertEqual(before,d)

if __name__=='__main__':unittest.main(verbosity=2)
